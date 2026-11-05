package io.kton.connect.storage

import io.kton.connect.protocol.ProtocolJson
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedFileSessionStorage(
    private val file: File,
    private val key: ByteArray,
) : SessionStorage {
    init {
        require(key.size == 32) { "Session key must be 32 bytes" }
    }

    override suspend fun load(): StoredSession? {
        if (!file.isFile || file.length() == 0L) return null
        val plain = decrypt(file.readBytes(), key)
        return ProtocolJson.decodeFromString(StoredSession.serializer(), plain.decodeToString())
    }

    override suspend fun save(session: StoredSession?) {
        if (session == null) {
            file.delete()
            return
        }
        val plain = ProtocolJson.encodeToString(StoredSession.serializer(), session).encodeToByteArray()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(encrypt(plain, key))
        if (!tmp.renameTo(file)) {
            file.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }
}

internal fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    val body = cipher.doFinal(plain)
    return byteArrayOf(1) + iv + body
}

internal fun decrypt(sealed: ByteArray, key: ByteArray): ByteArray {
    require(sealed.size > 13 && sealed[0] == 1.toByte()) { "Session file is not a kton session" }
    val iv = sealed.copyOfRange(1, 13)
    val body = sealed.copyOfRange(13, sealed.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return cipher.doFinal(body)
}
