package io.github.cbird.ht203u

import org.junit.Assert.*
import org.junit.Test

class AlignmentMathTest {
    private val corners=listOf(0f to 0f,1f to 0f,1f to 1f,0f to 1f)
    @Test fun fitsPerspectiveCornersAndInteriorPoint() {
        val reference=listOf(.7f,.1f,.1f,-.05f,.8f,.15f,.12f,.08f,1f)
        val target=corners.map { AlignmentMath.map(reference,it.first,it.second) }
        val fit=AlignmentMath.fit(corners,target)!!
        val expected=AlignmentMath.map(reference,.37f,.61f)
        val actual=AlignmentMath.map(fit,.37f,.61f)
        assertEquals(expected.first,actual.first,0.0001f)
        assertEquals(expected.second,actual.second,0.0001f)
    }
    @Test fun rejectsCollinearCorrespondencesRatherThanSavingAnUnusableFit() {
        assertNull(AlignmentMath.fit(listOf(0f to 0f,.3f to 0f,.6f to 0f,1f to 0f),corners))
    }
}
