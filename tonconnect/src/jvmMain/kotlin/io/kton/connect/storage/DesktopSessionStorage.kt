package io.kton.connect.storage

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.security.SecureRandom

fun encryptedSessionStorage(directory: File): SessionStorage {
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IllegalStateException("Cannot create ${directory.path}")
    }
    val key = SessionKeyFile(File(directory, "session.key")).loadOrCreate()
    return EncryptedFileSessionStorage(File(directory, "session.bin"), key)
}

internal class SessionKeyFile(private val file: File) {
    fun loadOrCreate(): ByteArray {
        if (file.isFile) return unwrap(file.readBytes())
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeBytes(wrap(key))
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        return key
    }

    private fun wrap(key: ByteArray): ByteArray {
        val protected = if (isWindows) protect(key) else key
        return byteArrayOf(1, if (isWindows) 1 else 0) + protected
    }

    private fun unwrap(bytes: ByteArray): ByteArray {
        require(bytes.size > 2 && bytes[0] == 1.toByte()) { "Session key file is damaged" }
        val body = bytes.copyOfRange(2, bytes.size)
        return if (bytes[1] == 1.toByte()) unprotect(body) else body
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

private fun protect(data: ByteArray): ByteArray = transform(data, protect = true)

private fun unprotect(data: ByteArray): ByteArray = transform(data, protect = false)

private fun transform(data: ByteArray, protect: Boolean): ByteArray {
    val input = blob(data)
    val output = DataBlob()
    val ok = if (protect) {
        Crypt32.INSTANCE.CryptProtectData(input, null, null, null, null, UI_FORBIDDEN, output)
    } else {
        Crypt32.INSTANCE.CryptUnprotectData(input, null, null, null, null, UI_FORBIDDEN, output)
    }
    if (!ok) throw IllegalStateException("Windows DPAPI rejected the session key")
    output.read()
    val pointer = output.pbData ?: throw IllegalStateException("Windows DPAPI returned an empty key")
    val result = pointer.getByteArray(0, output.cbData)
    Kernel32.INSTANCE.LocalFree(pointer)
    return result
}

private fun blob(data: ByteArray): DataBlob {
    val memory = Memory(data.size.toLong())
    memory.write(0, data, 0, data.size)
    return DataBlob().also {
        it.cbData = data.size
        it.pbData = memory
        it.write()
    }
}

@Structure.FieldOrder("cbData", "pbData")
class DataBlob : Structure() {
    @JvmField var cbData: Int = 0
    @JvmField var pbData: Pointer? = null
}

interface Crypt32 : StdCallLibrary {
    fun CryptProtectData(
        inBlob: DataBlob,
        description: Pointer?,
        entropy: Pointer?,
        reserved: Pointer?,
        prompt: Pointer?,
        flags: Int,
        outBlob: DataBlob,
    ): Boolean

    fun CryptUnprotectData(
        inBlob: DataBlob,
        description: Pointer?,
        entropy: Pointer?,
        reserved: Pointer?,
        prompt: Pointer?,
        flags: Int,
        outBlob: DataBlob,
    ): Boolean

    companion object {
        val INSTANCE: Crypt32 = Native.load("Crypt32", Crypt32::class.java)
    }
}

interface Kernel32 : StdCallLibrary {
    fun LocalFree(pointer: Pointer?): Pointer?

    companion object {
        val INSTANCE: Kernel32 = Native.load("Kernel32", Kernel32::class.java)
    }
}

private const val UI_FORBIDDEN = 0x1
