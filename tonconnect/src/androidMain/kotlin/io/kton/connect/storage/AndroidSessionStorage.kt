package io.kton.connect.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.kton.connect.protocol.ProtocolJson

class AndroidSessionStorage(context: Context) : SessionStorage {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override suspend fun load(): StoredSession? {
        val text = preferences.getString(KEY, null) ?: return null
        return ProtocolJson.decodeFromString(StoredSession.serializer(), text)
    }

    override suspend fun save(session: StoredSession?) {
        preferences.edit().apply {
            if (session == null) remove(KEY) else putString(KEY, ProtocolJson.encodeToString(StoredSession.serializer(), session))
        }.apply()
    }

    private companion object {
        const val FILE = "io.kton.connect.session"
        const val KEY = "session"
    }
}
