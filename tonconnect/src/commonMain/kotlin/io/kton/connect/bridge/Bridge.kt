package io.kton.connect.bridge

import io.kton.connect.protocol.ProtocolJson
import io.kton.connect.protocol.WalletApp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.cancellation.CancellationException

@Serializable
internal data class BridgeEnvelope(
    val from: String,
    val message: String,
    @SerialName("trace_id") val traceId: String? = null,
)

internal data class BridgeDelivery(val eventId: String?, val envelope: BridgeEnvelope)

internal class BridgeException(message: String, val status: Int? = null) : RuntimeException(message)

internal class BridgeClient(private val http: HttpClient) {
    suspend fun send(
        bridgeUrl: String,
        fromClientId: String,
        toClientId: String,
        bodyBase64: String,
        topic: String? = null,
        ttlSeconds: Int = 300,
        traceId: String? = null,
    ) {
        val response = http.post(bridgePath(bridgeUrl, "message")) {
            parameter("client_id", fromClientId)
            parameter("to", toClientId)
            parameter("ttl", ttlSeconds)
            if (!topic.isNullOrBlank()) parameter("topic", topic)
            if (!traceId.isNullOrBlank()) parameter("trace_id", traceId)
            contentType(ContentType.Text.Plain)
            setBody(bodyBase64)
        }
        if (!response.status.isSuccess()) {
            val details = runCatching { response.bodyAsText() }.getOrDefault("").trim()
            throw BridgeException(
                "Bridge rejected message with HTTP ${response.status.value}${if (details.isEmpty()) "" else ": $details"}",
                response.status.value,
            )
        }
    }

    fun listen(
        bridgeUrl: String,
        clientId: String,
        lastEventId: String? = null,
        traceId: String? = null,
    ): Flow<BridgeDelivery> = flow {
        var cursor = lastEventId
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                http.prepareGet(bridgePath(bridgeUrl, "events")) {
                    header(HttpHeaders.Accept, "text/event-stream")
                    header(HttpHeaders.CacheControl, "no-cache")
                    parameter("client_id", clientId)
                    parameter("heartbeat", "message")
                    if (!traceId.isNullOrBlank()) parameter("trace_id", traceId)
                    if (!cursor.isNullOrBlank()) {
                        parameter("last_event_id", cursor)
                        header(HttpHeaders.LastEventID, cursor)
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        throw BridgeException(
                            "Bridge SSE failed with HTTP ${response.status.value}",
                            response.status.value,
                        )
                    }
                    attempt = 0
                    val parser = SseParser()
                    val channel = response.bodyAsChannel()
                    readSse(channel, parser) { event ->
                        if (!event.id.isNullOrBlank()) cursor = event.id
                        val data = event.data
                        if (data.isBlank() || data == "heartbeat" || event.event == "heartbeat") return@readSse
                        val envelope = runCatching {
                            ProtocolJson.decodeFromString(BridgeEnvelope.serializer(), data)
                        }.getOrNull() ?: return@readSse
                        emit(BridgeDelivery(event.id, envelope))
                    }
                }
                delay(300)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BridgeException) {
                if (error.status != null && error.status in 400..499) throw error
                attempt += 1
                delay(reconnectDelay(attempt))
            } catch (_: Throwable) {
                attempt += 1
                delay(reconnectDelay(attempt))
            }
        }
    }

    suspend fun wallets(url: String): List<WalletApp> {
        val response = http.get(url)
        if (!response.status.isSuccess()) {
            throw BridgeException("Wallet list request failed with HTTP ${response.status.value}")
        }
        return ProtocolJson.decodeFromString(ListSerializer(WalletApp.serializer()), response.bodyAsText())
    }
}

private suspend fun readSse(
    channel: ByteReadChannel,
    parser: SseParser,
    onEvent: suspend (SseEvent) -> Unit,
) {
    val buffer = ByteArray(8 * 1024)
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read == 0) {
            channel.awaitContent()
            continue
        }
        for (event in parser.feed(buffer, read)) onEvent(event)
    }
    for (event in parser.finish()) onEvent(event)
}

internal fun bridgePath(bridgeUrl: String, path: String): String =
    bridgeUrl.trimEnd('/') + "/" + path.trimStart('/')

private fun reconnectDelay(attempt: Int): Long {
    val shift = attempt.coerceAtMost(5)
    return 500L shl shift
}

internal data class SseEvent(val event: String?, val id: String?, val data: String)

internal class SseParser {
    private val buffer = StringBuilder()

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<SseEvent> {
        for (i in 0 until length) buffer.append(Char(bytes[i].toInt() and 0xFF))
        return drain(flush = false)
    }

    fun finish(): List<SseEvent> = drain(flush = true)

    private fun drain(flush: Boolean): List<SseEvent> {
        val normalized = buffer.toString().replace("\r\n", "\n").replace('\r', '\n')
        buffer.clear()
        buffer.append(normalized)
        val events = mutableListOf<SseEvent>()
        while (true) {
            val text = buffer.toString()
            val separator = text.indexOf("\n\n")
            if (separator < 0) break
            val raw = text.substring(0, separator)
            buffer.delete(0, separator + 2)
            parseEvent(raw)?.let { events.add(it) }
        }
        if (flush && buffer.isNotEmpty()) {
            parseEvent(buffer.toString())?.let { events.add(it) }
            buffer.clear()
        }
        return events
    }

    private fun parseEvent(raw: String): SseEvent? {
        var name: String? = null
        var id: String? = null
        val data = StringBuilder()
        var sawData = false
        for (line in raw.split('\n')) {
            if (line.isEmpty() || line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.drop(1)
            when (field) {
                "event" -> name = value
                "id" -> id = value
                "data" -> {
                    if (sawData) data.append('\n')
                    data.append(value)
                    sawData = true
                }
            }
        }
        if (!sawData && name == null && id == null) return null
        return SseEvent(name, id, if (sawData) data.toString() else "")
    }
}
