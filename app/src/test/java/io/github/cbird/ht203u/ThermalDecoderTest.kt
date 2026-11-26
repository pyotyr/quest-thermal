package io.github.cbird.ht203u

import org.junit.Assert.*
import org.junit.Test

class ThermalDecoderTest {
    private fun reportedFrame(): ShortArray = ShortArray(256*392).also { words ->
        for(i in 0 until 256*192) words[i]=(5200+i%300).toShort()
        for(i in 0 until 256*192) words[256*196+i]=(0x8000 or (i%256)).toShort()
        // Statistic record from the controlled centre/edge hand check. The image
        // itself is synthetic; no private camera capture is bundled in tests.
        val footer=intArrayOf(65535,61166,5185,62,565,4212,215,952,4696,4962)
        footer.forEachIndexed { i,value -> words[388*256+i]=value.toShort() }
    }
    @Test fun allThreeReadingsSurviveCleanPlaneDecodingWithTheirOwnCoordinates() {
        val image=ThermalDecoder.decode(reportedFrame(),256,392,"YUY2",true)
        assertEquals("Clean 16-bit thermal image",image.source)
        assertEquals("31.0\u00B0C",image.maximum!!.label())
        assertEquals("15.8\u00B0C",image.minimum!!.label())
        assertEquals("27.5\u00B0C",image.spot!!.label())
        assertEquals(55,image.minimum.x); assertEquals(182,image.minimum.y)
        assertEquals(128,image.spot.x); assertEquals(96,image.spot.y)
        assertNotEquals(image.maximum.celsius,image.spot.celsius)
        // Word 8 is the whole-frame average, not the spot.
        assertNotEquals(4696/64f-50f,image.spot.celsius)
    }
    @Test fun centreUsesItsReportedValueRatherThanTheHottestPixelOrAverage() {
        val words=reportedFrame()
        val before=ThermalDecoder.reportedSpot(words,256,392)!!
        words[388*256+9]=4619.toShort()
        assertEquals("22.1\u00B0C",ThermalDecoder.reportedSpot(words,256,392)!!.label())
        assertEquals("27.5\u00B0C",before.label())
        assertEquals("31.0\u00B0C",ThermalDecoder.reportedMaximum(words,256,392)!!.label())
    }
    @Test fun invalidColdMetadataDoesNotInventAReadingOrSuppressOtherMarkers() {
        val words=reportedFrame()
        words[388*256+6]=1001.toShort()
        assertNull(ThermalDecoder.reportedMinimum(words,256,392))
        assertNotNull(ThermalDecoder.reportedMaximum(words,256,392))
        assertNotNull(ThermalDecoder.reportedSpot(words,256,392))
        words[388*256+9]=0
        assertNull(ThermalDecoder.reportedSpot(words,256,392))
        assertNull(ThermalDecoder.reportedMinimum(words,256,192))
        assertNull(ThermalDecoder.reportedSpot(words,256,192))
    }
    @Test fun cameraMaximumUsesTrailerInsteadOfRawSensorCounts() {
        val words=ShortArray(256*392)
        val o=388*256
        for ((i,value) in intArrayOf(65535,61166,5468,670,209).withIndex()) words[o+i]=value.toShort()
        val maximum=ThermalDecoder.reportedMaximum(words,256,392)!!
        assertEquals(35.4375f,maximum.celsius,0.0001f)
        assertEquals(171,maximum.x); assertEquals(40,maximum.y)
        words[o+2]=4571.toShort(); words[o+3]=1000.toShort(); words[o+4]=994.toShort()
        val room=ThermalDecoder.reportedMaximum(words,256,392)!!
        assertEquals(21.421875f,room.celsius,0.0001f)
        assertEquals(255,room.x); assertEquals(190,room.y)
        words[o+2]=5233.toShort()
        assertEquals("31.7\u00B0C",ThermalDecoder.reportedMaximum(words,256,392)!!.label())
    }
    @Test fun unrecognizedTemperatureMetadataIsNotShownAsCelsius() {
        val words=ShortArray(256*392)
        assertNull(ThermalDecoder.reportedMaximum(words,256,392))
        words[388*256]=65535.toShort(); words[388*256+1]=61166.toShort()
        words[388*256+3]=1001.toShort()
        assertNull(ThermalDecoder.reportedMaximum(words,256,392))
        assertNull(ThermalDecoder.reportedMaximum(words,256,192))
    }
    @Test fun sixteenBitGradientRemainsMonotonicAcrossByteBoundaries() {
        val input=intArrayOf(254,255,256,257,510,511,512)
        val image=ThermalDecoder.decode(input.map { it.toShort() }.toShortArray(),7,1,"Y16 ",true)
        assertArrayEquals(input,image.values)
        val normalized=ThermalDecoder.normalize(image.values)
        assertTrue(normalized.toList().zipWithNext().all { (a,b) -> a<=b })
        assertEquals(0,normalized.first()); assertEquals(255,normalized.last())
    }
    @Test fun neutralYuy2UsesItsLuminanceRatherThanTreatingChromaAsTemperature() {
        val input=intArrayOf(0,64,128,255)
        val image=ThermalDecoder.decode(input.map { (0x8000 or it).toShort() }.toShortArray(),4,1,"YUY2",true)
        assertArrayEquals(input,image.values)
    }
    @Test fun hikRawCountsInYuy2DescriptorAreNotReducedToEightBits() {
        val input=intArrayOf(10000,10255,10256,10512)
        assertArrayEquals(input,ThermalDecoder.decode(input.map { it.toShort() }.toShortArray(),4,1,"YUY2",true).values)
    }
    @Test fun stackedHikStreamUsesProcessedPlaneAndSkipsTelemetryRows() {
        val words=ShortArray(4*392)
        for(i in 0 until 4*192) words[4*196+i]=(0x8000 or (i%256)).toShort()
        val image=ThermalDecoder.decode(words,4,392,"YUY2",true)
        assertEquals(192,image.height)
        assertEquals("Camera-processed grayscale",image.source)
        assertEquals(0,image.values[0]); assertEquals(255,image.values[255])
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedFramesAreRejected() {
        ThermalDecoder.decode(ShortArray(10),256,192,"Y16 ",true)
    }
    @Test fun annotatedPreviewCannotLeakIntoCleanSensorPlane() {
        val bytes=javaClass.getResourceAsStream("/hik-hand-frame.bin")!!.use { it.readBytes() }
        val words=ShortArray(bytes.size/2)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(words)
        val image=ThermalDecoder.decode(words,256,392,"YUY2",true)
        assertEquals("Clean 16-bit thermal image",image.source)
        assertArrayEquals(IntArray(256*192) { words[it].toInt() and 65535 },image.values)
        assertTrue(image.values.maxOrNull()!! > 255)
        assertNotEquals(words[196*256].toInt() and 255,image.values.first())
    }
}
