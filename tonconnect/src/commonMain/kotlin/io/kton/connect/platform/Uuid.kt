package io.kton.connect.platform

import io.kton.connect.crypto.toHex

internal fun randomUuid(): String {
    val bytes = ByteArray(16)
    secureRandom(bytes)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.toHex()
    return hex.substring(0, 8) + "-" +
        hex.substring(8, 12) + "-" +
        hex.substring(12, 16) + "-" +
        hex.substring(16, 20) + "-" +
        hex.substring(20)
}
