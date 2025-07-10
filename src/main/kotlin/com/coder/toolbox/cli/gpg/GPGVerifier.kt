package com.coder.toolbox.cli.gpg

import com.coder.toolbox.CoderToolboxContext
import com.coder.toolbox.cli.gpg.VerificationResult.Failed
import com.coder.toolbox.cli.gpg.VerificationResult.Invalid
import com.coder.toolbox.cli.gpg.VerificationResult.SignatureNotFound
import com.coder.toolbox.cli.gpg.VerificationResult.Valid
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.openpgp.PGPException
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

    fun verifySignature(
        cli: Path,
        signature: Path,
    ): VerificationResult {
        return try {
            if (!Files.exists(signature)) {
                context.logger.warn("Signature file not found, skipping verification")
                return SignatureNotFound
            }

            val signatureBytes = Files.readAllBytes(signature)
            val cliBytes = Files.readAllBytes(cli)

            val publicKeyRing = getCoderPublicKeyRing()
            return verifyDetachedSignature(
                cliBytes = cliBytes,
                signatureBytes = signatureBytes,
                publicKeyRing = publicKeyRing
            )
        } catch (e: Exception) {
            context.logger.error(e, "GPG signature verification failed")
            Failed(e)
        }
    }

    private fun getCoderPublicKeyRing(): PGPPublicKeyRing {
        return try {
            getDefaultCoderPublicKeyRing()
        } catch (e: Exception) {
            throw PGPException("Failed to load Coder public GPG key", e)
        }
    }

    private fun getDefaultCoderPublicKeyRing(): PGPPublicKeyRing {
        val coderPublicKey = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----
            
            # Replace this with Coder's actual public key
            
            -----END PGP PUBLIC KEY BLOCK-----
        """.trimIndent()

        return loadPublicKeyRing(coderPublicKey.toByteArray())
    }

    /**
     * Verify a detached GPG signature
     */
    fun verifyDetachedSignature(
        cliBytes: ByteArray,
        signatureBytes: ByteArray,
        publicKeyRing: PGPPublicKeyRing
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
            val publicKey = publicKeyRing.getPublicKey(signature.keyID)
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
     * Load public key ring from bytes
     */
    fun loadPublicKeyRing(publicKeyBytes: ByteArray): PGPPublicKeyRing {
        return try {
            val keyInputStream = ArmoredInputStream(ByteArrayInputStream(publicKeyBytes))
            val keyRingCollection = PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(keyInputStream),
                JcaKeyFingerprintCalculator()
            )
            keyRingCollection.keyRings.next()
        } catch (e: Exception) {
            throw PGPException("Failed to load public key ring", e)
        }
    }
}