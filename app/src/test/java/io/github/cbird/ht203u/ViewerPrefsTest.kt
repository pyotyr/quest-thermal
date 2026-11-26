package io.github.cbird.ht203u

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ViewerPrefsTest {
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("preset-test",Context.MODE_PRIVATE)
        .apply { edit().clear().commit() }

    @Test fun freshInstallMatchesTheExportedHeadsetPreset() {
        val actual=ViewerPrefs.read(prefs())
        val preset=javaClass.getResourceAsStream("/pyotyr-preset.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val floats=mapOf("hfov" to actual.horizontalFov,"vfov" to actual.verticalFov,
            "x" to actual.horizontalOffset,"y" to actual.verticalOffset,"roll" to actual.roll,
            "distance" to actual.distance,"opacity" to actual.opacity,"grayOpacity" to actual.grayscaleOpacity,
            "redOpacity" to actual.redOpacity,"blueOpacity" to actual.blueOpacity,
            "hotPercent" to actual.hotPercent,"coldPercent" to actual.coldPercent)
        floats.forEach { (key,value) -> assertEquals(key,preset.getDouble(key).toFloat(),value,.000001f) }
        val flags=mapOf("curved" to actual.curved,"min" to actual.showMin,"max" to actual.showMax,
            "center" to actual.showCenter,"mean" to actual.showMean,"readings" to actual.showReadings,
            "diagnostics" to actual.diagnostics,"histogram" to actual.histogramContrast)
        flags.forEach { (key,value) -> assertEquals(key,preset.getBoolean(key),value) }
        assertEquals(preset.getInt("palette"),actual.colorMap)
        val warp=preset.getJSONArray("warp")
        assertEquals(warp.length(),actual.warp.size)
        actual.warp.forEachIndexed { i,v -> assertEquals(warp.getDouble(i).toFloat(),v,.000001f) }
        assertEquals(preset.getInt("rotationQuarterTurns"),ViewerDefaults.CAMERA_ROTATION_QUARTER_TURNS)
    }

    @Test fun anUpgradeKeepsSavedChoicesIncludingAnExplicitlyResetAlignment() {
        val p=prefs();p.edit().putFloat("opacity",.17f).putString("warp","").putBoolean("headLocked",false).commit()
        val actual=ViewerPrefs.read(p)
        assertEquals(.17f,actual.opacity,.000001f)
        assertTrue(actual.warp.isEmpty())
        assertFalse(actual.headLocked)
        assertEquals(ViewerSettings().coldPercent,actual.coldPercent,.000001f)
    }

    @Test fun malformedSavedAlignmentFallsBackToThePublishedPreset() {
        val p=prefs();p.edit().putString("warp","NaN,1,2").commit()
        assertEquals(ViewerSettings().warp,ViewerPrefs.read(p).warp)
    }
}
