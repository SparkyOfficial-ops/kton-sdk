# kton — TonConnect 2 для Kotlin

Нативный клиент [TonConnect 2](https://github.com/ton-blockchain/ton-connect) без WebView.
Приложение говорит с кошельком напрямую: ключи X25519, `crypto_box` как в официальном JS SDK, мост по HTTP SSE и universal link.

Весь протокол лежит в `commonMain`. Таргеты `jvm` и `android` делят OkHttp, ZXing и байткод Java 11 (`src/jvmAndAndroidMain`). Android-модуль подключает проект как KMP-зависимость. Путь к SDK — `sdk.dir` в `local.properties`. Колбэки приходят не на UI-потоке: перед обновлением экрана переключитесь на `Dispatchers.Main` (на Android он приходит вместе с библиотекой).

## Подключение

Из этого репозитория: `implementation(project(":tonconnect"))`.

Из Maven Local после `gradlew publishToMavenLocal`:

```kotlin
dependencies {
    implementation("io.kton:tonconnect:0.1.0")
}
```

В обычном Android- или JVM-проекте координаты `io.kton:tonconnect-android:0.1.0` и `io.kton:tonconnect-jvm:0.1.0`.

## Кошелёк

```kotlin
import io.kton.connect.TonConnect
import io.kton.connect.protocol.OutgoingMessage
import io.kton.connect.protocol.TonNetwork

val tonConnect = TonConnect(
    manifestUrl = "https://example.com/tonconnect-manifest.json",
)

val link = tonConnect.connect(wallet)
// Кнопка на телефоне: context.openWallet(link) открывает link.universalUrl.
// QR: link.qrCode().toSvg() на десктопе, link.qrCode().toBitmap() на Android.
// link.qrPayload — та же строка, что universal link. link.unifiedUrl — схема tc://.

tonConnect.onWalletConnected { wallet ->
    println(wallet.address)
}

val boc = tonConnect.sendTransaction(
    OutgoingMessage(
        address = "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c",
        amount = "100000000", // наноTON
    ),
    network = TonNetwork.MAINNET,
)

tonConnect.onTransactionSigned { signed ->
    println(signed.bocBase64)
}
```

`sendTransaction` ждёт подпись и одновременно шлёт событие `TransactionSigned`. Список кошельков:

```kotlin
val wallets = tonConnect.wallets(platform = "android")
```

Каталог по умолчанию — `wallets-v2.json` из [ton-connect/wallets-list](https://github.com/ton-connect/wallets-list). У кошелька без SSE-моста (чистое браузерное расширение) `connect` бросит ошибку: JS-мост живёт в WebView, этот клиент его не тащит.

Сессию можно сохранить между запусками. В `StoredSession.secretKeyHex` секрет, которым расшифровываются все сообщения. На Android он кладётся в EncryptedSharedPreferences (Android Keystore). На десктопе файл сессии шифруется AES-GCM, а ключ на Windows закрывается DPAPI.

```kotlin
val tonConnect = TonConnect(context, manifestUrl = "https://example.com/tonconnect-manifest.json")

val desktop = TonConnect(
    manifestUrl = "https://example.com/tonconnect-manifest.json",
    storage = encryptedSessionStorage(File(System.getProperty("user.home"), ".kton")),
)
```

## Что реализовано

- Сессия: X25519 и NaCl `crypto_box` (`nonce || ciphertext`), совместимо с TweetNaCl.
- Мост: `GET /events` (SSE, переподключение с `last_event_id`) и `POST /message`.
- Universal link, custom scheme и `tc://` с `v`, `id`, `r`, `ret`.
- `sendTransaction`, `signData`, `disconnect`, события `connect` / `connect_error` / `disconnect`.
- Проверка подписи `ton_proof` (Ed25519) и привязка ключа к адресу через `walletStateInit`. Поддерживаются кошельки v2r1–v5r1, в том числе контракты Tonkeeper и MyTonWallet. Неизвестный код контракта соединение отклоняет.
- Публикация `io.kton:tonconnect:0.1.0` (`publishToMavenLocal`). iOS-таргета нет: Kotlin/Native для iOS собирается только на macOS.

## Сборка

```
gradlew.bat :tonconnect:jvmTest :tonconnect:testDebugUnitTest
```

`jvmTest` гоняет протокол и локальный мост. `testDebugUnitTest` собирает Android-таргет и прогоняет общие тесты на JVM Android SDK. Нужны JDK 21 и Android SDK с платформой 36.
