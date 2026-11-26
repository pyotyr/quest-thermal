package io.github.cbird.ht203u

import org.junit.Assert.*
import org.junit.Test

class ViewerSettingsTest {
    @Test fun histogramPreservesHeatOrderWithoutTurningSmallNoiseIntoFullContrast() {
        val histogram = IntArray(256)
        for (i in 100..103) histogram[i] = 10
        val lookup = ViewerState.contrastLookup(histogram, true)
        assertTrue(lookup[103] > lookup[100])
        assertTrue(lookup[103]-lookup[100] < 32)
        assertTrue(lookup.toList().zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun uniformImageDoesNotInventHotColdRegionsOrContrast() {
        val histogram = IntArray(256).apply { this[100] = 500 }
        val (cold, hot) = ViewerState.adaptiveThresholds(histogram, 5f, 5f)
        val mapped = ViewerState.contrastLookup(histogram, true)[100]
        assertEquals(128, mapped)
        assertEquals(0xFF808080.toInt(), ViewerState.color(mapped, ViewerSettings(grayscaleOpacity=1f), IntArray(256), cold, hot, 100))
    }

    @Test fun thresholdMembershipUsesRawHeatRegardlessOfDisplayContrast() {
        val histogram = IntArray(256).apply { this[10] = 5; this[100] = 90; this[240] = 5 }
        val (cold, hot) = ViewerState.adaptiveThresholds(histogram, 5f, 5f)
        val settings = ViewerSettings(grayscaleOpacity=1f)
        val iron = IntArray(256)
        assertEquals(0xFFFF3030.toInt(), ViewerState.color(20, settings, iron, cold, hot, 240))
        assertEquals(0xFF3080FF.toInt(), ViewerState.color(230, settings, iron, cold, hot, 10))
        assertEquals(0xFF808080.toInt(), ViewerState.color(128, settings, iron, cold, hot, 100))
    }

    @Test fun zeroTailDisablesItsHighlightAndLinearKeepsInputIntensity() {
        val settings = ViewerSettings(coldPercent = 0f, hotPercent = 0f, grayscaleOpacity=1f)
        assertEquals(0xFFFAFAFA.toInt(), ViewerState.color(250, settings, IntArray(256), 10, 240))
        assertEquals(123, ViewerState.contrastLookup(IntArray(256), false)[123])
    }

    @Test fun opacityCanHideGrayscaleWithoutFadingHotColdHighlights() {
        val settings = ViewerSettings(grayscaleOpacity = 0f, redOpacity = 1f, blueOpacity = 0.5f)
        val iron = IntArray(256)
        assertEquals(0, ViewerState.color(128, settings, iron, 10, 240, 100) ushr 24)
        assertEquals(255, ViewerState.color(255, settings, iron, 10, 240, 250) ushr 24)
        assertEquals(127, ViewerState.color(0, settings, iron, 10, 240, 5) ushr 24)
    }
}
