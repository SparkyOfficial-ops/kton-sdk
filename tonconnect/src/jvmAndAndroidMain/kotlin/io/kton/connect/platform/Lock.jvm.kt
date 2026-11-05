package io.kton.connect.platform

internal actual class Lock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
