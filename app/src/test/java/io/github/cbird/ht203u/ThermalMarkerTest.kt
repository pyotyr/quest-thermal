package io.github.cbird.ht203u

import org.junit.Assert.*
import org.junit.Test

class ThermalMarkerTest {
    @Test fun markerCoordinatesFollowAllFourImageRotations() {
        val marker=ThermalMarker("Max",.2f,.3f,"35.4 C")
        val expected=listOf(.2f to .3f,.7f to .2f,.8f to .7f,.3f to .8f)
        expected.forEachIndexed { i,p ->
            val actual=marker.rotated(i)
            assertEquals(p.first,actual.x,.00001f); assertEquals(p.second,actual.y,.00001f)
            assertEquals("35.4 C",actual.reading)
        }
    }
    @Test fun markerSwitchesControlEachKindIndependently() {
        val settings=ViewerSettings(showMin=false,showMax=true,showCenter=false)
        assertFalse(ThermalMarker("Min",.5f,.5f).visible(settings))
        assertTrue(ThermalMarker("Max",.5f,.5f).visible(settings))
        assertFalse(ThermalMarker("Spot",.5f,.5f).visible(settings))
    }
}
