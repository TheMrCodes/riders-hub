package at.themrcodes.ridershub.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface ApiCredentialProtection {
    fun encrypt(value: String, purpose: String): String
    fun decrypt(value: String, purpose: String): String
}

internal class ApiVault : ApiCredentialProtection {
    @Synchronized
    override fun encrypt(value: String, purpose: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad(purpose))
        return listOf(
            VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP),
        ).joinToString(DELIMITER)
    }

    @Synchronized
    override fun decrypt(value: String, purpose: String): String = try {
        val parts = value.split(DELIMITER, limit = 3)
        require(parts.size == 3 && parts[0] == VERSION)
        Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, Base64.decode(parts[1], Base64.DEFAULT)),
            )
            updateAAD(aad(purpose))
            doFinal(Base64.decode(parts[2], Base64.DEFAULT)).toString(Charsets.UTF_8)
        }
    } catch (error: Exception) {
        throw IOException("Stored API credentials are unavailable", error)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun aad(purpose: String) = "riders-hub:self-hosted-api:$purpose".toByteArray()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "riders_hub_self_hosted_api"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VERSION = "v1"
        private const val DELIMITER = ":"
    }
}
