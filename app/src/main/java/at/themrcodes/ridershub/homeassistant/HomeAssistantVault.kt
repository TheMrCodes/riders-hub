package at.themrcodes.ridershub.homeassistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protects retained Home Assistant credentials with a non-exportable Android Keystore key. */
internal class HomeAssistantVault {
    @Synchronized
    fun encrypt(value: String, purpose: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad(purpose))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(
            VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(DELIMITER)
    }

    @Synchronized
    fun decrypt(envelope: String, purpose: String): String {
        val parts = envelope.split(DELIMITER, limit = 3)
        if (parts.size != 3 || parts[0] != VERSION) throw IOException("Unsupported credential format")
        try {
            val iv = Base64.decode(parts[1], Base64.DEFAULT)
            val encrypted = Base64.decode(parts[2], Base64.DEFAULT)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad(purpose))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw IOException("Stored Home Assistant credentials are unavailable", error)
        }
    }

    @Synchronized
    fun deleteKey() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun aad(purpose: String): ByteArray = "riders-hub:home-assistant:$purpose".toByteArray(Charsets.UTF_8)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "riders_hub_home_assistant_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val VERSION = "v1"
        private const val DELIMITER = ":"
    }
}
