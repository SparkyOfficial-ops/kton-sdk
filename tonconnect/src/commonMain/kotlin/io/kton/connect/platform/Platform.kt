package io.kton.connect.platform

internal expect fun secureRandom(bytes: ByteArray)

internal expect fun currentTimeMillis(): Long
