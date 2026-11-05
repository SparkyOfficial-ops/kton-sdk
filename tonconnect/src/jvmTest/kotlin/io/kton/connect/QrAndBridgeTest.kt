package io.kton.connect

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.kton.connect.bridge.BridgeClient
import io.kton.connect.bridge.BridgeException
import io.kton.connect.http.createPlatformHttpClient
import io.kton.connect.protocol.ConnectItem
import io.kton.connect.protocol.ConnectOptions
import io.kton.connect.protocol.ConnectRequest
import io.kton.connect.protocol.WalletApp
import io.kton.connect.protocol.buildConnectLink
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class QrAndBridgeTest {
    @Test
    fun qrSvgDecodesBackToTheUniversalLink() {
        val link = buildConnectLink(
            WalletApp(
                appName = "tonkeeper",
                name = "Tonkeeper",
                universalUrl = "https://app.tonkeeper.com/ton-connect",
            ),
            clientId = "ab".repeat(32),
            request = ConnectRequest(
                manifestUrl = "https://example.com/tonconnect-manifest.json",
                items = listOf(ConnectItem.TonAddress()),
            ),
            options = ConnectOptions(),
        )
        val qr = link.qrCode()
        assertTrue(qr.toSvg().startsWith("<svg"))
        assertEquals(link.qrPayload, decode(qr))
        assertEquals(1, qr.isDark(0, 0).let { if (it) 1 else 0 })
    }

    @Test
    fun bridgeDoesNotRetryClientErrors() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            routing {
                get("/bridge/events") {
                    call.respond(HttpStatusCode.BadRequest, "bad client id")
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = createPlatformHttpClient()
        try {
            val client = BridgeClient(http)
            val error = assertFailsWith<BridgeException> {
                withTimeout(5.seconds) {
                    client.listen("http://127.0.0.1:$port/bridge", "ab".repeat(32)).first()
                }
            }
            assertEquals(400, error.status)
        } finally {
            http.close()
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
}

private fun decode(qr: io.kton.connect.qr.QrCode): String {
    val quiet = 4
    val scale = 4
    val modules = qr.size + quiet * 2
    val size = modules * scale
    val source = object : LuminanceSource(size, size) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val line = if (row == null || row.size < width) ByteArray(width) else row
            val moduleY = y / scale
            for (x in 0 until width) line[x] = luminance(x / scale, moduleY)
            return line
        }

        override fun getMatrix(): ByteArray {
            val matrix = ByteArray(width * height)
            for (y in 0 until height) {
                val moduleY = y / scale
                val offset = y * width
                for (x in 0 until width) matrix[offset + x] = luminance(x / scale, moduleY)
            }
            return matrix
        }

        private fun luminance(moduleX: Int, moduleY: Int): Byte {
            val inside = moduleX >= quiet && moduleY >= quiet &&
                moduleX < quiet + qr.size && moduleY < quiet + qr.size
            val dark = inside && qr.isDark(moduleX - quiet, moduleY - quiet)
            return if (dark) 0 else 0xFF.toByte()
        }
    }
    return QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source))).text
}
