package io.kton.connect.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.TimeUnit

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
    }
    engine {
        config {
            // SSE stays open for the whole session. OkHttp treats 0 as "no timeout".
            readTimeout(0, TimeUnit.MILLISECONDS)
            connectTimeout(30, TimeUnit.SECONDS)
        }
    }
}
