package com.example.callmirror

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Military-grade AES-256-GCM authenticated encryption for cloud and remote mirrored messages.
 * 
 * Security features:
 * 1. AES-256-GCM with standard 12-byte (96-bit) cryptographically random Nonce/IV per message.
 * 2. 128-bit authentication tag guarantees confidentiality and tamper-proof integrity.
 * 3. Fail-Closed design: Returns null on any encryption or decryption error; NEVER leaks plaintext.
 * 4. Deterministic topic separation: Topic name is derived from SHA-256(code + ":topic_v2") to ensure
 *    the secret pairing code itself is never visible in relay server logs or URLs.
 */
object CryptoUtils {

    private const val TAG = "CryptoUtils"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val PREFIX = "GCM:"

    private val secureRandom = SecureRandom()

    private fun deriveKey(secretKeyPass: String): SecretKeySpec {
        val keyDigest = MessageDigest.getInstance("SHA-256")
        // Salt the key derivation to bind it specifically to AES payload encryption
        keyDigest.update("callmirror_payload_key:".toByteArray(Charsets.UTF_8))
        val keyBytes = keyDigest.digest(secretKeyPass.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Derives a safe, one-way cloud topic name from the pairing code.
     * The actual pairing code secret is never exposed in the URL or topic name.
     */
    fun deriveTopicName(pairingCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("callmirror_topic_v2:".toByteArray(Charsets.UTF_8))
        val hash = digest.digest(pairingCode.trim().toByteArray(Charsets.UTF_8))
        // Take first 16 bytes encoded as hex for clean, unguessable topic name
        return "cm_" + hash.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypts plaintext using AES-256-GCM with a random 12-byte IV.
     * Fail-closed: returns null if encryption fails. Never returns plaintext.
     */
    fun encrypt(plainText: String, secretKeyPass: String): String? {
        return try {
            val secretKey = deriveKey(secretKeyPass)
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // Pack: [12 bytes IV] + [Ciphertext + 16 bytes GCM Auth Tag]
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}")
            null // FAIL-CLOSED: Never send raw plaintext!
        }
    }

    /**
     * Decrypts ciphertext using AES-256-GCM.
     * Validates authentication tag automatically. Returns null on tampering or wrong key.
     */
    fun decrypt(cipherText: String, secretKeyPass: String): String? {
        if (!cipherText.startsWith(PREFIX)) {
            Log.w(TAG, "Rejecting unencrypted or unsupported payload format")
            return null // Reject unencrypted payloads over cloud
        }

        return try {
            val base64Data = cipherText.substring(PREFIX.length)
            val combined = Base64.decode(base64Data, Base64.NO_WRAP)

            if (combined.size < GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
                Log.w(TAG, "Ciphertext too short for GCM payload")
                return null
            }

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)

            val cipherLength = combined.size - GCM_IV_LENGTH_BYTES
            val cipherBytes = ByteArray(cipherLength)
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherBytes, 0, cipherLength)

            val secretKey = deriveKey(secretKeyPass)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption or authentication failed (invalid key or corrupted data)")
            null
        }
    }
}
