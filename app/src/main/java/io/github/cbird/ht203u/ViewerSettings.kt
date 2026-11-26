package io.github.cbird.ht203u

data class ViewerSettings(
    val headLocked: Boolean = true,
    val horizontalFov: Float = 54f,
    val verticalFov: Float = 42f,
    val horizontalOffset: Float = -2.5f,
    val verticalOffset: Float = -1.5f,
    val roll: Float = 0f,
    val distance: Float = 1.15f,
    val curved: Boolean = true,
    val opacity: Float = 0.8f,
    val grayscaleOpacity: Float = .86f,
    val redOpacity: Float = 1f,
    val blueOpacity: Float = 1f,
    val showMin: Boolean = false,
    val showMax: Boolean = true,
    val showCenter: Boolean = false,
    val showMean: Boolean = false,
    val showReadings: Boolean = true,
    val diagnostics: Boolean = false,
    val colorMap: Int = 0,
    val histogramContrast: Boolean = true,
    val coldPercent: Float = 13f,
    val hotPercent: Float = 1f,
    val warp: List<Float> = listOf(
        .84012514f, -.06076395f, .100477286f,
        -.0019251748f, .6207928f, .16046312f,
        -.019204846f, -.06764792f, 1f,
    ),
)

object ViewerDefaults {
    const val CAMERA_ROTATION_QUARTER_TURNS = 2
}

object ViewerState {
    val cameraFeed = CameraFeedState()
    @Volatile var settings = ViewerSettings()
    @Volatile var optionsVisible = false
    @Volatile var adjusting = false
    @Volatile var calibrating = false
    val commands = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val colorMapNames = listOf("Gray + hot/cold", "Grayscale", "Ironbow", "Inferno", "Rainbow")

    fun color(index: Int, settings: ViewerSettings, iron: IntArray, cold: Int, hot: Int, thermalIndex: Int = index): Int {
        val t = index.coerceIn(0, 255)
        fun fade(color: Int, opacity: Float) = ((opacity.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0x00FFFFFF)
        return when (settings.colorMap) {
            0 -> when {
                cold >= hot -> fade(gray(t), settings.grayscaleOpacity)
                settings.hotPercent > 0f && thermalIndex >= hot -> fade(0xFFFF3030.toInt(), settings.redOpacity)
                settings.coldPercent > 0f && thermalIndex <= cold -> fade(0xFF3080FF.toInt(), settings.blueOpacity)
                else -> fade(gray(t), settings.grayscaleOpacity)
            }
            1 -> fade(gray(t), settings.grayscaleOpacity)
            2 -> iron[t]
            3 -> interpolate(t, intArrayOf(0xFF000004.toInt(), 0xFF420A68.toInt(),
                0xFF932667.toInt(), 0xFFDD513A.toInt(), 0xFFFCA50A.toInt(), 0xFFFCFFA4.toInt()))
            else -> {
                // Blue -> cyan -> green -> yellow -> red, with no wrap at the hot end.
                interpolate(t, intArrayOf(0xFF0000FF.toInt(), 0xFF00FFFF.toInt(),
                    0xFF00FF00.toInt(), 0xFFFFFF00.toInt(), 0xFFFF0000.toInt()))
            }
        }
    }

    private fun gray(t: Int) = (0xFF shl 24) or (t shl 16) or (t shl 8) or t

    fun contrastLookup(histogram: IntArray, equalize: Boolean): IntArray {
        if (!equalize) return IntArray(256) { it }
        val total = histogram.sum()
        val first = histogram.firstOrNull { it > 0 } ?: return IntArray(256) { it }
        if (total == first) return IntArray(256) { 128 }
        // Limit amplification of sensor noise in large, nearly uniform surfaces.
        // Redistribute clipped histogram mass rather than stretching tiny
        // variations across the entire black-to-white range.
        val limit = (4.0 * total / 256).coerceAtLeast(1.0)
        val clipped = DoubleArray(256) { minOf(histogram[it].toDouble(), limit) }
        val redistributed = (total-clipped.sum())/256
        val base = clipped[0]+redistributed
        var cumulative = 0.0
        return IntArray(256) { i ->
            cumulative += clipped[i]+redistributed
            ((cumulative-base)*255/(total-base)).toInt().coerceIn(0,255)
        }
    }

    /** Quantiles use this frame's distribution; no absolute temperature cutoff. */
    fun adaptiveThresholds(histogram: IntArray, coldPercent: Float, hotPercent: Float): Pair<Int, Int> {
        val total = histogram.sum()
        if (total == 0) return 0 to 0
        fun quantile(rank: Int): Int {
            val target = rank.coerceIn(0, total - 1)
            var count = 0
            for (i in histogram.indices) {
                count += histogram[i]
                if (count > target) return i
            }
            return histogram.lastIndex
        }
        val coldRank = kotlin.math.ceil(total * coldPercent.toDouble() / 100.0).toInt() - 1
        val hotRank = (total * (100.0 - hotPercent) / 100.0).toInt()
        return quantile(coldRank) to quantile(hotRank)
    }

    private fun interpolate(t: Int, stops: IntArray): Int {
        val p = t / 255f * (stops.size - 1)
        val lo = p.toInt().coerceAtMost(stops.lastIndex - 1)
        val mix = p - lo
        fun channel(shift: Int): Int {
            val a = (stops[lo] ushr shift) and 255
            val b = (stops[lo + 1] ushr shift) and 255
            return (a + (b - a) * mix).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
