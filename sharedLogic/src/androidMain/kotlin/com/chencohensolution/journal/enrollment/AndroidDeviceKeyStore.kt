package com.chencohensolution.journal.enrollment

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.chencohensolution.journal.tls.parseX509Certificate
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal const val ANDROID_KEY_STORE = "AndroidKeyStore"

internal fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

/**
 * Keys in the app's Android Keystore: in StrongBox when the device has it, otherwise in the TEE
 * (or in software on emulators without either).
 */
internal class AndroidDeviceKeyStore : DeviceKeyStore {

    override fun generate(alias: String): DeviceKey {
        delete(alias)
        val pair = try {
            generateKeyPair(alias, strongBox = true)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException, or a StrongBox that cannot make this key.
            generateKeyPair(alias, strongBox = false)
        }
        return AndroidDeviceKey(pair, securityLevel(pair.private))
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        val keyStore = androidKeyStore()
        val key = keyStore.getKey(alias, null) ?: error("No key for $alias")
        // For a Keystore key this replaces the placeholder self-signed certificate only.
        keyStore.setKeyEntry(alias, key, null, arrayOf(parseX509Certificate(certificateDer)))
    }

    override fun delete(alias: String) {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun certificateNotAfter(alias: String): Long? =
        (androidKeyStore().getCertificate(alias) as? java.security.cert.X509Certificate)?.notAfter?.time

    private fun generateKeyPair(alias: String, strongBox: Boolean): KeyPair {
        // StrongBox only supports SHA-256; the TLS stack may also ask for other digests or none.
        val digests = if (strongBox) {
            arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
        } else {
            arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
        }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(*digests)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun securityLevel(key: PrivateKey): KeyProtection {
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE).getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtection.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtection.TEE
            else -> KeyProtection.SOFTWARE
        }
    }
}

private class AndroidDeviceKey(private val pair: KeyPair, override val protection: KeyProtection) : DeviceKey {
    override val publicKeyInfo: ByteArray get() = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
}
