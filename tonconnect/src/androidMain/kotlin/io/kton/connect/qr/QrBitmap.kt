package io.kton.connect.qr

import android.graphics.Bitmap
import android.graphics.Color

fun QrCode.toBitmap(moduleSize: Int = 8, quietZone: Int = 4): Bitmap {
    require(moduleSize > 0 && quietZone >= 0)
    val dimension = (size + quietZone * 2) * moduleSize
    val pixels = IntArray(dimension * dimension) { Color.WHITE }
    for (y in 0 until size) {
        for (x in 0 until size) {
            if (!isDark(x, y)) continue
            val originX = (x + quietZone) * moduleSize
            val originY = (y + quietZone) * moduleSize
            for (dy in 0 until moduleSize) {
                val row = (originY + dy) * dimension + originX
                for (dx in 0 until moduleSize) pixels[row + dx] = Color.BLACK
            }
        }
    }
    return Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888)
}
