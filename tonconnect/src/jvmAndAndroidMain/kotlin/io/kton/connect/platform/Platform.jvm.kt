package io.kton.connect.platform

import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureRandom(bytes: ByteArray) {
    random.nextBytes(bytes)
}

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()
