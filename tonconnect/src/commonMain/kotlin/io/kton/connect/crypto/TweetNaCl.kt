package io.kton.connect.crypto

import kotlin.math.floor

/**
 * Session box and Ed25519, line-for-line with tweetnacl (public domain).
 * The HTTP bridge of TonConnect encrypts with NaCl `crypto_box`, the same
 * construction the official JavaScript SDK uses.
 */
internal object TweetNaCl {
    fun scalarmultBase(secret: ByteArray): ByteArray {
        require(secret.size == 32) { "X25519 secret must be 32 bytes" }
        val out = ByteArray(32)
        cryptoScalarmultBase(out, secret)
        return out
    }

    fun box(message: ByteArray, nonce: ByteArray, recipientPublic: ByteArray, senderSecret: ByteArray): ByteArray {
        require(nonce.size == 24 && recipientPublic.size == 32 && senderSecret.size >= 32)
        val key = ByteArray(32)
        cryptoBoxBefore(key, recipientPublic, senderSecret)
        return secretbox(message, nonce, key)
    }

    fun boxOpen(box: ByteArray, nonce: ByteArray, senderPublic: ByteArray, recipientSecret: ByteArray): ByteArray? {
        require(nonce.size == 24 && senderPublic.size == 32 && recipientSecret.size >= 32)
        val key = ByteArray(32)
        cryptoBoxBefore(key, senderPublic, recipientSecret)
        return secretboxOpen(box, nonce, key)
    }

    fun secretbox(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val padded = ByteArray(32 + message.size)
        message.copyInto(padded, 32)
        val boxed = ByteArray(padded.size)
        check(cryptoSecretbox(boxed, padded, padded.size, nonce, key) == 0)
        return boxed.copyOfRange(16, boxed.size)
    }

    fun secretboxOpen(box: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        val padded = ByteArray(16 + box.size)
        box.copyInto(padded, 16)
        val opened = ByteArray(padded.size)
        if (cryptoSecretboxOpen(opened, padded, padded.size, nonce, key) != 0) return null
        return opened.copyOfRange(32, opened.size)
    }

    fun sha512(message: ByteArray): ByteArray {
        val out = ByteArray(64)
        cryptoHash(out, message, message.size)
        return out
    }

    fun signPublicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        val publicKey = ByteArray(32)
        val secretKey = ByteArray(64)
        seed.copyInto(secretKey)
        cryptoSignKeypair(publicKey, secretKey, seeded = true)
        return publicKey
    }

    fun signDetached(message: ByteArray, seed: ByteArray): ByteArray {
        require(seed.size == 32)
        val publicKey = ByteArray(32)
        val secretKey = ByteArray(64)
        seed.copyInto(secretKey)
        cryptoSignKeypair(publicKey, secretKey, seeded = true)
        val signed = ByteArray(64 + message.size)
        cryptoSign(signed, message, secretKey)
        return signed.copyOf(64)
    }

    fun signVerify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val sm = ByteArray(64 + message.size)
        signature.copyInto(sm)
        message.copyInto(sm, 64)
        val opened = ByteArray(sm.size)
        return cryptoSignOpen(opened, sm, publicKey) >= 0
    }
}

private class U64(val hi: Int, val lo: Int)

private val sigma = byteArrayOf(
    101, 120, 112, 97, 110, 100, 32, 51, 50, 45, 98, 121, 116, 101, 32, 107,
)
private val nine = ByteArray(32).also { it[0] = 9 }
private val zero16 = ByteArray(16)

private fun gf(init: IntArray = intArrayOf()): DoubleArray {
    val out = DoubleArray(16)
    for (i in init.indices) out[i] = init[i].toDouble()
    return out
}

private val gf0 = gf()
private val gf1 = gf(intArrayOf(1))
private val d121665 = gf(intArrayOf(0xdb41, 1))
private val D = gf(intArrayOf(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203))
private val D2 = gf(intArrayOf(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406))
private val X = gf(intArrayOf(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169))
private val Y = gf(intArrayOf(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666))
private val I = gf(intArrayOf(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83))
private val L = doubleArrayOf(
    0xed.toDouble(), 0xd3.toDouble(), 0xf5.toDouble(), 0x5c.toDouble(), 0x1a.toDouble(), 0x63.toDouble(), 0x12.toDouble(), 0x58.toDouble(),
    0xd6.toDouble(), 0x9c.toDouble(), 0xf7.toDouble(), 0xa2.toDouble(), 0xde.toDouble(), 0xf9.toDouble(), 0xde.toDouble(), 0x14.toDouble(),
    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 16.0,
)
private val sha512Iv = byteArrayOf(
    0x6a, 0x09, 0xe6.toByte(), 0x67, 0xf3.toByte(), 0xbc.toByte(), 0xc9.toByte(), 0x08,
    0xbb.toByte(), 0x67, 0xae.toByte(), 0x85.toByte(), 0x84.toByte(), 0xca.toByte(), 0xa7.toByte(), 0x3b,
    0x3c, 0x6e, 0xf3.toByte(), 0x72, 0xfe.toByte(), 0x94.toByte(), 0xf8.toByte(), 0x2b,
    0xa5.toByte(), 0x4f, 0xf5.toByte(), 0x3a, 0x5f, 0x1d, 0x36, 0xf1.toByte(),
    0x51, 0x0e, 0x52, 0x7f, 0xad.toByte(), 0xe6.toByte(), 0x82.toByte(), 0xd1.toByte(),
    0x9b.toByte(), 0x05, 0x68, 0x8c.toByte(), 0x2b, 0x3e, 0x6c, 0x1f,
    0x1f, 0x83.toByte(), 0xd9.toByte(), 0xab.toByte(), 0xfb.toByte(), 0x41, 0xbd.toByte(), 0x6b,
    0x5b, 0xe0.toByte(), 0xcd.toByte(), 0x19, 0x13, 0x7e, 0x21, 0x79,
)

private val sha512K = arrayOf(
    U64(0x428a2f98, 0xd728ae22.toInt()), U64(0x71374491, 0x23ef65cd),
    U64(0xb5c0fbcf.toInt(), 0xec4d3b2f.toInt()), U64(0xe9b5dba5.toInt(), 0x8189dbbc.toInt()),
    U64(0x3956c25b, 0xf348b538.toInt()), U64(0x59f111f1, 0xb605d019.toInt()),
    U64(0x923f82a4.toInt(), 0xaf194f9b.toInt()), U64(0xab1c5ed5.toInt(), 0xda6d8118.toInt()),
    U64(0xd807aa98.toInt(), 0xa3030242.toInt()), U64(0x12835b01, 0x45706fbe),
    U64(0x243185be, 0x4ee4b28c), U64(0x550c7dc3, 0xd5ffb4e2.toInt()),
    U64(0x72be5d74, 0xf27b896f.toInt()), U64(0x80deb1fe.toInt(), 0x3b1696b1),
    U64(0x9bdc06a7.toInt(), 0x25c71235), U64(0xc19bf174.toInt(), 0xcf692694.toInt()),
    U64(0xe49b69c1.toInt(), 0x9ef14ad2.toInt()), U64(0xefbe4786.toInt(), 0x384f25e3),
    U64(0x0fc19dc6, 0x8b8cd5b5.toInt()), U64(0x240ca1cc, 0x77ac9c65),
    U64(0x2de92c6f, 0x592b0275), U64(0x4a7484aa, 0x6ea6e483),
    U64(0x5cb0a9dc, 0xbd41fbd4.toInt()), U64(0x76f988da, 0x831153b5.toInt()),
    U64(0x983e5152.toInt(), 0xee66dfab.toInt()), U64(0xa831c66d.toInt(), 0x2db43210),
    U64(0xb00327c8.toInt(), 0x98fb213f.toInt()), U64(0xbf597fc7.toInt(), 0xbeef0ee4.toInt()),
    U64(0xc6e00bf3.toInt(), 0x3da88fc2), U64(0xd5a79147.toInt(), 0x930aa725.toInt()),
    U64(0x06ca6351, 0xe003826f.toInt()), U64(0x14292967, 0x0a0e6e70),
    U64(0x27b70a85, 0x46d22ffc), U64(0x2e1b2138, 0x5c26c926),
    U64(0x4d2c6dfc, 0x5ac42aed), U64(0x53380d13, 0x9d95b3df.toInt()),
    U64(0x650a7354, 0x8baf63de.toInt()), U64(0x766a0abb, 0x3c77b2a8),
    U64(0x81c2c92e.toInt(), 0x47edaee6), U64(0x92722c85.toInt(), 0x1482353b),
    U64(0xa2bfe8a1.toInt(), 0x4cf10364), U64(0xa81a664b.toInt(), 0xbc423001.toInt()),
    U64(0xc24b8b70.toInt(), 0xd0f89791.toInt()), U64(0xc76c51a3.toInt(), 0x0654be30),
    U64(0xd192e819.toInt(), 0xd6ef5218.toInt()), U64(0xd6990624.toInt(), 0x5565a910),
    U64(0xf40e3585.toInt(), 0x5771202a), U64(0x106aa070, 0x32bbd1b8),
    U64(0x19a4c116, 0xb8d2d0c8.toInt()), U64(0x1e376c08, 0x5141ab53),
    U64(0x2748774c, 0xdf8eeb99.toInt()), U64(0x34b0bcb5, 0xe19b48a8.toInt()),
    U64(0x391c0cb3, 0xc5c95a63.toInt()), U64(0x4ed8aa4a, 0xe3418acb.toInt()),
    U64(0x5b9cca4f, 0x7763e373), U64(0x682e6ff3, 0xd6b2b8a3.toInt()),
    U64(0x748f82ee, 0x5defb2fc), U64(0x78a5636f, 0x43172f60),
    U64(0x84c87814.toInt(), 0xa1f0ab72.toInt()), U64(0x8cc70208.toInt(), 0x1a6439ec),
    U64(0x90befffa.toInt(), 0x23631e28), U64(0xa4506ceb.toInt(), 0xde82bde9.toInt()),
    U64(0xbef9a3f7.toInt(), 0xb2c67915.toInt()), U64(0xc67178f2.toInt(), 0xe372532b.toInt()),
    U64(0xca273ece.toInt(), 0xea26619c.toInt()), U64(0xd186b8c7.toInt(), 0x21c0c207),
    U64(0xeada7dd6.toInt(), 0xcde0eb1e.toInt()), U64(0xf57d4f7f.toInt(), 0xee6ed178.toInt()),
    U64(0x06f067aa, 0x72176fba), U64(0x0a637dc5, 0xa2c898a6.toInt()),
    U64(0x113f9804, 0xbef90dae.toInt()), U64(0x1b710b35, 0x131c471b),
    U64(0x28db77f5, 0x23047d84), U64(0x32caab7b, 0x40c72493),
    U64(0x3c9ebe0a, 0x15c9bebc), U64(0x431d67c4, 0x9c100d4c.toInt()),
    U64(0x4cc5d4be, 0xcb3e42b6.toInt()), U64(0x597f299c, 0xfc657e2a.toInt()),
    U64(0x5fcb6fab, 0x3ad6faec), U64(0x6c44198c, 0x4a475817),
)

private fun Double.toJsInt32(): Int {
    if (!isFinite()) return 0
    val negative = this < 0
    val magnitude = floor(kotlin.math.abs(this))
    val two32 = 4294967296.0
    var mod = magnitude % two32
    if (negative && mod != 0.0) mod = two32 - mod
    return mod.toLong().toInt()
}

private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

private fun ld32(x: ByteArray, i: Int): Int =
    (u8(x, i + 3) shl 24) or (u8(x, i + 2) shl 16) or (u8(x, i + 1) shl 8) or u8(x, i)

private fun st32(x: ByteArray, j: Int, value: Int) {
    var u = value
    for (i in 0 until 4) {
        x[j + i] = (u and 255).toByte()
        u = u ushr 8
    }
}

private fun L32(x: Int, c: Int): Int = (x shl c) or (x ushr (32 - c))

private fun vn(x: ByteArray, xi: Int, y: ByteArray, yi: Int, n: Int): Int {
    var d = 0
    for (i in 0 until n) d = d or (u8(x, xi + i) xor u8(y, yi + i))
    return (1 and ((d - 1) ushr 8)) - 1
}

private fun core(out: ByteArray, inp: ByteArray, k: ByteArray, c: ByteArray, h: Boolean) {
    val w = IntArray(16)
    val x = IntArray(16)
    val y = IntArray(16)
    val t = IntArray(4)
    for (i in 0 until 4) {
        x[5 * i] = ld32(c, 4 * i)
        x[1 + i] = ld32(k, 4 * i)
        x[6 + i] = ld32(inp, 4 * i)
        x[11 + i] = ld32(k, 16 + 4 * i)
    }
    for (i in 0 until 16) y[i] = x[i]
    for (round in 0 until 20) {
        for (j in 0 until 4) {
            for (m in 0 until 4) t[m] = x[(5 * j + 4 * m) % 16]
            t[1] = t[1] xor L32(t[0] + t[3], 7)
            t[2] = t[2] xor L32(t[1] + t[0], 9)
            t[3] = t[3] xor L32(t[2] + t[1], 13)
            t[0] = t[0] xor L32(t[3] + t[2], 18)
            for (m in 0 until 4) w[4 * j + (j + m) % 4] = t[m]
        }
        for (m in 0 until 16) x[m] = w[m]
    }
    if (h) {
        for (i in 0 until 16) x[i] = x[i] + y[i]
        for (i in 0 until 4) {
            x[5 * i] = x[5 * i] - ld32(c, 4 * i)
            x[6 + i] = x[6 + i] - ld32(inp, 4 * i)
        }
        for (i in 0 until 4) {
            st32(out, 4 * i, x[5 * i])
            st32(out, 16 + 4 * i, x[6 + i])
        }
    } else {
        for (i in 0 until 16) st32(out, 4 * i, x[i] + y[i])
    }
}

private fun cryptoStreamSalsa20Xor(c: ByteArray, cpos: Int, m: ByteArray?, mpos0: Int, length: Int, n: ByteArray, k: ByteArray) {
    val z = ByteArray(16)
    val block = ByteArray(64)
    for (i in 0 until 8) z[i] = n[i]
    var b = length
    var cp = cpos
    var mp = mpos0
    while (b >= 64) {
        core(block, z, k, sigma, false)
        for (i in 0 until 64) c[cp + i] = ((if (m != null) u8(m, mp + i) else 0) xor u8(block, i)).toByte()
        var carry = 1
        for (i in 8 until 16) {
            carry += u8(z, i)
            z[i] = (carry and 0xFF).toByte()
            carry = carry ushr 8
        }
        b -= 64
        cp += 64
        if (m != null) mp += 64
    }
    if (b > 0) {
        core(block, z, k, sigma, false)
        for (i in 0 until b) c[cp + i] = ((if (m != null) u8(m, mp + i) else 0) xor u8(block, i)).toByte()
    }
}

private fun cryptoStreamXor(c: ByteArray, m: ByteArray?, length: Int, nonce: ByteArray, key: ByteArray) {
    val subkey = ByteArray(32)
    core(subkey, nonce, key, sigma, true)
    val salsaNonce = nonce.copyOfRange(16, 24)
    cryptoStreamSalsa20Xor(c, 0, m, 0, length, salsaNonce, subkey)
}

private fun cryptoStream(c: ByteArray, length: Int, nonce: ByteArray, key: ByteArray) {
    cryptoStreamXor(c, null, length, nonce, key)
}

private val minusp = intArrayOf(5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 252)

private fun add1305(h: IntArray, c: IntArray) {
    var u = 0
    for (j in 0 until 17) {
        u += h[j] + c[j]
        h[j] = u and 255
        u = u ushr 8
    }
}

private fun cryptoOnetimeAuth(out: ByteArray, outpos: Int, m: ByteArray, mpos0: Int, length: Int, k: ByteArray) {
    val x = IntArray(17)
    val r = IntArray(17)
    val h = IntArray(17)
    val c = IntArray(17)
    val g = IntArray(17)
    for (j in 0 until 16) r[j] = u8(k, j)
    r[3] = r[3] and 15
    r[4] = r[4] and 252
    r[7] = r[7] and 15
    r[8] = r[8] and 252
    r[11] = r[11] and 15
    r[12] = r[12] and 252
    r[15] = r[15] and 15
    var n = length
    var mpos = mpos0
    while (n > 0) {
        for (j in 0 until 17) c[j] = 0
        var j = 0
        while (j < 16 && j < n) {
            c[j] = u8(m, mpos + j)
            j++
        }
        c[j] = 1
        mpos += j
        n -= j
        add1305(h, c)
        for (i in 0 until 17) {
            x[i] = 0
            for (jj in 0 until 17) {
                val factor = if (jj <= i) r[i - jj] else 320 * r[i + 17 - jj]
                x[i] = x[i] + h[jj] * factor
            }
        }
        for (i in 0 until 17) h[i] = x[i]
        var u = 0
        for (jj in 0 until 16) {
            u += h[jj]
            h[jj] = u and 255
            u = u ushr 8
        }
        u += h[16]
        h[16] = u and 3
        u = 5 * (u ushr 2)
        for (jj in 0 until 16) {
            u += h[jj]
            h[jj] = u and 255
            u = u ushr 8
        }
        u += h[16]
        h[16] = u
    }
    for (j in 0 until 17) g[j] = h[j]
    add1305(h, minusp)
    val s = -(h[16] ushr 7)
    for (j in 0 until 17) h[j] = h[j] xor (s and (g[j] xor h[j]))
    for (j in 0 until 16) c[j] = u8(k, j + 16)
    c[16] = 0
    add1305(h, c)
    for (j in 0 until 16) out[outpos + j] = (h[j] and 255).toByte()
}

private fun cryptoSecretbox(c: ByteArray, m: ByteArray, d: Int, n: ByteArray, k: ByteArray): Int {
    if (d < 32) return -1
    cryptoStreamXor(c, m, d, n, k)
    cryptoOnetimeAuth(c, 16, c, 32, d - 32, c)
    for (i in 0 until 16) c[i] = 0
    return 0
}

private fun cryptoSecretboxOpen(m: ByteArray, c: ByteArray, d: Int, n: ByteArray, k: ByteArray): Int {
    if (d < 32) return -1
    val polyKey = ByteArray(32)
    cryptoStream(polyKey, 32, n, k)
    val mac = ByteArray(16)
    cryptoOnetimeAuth(mac, 0, c, 32, d - 32, polyKey)
    if (vn(c, 16, mac, 0, 16) != 0) return -1
    cryptoStreamXor(m, c, d, n, k)
    for (i in 0 until 32) m[i] = 0
    return 0
}

private fun car25519(o: DoubleArray) {
    for (i in 0 until 16) {
        o[i] += 65536.0
        val c = floor(o[i] / 65536.0)
        val idx = if (i < 15) i + 1 else 0
        val extra = if (i == 15) 37.0 * (c - 1) else 0.0
        o[idx] += c - 1 + extra
        o[i] -= c * 65536.0
    }
}

private fun sel25519(p: DoubleArray, q: DoubleArray, b: Int) {
    val c = (b - 1).inv()
    for (i in 0 until 16) {
        val pi = p[i].toJsInt32()
        val qi = q[i].toJsInt32()
        val t = c and (pi xor qi)
        p[i] = (pi xor t).toDouble()
        q[i] = (qi xor t).toDouble()
    }
}

private fun pack25519(o: ByteArray, n: DoubleArray) {
    val m = DoubleArray(16)
    val t = DoubleArray(16)
    for (i in 0 until 16) t[i] = n[i]
    car25519(t)
    car25519(t)
    car25519(t)
    for (round in 0 until 2) {
        m[0] = t[0] - 0xffed
        for (i in 1 until 15) {
            m[i] = t[i] - 0xffff - ((m[i - 1].toJsInt32() shr 16) and 1)
            m[i - 1] = (m[i - 1].toJsInt32() and 0xffff).toDouble()
        }
        m[15] = t[15] - 0x7fff - ((m[14].toJsInt32() shr 16) and 1)
        val borrow = (m[15].toJsInt32() shr 16) and 1
        m[14] = (m[14].toJsInt32() and 0xffff).toDouble()
        sel25519(t, m, 1 - borrow)
    }
    for (i in 0 until 16) {
        val ti = t[i].toJsInt32()
        o[2 * i] = (ti and 0xff).toByte()
        o[2 * i + 1] = (ti shr 8).toByte()
    }
}

private fun unpack25519(o: DoubleArray, n: ByteArray) {
    for (i in 0 until 16) o[i] = (u8(n, 2 * i) + (u8(n, 2 * i + 1) shl 8)).toDouble()
    o[15] = (o[15].toJsInt32() and 0x7fff).toDouble()
}

private fun set25519(r: DoubleArray, a: DoubleArray) {
    for (i in 0 until 16) r[i] = a[i].toJsInt32().toDouble()
}

private fun A(o: DoubleArray, a: DoubleArray, b: DoubleArray) {
    for (i in 0 until 16) o[i] = (a[i] + b[i]).toJsInt32().toDouble()
}

private fun Z(o: DoubleArray, a: DoubleArray, b: DoubleArray) {
    for (i in 0 until 16) o[i] = (a[i] - b[i]).toJsInt32().toDouble()
}

private fun M(o: DoubleArray, a: DoubleArray, b: DoubleArray) {
    val t = DoubleArray(31)
    for (i in 0 until 16) {
        for (j in 0 until 16) t[i + j] += a[i] * b[j]
    }
    for (i in 0 until 15) t[i] += 38 * t[i + 16]
    for (i in 0 until 16) o[i] = t[i]
    car25519(o)
    car25519(o)
}

private fun S(o: DoubleArray, a: DoubleArray) = M(o, a, a)

private fun inv25519(o: DoubleArray, input: DoubleArray) {
    val c = gf()
    for (i in 0 until 16) c[i] = input[i]
    for (a in 253 downTo 0) {
        S(c, c)
        if (a != 2 && a != 4) M(c, c, input)
    }
    for (i in 0 until 16) o[i] = c[i]
}

private fun pow2523(o: DoubleArray, input: DoubleArray) {
    val c = gf()
    for (i in 0 until 16) c[i] = input[i]
    for (a in 250 downTo 0) {
        S(c, c)
        if (a != 1) M(c, c, input)
    }
    for (i in 0 until 16) o[i] = c[i]
}

private fun cryptoScalarmult(q: ByteArray, n: ByteArray, p: ByteArray) {
    val z = ByteArray(32)
    for (i in 0 until 31) z[i] = n[i]
    z[31] = ((u8(n, 31) and 127) or 64).toByte()
    z[0] = (u8(z, 0) and 248).toByte()
    val x = DoubleArray(16)
    unpack25519(x, p)
    val a = gf()
    val b = gf()
    val c = gf()
    val d = gf()
    val e = gf()
    val f = gf()
    for (i in 0 until 16) b[i] = x[i]
    a[0] = 1.0
    d[0] = 1.0
    for (i in 254 downTo 0) {
        val r = (u8(z, i ushr 3) ushr (i and 7)) and 1
        sel25519(a, b, r)
        sel25519(c, d, r)
        A(e, a, c)
        Z(a, a, c)
        A(c, b, d)
        Z(b, b, d)
        S(d, e)
        S(f, a)
        M(a, c, a)
        M(c, b, e)
        A(e, a, c)
        Z(a, a, c)
        S(b, a)
        Z(c, d, f)
        M(a, c, d121665)
        A(a, a, d)
        M(c, c, a)
        M(a, d, f)
        M(d, b, x)
        S(b, e)
        sel25519(a, b, r)
        sel25519(c, d, r)
    }
    val x16 = DoubleArray(16)
    val x32 = DoubleArray(16)
    for (i in 0 until 16) {
        x16[i] = a[i]
        x32[i] = c[i]
    }
    inv25519(x32, x32)
    M(x16, x16, x32)
    pack25519(q, x16)
}

private fun cryptoScalarmultBase(q: ByteArray, n: ByteArray) = cryptoScalarmult(q, n, nine)

private fun cryptoBoxBefore(k: ByteArray, publicKey: ByteArray, secretKey: ByteArray) {
    val shared = ByteArray(32)
    cryptoScalarmult(shared, secretKey, publicKey)
    core(k, zero16, shared, sigma, true)
}

private fun dl64(x: ByteArray, i: Int): U64 {
    val h = (u8(x, i) shl 24) or (u8(x, i + 1) shl 16) or (u8(x, i + 2) shl 8) or u8(x, i + 3)
    val l = (u8(x, i + 4) shl 24) or (u8(x, i + 5) shl 16) or (u8(x, i + 6) shl 8) or u8(x, i + 7)
    return U64(h, l)
}

private fun ts64(x: ByteArray, i: Int, u: U64) {
    x[i] = ((u.hi ushr 24) and 0xFF).toByte()
    x[i + 1] = ((u.hi ushr 16) and 0xFF).toByte()
    x[i + 2] = ((u.hi ushr 8) and 0xFF).toByte()
    x[i + 3] = (u.hi and 0xFF).toByte()
    x[i + 4] = ((u.lo ushr 24) and 0xFF).toByte()
    x[i + 5] = ((u.lo ushr 16) and 0xFF).toByte()
    x[i + 6] = ((u.lo ushr 8) and 0xFF).toByte()
    x[i + 7] = (u.lo and 0xFF).toByte()
}

private fun add64(vararg xs: U64): U64 {
    var a = 0
    var b = 0
    var c = 0
    var d = 0
    val m16 = 65535
    for (x in xs) {
        a += x.lo and m16
        b += x.lo ushr 16
        c += x.hi and m16
        d += x.hi ushr 16
    }
    b += a ushr 16
    c += b ushr 16
    d += c ushr 16
    return U64((c and m16) or (d shl 16), (a and m16) or (b shl 16))
}

private fun shr64(x: U64, c: Int) = U64(x.hi ushr c, (x.lo ushr c) or (x.hi shl (32 - c)))

private fun xor64(vararg xs: U64): U64 {
    var hi = 0
    var lo = 0
    for (x in xs) {
        hi = hi xor x.hi
        lo = lo xor x.lo
    }
    return U64(hi, lo)
}

private fun R(x: U64, c: Int): U64 {
    val c1 = 32 - c
    return if (c < 32) {
        U64((x.hi ushr c) or (x.lo shl c1), (x.lo ushr c) or (x.hi shl c1))
    } else {
        U64((x.lo ushr c) or (x.hi shl c1), (x.hi ushr c) or (x.lo shl c1))
    }
}

private fun Ch(x: U64, y: U64, z: U64) = U64(
    (x.hi and y.hi) xor (x.hi.inv() and z.hi),
    (x.lo and y.lo) xor (x.lo.inv() and z.lo),
)

private fun Maj(x: U64, y: U64, z: U64) = U64(
    (x.hi and y.hi) xor (x.hi and z.hi) xor (y.hi and z.hi),
    (x.lo and y.lo) xor (x.lo and z.lo) xor (y.lo and z.lo),
)

private fun Sigma0(x: U64) = xor64(R(x, 28), R(x, 34), R(x, 39))
private fun Sigma1(x: U64) = xor64(R(x, 14), R(x, 18), R(x, 41))
private fun sigma0(x: U64) = xor64(R(x, 1), R(x, 8), shr64(x, 7))
private fun sigma1(x: U64) = xor64(R(x, 19), R(x, 61), shr64(x, 6))

private fun cryptoHashBlocks(state: ByteArray, message: ByteArray, length: Int): Int {
    val z = arrayOfNulls<U64>(8)
    val a = arrayOfNulls<U64>(8)
    val b = arrayOfNulls<U64>(8)
    val w = arrayOfNulls<U64>(16)
    for (i in 0 until 8) {
        val word = dl64(state, 8 * i)
        z[i] = word
        a[i] = word
    }
    var n = length
    var pos = 0
    while (n >= 128) {
        for (i in 0 until 16) w[i] = dl64(message, 8 * i + pos)
        for (i in 0 until 80) {
            for (j in 0 until 8) b[j] = a[j]
            val t = add64(a[7]!!, Sigma1(a[4]!!), Ch(a[4]!!, a[5]!!, a[6]!!), sha512K[i], w[i % 16]!!)
            b[7] = add64(t, Sigma0(a[0]!!), Maj(a[0]!!, a[1]!!, a[2]!!))
            b[3] = add64(b[3]!!, t)
            for (j in 0 until 8) a[(j + 1) % 8] = b[j]
            if (i % 16 == 15) {
                for (j in 0 until 16) {
                    w[j] = add64(w[j]!!, w[(j + 9) % 16]!!, sigma0(w[(j + 1) % 16]!!), sigma1(w[(j + 14) % 16]!!))
                }
            }
        }
        for (i in 0 until 8) {
            a[i] = add64(a[i]!!, z[i]!!)
            z[i] = a[i]
        }
        pos += 128
        n -= 128
    }
    for (i in 0 until 8) ts64(state, 8 * i, z[i]!!)
    return n
}

private fun cryptoHash(out: ByteArray, message: ByteArray, length: Int) {
    val h = ByteArray(64)
    sha512Iv.copyInto(h)
    cryptoHashBlocks(h, message, length)
    var n = length % 128
    val x = ByteArray(256)
    for (i in 0 until n) x[i] = message[length - n + i]
    x[n] = 128.toByte()
    n = 256 - 128 * (if (n < 112) 1 else 0)
    val bitHi = length ushr 29
    val bitLo = length shl 3
    ts64(x, n - 8, U64(bitHi, bitLo))
    cryptoHashBlocks(h, x, n)
    h.copyInto(out)
}

private fun add(p: Array<DoubleArray>, q: Array<DoubleArray>) {
    val a = gf(); val b = gf(); val c = gf(); val d = gf()
    val e = gf(); val f = gf(); val g = gf(); val h = gf(); val t = gf()
    Z(a, p[1], p[0])
    Z(t, q[1], q[0])
    M(a, a, t)
    A(b, p[0], p[1])
    A(t, q[0], q[1])
    M(b, b, t)
    M(c, p[3], q[3])
    M(c, c, D2)
    M(d, p[2], q[2])
    A(d, d, d)
    Z(e, b, a)
    Z(f, d, c)
    A(g, d, c)
    A(h, b, a)
    M(p[0], e, f)
    M(p[1], h, g)
    M(p[2], g, f)
    M(p[3], e, h)
}

private fun cswap(p: Array<DoubleArray>, q: Array<DoubleArray>, b: Int) {
    for (i in 0 until 4) sel25519(p[i], q[i], b)
}

private fun pack(r: ByteArray, p: Array<DoubleArray>) {
    val tx = gf(); val ty = gf(); val zi = gf()
    inv25519(zi, p[2])
    M(tx, p[0], zi)
    M(ty, p[1], zi)
    pack25519(r, ty)
    r[31] = (u8(r, 31) xor (par25519(tx) shl 7)).toByte()
}

private fun par25519(a: DoubleArray): Int {
    val d = ByteArray(32)
    pack25519(d, a)
    return u8(d, 0) and 1
}

private fun neq25519(a: DoubleArray, b: DoubleArray): Int {
    val c = ByteArray(32)
    val d = ByteArray(32)
    pack25519(c, a)
    pack25519(d, b)
    return vn(c, 0, d, 0, 32)
}

private fun scalarmult(p: Array<DoubleArray>, q: Array<DoubleArray>, s: ByteArray) {
    set25519(p[0], gf0)
    set25519(p[1], gf1)
    set25519(p[2], gf1)
    set25519(p[3], gf0)
    for (i in 255 downTo 0) {
        val bit = (u8(s, i / 8) shr (i and 7)) and 1
        cswap(p, q, bit)
        add(q, p)
        add(p, p)
        cswap(p, q, bit)
    }
}

private fun scalarbase(p: Array<DoubleArray>, s: ByteArray) {
    val q = Array(4) { gf() }
    set25519(q[0], X)
    set25519(q[1], Y)
    set25519(q[2], gf1)
    M(q[3], X, Y)
    scalarmult(p, q, s)
}

private fun cryptoSignKeypair(publicKey: ByteArray, secretKey: ByteArray, seeded: Boolean) {
    val d = ByteArray(64)
    val p = Array(4) { gf() }
    cryptoHash(d, secretKey.copyOf(32), 32)
    d[0] = (u8(d, 0) and 248).toByte()
    d[31] = ((u8(d, 31) and 127) or 64).toByte()
    scalarbase(p, d)
    pack(publicKey, p)
    publicKey.copyInto(secretKey, 32)
}

private fun modL(r: ByteArray, rOff: Int, x: DoubleArray) {
    for (i in 63 downTo 32) {
        var carry = 0.0
        var j = i - 32
        val k = i - 12
        while (j < k) {
            x[j] += carry - 16 * x[i] * L[j - (i - 32)]
            carry = floor((x[j] + 128) / 256)
            x[j] -= carry * 256
            j++
        }
        x[j] += carry
        x[i] = 0.0
    }
    var carry = 0.0
    for (j in 0 until 32) {
        x[j] += carry - (x[31].toJsInt32() shr 4) * L[j]
        carry = (x[j].toJsInt32() shr 8).toDouble()
        x[j] = (x[j].toJsInt32() and 255).toDouble()
    }
    for (j in 0 until 32) x[j] -= carry * L[j]
    for (i in 0 until 32) {
        x[i + 1] += (x[i].toJsInt32() shr 8).toDouble()
        r[rOff + i] = (x[i].toJsInt32() and 255).toByte()
    }
}

private fun reduce(r: ByteArray) {
    val x = DoubleArray(64)
    for (i in 0 until 64) x[i] = u8(r, i).toDouble()
    for (i in 0 until 64) r[i] = 0
    modL(r, 0, x)
}

private fun cryptoSign(sm: ByteArray, message: ByteArray, secretKey: ByteArray) {
    val d = ByteArray(64)
    val h = ByteArray(64)
    val r = ByteArray(64)
    val x = DoubleArray(64)
    val p = Array(4) { gf() }
    cryptoHash(d, secretKey.copyOf(32), 32)
    d[0] = (u8(d, 0) and 248).toByte()
    d[31] = ((u8(d, 31) and 127) or 64).toByte()
    val n = message.size
    message.copyInto(sm, 64)
    d.copyInto(sm, 32, 32, 64)
    val nonceInput = sm.copyOfRange(32, 32 + n + 32)
    cryptoHash(r, nonceInput, nonceInput.size)
    reduce(r)
    scalarbase(p, r)
    pack(sm, p)
    secretKey.copyInto(sm, 32, 32, 64)
    cryptoHash(h, sm.copyOf(n + 64), n + 64)
    reduce(h)
    for (i in 0 until 32) x[i] = u8(r, i).toDouble()
    for (i in 0 until 32) {
        for (j in 0 until 32) x[i + j] += u8(h, i) * u8(d, j).toDouble()
    }
    modL(sm, 32, x)
}

private fun unpackneg(r: Array<DoubleArray>, p: ByteArray): Int {
    val t = gf(); val chk = gf(); val num = gf(); val den = gf()
    val den2 = gf(); val den4 = gf(); val den6 = gf()
    set25519(r[2], gf1)
    unpack25519(r[1], p)
    S(num, r[1])
    M(den, num, D)
    Z(num, num, r[2])
    A(den, r[2], den)
    S(den2, den)
    S(den4, den2)
    M(den6, den4, den2)
    M(t, den6, num)
    M(t, t, den)
    pow2523(t, t)
    M(t, t, num)
    M(t, t, den)
    M(t, t, den)
    M(r[0], t, den)
    S(chk, r[0])
    M(chk, chk, den)
    if (neq25519(chk, num) != 0) M(r[0], r[0], I)
    S(chk, r[0])
    M(chk, chk, den)
    if (neq25519(chk, num) != 0) return -1
    if (par25519(r[0]) == (u8(p, 31) shr 7)) Z(r[0], gf0, r[0])
    M(r[3], r[0], r[1])
    return 0
}

private fun cryptoSignOpen(m: ByteArray, sm: ByteArray, publicKey: ByteArray): Int {
    if (sm.size < 64) return -1
    val t = ByteArray(32)
    val h = ByteArray(64)
    val p = Array(4) { gf() }
    val q = Array(4) { gf() }
    if (unpackneg(q, publicKey) != 0) return -1
    sm.copyInto(m)
    publicKey.copyInto(m, 32)
    cryptoHash(h, m.copyOf(sm.size), sm.size)
    reduce(h)
    scalarmult(p, q, h)
    scalarbase(q, sm.copyOfRange(32, 64))
    add(p, q)
    pack(t, p)
    val messageLength = sm.size - 64
    if (vn(sm, 0, t, 0, 32) != 0) return -1
    for (i in 0 until messageLength) m[i] = sm[i + 64]
    return messageLength
}
