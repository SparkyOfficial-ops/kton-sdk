package io.kton.connect

import io.kton.connect.bridge.BridgeClient
import io.kton.connect.bridge.BridgeDelivery
import io.kton.connect.bridge.BridgeException
import io.kton.connect.crypto.SessionCrypto
import io.kton.connect.crypto.base64ToBytes
import io.kton.connect.crypto.hexToBytes
import io.kton.connect.crypto.toBase64
import io.kton.connect.crypto.toHex
import io.kton.connect.http.createPlatformHttpClient
import io.kton.connect.platform.Lock
import io.kton.connect.platform.currentTimeMillis
import io.kton.connect.protocol.ConnectLink
import io.kton.connect.protocol.ConnectOptions
import io.kton.connect.protocol.ConnectRequest
import io.kton.connect.protocol.randomTraceId
import io.kton.connect.protocol.OutgoingMessage
import io.kton.connect.protocol.ProtocolJson
import io.kton.connect.protocol.SignDataPayload
import io.kton.connect.protocol.SignDataResult
import io.kton.connect.protocol.TonConnectError
import io.kton.connect.protocol.Transaction
import io.kton.connect.protocol.ConnectItem
import io.kton.connect.protocol.WalletApp
import io.kton.connect.protocol.WalletFeature
import io.kton.connect.protocol.WalletMessage
import io.kton.connect.protocol.buildConnectLink
import io.kton.connect.protocol.parseWalletMessage
import io.kton.connect.protocol.parseWalletStateInit
import io.kton.connect.protocol.verifyTonProof
import io.kton.connect.protocol.verifyWalletIdentity
import io.kton.connect.storage.MemorySessionStorage
import io.kton.connect.storage.SessionStorage
import io.kton.connect.storage.StoredSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class ConnectionStatus { Disconnected, Connecting, Connected }

data class ConnectedWallet(
    val appName: String,
    val address: String,
    val network: String,
    val publicKeyHex: String,
    val walletStateInit: String,
    val platform: String,
    val appVersion: String,
    val features: List<WalletFeature>,
    val walletVersion: String = "",
)

data class SignedTransaction(val bocBase64: String, val requestId: String)

data class SignedMessage(val internalBoc: String, val requestId: String)

sealed interface TonConnectEvent {
    data class WalletConnected(val wallet: ConnectedWallet) : TonConnectEvent
    data class ConnectFailed(val code: Int, val message: String) : TonConnectEvent
    data class TransactionSigned(val transaction: SignedTransaction) : TonConnectEvent
    data class MessageSigned(val message: SignedMessage) : TonConnectEvent
    data class RequestFailed(val requestId: String, val code: Int, val message: String) : TonConnectEvent
    data object Disconnected : TonConnectEvent
}

fun interface TonConnectLogger {
    fun log(message: String)
}

class Subscription internal constructor(private val job: Job) {
    fun unsubscribe() {
        job.cancel()
    }
}

open class TonConnectException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class WalletException(val code: Int, message: String) : TonConnectException(message)

/**
 * TonConnect 2 client for a native app.
 *
 * [connect] returns the universal link. The same string is the QR payload.
 * Opening it is the app's job (an Android intent, a desktop browser, a QR view).
 * [sendTransaction] suspends until the wallet signs and also emits
 * [TonConnectEvent.TransactionSigned], so callbacks and coroutines both work.
 */
fun interface WalletIdentity {
    fun matches(address: String, publicKeyHex: String, walletStateInit: String): Boolean
}

class TonConnect(
    val manifestUrl: String,
    private val storage: SessionStorage = MemorySessionStorage(),
    httpClient: HttpClient? = null,
    private val logger: TonConnectLogger? = null,
    private val walletsListUrl: String = DEFAULT_WALLETS_URL,
    private val appDomain: String? = null,
    private val walletIdentity: WalletIdentity = WalletIdentity { address, key, stateInit ->
        verifyWalletIdentity(address, key, stateInit)
    },
) {
    private val ownsClient = httpClient == null
    private val http = httpClient ?: createPlatformHttpClient()
    private val bridge = BridgeClient(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingMutex = Mutex()
    private val lifecycle = Lock()
    private val pending = mutableMapOf<String, CompletableDeferred<WalletMessage.Response>>()
    @Volatile private var live: LiveSession? = null
    @Volatile private var listenJob: Job? = null
    @Volatile private var connectWait: CompletableDeferred<ConnectedWallet>? = null
    @Volatile private var restoreSuperseded = false

    private val _events = MutableSharedFlow<TonConnectEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<TonConnectEvent> = _events.asSharedFlow()

    private val _wallet = MutableStateFlow<ConnectedWallet?>(null)
    val wallet: StateFlow<ConnectedWallet?> = _wallet.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    init {
        if (storage is MemorySessionStorage) {
            logger?.log("Session secret stays in process memory and disappears when the process exits")
        }
        scope.launch {
            runCatching { restore() }.onFailure { logger?.log("session restore failed: ${it.message}") }
        }
    }

    suspend fun wallets(platform: String? = null): List<WalletApp> {
        val all = bridge.wallets(walletsListUrl)
        return if (platform == null) all else all.filter { platform in it.platforms && it.sseBridgeUrl != null }
    }

    fun connect(wallet: WalletApp, options: ConnectOptions = ConnectOptions()): ConnectLink {
        val bridgeUrl = wallet.sseBridgeUrl
            ?: throw TonConnectException("${wallet.name} has no HTTP bridge. This client does not embed a WebView for the JS bridge.")
        if (wallet.universalUrl.isNullOrBlank()) {
            throw TonConnectException("${wallet.name} does not publish a universal link")
        }
        val proofPayload = options.items.filterIsInstance<ConnectItem.TonProof>().firstOrNull()?.payload
        if (proofPayload != null && appDomain.isNullOrBlank()) {
            throw TonConnectException("ton_proof needs appDomain so the signed domain can be checked")
        }
        val crypto = SessionCrypto.generate()
        val traceId = options.traceId?.takeIf { it.isNotBlank() } ?: randomTraceId()
        val session = LiveSession(
            crypto = crypto,
            bridgeUrl = bridgeUrl,
            appName = wallet.appName,
            traceId = traceId,
            expectedProofPayload = proofPayload,
        )
        val wait = CompletableDeferred<ConnectedWallet>()
        lifecycle.withLock {
            restoreSuperseded = true
            connectWait?.completeExceptionally(TonConnectException("Replaced by a new connect()"))
            connectWait = wait
            install(session, ConnectionStatus.Connecting, wallet = null)
        }
        logger?.log("connecting to ${wallet.appName}")
        return buildConnectLink(
            wallet,
            crypto.clientId,
            ConnectRequest(manifestUrl, options.items),
            options.copy(traceId = traceId),
        )
    }

    suspend fun awaitConnect(timeout: Duration = 5.minutes): ConnectedWallet {
        _wallet.value?.let { return it }
        val wait = connectWait ?: throw TonConnectException("Call connect() before waiting for the wallet")
        return withTimeout(timeout) { wait.await() }
    }

    suspend fun sendTransaction(
        messages: List<OutgoingMessage>,
        network: String? = null,
        validUntil: Long? = null,
        from: String? = null,
        timeout: Duration = 5.minutes,
    ): String = sendTransaction(
        Transaction(messages = messages, network = network, validUntil = validUntil, from = from),
        timeout,
    )

    suspend fun sendTransaction(vararg messages: OutgoingMessage, timeout: Duration = 5.minutes): String =
        sendTransaction(messages.toList(), timeout = timeout)

    suspend fun sendTransaction(transaction: Transaction, timeout: Duration = 5.minutes): String {
        val connected = requireWallet()
        val payload = normalize(transaction, connected)
        validateTransaction(payload, connected, "SendTransaction")
        val response = request(
            "sendTransaction",
            listOf(ProtocolJson.encodeToString(Transaction.serializer(), payload)),
            timeout,
        )
        val boc = (response.result as? JsonPrimitive)?.contentOrNull
            ?: throw TonConnectException("Wallet returned an empty transaction result")
        val signed = SignedTransaction(boc, response.id)
        emit(TonConnectEvent.TransactionSigned(signed))
        return boc
    }

    suspend fun signMessage(transaction: Transaction, timeout: Duration = 5.minutes): String {
        val connected = requireWallet()
        requireFeature("SignMessage")
        val payload = normalize(transaction, connected)
        validateTransaction(payload, connected, "SignMessage")
        val response = request(
            "signMessage",
            listOf(ProtocolJson.encodeToString(Transaction.serializer(), payload)),
            timeout,
        )
        val boc = internalBoc(response.result) ?: throw TonConnectException("Wallet returned an empty signMessage result")
        emit(TonConnectEvent.MessageSigned(SignedMessage(boc, response.id)))
        return boc
    }

    suspend fun signData(payload: SignDataPayload, timeout: Duration = 5.minutes): SignDataResult {
        requireFeature("SignData")
        val response = request(
            "signData",
            listOf(ProtocolJson.encodeToString(SignDataPayload.serializer(), payload)),
            timeout,
        )
        val obj = response.result as? JsonObject ?: throw TonConnectException("Wallet returned an empty signData result")
        return SignDataResult(
            signature = obj.string("signature") ?: throw TonConnectException("Missing signature"),
            address = obj.string("address").orEmpty(),
            timestamp = obj.string("timestamp")?.toLongOrNull() ?: 0L,
            domain = obj.string("domain").orEmpty(),
            payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    suspend fun disconnect() {
        val session = live
        if (session?.walletClientId != null && session.connected != null) {
            runCatching { request("disconnect", emptyList(), 10.seconds) }
        }
        clear()
    }

    fun onWalletConnected(handler: (ConnectedWallet) -> Unit): Subscription {
        val job = scope.launch {
            wallet.collect { current -> if (current != null) handler(current) }
        }
        return Subscription(job)
    }

    fun onTransactionSigned(handler: (SignedTransaction) -> Unit): Subscription {
        val job = scope.launch {
            events.filterIsInstance<TonConnectEvent.TransactionSigned>().collect { handler(it.transaction) }
        }
        return Subscription(job)
    }

    fun onMessageSigned(handler: (SignedMessage) -> Unit): Subscription {
        val job = scope.launch {
            events.filterIsInstance<TonConnectEvent.MessageSigned>().collect { handler(it.message) }
        }
        return Subscription(job)
    }

    fun onConnectFailed(handler: (code: Int, message: String) -> Unit): Subscription {
        val job = scope.launch {
            events.filterIsInstance<TonConnectEvent.ConnectFailed>().collect { handler(it.code, it.message) }
        }
        return Subscription(job)
    }

    fun close() {
        listenJob?.cancel()
        live?.crypto?.destroy()
        live = null
        scope.cancel()
        if (ownsClient) http.close()
    }

    private suspend fun restore() {
        val stored = storage.load() ?: return
        val crypto = SessionCrypto.fromSecretKey(stored.secretKeyHex.hexToBytes())
        val features = runCatching {
            ProtocolJson.decodeFromString(ListSerializer(WalletFeature.serializer()), stored.featuresJson)
        }.getOrDefault(emptyList())
        val connected = ConnectedWallet(
            appName = stored.appName,
            address = stored.address,
            network = stored.network,
            publicKeyHex = stored.publicKeyHex,
            walletStateInit = stored.walletStateInit,
            platform = stored.platform,
            appVersion = stored.appVersion,
            features = features,
            walletVersion = stored.walletVersion,
        )
        val session = LiveSession(
            crypto = crypto,
            bridgeUrl = stored.bridgeUrl,
            appName = stored.appName,
            walletClientId = stored.walletClientId,
            connected = connected,
            nextRequestId = stored.nextRequestId,
            lastWalletEventId = stored.lastWalletEventId,
            bridgeLastEventId = stored.bridgeLastEventId,
            traceId = stored.traceId,
        )
        val published = lifecycle.withLock {
            if (restoreSuperseded || live != null) {
                false
            } else {
                install(session, ConnectionStatus.Connected, connected)
                true
            }
        }
        if (published) logger?.log("restored session with ${stored.appName}") else crypto.destroy()
    }

    private fun install(session: LiveSession, status: ConnectionStatus, wallet: ConnectedWallet?) {
        listenJob?.cancel()
        live = session
        _wallet.value = wallet
        _status.value = status
        listenJob = scope.launch { listen(session) }
    }

    private suspend fun listen(session: LiveSession) {
        try {
            bridge.listen(
                session.bridgeUrl,
                session.crypto.clientId,
                session.bridgeLastEventId,
                session.traceId,
            ).collect { delivery ->
                if (live !== session) return@collect
                handle(session, delivery)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: BridgeException) {
            logger?.log("bridge stopped: ${error.message}")
            if (live === session && session.connected == null) {
                onConnectFailed(
                    WalletMessage.ConnectFailed(
                        id = 0,
                        code = error.status ?: TonConnectError.UNKNOWN,
                        message = error.message ?: "Bridge error",
                    ),
                )
            }
        }
    }

    private suspend fun handle(session: LiveSession, delivery: BridgeDelivery) {
        if (!delivery.eventId.isNullOrBlank()) session.bridgeLastEventId = delivery.eventId
        val envelope = delivery.envelope
        val sender = runCatching { envelope.from.hexToBytes() }.getOrNull() ?: return
        if (sender.size != 32) return
        val bytes = runCatching { envelope.message.base64ToBytes() }.getOrNull() ?: return
        val plaintext = runCatching { session.crypto.decrypt(bytes, sender) }.getOrElse {
            logger?.log("dropped a message that failed to decrypt")
            return
        }
        when (val message = parseWalletMessage(plaintext)) {
            is WalletMessage.Connected -> onConnected(session, envelope.from, message)
            is WalletMessage.ConnectFailed -> onConnectFailed(message)
            is WalletMessage.Disconnected -> {
                logger?.log("wallet disconnected the session")
                clear()
            }
            is WalletMessage.Response -> {
                dispatchResponse(message)
                if (live === session && session.connected != null) persist(session)
            }
            null -> logger?.log("dropped an unrecognised wallet message")
        }
    }

    private suspend fun onConnected(session: LiveSession, walletClientId: String, message: WalletMessage.Connected) {
        if (!session.acceptEvent(message.id)) return
        if (!walletIdentity.matches(message.address, message.publicKeyHex, message.walletStateInit)) {
            onConnectFailed(
                WalletMessage.ConnectFailed(
                    id = message.id,
                    code = TonConnectError.BAD_REQUEST,
                    message = "Wallet state init does not match the address and public key",
                ),
            )
            return
        }
        val expectedProof = session.expectedProofPayload
        if (expectedProof != null) {
            val proof = message.proof
            val domain = appDomain
            val signed = proof != null && domain != null && verifyTonProof(
                address = message.address,
                publicKeyHex = message.publicKeyHex,
                proof = proof,
                expectedDomain = domain,
                expectedPayload = expectedProof,
            )
            if (!signed) {
                onConnectFailed(
                    WalletMessage.ConnectFailed(
                        id = message.id,
                        code = TonConnectError.BAD_REQUEST,
                        message = "ton_proof was rejected",
                    ),
                )
                return
            }
        }
        session.walletClientId = walletClientId
        val version = runCatching { parseWalletStateInit(message.walletStateInit).version.wire }.getOrDefault("")
        val connected = ConnectedWallet(
            appName = message.device.appName.ifBlank { session.appName },
            address = message.address,
            network = message.network,
            publicKeyHex = message.publicKeyHex,
            walletStateInit = message.walletStateInit,
            platform = message.device.platform,
            appVersion = message.device.appVersion,
            features = message.device.features,
            walletVersion = version,
        )
        session.connected = connected
        _wallet.value = connected
        _status.value = ConnectionStatus.Connected
        persist(session)
        connectWait?.complete(connected)
        emit(TonConnectEvent.WalletConnected(connected))
        logger?.log("wallet connected ${connected.address}")
        val embeddedError = message.embeddedError
        if (embeddedError != null) {
            emit(TonConnectEvent.RequestFailed("embedded", embeddedError.code, embeddedError.message))
        }
        val embeddedBoc = internalBoc(message.embeddedResult)
        if (embeddedBoc != null && message.embeddedResult is JsonObject) {
            emit(TonConnectEvent.MessageSigned(SignedMessage(embeddedBoc, "embedded")))
        } else if (embeddedBoc != null) {
            emit(TonConnectEvent.TransactionSigned(SignedTransaction(embeddedBoc, "embedded")))
        }
    }

    private suspend fun onConnectFailed(message: WalletMessage.ConnectFailed) {
        _status.value = ConnectionStatus.Disconnected
        connectWait?.completeExceptionally(WalletException(message.code, message.message))
        connectWait = null
        emit(TonConnectEvent.ConnectFailed(message.code, message.message))
        logger?.log("connect failed ${message.code}: ${message.message}")
    }

    private suspend fun dispatchResponse(message: WalletMessage.Response) {
        val deferred = pendingMutex.withLock { pending.remove(message.id) } ?: return
        deferred.complete(message)
    }

    private suspend fun request(method: String, params: List<String>, timeout: Duration): WalletMessage.Response {
        val session = live ?: throw TonConnectException("Wallet is not connected")
        val walletClientId = session.walletClientId ?: throw TonConnectException("Wallet is not connected")
        val deferred = CompletableDeferred<WalletMessage.Response>()
        val id = pendingMutex.withLock {
            val next = session.nextRequestId.toString()
            session.nextRequestId += 1
            pending[next] = deferred
            next
        }
        val body = ProtocolJson.encodeToString(AppRequest.serializer(), AppRequest(method, params, id))
        try {
            val encrypted = session.crypto.encrypt(body, walletClientId.hexToBytes())
            bridge.send(
                bridgeUrl = session.bridgeUrl,
                fromClientId = session.crypto.clientId,
                toClientId = walletClientId,
                bodyBase64 = encrypted.toBase64(),
                topic = method,
                traceId = session.traceId,
            )
            persist(session)
            logger?.log("sent $method #$id")
        } catch (error: Throwable) {
            pendingMutex.withLock { pending.remove(id) }
            throw error
        }
        val response = withTimeout(timeout) { deferred.await() }
        val error = response.error
        if (error != null) {
            emit(TonConnectEvent.RequestFailed(response.id, error.code, error.message))
            throw WalletException(error.code, error.message)
        }
        return response
    }

    private fun requireWallet(): ConnectedWallet =
        live?.connected ?: throw TonConnectException("Wallet is not connected")

    private fun requireFeature(name: String) {
        val features = requireWallet().features
        if (features.isEmpty()) return
        if (features.none { it.name == name }) throw TonConnectException("Connected wallet does not support $name")
    }

    private fun normalize(transaction: Transaction, wallet: ConnectedWallet): Transaction = transaction.copy(
        network = transaction.network ?: wallet.network,
        from = transaction.from ?: wallet.address,
        validUntil = transaction.validUntil ?: (currentTimeMillis() / 1000 + 300),
    )

    private fun validateTransaction(transaction: Transaction, wallet: ConnectedWallet, featureName: String) {
        val hasMessages = !transaction.messages.isNullOrEmpty()
        val hasItems = !transaction.items.isNullOrEmpty()
        if (hasMessages == hasItems) {
            throw TonConnectException("A transaction needs messages or items, and not both")
        }
        transaction.messages?.forEach { message ->
            require(message.address.isNotBlank()) { "Message address is empty" }
            require(message.amount.all { it.isDigit() }) { "Amount must be a decimal nanocoin string" }
        }
        if (wallet.features.isNotEmpty() && wallet.features.none { it.name == featureName }) {
            throw TonConnectException("Connected wallet does not support $featureName")
        }
        val max = when (featureName) {
            "SignMessage" -> wallet.features.filterIsInstance<WalletFeature.SignMessage>().firstOrNull()?.maxMessages
            else -> wallet.features.filterIsInstance<WalletFeature.SendTransaction>().firstOrNull()?.maxMessages
        }
        if (max != null && (transaction.messages?.size ?: 0) > max) {
            throw TonConnectException("Wallet accepts at most $max messages in one request")
        }
        val extraCurrency = when (featureName) {
            "SignMessage" -> wallet.features.filterIsInstance<WalletFeature.SignMessage>().firstOrNull()?.extraCurrencySupported
            else -> wallet.features.filterIsInstance<WalletFeature.SendTransaction>().firstOrNull()?.extraCurrencySupported
        }
        if (extraCurrency == false && transaction.messages.orEmpty().any { !it.extraCurrency.isNullOrEmpty() }) {
            throw TonConnectException("Connected wallet does not support extra currencies")
        }
    }

    private suspend fun persist(session: LiveSession) {
        val connected = session.connected ?: return
        val peer = session.walletClientId ?: return
        storage.save(
            StoredSession(
                secretKeyHex = session.crypto.secretKey.toHex(),
                bridgeUrl = session.bridgeUrl,
                walletClientId = peer,
                appName = connected.appName,
                address = connected.address,
                network = connected.network,
                publicKeyHex = connected.publicKeyHex,
                walletStateInit = connected.walletStateInit,
                platform = connected.platform,
                appVersion = connected.appVersion,
                nextRequestId = session.nextRequestId,
                lastWalletEventId = session.lastWalletEventId,
                bridgeLastEventId = session.bridgeLastEventId,
                featuresJson = ProtocolJson.encodeToString(ListSerializer(WalletFeature.serializer()), connected.features),
                traceId = session.traceId,
                walletVersion = connected.walletVersion,
            ),
        )
    }

    private suspend fun clear() {
        val job = lifecycle.withLock {
            val current = listenJob
            listenJob = null
            live?.crypto?.destroy()
            live = null
            current
        }
        pendingMutex.withLock {
            pending.values.forEach { it.completeExceptionally(TonConnectException("Session closed")) }
            pending.clear()
        }
        _wallet.value = null
        _status.value = ConnectionStatus.Disconnected
        storage.save(null)
        emit(TonConnectEvent.Disconnected)
        job?.cancel()
    }

    private suspend fun emit(event: TonConnectEvent) {
        _events.emit(event)
    }

    private class LiveSession(
        val crypto: SessionCrypto,
        val bridgeUrl: String,
        val appName: String,
        var walletClientId: String? = null,
        var connected: ConnectedWallet? = null,
        var nextRequestId: Long = 1,
        var lastWalletEventId: Long? = null,
        var bridgeLastEventId: String? = null,
        var traceId: String? = null,
        var expectedProofPayload: String? = null,
    ) {
        fun acceptEvent(id: Long): Boolean {
            val previous = lastWalletEventId
            if (previous != null && id <= previous) return false
            lastWalletEventId = id
            return true
        }
    }

    companion object {
        const val DEFAULT_WALLETS_URL =
            "https://raw.githubusercontent.com/ton-connect/wallets-list/main/wallets-v2.json"
    }
}

@Serializable
private data class AppRequest(
    val method: String,
    val params: List<String>,
    val id: String,
)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun internalBoc(result: JsonElement?): String? = when (result) {
    is JsonObject -> result.string("internalBoc")
    is JsonPrimitive -> result.contentOrNull
    else -> null
}
