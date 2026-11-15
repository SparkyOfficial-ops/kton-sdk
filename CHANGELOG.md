# Changelog


- chore: initial project setup (auto)

- feat: add TonConnect core module (auto)

- feat: implement SessionCrypto with TweetNaCl (auto)

- feat: add Bridge SSE transport (auto)

- feat: add AndroidTonConnect implementation (auto)

- feat: add AndroidSessionStorage (auto)

- feat: add QR code generation (auto)

- feat: add OpenWallet deep-link helper (auto)

- feat: add JVM and Android shared crypto base (auto)

- feat: add JVM session storage (auto)

- feat: add iOS/macOS session storage stub (auto)

- feat: add platform Lock abstraction (auto)

- feat: add platform Uuid abstraction (auto)

- feat: add platform info (auto)

- feat: implement HttpClientFactory for all targets (auto)

- feat: add WalletState model (auto)

- feat: add Protocol message types (auto)

- feat: add TonProof verification (auto)

- feat: add Links builder for connect URL (auto)

- feat: add Boc serialization helper (auto)

- feat: implement QrCode common expect (auto)

- test: add CryptoTest for TweetNaCl (auto)

- test: add ProtocolTest for connect URL (auto)

- ci: add GitHub Actions workflow (auto)

- docs: add README (auto)

- chore: add .gitignore and .gitattributes (auto)

- chore: configure Gradle wrapper (auto)

- chore: add gradle.properties (auto)

- chore: configure root build.gradle.kts (auto)

- chore: configure tonconnect module build.gradle.kts (auto)

- refactor: extract Bytes utility functions (auto)

- refactor: extract Sha256 to separate file (auto)

- refactor: clean up Bridge error handling (auto)

- refactor: simplify SessionCrypto key derivation (auto)

- fix: handle null body in Bridge SSE response (auto)

- fix: correct base64url encoding in Links (auto)

- fix: AndroidSessionStorage NPE on missing prefs key (auto)

- fix: TonProof timestamp overflow on 32-bit (auto)

- fix: QrCode bitmap recycling on Android (auto)

- fix: HttpClient engine selection on JVM (auto)

- perf: cache parsed WalletState in memory (auto)

- perf: reuse HttpClient instance across requests (auto)

- style: apply ktlint formatting (auto)

- feat: add OpenWallet deep-link helper

- feat: add JVM and Android shared crypto base

- feat: add JVM session storage

- feat: add iOS platform stub

- feat: add platform Lock abstraction

- feat: add platform Uuid abstraction

- feat: add platform info helpers

- feat: implement HttpClientFactory for all targets

- feat: add WalletState model

- feat: add Protocol message types

- feat: add TonProof verification

- feat: add Links builder for connect URL

- feat: add Boc serialization helper

- feat: implement QrCode common expect

- test: add CryptoTest for TweetNaCl

- test: add ProtocolTest for connect URL

- refactor: extract Bytes utility functions

- refactor: extract Sha256 to separate file

- refactor: clean up Bridge error handling

- refactor: simplify SessionCrypto key derivation

- fix: handle null body in Bridge SSE response

- fix: correct base64url encoding in Links

- fix: AndroidSessionStorage NPE on missing prefs key

- fix: TonProof timestamp overflow on 32-bit

- fix: QrCode bitmap recycling on Android

- fix: HttpClient engine selection on JVM

- perf: cache parsed WalletState in memory

- perf: reuse HttpClient instance across requests

- style: apply ktlint formatting

- style: add file headers

- docs: document TonConnect public API

- docs: add protocol overview to README

- docs: add QR code usage example

- docs: add TonProof verification guide

- chore: bump ktor to 3.1.3

- chore: bump kotlinx-serialization to 1.8.1

- chore: bump kotlinx-coroutines to 1.10.2

- chore: add dependabot config

- feat: add disconnect support to TonConnect

- feat: add reconnect logic in Bridge
