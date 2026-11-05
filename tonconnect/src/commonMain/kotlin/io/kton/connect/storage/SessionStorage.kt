package io.kton.connect.storage

import kotlinx.serialization.Serializable

/**
 * Where a connected session lives between app launches.
 * The secret key decrypts every message of the session, so the implementation
 * should use encrypted storage on device (for example EncryptedSharedPreferences).
 */
interface SessionStorage {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession?)
}

@Serializable
data class StoredSession(
    val secretKeyHex: String,
    val bridgeUrl: String,
    val walletClientId: String,
    val appName: String,
    val address: String,
    val network: String,
    val publicKeyHex: String,
    val walletStateInit: String,
    val platform: String,
    val appVersion: String,
    val nextRequestId: Long,
    val lastWalletEventId: Long? = null,
    val bridgeLastEventId: String? = null,
    val featuresJson: String = "[]",
)

class MemorySessionStorage : SessionStorage {
    private var current: StoredSession? = null

    override suspend fun load(): StoredSession? = current

    override suspend fun save(session: StoredSession?) {
        current = session
    }
}
