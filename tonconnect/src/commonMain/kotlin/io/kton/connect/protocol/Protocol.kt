@file:OptIn(ExperimentalSerializationApi::class)

package io.kton.connect.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

object TonNetwork {
    const val MAINNET = "-239"
    const val TESTNET = "-3"
}

object TonConnectError {
    const val UNKNOWN = 0
    const val BAD_REQUEST = 1
    const val MANIFEST_NOT_FOUND = 2
    const val MANIFEST_CONTENT_ERROR = 3
    const val UNKNOWN_APP = 100
    const val USER_DECLINED = 300
    const val METHOD_NOT_SUPPORTED = 400
}

@Serializable
data class ConnectRequest(
    val manifestUrl: String,
    val items: List<ConnectItem>,
)

@JsonClassDiscriminator("name")
@Serializable
sealed class ConnectItem {
    @Serializable
    @SerialName("ton_addr")
    data class TonAddress(val network: String? = null) : ConnectItem()

    @Serializable
    @SerialName("ton_proof")
    data class TonProof(val payload: String) : ConnectItem()
}

@Serializable
data class OutgoingMessage(
    val address: String,
    val amount: String,
    val payload: String? = null,
    val stateInit: String? = null,
    @SerialName("extra_currency") val extraCurrency: Map<String, String>? = null,
)

@Serializable
sealed class TransactionItem {
    @Serializable
    @SerialName("ton")
    data class Ton(
        val address: String,
        val amount: String,
        val payload: String? = null,
        val stateInit: String? = null,
        @SerialName("extra_currency") val extraCurrency: Map<String, String>? = null,
    ) : TransactionItem()

    @Serializable
    @SerialName("jetton")
    data class Jetton(
        val master: String,
        val destination: String,
        val amount: String,
        val attachAmount: String? = null,
        val queryId: String? = null,
        val responseDestination: String? = null,
        val customPayload: String? = null,
        val forwardAmount: String? = null,
        val forwardPayload: String? = null,
    ) : TransactionItem()

    @Serializable
    @SerialName("nft")
    data class Nft(
        val nftAddress: String,
        val newOwner: String,
        val attachAmount: String? = null,
        val queryId: String? = null,
        val responseDestination: String? = null,
        val customPayload: String? = null,
        val forwardAmount: String? = null,
        val forwardPayload: String? = null,
    ) : TransactionItem()
}

@Serializable
data class Transaction(
    val messages: List<OutgoingMessage>? = null,
    val items: List<TransactionItem>? = null,
    val network: String? = null,
    val from: String? = null,
    @SerialName("valid_until") val validUntil: Long? = null,
)

@Serializable
sealed class SignDataPayload {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val network: String? = null,
        val from: String? = null,
    ) : SignDataPayload()

    @Serializable
    @SerialName("binary")
    data class Binary(
        val bytes: String,
        val network: String? = null,
        val from: String? = null,
    ) : SignDataPayload()

    @Serializable
    @SerialName("cell")
    data class Cell(
        val schema: String,
        val cell: String,
        val network: String? = null,
        val from: String? = null,
    ) : SignDataPayload()
}

@Serializable(with = WalletFeatureSerializer::class)
sealed class WalletFeature {
    abstract val name: String

    data class SendTransaction(
        val maxMessages: Int? = null,
        val extraCurrencySupported: Boolean? = null,
        val itemTypes: List<String>? = null,
    ) : WalletFeature() {
        override val name: String = "SendTransaction"
    }

    data class SignData(val types: List<String> = emptyList()) : WalletFeature() {
        override val name: String = "SignData"
    }

    data class SignMessage(
        val maxMessages: Int? = null,
        val extraCurrencySupported: Boolean? = null,
        val itemTypes: List<String>? = null,
    ) : WalletFeature() {
        override val name: String = "SignMessage"
    }

    data object EmbeddedRequest : WalletFeature() {
        override val name: String = "EmbeddedRequest"
    }

    data class Unknown(override val name: String) : WalletFeature()
}

internal object WalletFeatureSerializer : KSerializer<WalletFeature> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): WalletFeature {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return decodeFeature(element)
    }

    override fun serialize(encoder: Encoder, value: WalletFeature) {
        (encoder as JsonEncoder).encodeJsonElement(encodeFeature(value))
    }
}

private fun decodeFeature(element: JsonElement): WalletFeature {
    if (element is JsonPrimitive) {
        return featureByName(element.content, null)
    }
    val obj = element as? JsonObject ?: return WalletFeature.Unknown("unknown")
    return featureByName(obj.string("name").orEmpty(), obj)
}

private fun featureByName(name: String, obj: JsonObject?): WalletFeature = when (name) {
    "SendTransaction" -> WalletFeature.SendTransaction(
        maxMessages = obj?.int("maxMessages"),
        extraCurrencySupported = obj?.boolean("extraCurrencySupported"),
        itemTypes = obj?.stringList("itemTypes"),
    )
    "SignData" -> WalletFeature.SignData(obj?.stringList("types").orEmpty())
    "SignMessage" -> WalletFeature.SignMessage(
        maxMessages = obj?.int("maxMessages"),
        extraCurrencySupported = obj?.boolean("extraCurrencySupported"),
        itemTypes = obj?.stringList("itemTypes"),
    )
    "EmbeddedRequest" -> WalletFeature.EmbeddedRequest
    else -> WalletFeature.Unknown(name.ifEmpty { "unknown" })
}

private fun encodeFeature(feature: WalletFeature): JsonElement = when (feature) {
    is WalletFeature.SendTransaction -> buildJsonObject {
        put("name", feature.name)
        feature.maxMessages?.let { put("maxMessages", it) }
        feature.extraCurrencySupported?.let { put("extraCurrencySupported", it) }
    }
    is WalletFeature.SignData -> buildJsonObject {
        put("name", feature.name)
        put("types", JsonArray(feature.types.map { JsonPrimitive(it) }))
    }
    is WalletFeature.SignMessage -> buildJsonObject {
        put("name", feature.name)
        feature.maxMessages?.let { put("maxMessages", it) }
    }
    WalletFeature.EmbeddedRequest -> buildJsonObject { put("name", feature.name) }
    is WalletFeature.Unknown -> buildJsonObject { put("name", feature.name) }
}

@Serializable
data class DeviceInfo(
    val platform: String,
    val appName: String,
    val appVersion: String,
    val maxProtocolVersion: Int = 2,
    val features: List<WalletFeature> = emptyList(),
)

@Serializable
data class BridgeEndpoint(
    val type: String,
    val url: String? = null,
    val key: String? = null,
)

@Serializable
data class WalletApp(
    @SerialName("app_name") val appName: String,
    val name: String,
    val image: String = "",
    @SerialName("about_url") val aboutUrl: String = "",
    @SerialName("universal_url") val universalUrl: String? = null,
    val deepLink: String? = null,
    val bridge: List<BridgeEndpoint> = emptyList(),
    val platforms: List<String> = emptyList(),
    val features: List<WalletFeature> = emptyList(),
) {
    val sseBridgeUrl: String? get() = bridge.firstOrNull { it.type == "sse" }?.url
}

data class TonProof(
    val timestamp: Long,
    val domain: String,
    val domainLengthBytes: Int,
    val payload: String,
    val signatureBase64: String,
)

data class RpcError(val code: Int, val message: String)

data class SignDataResult(
    val signature: String,
    val address: String,
    val timestamp: Long,
    val domain: String,
    val payload: JsonObject,
)

internal sealed interface WalletMessage {
    data class Connected(
        val id: Long,
        val address: String,
        val network: String,
        val publicKeyHex: String,
        val walletStateInit: String,
        val device: DeviceInfo,
        val proof: TonProof?,
        val embeddedResult: JsonElement?,
        val embeddedError: RpcError?,
    ) : WalletMessage

    data class ConnectFailed(val id: Long, val code: Int, val message: String) : WalletMessage
    data class Disconnected(val id: Long) : WalletMessage
    data class Response(val id: String, val result: JsonElement?, val error: RpcError?) : WalletMessage
}

internal fun parseWalletMessage(plaintext: String): WalletMessage? {
    val root = runCatching { ProtocolJson.parseToJsonElement(plaintext).jsonObject }.getOrNull() ?: return null
    val event = root.string("event")
    if (event != null) {
        val id = root.long("id") ?: 0L
        return when (event) {
            "connect" -> parseConnect(root, id)
            "connect_error" -> {
                val payload = root.obj("payload")
                WalletMessage.ConnectFailed(
                    id = id,
                    code = payload?.int("code") ?: TonConnectError.UNKNOWN,
                    message = payload?.string("message") ?: "Connection rejected",
                )
            }
            "disconnect" -> WalletMessage.Disconnected(id)
            else -> null
        }
    }
    if (root.containsKey("result") || root.containsKey("error")) {
        return WalletMessage.Response(
            id = root.string("id") ?: return null,
            result = root["result"],
            error = root.obj("error")?.let { rpcError(it) },
        )
    }
    return null
}

private fun parseConnect(root: JsonObject, id: Long): WalletMessage.Connected? {
    val payload = root.obj("payload") ?: return null
    val items = payload["items"] as? JsonArray ?: return null
    val addressItem = items.mapNotNull { it as? JsonObject }.firstOrNull { it.string("name") == "ton_addr" } ?: return null
    val proofItem = items.mapNotNull { it as? JsonObject }.firstOrNull { it.string("name") == "ton_proof" }
    val device = payload.obj("device")?.let {
        runCatching { ProtocolJson.decodeFromJsonElement(DeviceInfo.serializer(), it) }.getOrNull()
    } ?: DeviceInfo(platform = "unknown", appName = "unknown", appVersion = "unknown")
    val response = root.obj("response")
    return WalletMessage.Connected(
        id = id,
        address = addressItem.string("address") ?: return null,
        network = addressItem.string("network") ?: TonNetwork.MAINNET,
        publicKeyHex = addressItem.string("publicKey") ?: return null,
        walletStateInit = addressItem.string("walletStateInit").orEmpty(),
        device = device,
        proof = proofItem?.obj("proof")?.let { parseProof(it) },
        embeddedResult = response?.get("result"),
        embeddedError = response?.obj("error")?.let { rpcError(it) },
    )
}

private fun parseProof(proof: JsonObject): TonProof? {
    val domain = proof.obj("domain") ?: return null
    return TonProof(
        timestamp = proof.long("timestamp") ?: return null,
        domain = domain.string("value") ?: return null,
        domainLengthBytes = domain.int("lengthBytes") ?: return null,
        payload = proof.string("payload") ?: return null,
        signatureBase64 = proof.string("signature") ?: return null,
    )
}

private fun rpcError(obj: JsonObject) = RpcError(
    code = obj.int("code") ?: TonConnectError.UNKNOWN,
    message = obj.string("message") ?: "Wallet error",
)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? {
    val primitive = this[key]?.jsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

private fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toLongOrNull()
}

private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.stringList(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

