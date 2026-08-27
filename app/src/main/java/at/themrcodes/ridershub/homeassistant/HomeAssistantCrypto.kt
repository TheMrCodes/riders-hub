package at.themrcodes.ridershub.homeassistant

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.SecretBox
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.SecureRandom

/** Home Assistant's modern mobile_app SecretBox wire format. */
internal class HomeAssistantCrypto {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    private val secureRandom = SecureRandom()

    fun encryptJson(value: Any, secret: String): String {
        require(value is JSONObject || value is JSONArray) { "Webhook data must be JSON" }
        val key = decodeKey(secret)
        val message = value.toString().toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(SecretBox.NONCEBYTES).also(secureRandom::nextBytes)
        val ciphertext = ByteArray(message.size + SecretBox.MACBYTES)
        try {
            if (!sodium.cryptoSecretBoxEasy(ciphertext, message, message.size.toLong(), nonce, key)) {
                throw IOException("Could not encrypt Home Assistant payload")
            }
            return Base64.encodeToString(nonce + ciphertext, Base64.NO_WRAP)
        } finally {
            key.fill(0)
            message.fill(0)
        }
    }

    fun decryptJson(encoded: String, secret: String): Any {
        val combined = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrElse { throw IOException("Invalid encrypted Home Assistant response", it) }
        if (combined.size < SecretBox.NONCEBYTES + SecretBox.MACBYTES) {
            throw IOException("Invalid encrypted Home Assistant response")
        }
        val nonce = combined.copyOfRange(0, SecretBox.NONCEBYTES)
        val ciphertext = combined.copyOfRange(SecretBox.NONCEBYTES, combined.size)
        val plaintext = ByteArray(ciphertext.size - SecretBox.MACBYTES)
        val key = decodeKey(secret)
        try {
            if (!sodium.cryptoSecretBoxOpenEasy(
                    plaintext,
                    ciphertext,
                    ciphertext.size.toLong(),
                    nonce,
                    key,
                )
            ) {
                throw IOException("Could not authenticate Home Assistant response")
            }
            val decoded = JSONTokener(plaintext.toString(Charsets.UTF_8)).nextValue()
            if (decoded !is JSONObject && decoded !is JSONArray) {
                throw IOException("Home Assistant returned invalid encrypted JSON")
            }
            return decoded
        } finally {
            key.fill(0)
            plaintext.fill(0)
        }
    }

    fun validateSecret(secret: String): String {
        decodeKey(secret).fill(0)
        return secret.trim().lowercase()
    }

    private fun decodeKey(secret: String): ByteArray {
        val normalized = secret.trim()
        if (normalized.length != SecretBox.KEYBYTES * 2 || normalized.any { it.digitToIntOrNull(16) == null }) {
            throw IOException("Home Assistant returned an invalid encryption key")
        }
        return ByteArray(SecretBox.KEYBYTES) { index ->
            val offset = index * 2
            ((normalized[offset].digitToInt(16) shl 4) or normalized[offset + 1].digitToInt(16)).toByte()
        }
    }
}
