package io.kton.connect

import io.kton.connect.storage.StoredSession
import io.kton.connect.storage.encryptedSessionStorage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStorageTest {
    @Test
    fun encryptedFileSurvivesANewProcess() = runBlocking {
        val directory = File("build/session-test-${System.nanoTime()}").apply { mkdirs() }
        val session = StoredSession(
            secretKeyHex = "ab".repeat(32),
            bridgeUrl = "https://bridge.tonapi.io/bridge",
            walletClientId = "cd".repeat(32),
            appName = "tonkeeper",
            address = "0:" + "11".repeat(32),
            network = "-239",
            publicKeyHex = "22".repeat(32),
            walletStateInit = "te6",
            platform = "windows",
            appVersion = "4",
            nextRequestId = 3,
            walletVersion = "v4r2",
        )
        encryptedSessionStorage(directory).save(session)
        assertEquals(session, encryptedSessionStorage(directory).load())
        encryptedSessionStorage(directory).save(null)
        assertNull(encryptedSessionStorage(directory).load())
    }
}