package io.github.cbird.ht203u

import android.content.Context

object ViewerPrefs {
    private var loaded = false
    @Synchronized fun load(context: Context) {
        if (loaded) return
        ViewerState.settings = read(context.getSharedPreferences("viewer", Context.MODE_PRIVATE))
        loaded = true
    }

    internal fun read(p: android.content.SharedPreferences): ViewerSettings {
        val d = ViewerSettings()
        fun f(k: String, fallback: Float) = p.getFloat(k, fallback)
        fun b(k: String, fallback: Boolean) = p.getBoolean(k, fallback)
        val warp = p.getString("warp", null)?.let { text ->
            if (text.isBlank()) emptyList() else {
                val parts = text.split(',').mapNotNull { it.toFloatOrNull() }
                if (parts.size == 9 && parts.all { it.isFinite() }) parts else d.warp
            }
        } ?: d.warp
        return d.copy(
            headLocked = b("headLocked", d.headLocked),
            horizontalFov = f("hfov", d.horizontalFov), verticalFov = f("vfov", d.verticalFov),
            horizontalOffset = f("x", d.horizontalOffset), verticalOffset = f("y", d.verticalOffset), roll = f("roll", d.roll),
            distance = f("distance", d.distance), curved = b("curved", d.curved),
            opacity = f("opacity", d.opacity), grayscaleOpacity = f("grayOpacity", d.grayscaleOpacity),
            redOpacity = f("redOpacity", d.redOpacity), blueOpacity = f("blueOpacity", d.blueOpacity),
            showMin = b("min", d.showMin), showMax = b("max", d.showMax), showCenter = b("center", d.showCenter),
            showMean = b("mean", d.showMean), showReadings = b("readings", d.showReadings),
            diagnostics = b("diagnostics", d.diagnostics), colorMap = p.getInt("palette", d.colorMap).coerceIn(0, 4),
            histogramContrast = b("histogram", d.histogramContrast), coldPercent = f("coldPercent", d.coldPercent),
            hotPercent = f("hotPercent", d.hotPercent), warp = warp,
        )
    }

    fun save(context: Context, s: ViewerSettings = ViewerState.settings) {
        ViewerState.settings = s
        context.getSharedPreferences("viewer", Context.MODE_PRIVATE).edit()
            .putBoolean("headLocked", s.headLocked)
            .putFloat("hfov", s.horizontalFov).putFloat("vfov", s.verticalFov)
            .putFloat("x", s.horizontalOffset).putFloat("y", s.verticalOffset).putFloat("roll", s.roll)
            .putFloat("distance", s.distance).putBoolean("curved", s.curved).putFloat("opacity", s.opacity)
            .putFloat("grayOpacity", s.grayscaleOpacity).putFloat("redOpacity", s.redOpacity).putFloat("blueOpacity", s.blueOpacity)
            .putBoolean("min", s.showMin).putBoolean("max", s.showMax).putBoolean("center", s.showCenter)
            .putBoolean("mean", s.showMean).putBoolean("readings", s.showReadings).putBoolean("diagnostics", s.diagnostics)
            .putInt("palette", s.colorMap).putBoolean("histogram", s.histogramContrast)
            .putFloat("coldPercent", s.coldPercent).putFloat("hotPercent", s.hotPercent)
            .putString("warp", s.warp.joinToString(",")).apply()
    }
}
