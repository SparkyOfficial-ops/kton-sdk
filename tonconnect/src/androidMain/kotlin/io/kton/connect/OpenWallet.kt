package io.kton.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.kton.connect.protocol.ConnectLink

fun Context.openWallet(link: ConnectLink) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.universalUrl))
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
