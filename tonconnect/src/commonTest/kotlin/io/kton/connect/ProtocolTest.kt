package io.kton.connect

import io.kton.connect.bridge.SseParser
import io.kton.connect.crypto.base64UrlToBytes
import io.kton.connect.protocol.ConnectItem
import io.kton.connect.protocol.ConnectOptions
import io.kton.connect.protocol.ConnectRequest
import io.kton.connect.protocol.EmbeddedAction
import io.kton.connect.protocol.OutgoingMessage
import io.kton.connect.protocol.Transaction
import io.kton.connect.protocol.ProtocolJson
import io.kton.connect.protocol.ReturnStrategy
import io.kton.connect.protocol.TonProof
import io.kton.connect.crypto.hexToBytes
import io.kton.connect.crypto.toBase64
import io.kton.connect.crypto.toHex
import io.kton.connect.protocol.BitBuilder
import io.kton.connect.protocol.WalletVersion
import io.kton.connect.protocol.WalletApp
import io.kton.connect.protocol.buildWallet
import io.kton.connect.protocol.crc32c
import io.kton.connect.protocol.parseBoc
import io.kton.connect.protocol.parseWalletStateInit
import io.kton.connect.protocol.toBoc
import io.kton.connect.protocol.verifyWalletIdentity
import io.kton.connect.protocol.appendQuery
import io.kton.connect.protocol.buildConnectLink
import io.kton.connect.protocol.connectQuery
import io.kton.connect.protocol.parseWalletMessage
import io.kton.connect.protocol.verifyTonProof
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun connectLinkMatchesJsEncodeURIComponent() {
        val request = ConnectRequest(
            manifestUrl = "https://example.com/tonconnect-manifest.json",
            items = listOf(ConnectItem.TonAddress()),
        )
        val clientId = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        val query = connectQuery(clientId, request, ConnectOptions())
        val encoded = "%7B%22manifestUrl%22%3A%22https%3A%2F%2Fexample.com%2Ftonconnect-manifest.json%22%2C%22items%22%3A%5B%7B%22name%22%3A%22ton_addr%22%7D%5D%7D"
        assertEquals("v=2&id=$clientId&r=$encoded&ret=back", query)
        val wallet = WalletApp(
            appName = "tonkeeper",
            name = "Tonkeeper",
            universalUrl = "https://app.tonkeeper.com/ton-connect",
            deepLink = "tonkeeper-tc://",
        )
        val link = buildConnectLink(wallet, clientId, request, ConnectOptions(returnStrategy = ReturnStrategy.Back))
        assertEquals("https://app.tonkeeper.com/ton-connect?$query", link.universalUrl)
        assertEquals(link.universalUrl, link.qrPayload)
        assertEquals("tonkeeper-tc://?$query", link.deepLink)
        assertEquals("tc://?$query", link.unifiedUrl)
        assertTrue(appendQuery("https://wallet.example/tc?x=1", "v=2").contains("&v=2"))
    }

    @Test
    fun embeddedRequestIsBase64UrlJson() {
        val transaction = Transaction(
            messages = listOf(OutgoingMessage(address = "EQ", amount = "1000")),
            network = "-239",
            validUntil = 10,
        )
        val encoded = io.kton.connect.protocol.embeddedParameter(EmbeddedAction.SendTransaction(transaction))
        val json = encoded.base64UrlToBytes().decodeToString()
        assertEquals("""{"m":"st","n":"-239","vu":10,"ms":[{"a":"EQ","am":"1000"}]}""", json)
        val query = connectQuery(
            "aa",
            ConnectRequest("https://example.com/tonconnect-manifest.json", listOf(ConnectItem.TonAddress())),
            ConnectOptions(traceId = "trace-1", embedded = EmbeddedAction.SignMessage(transaction)),
        )
        assertTrue(query.contains("trace_id=trace-1"))
        assertTrue(query.contains("e="))
        assertTrue(query.contains("tc://").not())
    }

    @Test
    fun parsesConnectEventAndTransactionResponse() {
        val connected = parseWalletMessage(
            """
            {"event":"connect","id":1,"payload":{"items":[{"name":"ton_addr","address":"0:abcd","network":"-239","publicKey":"aa","walletStateInit":"te6"}],"device":{"platform":"android","appName":"tonkeeper","appVersion":"4.0","maxProtocolVersion":2,"features":["SendTransaction",{"name":"SendTransaction","maxMessages":4}]}}}
            """.trimIndent(),
        )
        val event = assertIs<io.kton.connect.protocol.WalletMessage.Connected>(connected)
        assertEquals("0:abcd", event.address)
        assertEquals(4, event.device.features.filterIsInstance<io.kton.connect.protocol.WalletFeature.SendTransaction>().last().maxMessages)
        val response = parseWalletMessage("""{"result":"te6ccg","id":"7"}""")
        assertIs<io.kton.connect.protocol.WalletMessage.Response>(response)
        assertNull(parseWalletMessage("not-json"))
    }

    @Test
    fun tonProofVerifiesVectorSignedByTweetNaCl() {
        val proof = TonProof(
            timestamp = 1700000000,
            domain = "example.com",
            domainLengthBytes = "example.com".encodeToByteArray().size,
            payload = "nonce-1",
            signatureBase64 = "azkYeYmAcIT5g8fPK2PYlzBB2vrIle5+JS2kMDeClgo1wJcpnBolOdbRoKXqQjfHVa4LVA2mgmhbf72grZzZDQ==",
        )
        val key = "fd1724385aa0c75b64fb78cd602fa1d991fdebf76b13c58ed702eac835e9f618"
        val address = "0:abababababababababababababababababababababababababababababababab"
        assertTrue(
            verifyTonProof(
                address = address,
                publicKeyHex = key,
                proof = proof,
                expectedDomain = "example.com",
                expectedPayload = "nonce-1",
                nowSeconds = 1700000000,
            ),
        )
    }

    @Test
    fun crc32cMatchesTheCastagnoliCheckValue() {
        assertEquals(0xE3069283.toInt(), crc32c("123456789".encodeToByteArray()))
    }

    @Test
    fun walletStateInitBindsKeyAndAddress() {
        val key = ByteArray(32) { 0x7A }
        val built = buildWallet(WalletVersion.V5R1, key, walletId = 0x7FFFFF11)
        val contract = parseWalletStateInit(built.stateInitBase64)
        assertEquals(WalletVersion.V5R1, contract.version)
        assertEquals(built.publicKeyHex, contract.publicKey.toHex())
        assertTrue(verifyWalletIdentity(built.address, built.publicKeyHex, built.stateInitBase64))
        assertTrue(!verifyWalletIdentity(built.address, "00".repeat(32), built.stateInitBase64))
        assertTrue(!verifyWalletIdentity("0:" + "ab".repeat(32), built.publicKeyHex, built.stateInitBase64))
    }

    @Test
    fun mainnetV3R2StateMatchesTheAccount() {
        val code = parseBoc(MAINNET_V3R2_CODE.hexToBytes()).single()
        val withChecksum = parseBoc(
            "B5EE9C724101010100710000DEFF0020DD2082014C97BA218201339CBAB19F71B0ED44D0D31FD31F31D70BFFE304E0A4F2608308D71820D31FD31FD31FF82313BBF263ED44D0D31FD31FD3FFD15132BAF2A15144BAF2A204F901541055F910F2A3F8009320D74A96D307D402FB00E8D101A4C8CB1FCB1FCBFFC9ED5410BD6DAD".hexToBytes(),
        ).single()
        assertTrue(code.hash.contentEquals(withChecksum.hash))
        val data = parseBoc(MAINNET_V3R2_DATA.hexToBytes()).single()
        val walletId = uint(data.bits, 32, 32)
        val publicKey = io.kton.connect.protocol.Slice(data).apply { skip(64) }.loadBytes(32)
        assertEquals(0x29a9a317, walletId)
        assertEquals("72c9ed6b62a6e2eba14a93b90462e7a367777beb8a38fb15b9f33844d22ce2ff", publicKey.toHex())
        val initialData = BitBuilder().apply {
            writeUint(0, 32)
            writeUint(walletId, 32)
            writeBytes(publicKey)
        }.toCell()
        val root = BitBuilder().apply {
            writeBit(false)
            writeBit(false)
            writeBit(true)
            writeBit(true)
            writeBit(false)
        }.toCell(listOf(code, initialData))
        val hash = "83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"
        assertEquals(hash, root.hash.toHex())
        val contract = parseWalletStateInit(root.toBoc().toBase64())
        assertEquals(WalletVersion.V3R2, contract.version)
        assertEquals(publicKey.toHex(), contract.publicKey.toHex())
        assertTrue(verifyWalletIdentity("0:$hash", contract.publicKey.toHex(), root.toBoc().toBase64()))
    }

    @Test
    fun walletListDecodesSseAndJsBridges() {
        val json = """
            [{
              "app_name": "tonkeeper",
              "name": "Tonkeeper",
              "image": "https://tonkeeper.com/assets/tonconnect-icon.png",
              "about_url": "https://tonkeeper.com",
              "universal_url": "https://app.tonkeeper.com/ton-connect",
              "deepLink": "tonkeeper-tc://",
              "bridge": [
                {"type": "sse", "url": "https://bridge.tonapi.io/bridge"},
                {"type": "js", "key": "tonkeeper"}
              ],
              "platforms": ["android", "ios"],
              "features": [{"name": "SendTransaction", "maxMessages": 4}]
            }]
        """.trimIndent()
        val wallets = ProtocolJson.decodeFromString(ListSerializer(WalletApp.serializer()), json)
        assertEquals("https://bridge.tonapi.io/bridge", wallets.single().sseBridgeUrl)
    }

    @Test
    fun sseParserSplitsChunksAndIgnoresComments() {
        val parser = SseParser()
        assertTrue(parser.feed(": keep-alive\n\n".encodeToByteArray()).isEmpty())
        val first = parser.feed("id: 10\ndata: {\"from\":\"aa\"".encodeToByteArray())
        assertTrue(first.isEmpty())
        val second = parser.feed(",\"message\":\"bb\"}\n\n".encodeToByteArray())
        assertEquals(1, second.size)
        assertEquals("10", second.single().id)
        assertEquals("""{"from":"aa","message":"bb"}""", second.single().data)
    }
}

private fun uint(bits: BooleanArray, offset: Int, width: Int): Long {
    var value = 0L
    repeat(width) { index ->
        value = (value shl 1) or if (bits[offset + index]) 1L else 0L
    }
    return value
}

private const val MAINNET_V3R2_CODE =
    "b5ee9c720101010100710000deff0020dd2082014c97ba218201339cbab19f71b0ed44d0d31fd31f31d70bffe304e0a4f2608308d71820d31fd31fd31ff82313bbf263ed44d0d31fd31fd3ffd15132baf2a15144baf2a204f901541055f910f2a3f8009320d74a96d307d402fb00e8d101a4c8cb1fcb1fcbffc9ed54"

private const val MAINNET_V3R2_DATA =
    "b5ee9c7201010101002a0000500000015729a9a31772c9ed6b62a6e2eba14a93b90462e7a367777beb8a38fb15b9f33844d22ce2ff"
