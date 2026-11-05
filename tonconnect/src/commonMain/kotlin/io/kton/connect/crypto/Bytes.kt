package io.kton.connect.crypto

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xFF
        append(HEX[value ushr 4])
        append(HEX[value and 0x0F])
    }
}

internal fun String.hexToBytes(): ByteArray {
    val clean = if (startsWith("0x") || startsWith("0X")) substring(2) else this
    require(clean.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(clean.length / 2) { index ->
        val hi = hexValue(clean[index * 2])
        val lo = hexValue(clean[index * 2 + 1])
        ((hi shl 4) or lo).toByte()
    }
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> throw IllegalArgumentException("Invalid hex character '$char'")
}

private const val HEX = "0123456789abcdef"

internal fun ByteArray.toBase64(): String = base64(BASE64, pad = true)

internal fun String.base64ToBytes(): ByteArray = base64Decode(this, url = false)

internal fun ByteArray.toBase64UrlNoPadding(): String = base64(BASE64_URL, pad = false)

internal fun String.base64UrlToBytes(): ByteArray = base64Decode(this, url = true)

private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

private fun ByteArray.base64(alphabet: String, pad: Boolean): String {
    val out = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i + 3 <= size) {
        val n = ((this[i].toInt() and 0xFF) shl 16) or
            ((this[i + 1].toInt() and 0xFF) shl 8) or
            (this[i + 2].toInt() and 0xFF)
        out.append(alphabet[(n ushr 18) and 63])
        out.append(alphabet[(n ushr 12) and 63])
        out.append(alphabet[(n ushr 6) and 63])
        out.append(alphabet[n and 63])
        i += 3
    }
    val rem = size - i
    if (rem == 1) {
        val n = (this[i].toInt() and 0xFF) shl 16
        out.append(alphabet[(n ushr 18) and 63])
        out.append(alphabet[(n ushr 12) and 63])
        if (pad) out.append("==")
    } else if (rem == 2) {
        val n = ((this[i].toInt() and 0xFF) shl 16) or ((this[i + 1].toInt() and 0xFF) shl 8)
        out.append(alphabet[(n ushr 18) and 63])
        out.append(alphabet[(n ushr 12) and 63])
        out.append(alphabet[(n ushr 6) and 63])
        if (pad) out.append('=')
    }
    return out.toString()
}

private fun base64Decode(input: String, url: Boolean): ByteArray {
    val alphabet = if (url) BASE64_URL else BASE64
    val out = ArrayList<Byte>(input.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (char in input) {
        if (char.isWhitespace() || char == '=') continue
        val value = alphabet.indexOf(char)
        require(value >= 0) { "Invalid base64 character '$char'" }
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}
