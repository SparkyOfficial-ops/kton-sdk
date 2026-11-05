package io.kton.connect.protocol

import io.kton.connect.crypto.toBase64UrlNoPadding
import io.kton.connect.qr.QrCode
import io.kton.connect.qr.encodeQr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val TON_CONNECT_VERSION = 2

sealed class ReturnStrategy {
    data object Back : ReturnStrategy()
    data object None : ReturnStrategy()
    data class Url(val value: String) : ReturnStrategy()

    internal fun wire(): String = when (this) {
        Back -> "back"
        None -> "none"
        is Url -> value
    }
}

sealed class EmbeddedAction {
    abstract val transaction: Transaction

    data class SendTransaction(override val transaction: Transaction) : EmbeddedAction()
    data class SignMessage(override val transaction: Transaction) : EmbeddedAction()
}

data class ConnectOptions(
    val items: List<ConnectItem> = listOf(ConnectItem.TonAddress()),
    val returnStrategy: ReturnStrategy = ReturnStrategy.Back,
    val traceId: String? = null,
    val embedded: EmbeddedAction? = null,
)

/**
 * Links a dApp shows to the wallet. [universalUrl] is what a button opens and
 * what a wallet-specific QR code contains. [unifiedUrl] (`tc://`) is one QR
 * for every wallet that implements the unified scheme.
 */
data class ConnectLink(
    val universalUrl: String,
    val deepLink: String?,
    val unifiedUrl: String,
) {
    val qrPayload: String get() = universalUrl

    fun qrCode(): QrCode = encodeQr(qrPayload)
}

internal fun buildConnectLink(
    wallet: WalletApp,
    clientId: String,
    request: ConnectRequest,
    options: ConnectOptions,
): ConnectLink {
    val universal = wallet.universalUrl
        ?: throw IllegalArgumentException("Wallet ${wallet.appName} has no universal link")
    val query = connectQuery(clientId, request, options)
    return ConnectLink(
        universalUrl = appendQuery(universal, query),
        deepLink = wallet.deepLink?.let { appendQuery(it, query) },
        unifiedUrl = appendQuery("tc://", query),
    )
}

internal fun connectQuery(clientId: String, request: ConnectRequest, options: ConnectOptions): String {
    val encodedRequest = encodeURIComponent(ProtocolJson.encodeToString(ConnectRequest.serializer(), request))
    val parts = mutableListOf(
        "v=$TON_CONNECT_VERSION",
        "id=${encodeURIComponent(clientId)}",
        "r=$encodedRequest",
        "ret=${encodeURIComponent(options.returnStrategy.wire())}",
    )
    if (!options.traceId.isNullOrBlank()) {
        parts += "trace_id=${encodeURIComponent(options.traceId)}"
    }
    options.embedded?.let { action ->
        parts += "e=${encodeURIComponent(embeddedParameter(action))}"
    }
    return parts.joinToString("&")
}

internal fun embeddedParameter(action: EmbeddedAction): String {
    val method = when (action) {
        is EmbeddedAction.SendTransaction -> "st"
        is EmbeddedAction.SignMessage -> "sm"
    }
    val transaction = action.transaction
    val hasMessages = !transaction.messages.isNullOrEmpty()
    val hasItems = !transaction.items.isNullOrEmpty()
    require(hasMessages != hasItems) { "Embedded request needs messages or items, and not both" }
    val json = buildJsonObject {
        put("m", method)
        transaction.from?.let { put("f", it) }
        transaction.network?.let { put("n", it) }
        transaction.validUntil?.let { put("vu", it) }
        transaction.messages?.let { put("ms", JsonArray(it.map { message -> wireMessage(message) })) }
        transaction.items?.let { put("i", JsonArray(it.map { item -> wireItem(item) })) }
    }
    return ProtocolJson.encodeToString(JsonObject.serializer(), json).encodeToByteArray().toBase64UrlNoPadding()
}

private fun wireMessage(message: OutgoingMessage): JsonObject = buildJsonObject {
    put("a", message.address)
    put("am", message.amount)
    message.payload?.let { put("p", it) }
    message.stateInit?.let { put("si", it) }
    message.extraCurrency?.let { amounts ->
        put("ec", buildJsonObject { amounts.forEach { (id, amount) -> put(id, amount) } })
    }
}

private fun wireItem(item: TransactionItem): JsonObject = when (item) {
    is TransactionItem.Ton -> buildJsonObject {
        put("t", "ton")
        put("a", item.address)
        put("am", item.amount)
        item.payload?.let { put("p", it) }
        item.stateInit?.let { put("si", it) }
        item.extraCurrency?.let { amounts ->
            put("ec", buildJsonObject { amounts.forEach { (id, amount) -> put(id, amount) } })
        }
    }
    is TransactionItem.Jetton -> buildJsonObject {
        put("t", "jetton")
        put("ma", item.master)
        put("d", item.destination)
        put("am", item.amount)
        item.attachAmount?.let { put("aa", it) }
        item.queryId?.let { put("qi", it) }
        item.responseDestination?.let { put("rd", it) }
        item.customPayload?.let { put("cp", it) }
        item.forwardAmount?.let { put("fa", it) }
        item.forwardPayload?.let { put("fp", it) }
    }
    is TransactionItem.Nft -> buildJsonObject {
        put("t", "nft")
        put("na", item.nftAddress)
        put("no", item.newOwner)
        item.attachAmount?.let { put("aa", it) }
        item.queryId?.let { put("qi", it) }
        item.responseDestination?.let { put("rd", it) }
        item.customPayload?.let { put("cp", it) }
        item.forwardAmount?.let { put("fa", it) }
        item.forwardPayload?.let { put("fp", it) }
    }
}

internal fun appendQuery(base: String, query: String): String {
    val separator = when {
        base.endsWith("?") || base.endsWith("&") -> ""
        '?' in base -> "&"
        else -> "?"
    }
    return base + separator + query
}

internal fun encodeURIComponent(value: String): String {
    val bytes = value.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    for (byte in bytes) {
        val c = byte.toInt() and 0xFF
        if (c < 128 && c.toChar() in UNRESERVED) out.append(c.toChar()) else {
            out.append('%')
            out.append(HEX[c ushr 4])
            out.append(HEX[c and 0x0F])
        }
    }
    return out.toString()
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
private const val HEX = "0123456789ABCDEF"

internal fun randomTraceId(): String = io.kton.connect.platform.randomUuid()
