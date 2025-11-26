package com.coder.toolbox.util

import com.coder.toolbox.settings.ReadOnlyTLSSettings
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.File
import java.io.FileInputStream
import java.net.IDN
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.StandardConstants
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun sslContextFromPEMs(
    certPath: String?,
    keyPath: String?,
    caPath: String?,
): SSLContext {
    var km: Array<KeyManager>? = null
    if (!certPath.isNullOrBlank() && !keyPath.isNullOrBlank()) {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certInputStream = FileInputStream(expand(certPath))
        val certChain = certificateFactory.generateCertificates(certInputStream)
        certInputStream.close()

        // Ideally we would use something like PemReader from BouncyCastle, but
        // BC is used by the IDE.  This makes using BC very impractical since
        // type casting will mismatch due to the different class loaders.
        val privateKeyPem = File(expand(keyPath)).readText()
        val start: Int = privateKeyPem.indexOf("-----BEGIN PRIVATE KEY-----")
        val end: Int = privateKeyPem.indexOf("-----END PRIVATE KEY-----", start)
        val pemBytes: ByteArray =
            Base64.getDecoder().decode(
                privateKeyPem.substring(start + "-----BEGIN PRIVATE KEY-----".length, end)
                    .replace("\\s+".toRegex(), ""),
            )

        val privateKey =
            try {
                val kf = KeyFactory.getInstance("RSA")
                val keySpec = PKCS8EncodedKeySpec(pemBytes)
                kf.generatePrivate(keySpec)
            } catch (e: InvalidKeySpecException) {
                val kf = KeyFactory.getInstance("EC")
                val keySpec = PKCS8EncodedKeySpec(pemBytes)
                kf.generatePrivate(keySpec)
            }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        certChain.withIndex().forEach {
            keyStore.setCertificateEntry("cert${it.index}", it.value as X509Certificate)
        }
        keyStore.setKeyEntry("key", privateKey, null, certChain.toTypedArray())

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)
        km = keyManagerFactory.keyManagers
    }

    val sslContext = SSLContext.getInstance("TLS")

    val trustManagers = coderTrustManagers(caPath)
    sslContext.init(km, trustManagers, null)
    return sslContext
}

/**
 * Netflix TLS Workaround — SNI & Hostname Validation
 *
 * Context:
 * - The Netflix servers we connect to rely on the SNI in the ClientHello
 * beyond just the typical use case of serving multiple hostnames from a
 * single IP. The alternate hostname for the SNI can contain underscores
 * (non-compliant for hostnames).
 * - The server always presents the same certificate, regardless of the SNI
 * - The certificate’s SAN entries do not match the server’s DNS name, and in
 * - Because of this mismatch, the TLS handshake fails unless we apply two
 * client-side workarounds:
 *
 *  1. SNI manipulation — we rewrite the SNI in the ClientHello via a custom
 *     SSLSocketFactory. Even though the server’s cert does not vary by SNI,
 *     connections fail if this rewrite is removed. The server’s TLS stack
 *     appears to depend on the SNI being set in a particular way.
 *
 *  2. Hostname validation override — we relax certificate checks by allowing
 *     an “alternate hostname” to be matched against the cert SANs. This avoids
 *     rejections when the SAN does not align with the requested DNS name.
 *
 * See [this issue](https://github.com/coder/jetbrains-coder/issues/578) for more details.
 */
fun coderSocketFactory(settings: ReadOnlyTLSSettings): SSLSocketFactory {
    val sslContext = sslContextFromPEMs(settings.certPath, settings.keyPath, settings.caPath)

    val altHostname = settings.altHostname
    if (altHostname.isNullOrBlank()) {
        return sslContext.socketFactory
    }

    return AlternateNameSSLSocketFactory(sslContext.socketFactory, altHostname)
}

fun coderTrustManagers(tlsCAPath: String?): Array<TrustManager> {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    if (tlsCAPath.isNullOrBlank()) {
        // return default trust managers
        trustManagerFactory.init(null as KeyStore?)
        return trustManagerFactory.trustManagers
    }

    val certificateFactory = CertificateFactory.getInstance("X.509")
    val caInputStream = FileInputStream(expand(tlsCAPath))
    val certChain = certificateFactory.generateCertificates(caInputStream)

    val truststore = KeyStore.getInstance(KeyStore.getDefaultType())
    truststore.load(null)
    certChain.withIndex().forEach {
        truststore.setCertificateEntry("cert${it.index}", it.value as X509Certificate)
    }
    trustManagerFactory.init(truststore)
    return trustManagerFactory.trustManagers.map { MergedSystemTrustManger(it as X509TrustManager) }.toTypedArray()
}

class AlternateNameSSLSocketFactory(private val delegate: SSLSocketFactory, private val alternateName: String) :
    SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket {
        val socket = delegate.createSocket() as SSLSocket
        customizeSocket(socket)
        return socket
    }

    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket {
        val socket = delegate.createSocket(host, port) as SSLSocket
        customizeSocket(socket)
        return socket
    }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket {
        val socket = delegate.createSocket(host, port, localHost, localPort) as SSLSocket
        customizeSocket(socket)
        return socket
    }

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket {
        val socket = delegate.createSocket(host, port) as SSLSocket
        customizeSocket(socket)
        return socket
    }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket {
        val socket = delegate.createSocket(address, port, localAddress, localPort) as SSLSocket
        customizeSocket(socket)
        return socket
    }

    override fun createSocket(
        s: Socket?,
        host: String?,
        port: Int,
        autoClose: Boolean,
    ): Socket {
        val socket = delegate.createSocket(s, host, port, autoClose) as SSLSocket
        customizeSocket(socket)
        return socket
    }

    private fun customizeSocket(socket: SSLSocket) {
        val params = socket.sslParameters

        params.serverNames = listOf(RelaxedSNIHostname(alternateName))
        socket.sslParameters = params
    }
}

private class RelaxedSNIHostname(hostname: String) : SNIServerName(
    StandardConstants.SNI_HOST_NAME,
    IDN.toASCII(hostname, 0).toByteArray(StandardCharsets.UTF_8)
)

class CoderHostnameVerifier(private val alternateName: String?) : HostnameVerifier {

    override fun verify(
        host: String,
        session: SSLSession,
    ): Boolean {
        if (alternateName.isNullOrBlank()) {
            return OkHostnameVerifier.verify(host, session)
        }
        val certs = session.peerCertificates ?: return false
        for (cert in certs) {
            if (cert !is X509Certificate) {
                continue
            }
            val entries = cert.subjectAlternativeNames ?: continue
            for (entry in entries) {
                val kind = entry[0] as Int
                if (kind != 2) { // DNS Name
                    continue
                }
                val hostname = entry[1] as String
                if (hostname.lowercase(Locale.getDefault()) == alternateName) {
                    return true
                }
            }
        }
        return false
    }
}

class MergedSystemTrustManger(private val otherTrustManager: X509TrustManager) : X509TrustManager {
    private val systemTrustManager: X509TrustManager

    init {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        systemTrustManager = trustManagerFactory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String?,
    ) {
        try {
            otherTrustManager.checkClientTrusted(chain, authType)
        } catch (e: CertificateException) {
            systemTrustManager.checkClientTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String?,
    ) {
        try {
            otherTrustManager.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            systemTrustManager.checkServerTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        otherTrustManager.acceptedIssuers + systemTrustManager.acceptedIssuers
}

class ReloadableX509TrustManager(
    private val caPath: String?,
) : X509TrustManager {
    @Volatile
    private var delegate: X509TrustManager = loadTrustManager()

    private fun loadTrustManager(): X509TrustManager {
        val trustManagers = coderTrustManagers(caPath)
        return trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    fun reload() {
        delegate = loadTrustManager()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return delegate.acceptedIssuers
    }
}

class ReloadableSSLSocketFactory(
    private val settings: ReadOnlyTLSSettings,
) : SSLSocketFactory() {
    @Volatile
    private var delegate: SSLSocketFactory = loadSocketFactory()

    private fun loadSocketFactory(): SSLSocketFactory {
        return coderSocketFactory(settings)
    }

    fun reload() {
        delegate = loadSocketFactory()
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = delegate.createSocket()

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(s, host, port, autoClose)

    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort)
}

class ReloadableTlsContext(
    settings: ReadOnlyTLSSettings
) {
    val sslSocketFactory = ReloadableSSLSocketFactory(settings)
    val trustManager = ReloadableX509TrustManager(settings.caPath)

    fun reload() {
        sslSocketFactory.reload()
        trustManager.reload()
    }
}
