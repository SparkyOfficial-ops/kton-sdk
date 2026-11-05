package io.kton.connect.http

import io.ktor.client.HttpClient

internal expect fun createPlatformHttpClient(): HttpClient
