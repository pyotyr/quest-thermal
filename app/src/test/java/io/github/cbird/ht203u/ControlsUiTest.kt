package io.github.cbird.ht203u

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ControlsUiTest {
    @Before fun reset() {
        ViewerState.cameraFeed.reset()
        ViewerState.settings=ViewerSettings(warp=emptyList(), horizontalFov=45f, verticalFov=34f, horizontalOffset=0f, verticalOffset=0f, curved=false)
        ViewerState.calibrating=false; ViewerState.adjusting=false; ViewerState.optionsVisible=false
        ViewerState.commands.clear()
    }
    private fun find(view:View,text:String):TextView? {
        if(view is TextView && view.text.toString()==text) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),text)?.let { return it }
        return null
    }
    @Test fun controlsOpenWithoutCameraAndDispatchCalibrationCommand() {
        val controller=Robolectric.buildActivity(ControlsActivity::class.java).setup()
        val activity=controller.get()
        val root=activity.window.decorView
        assertNotNull(find(root,"Drag / resize"))
        find(root,"Match 4 points")!!.performClick()
        assertEquals("calibrate",ViewerState.commands.poll())
        assertFalse(ViewerState.optionsVisible)
        controller.pause().stop().destroy()
    }
    private fun view():ThermalImageView = ThermalImageView(RuntimeEnvironment.getApplication()).apply {
        layout(0,0,880,720)
        setImageBitmap(Bitmap.createBitmap(256,192,Bitmap.Config.ARGB_8888))
    }
    private fun click(view:View,x:Float,y:Float) {
        for(action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(0,1,action,x*880,y*720,0)
            view.onTouchEvent(event); event.recycle()
        }
    }
    @Test fun markersFollowAlignmentAndToggleWithoutWaitingForAnotherImage() {
        val view=view()
        ViewerState.settings=ViewerSettings(warp=listOf(.5f,0f,.2f,0f,.4f,.1f,0f,0f,1f),showMin=true,showMax=true)
        view.setMarkers(listOf(ThermalMarker("Min",.2f,.3f),ThermalMarker("Max",.8f,.7f)))
        val projected=view.projectedMarkers()
        assertEquals(.3f,projected[0].second.first,.00001f)
        assertEquals(.22f,projected[0].second.second,.00001f)
        assertEquals(.6f,projected[1].second.first,.00001f)
        assertEquals(.38f,projected[1].second.second,.00001f)
        ViewerState.settings=ViewerState.settings.copy(showMin=false)
        assertEquals(listOf("Max"),view.projectedMarkers().map { it.first.kind })
    }
    @Test fun alignmentWaitsForFreshFramesAfterCameraPause() {
        val view=view()
        val now=android.os.SystemClock.elapsedRealtime()
        ViewerState.cameraFeed.frameArrived(now-1000,1)
        view.beginCalibration()
        assertFalse(ViewerState.calibrating)
        ViewerState.cameraFeed.frameArrived(now,2)
        view.beginCalibration()
        assertTrue(ViewerState.calibrating)
        view.cancelCalibration()
    }
    @Test fun completesFourPointCalibrationAndSavesFit() {
        val view=view(); view.beginCalibration()
        for(p in listOf(.42f to .42f,.58f to .42f,.58f to .58f,.42f to .58f)) click(view,p.first,p.second)
        assertTrue(ViewerState.calibrating)
        for(p in listOf(.35f to .35f,.65f to .35f,.65f to .65f,.35f to .65f)) click(view,p.first,p.second)
        assertFalse(ViewerState.calibrating)
        assertEquals(9,ViewerState.settings.warp.size)
    }
    @Test fun dragMovesOverlayAndTapCanReopenControls() {
        val view=view(); ViewerState.adjusting=true
        val events=listOf(MotionEvent.ACTION_DOWN to (.5f to .5f),MotionEvent.ACTION_MOVE to (.52f to .53f),MotionEvent.ACTION_UP to (.52f to .53f))
        for((action,p) in events) {
            val event=MotionEvent.obtain(0,1,action,p.first*880,p.second*720,0)
            view.onTouchEvent(event); event.recycle()
        }
        val center=AlignmentMath.map(ViewerState.settings.warp,.5f,.5f)
        assertEquals(.52f,center.first,.001f); assertEquals(.53f,center.second,.001f)
        ViewerState.adjusting=false
        click(view,.5f,.5f)
        assertTrue(ViewerState.optionsVisible)
    }
    @Test fun cornerResizeKeepsOppositeCornerFixed() {
        val view=view(); ViewerState.adjusting=true
        val sx=(kotlin.math.tan(Math.toRadians(22.5))/kotlin.math.tan(Math.toRadians(55.0))).toFloat()
        val sy=kotlin.math.tan(Math.toRadians(17.0)).toFloat()
        val left=.5f-sx/2; val top=.5f-sy/2
        val events=listOf(MotionEvent.ACTION_DOWN to (left to top),
            MotionEvent.ACTION_MOVE to (left-.03f to top-.02f),MotionEvent.ACTION_UP to (left-.03f to top-.02f))
        for((action,p) in events) {
            val event=MotionEvent.obtain(0,1,action,p.first*880,p.second*720,0)
            view.onTouchEvent(event); event.recycle()
        }
        val h=ViewerState.settings.warp
        val tl=AlignmentMath.map(h,0f,0f); val br=AlignmentMath.map(h,1f,1f)
        assertEquals(left-.03f,tl.first,.001f); assertEquals(top-.02f,tl.second,.001f)
        assertEquals(.5f+sx/2,br.first,.001f); assertEquals(.5f+sy/2,br.second,.001f)
    }
}
