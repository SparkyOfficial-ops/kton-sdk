package io.kton.connect.qr

import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

internal actual fun encodeQr(payload: String): QrCode {
    require(payload.isNotEmpty()) { "QR payload is empty" }
    val code = try {
        Encoder.encode(payload, ErrorCorrectionLevel.M)
    } catch (error: WriterException) {
        throw IllegalArgumentException("Payload does not fit in a QR code", error)
    }
    val matrix = code.matrix
    val size = matrix.width
    val modules = BooleanArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            modules[y * size + x] = matrix.get(x, y).toInt() != 0
        }
    }
    return QrCode(size, modules)
}
