package io.kton.connect.qr

class QrCode(val size: Int, private val modules: BooleanArray) {
    init {
        require(size > 0 && modules.size == size * size) { "QR matrix is not square" }
    }

    fun isDark(x: Int, y: Int): Boolean {
        require(x in 0 until size && y in 0 until size)
        return modules[y * size + x]
    }

    fun toSvg(moduleSize: Int = 4, quietZone: Int = 4): String {
        require(moduleSize > 0 && quietZone >= 0)
        val dimension = (size + quietZone * 2) * moduleSize
        val svg = StringBuilder(size * size * 24)
        svg.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $dimension $dimension" shape-rendering="crispEdges">""")
        svg.append("""<rect width="100%" height="100%" fill="#ffffff"/>""")
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isDark(x, y)) continue
                val left = (x + quietZone) * moduleSize
                val top = (y + quietZone) * moduleSize
                svg.append("""<rect x="$left" y="$top" width="$moduleSize" height="$moduleSize" fill="#000000"/>""")
            }
        }
        svg.append("</svg>")
        return svg.toString()
    }
}

internal expect fun encodeQr(payload: String): QrCode
