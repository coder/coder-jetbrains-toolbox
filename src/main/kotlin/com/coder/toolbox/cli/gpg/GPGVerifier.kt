package com.coder.toolbox.cli.gpg

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.gpg.VerificationResult.Failed
import com.coder.toolbox.cli.gpg.VerificationResult.Invalid
import com.coder.toolbox.cli.gpg.VerificationResult.SignatureNotFound
import com.coder.toolbox.cli.gpg.VerificationResult.Valid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class GPGVerifier(
    private val context: CoderToolboxContext,
) {

    suspend fun verifySignature(
        cli: Path,
        signature: Path,
    ): VerificationResult {
        return try {
            if (!Files.exists(signature)) {
                context.logger.warn("Signature file not found, skipping verification")
                return SignatureNotFound
            }

            val (cliBytes, signatureBytes, publicKeyRing) = withContext(Dispatchers.IO) {
                val cliBytes = Files.readAllBytes(cli)
                val signatureBytes = Files.readAllBytes(signature)
                val publicKeyRing = getCoderPublicKeyRings()

                Triple(cliBytes, signatureBytes, publicKeyRing)
            }
            return verifyDetachedSignature(
                cliBytes = cliBytes,
                signatureBytes = signatureBytes,
                publicKeyRings = publicKeyRing
            )
        } catch (e: Exception) {
            context.logger.error(e, "GPG signature verification failed")
            Failed(e)
        }
    }

    private fun getCoderPublicKeyRings(): List<PGPPublicKeyRing> {
        try {
            val coderPublicKey = javaClass.getResourceAsStream("/META-INF/trusted-keys/pgp-public.key")
                ?.readAllBytes() ?: throw IllegalStateException("Trusted public key not found")
            return loadPublicKeyRings(coderPublicKey)
        } catch (e: Exception) {
            throw PGPException("Failed to load Coder public GPG key", e)
        }
    }

    /**
     * Load public key rings from bytes
     */
    fun loadPublicKeyRings(publicKeyBytes: ByteArray): List<PGPPublicKeyRing> {
        return try {
            val keyInputStream = ArmoredInputStream(ByteArrayInputStream(publicKeyBytes))
            val keyRingCollection = PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(keyInputStream),
                JcaKeyFingerprintCalculator()
            )
            keyRingCollection.keyRings.asSequence().toList()
        } catch (e: Exception) {
            throw PGPException("Failed to load public key ring", e)
        }
    }

    /**
     * Verify a detached GPG signature
     */
    fun verifyDetachedSignature(
        cliBytes: ByteArray,
        signatureBytes: ByteArray,
        publicKeyRings: List<PGPPublicKeyRing>
    ): VerificationResult {
        try {
            val signatureInputStream = ArmoredInputStream(ByteArrayInputStream(signatureBytes))
            val pgpObjectFactory = JcaPGPObjectFactory(signatureInputStream)
            val signatureList = pgpObjectFactory.nextObject() as? PGPSignatureList
                ?: throw PGPException("Invalid signature format")

            if (signatureList.isEmpty) {
                return Invalid("No signatures found in signature file")
            }

            val signature = signatureList[0]
            val publicKey = findPublicKey(publicKeyRings, signature.keyID)
                ?: throw PGPException("Public key not found for signature")

            signature.init(JcaPGPContentVerifierBuilderProvider(), publicKey)
            signature.update(cliBytes)

            val isValid = signature.verify()
            context.logger.info("GPG signature verification result: $isValid")
            if (isValid) {
                return Valid
            }
            return Invalid()
        } catch (e: Exception) {
            context.logger.error(e, "GPG signature verification failed")
            return Failed(e)
        }
    }

    /**
     * Find a public key across all key rings in the collection
     */
    private fun findPublicKey(
        keyRings: List<PGPPublicKeyRing>,
        keyId: Long
    ): PGPPublicKey? {
        keyRings.forEach { keyRing ->
            keyRing.getPublicKey(keyId)?.let { return it }
        }
        return null
    }
}