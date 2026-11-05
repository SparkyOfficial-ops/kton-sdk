package io.kton.connect.protocol


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

data class ConnectOptions(
    val items: List<ConnectItem> = listOf(ConnectItem.TonAddress()),
    val returnStrategy: ReturnStrategy = ReturnStrategy.Back,
    val traceId: String? = null,
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
    return parts.joinToString("&")
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
