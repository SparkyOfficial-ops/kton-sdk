package io.kton.connect

import io.kton.connect.crypto.SessionCrypto
import io.kton.connect.crypto.base64ToBytes
import io.kton.connect.crypto.hexToBytes
import io.kton.connect.crypto.toBase64
import io.kton.connect.protocol.OutgoingMessage
import io.kton.connect.protocol.TonNetwork
import io.kton.connect.protocol.WalletApp
import io.kton.connect.protocol.BridgeEndpoint
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeSessionTest {
    @Test
    fun connectAndSendTransactionOverSse() = runBlocking {
        val sessions = mutableMapOf<String, Channel<String>>()
        val posted = Channel<Posted>(1)
        val server = embeddedServer(CIO, port = 0) {
            install(SSE)
            routing {
                sse("/bridge/events") {
                    val clientId = call.request.queryParameters["client_id"] ?: return@sse
                    val channel = Channel<String>(8)
                    sessions[clientId] = channel
                    try {
                        for (payload in channel) send(data = payload, event = "message")
                    } finally {
                        sessions.remove(clientId)
                    }
                }
                post("/bridge/message") {
                    posted.send(
                        Posted(
                            from = call.request.queryParameters["client_id"].orEmpty(),
                            to = call.request.queryParameters["to"].orEmpty(),
                            topic = call.request.queryParameters["topic"],
                            ttl = call.request.queryParameters["ttl"],
                            body = call.receiveText(),
                        ),
                    )
                    call.respond(HttpStatusCode.OK)
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val ton = TonConnect(
            manifestUrl = "https://example.com/tonconnect-manifest.json",
            logger = TonConnectLogger { println(it) },
        )
        try {
            val walletApp = WalletApp(
                appName = "tonkeeper",
                name = "Tonkeeper",
                universalUrl = "https://app.tonkeeper.com/ton-connect",
                bridge = listOf(BridgeEndpoint(type = "sse", url = "http://127.0.0.1:$port/bridge")),
                platforms = listOf("android"),
            )
            val link = ton.connect(walletApp)
            assertTrue(link.universalUrl.contains("v=2"))
            val appId = withTimeout(10_000) {
                while (sessions.isEmpty()) kotlinx.coroutines.delay(20)
                sessions.keys.single()
            }
            val wallet = SessionCrypto.generate()
            val connectEvent = """
                {"event":"connect","id":1,"payload":{"items":[{"name":"ton_addr","address":"0:abcd","network":"-239","publicKey":"aa","walletStateInit":"te6"}],"device":{"platform":"android","appName":"tonkeeper","appVersion":"4.0","maxProtocolVersion":2,"features":[{"name":"SendTransaction","maxMessages":4}]}}}
            """.trimIndent()
            sessions.getValue(appId).send(envelope(wallet, appId, connectEvent))
            val connected = withTimeout(10_000) { ton.awaitConnect() }
            assertEquals("0:abcd", connected.address)
            assertEquals(TonNetwork.MAINNET, connected.network)

            val signed = CompletableDeferred<String>()
            val callback = ton.onTransactionSigned { signed.complete(it.bocBase64) }
            val send = launch {
                val boc = ton.sendTransaction(
                    listOf(OutgoingMessage(address = "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c", amount = "100000000")),
                    network = TonNetwork.MAINNET,
                )
                assertEquals("te6ccgEBAQEAAwAA", boc)
            }
            val incoming = withTimeout(10_000) { posted.receive() }
            assertEquals("sendTransaction", incoming.topic)
            assertEquals("300", incoming.ttl)
            assertEquals(wallet.clientId, incoming.to)
            val plaintext = wallet.decrypt(incoming.body.base64ToBytes(), appId.hexToBytes())
            assertTrue(plaintext.contains("\"method\":\"sendTransaction\""))
            assertTrue(plaintext.contains("100000000"))
            val requestId = Regex("\"id\":\"(\\d+)\"").find(plaintext)!!.groupValues[1]
            sessions.getValue(appId).send(envelope(wallet, appId, """{"result":"te6ccgEBAQEAAwAA","id":"$requestId"}"""))
            send.join()
            assertEquals("te6ccgEBAQEAAwAA", withTimeout(5_000) { signed.await() })
            callback.unsubscribe()
        } finally {
            ton.close()
            server.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        }
    }
}

private class Posted(val from: String, val to: String, val topic: String?, val ttl: String?, val body: String)

private fun envelope(wallet: SessionCrypto, appClientId: String, plaintext: String): String {
    val encrypted = wallet.encrypt(plaintext, appClientId.hexToBytes())
    return """{"from":"${wallet.clientId}","message":"${encrypted.toBase64()}"}"""
}
