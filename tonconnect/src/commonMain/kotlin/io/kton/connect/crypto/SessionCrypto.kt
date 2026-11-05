package io.kton.connect.crypto

import io.kton.connect.platform.secureRandom

/**
 * One side of a TonConnect session.
 *
 * [clientId] is the hex-encoded X25519 public key. Every bridge message after
 * the opening connect link is `nonce || nacl.box(plaintext)`.
 */
class SessionCrypto internal constructor(
    val secretKey: ByteArray,
    val publicKey: ByteArray,
) {
    val clientId: String = publicKey.toHex()

    init {
        require(secretKey.size == 32) { "Session secret key must be 32 bytes" }
        require(publicKey.size == 32) { "Session public key must be 32 bytes" }
    }

    fun encrypt(plaintext: String, receiverPublicKey: ByteArray): ByteArray {
        require(receiverPublicKey.size == 32) { "Receiver public key must be 32 bytes" }
        val nonce = ByteArray(24).also { secureRandom(it) }
        val boxed = TweetNaCl.box(plaintext.encodeToByteArray(), nonce, receiverPublicKey, secretKey)
        return nonce + boxed
    }

    fun decrypt(message: ByteArray, senderPublicKey: ByteArray): String {
        if (message.size <= NONCE_LENGTH || senderPublicKey.size != 32) {
            throw SessionDecryptException()
        }
        val nonce = message.copyOfRange(0, NONCE_LENGTH)
        val boxed = message.copyOfRange(NONCE_LENGTH, message.size)
        val plain = TweetNaCl.boxOpen(boxed, nonce, senderPublicKey, secretKey) ?: throw SessionDecryptException()
        return plain.decodeToString()
    }

    fun destroy() {
        secretKey.fill(0)
    }

    companion object {
        private const val NONCE_LENGTH = 24

        fun generate(): SessionCrypto {
            val secret = ByteArray(32)
            secureRandom(secret)
            return fromSecretKey(secret)
        }

        fun fromSecretKey(secretKey: ByteArray): SessionCrypto {
            require(secretKey.size == 32 || secretKey.size == 64) {
                "Expected a 32-byte seed or a 64-byte NaCl secret key"
            }
            val seed = secretKey.copyOf(32)
            return SessionCrypto(seed, TweetNaCl.scalarmultBase(seed))
        }
    }
}

class SessionDecryptException : IllegalArgumentException("TonConnect message failed authentication")
