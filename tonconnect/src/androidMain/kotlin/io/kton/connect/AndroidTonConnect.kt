package io.kton.connect

import android.content.Context
import io.kton.connect.storage.AndroidSessionStorage

fun TonConnect(
    context: Context,
    manifestUrl: String,
    appDomain: String? = null,
    logger: TonConnectLogger? = null,
): TonConnect = TonConnect(
    manifestUrl = manifestUrl,
    storage = AndroidSessionStorage(context),
    logger = logger,
    appDomain = appDomain,
)
