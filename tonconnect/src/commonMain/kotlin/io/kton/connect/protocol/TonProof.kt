package io.kton.connect.protocol

import io.kton.connect.crypto.TweetNaCl
import io.kton.connect.crypto.base64ToBytes
import io.kton.connect.crypto.hexToBytes
import io.kton.connect.crypto.sha256
import io.kton.connect.platform.currentTimeMillis

/**
 * Checks a `ton_proof` login signature against [publicKeyHex].
 *
 * Call [verifyWalletIdentity] as well: it checks that this key and [address]
 * are the ones committed in `walletStateInit`. [TonConnect] does both when a
 * wallet connects.
 */
fun verifyTonProof(
    address: String,
    publicKeyHex: String,
    proof: TonProof,
    expectedDomain: String,
    expectedPayload: String,
    nowSeconds: Long = currentTimeMillis() / 1000,
    maxAgeSeconds: Long = 15 * 60,
): Boolean {
    if (proof.domain != expectedDomain || proof.payload != expectedPayload) return false
    if (proof.domain.encodeToByteArray().size != proof.domainLengthBytes) return false
    if (kotlin.math.abs(nowSeconds - proof.timestamp) > maxAgeSeconds) return false
    val publicKey = runCatching { publicKeyHex.hexToBytes() }.getOrNull() ?: return false
    if (publicKey.size != 32) return false
    val signature = runCatching { proof.signatureBase64.base64ToBytes() }.getOrNull() ?: return false
    val message = tonProofSigningHash(address, proof.domain, proof.timestamp, proof.payload)
    return TweetNaCl.signVerify(message, signature, publicKey)
}

internal fun tonProofSigningHash(address: String, domain: String, timestamp: Long, payload: String): ByteArray {
    val (workchain, hash) = parseTonAddress(address)
    val prefix = "ton-proof-item-v2/".encodeToByteArray()
    val domainBytes = domain.encodeToByteArray()
    val payloadBytes = payload.encodeToByteArray()
    val message = ByteArray(prefix.size + 4 + hash.size + 4 + domainBytes.size + 8 + payloadBytes.size)
    var offset = 0
    prefix.copyInto(message, offset)
    offset += prefix.size
    writeInt32BE(message, offset, workchain)
    offset += 4
    hash.copyInto(message, offset)
    offset += 32
    writeInt32LE(message, offset, domainBytes.size)
    offset += 4
    domainBytes.copyInto(message, offset)
    offset += domainBytes.size
    writeInt64LE(message, offset, timestamp)
    offset += 8
    payloadBytes.copyInto(message, offset)
    val messageHash = sha256(message)
    val prefixed = ByteArray(2 + "ton-connect".length + messageHash.size)
    prefixed[0] = 0xFF.toByte()
    prefixed[1] = 0xFF.toByte()
    "ton-connect".encodeToByteArray().copyInto(prefixed, 2)
    messageHash.copyInto(prefixed, 2 + "ton-connect".length)
    return sha256(prefixed)
}

internal fun parseTonAddress(address: String): Pair<Int, ByteArray> {
    if (':' in address) {
        val (workchain, hex) = address.split(':', limit = 2)
        val hash = hex.hexToBytes()
        require(hash.size == 32) { "Raw address hash must be 32 bytes" }
        return workchain.toInt() to hash
    }
    val raw = address.base64UrlToBytesLenient()
    require(raw.size == 36) { "Friendly address must be 36 bytes" }
    val crc = crc16(raw.copyOf(34))
    val expected = ((raw[34].toInt() and 0xFF) shl 8) or (raw[35].toInt() and 0xFF)
    require(crc == expected) { "Address checksum mismatch" }
    return raw[1].toInt() to raw.copyOfRange(2, 34)
}

private fun String.base64UrlToBytesLenient(): ByteArray {
    val normalized = buildString(length) {
        for (char in this@base64UrlToBytesLenient) {
            append(
                when (char) {
                    '-' -> '+'
                    '_' -> '/'
                    else -> char
                }
            )
        }
    }
    return normalized.base64ToBytes()
}

internal fun crc16(data: ByteArray): Int {
    var crc = 0
    for (byte in data) {
        crc = crc xor ((byte.toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
            crc = crc and 0xFFFF
        }
    }
    return crc
}

private fun writeInt32BE(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value ushr 24).toByte()
    out[offset + 1] = (value ushr 16).toByte()
    out[offset + 2] = (value ushr 8).toByte()
    out[offset + 3] = value.toByte()
}

private fun writeInt32LE(out: ByteArray, offset: Int, value: Int) {
    out[offset] = value.toByte()
    out[offset + 1] = (value ushr 8).toByte()
    out[offset + 2] = (value ushr 16).toByte()
    out[offset + 3] = (value ushr 24).toByte()
}

private fun writeInt64LE(out: ByteArray, offset: Int, value: Long) {
    var current = value
    for (i in 0 until 8) {
        out[offset + i] = current.toByte()
        current = current ushr 8
    }
}
