package io.github.cbird.ht203u

/** Decodes complete UVC images without truncating 16-bit sensor values. */
object ThermalDecoder {
    data class Image(val width: Int, val height: Int, val values: IntArray, val source: String)

    fun decode(words: ShortArray, width: Int, height: Int, fourCC: String, hik: Boolean): Image {
        require(width > 0 && height > 0 && words.size >= width * height)
        fun neutralChroma(start: Int, count: Int): Boolean {
            var neutral = 0; var sampled = 0
            for (i in start until start + count step 13) {
                val chroma = (words[i].toInt() ushr 8) and 255
                if (chroma in 124..132) neutral++
                sampled++
            }
            return sampled > 0 && neutral.toFloat() / sampled > .85f
        }
        // HIK F2 stacked streams contain a clean 14/16-bit plane and a YUY2
        // preview. Live 2bdf:0102 captures show Max text and a crosshair burned
        // into the preview. Use the clean plane when its range is recognizable.
        if (hik && height in listOf(384, 392, 400)) {
            val planeRows = if (height == 384) 192 else if (height == 400) 200 else 196
            for (row in listOf(planeRows, 0)) {
                if (row + 192 <= height && neutralChroma(row * width, width * 192)) {
                    if (row > 0 && !neutralChroma(0, width*192)) {
                        val raw = IntArray(width*192) { words[it].toInt() and 65535 }
                        val lo = raw.minOrNull()!!; val hi = raw.maxOrNull()!!
                        if (lo > 0 && hi <= 16383 && hi > lo) {
                            return Image(width,192,raw,"Clean 16-bit thermal image")
                        }
                    }
                    return Image(width, 192, IntArray(width * 192) {
                        words[row * width + it].toInt() and 255
                    }, "Camera-processed grayscale")
                }
            }
        }
        val rows = if (hik) minOf(height, 192) else height
        val n = width * rows
        val y16 = fourCC.trim().uppercase() in listOf("Y16", "Y14", "Y12", "Z16", "D16")
        val luma = !y16 && (fourCC == "YUY2" || fourCC == "YUYV") &&
            (!hik || neutralChroma(0, n))
        return Image(width, rows, IntArray(n) {
            val value = words[it].toInt() and 65535
            if (luma) value and 255 else value
        }, if (luma) "Camera grayscale" else "Full 16-bit thermal image")
    }

    fun normalize(values: IntArray): IntArray {
        if (values.isEmpty()) return IntArray(0)
        val lo = values.minOrNull()!!
        val hi = values.maxOrNull()!!
        if (lo == hi) return IntArray(values.size) { 128 }
        return IntArray(values.size) { ((values[it] - lo).toLong() * 255 / (hi - lo)).toInt() }
    }
}
