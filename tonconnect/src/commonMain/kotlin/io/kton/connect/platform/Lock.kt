package io.kton.connect.platform

internal expect class Lock() {
    fun <T> withLock(block: () -> T): T
}
