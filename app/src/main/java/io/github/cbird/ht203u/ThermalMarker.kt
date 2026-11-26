package io.github.cbird.ht203u

/** Source-image coordinates, normalized to pixel centres. Labels stay upright in the HUD. */
data class ThermalMarker(val kind: String, val x: Float, val y: Float, val reading: String? = null) {
    fun rotated(quarterTurns: Int): ThermalMarker = when (quarterTurns and 3) {
        1 -> copy(x=1f-y,y=x)
        2 -> copy(x=1f-x,y=1f-y)
        3 -> copy(x=y,y=1f-x)
        else -> this
    }
    fun visible(settings: ViewerSettings): Boolean = when(kind) {
        "Min" -> settings.showMin
        "Max" -> settings.showMax
        else -> settings.showCenter
    }
}
