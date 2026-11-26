package io.github.cbird.ht203u

/** Decodes complete UVC images without truncating 16-bit sensor values. */
object ThermalDecoder {
    data class ReportedTemperature(val x: Int, val y: Int, val celsius: Float) {
        // The tested camera OSD truncates to tenths, rather than rounding.
        fun label(): String = String.format(java.util.Locale.US,"%.1f\u00B0C",(celsius*10).toInt()/10f)
    }
    data class Image(val width: Int, val height: Int, val values: IntArray, val source: String,
                     val maximum: ReportedTemperature? = null,
                     val minimum: ReportedTemperature? = null,
                     val spot: ReportedTemperature? = null)

    /** Camera-reported statistic, not a conversion of the clean sensor pixels.
     * Verified against the same-frame camera OSD at room and hand temperatures.
     * This record layout is only established for the tested 256x392 USB mode.
     */
    internal fun reportedMaximum(words: ShortArray, width: Int, height: Int): ReportedTemperature? =
        reportedPoint(words,width,height,2,3,4)

    internal fun reportedMinimum(words: ShortArray, width: Int, height: Int): ReportedTemperature? =
        reportedPoint(words,width,height,5,6,7)

    internal fun reportedSpot(words: ShortArray, width: Int, height: Int): ReportedTemperature? =
        reportedPoint(words,width,height,9,null,null)

    /** Trailer words: max/T/X/Y, min/T/X/Y, average/T, centre/T after the magic.
     * The centre field was distinguished from the average with a centred/edge
     * hand capture; minimum coordinates track the clean plane's coldest region.
     * These values share the camera's statistic encoding, not the raw ADC units.
     */
    private fun reportedPoint(words: ShortArray, width: Int, height: Int, temperatureIndex: Int,
                              xIndex: Int?, yIndex: Int?): ReportedTemperature? {
        if (width != 256 || height != 392 || words.size < width*height) return null
        val offset=388*width
        fun u16(i: Int)=words[offset+i].toInt() and 65535
        if (u16(0) != 65535 || u16(1) != 61166) return null
        val encoded=u16(temperatureIndex)
        if (encoded == 0 || encoded == 65535) return null
        val x=xIndex?.let { u16(it) } ?: 500
        val y=yIndex?.let { u16(it) } ?: 500
        if (x !in 0..1000 || y !in 0..1000) return null
        return ReportedTemperature((x*width/1000).coerceAtMost(width-1),
            (y*192/1000).coerceAtMost(191),encoded/64f-50f)
    }

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
                            return Image(width,192,raw,"Clean 16-bit thermal image",reportedMaximum(words,width,height),
                                reportedMinimum(words,width,height),reportedSpot(words,width,height))
                        }
                    }
                    return Image(width, 192, IntArray(width * 192) {
                        words[row * width + it].toInt() and 255
                    }, "Camera-processed grayscale",reportedMaximum(words,width,height),
                        reportedMinimum(words,width,height),reportedSpot(words,width,height))
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
