package io.github.cbird.ht203u

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.*

/** The image occupies an adjustable region of a fixed, transparent calibration canvas. */
class ThermalImageView(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitCorners = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
    private val sourcePoints = mutableListOf<Pair<Float, Float>>()
    private val targetPoints = mutableListOf<Pair<Float, Float>>()
    private var stage = 0
    private var frozen: Bitmap? = null
    private var gestureStart = 0f to 0f
    private var gestureCorners = emptyList<Pair<Float, Float>>()
    private var corner = -2
    private var moved = false
    private var message = ""
    private var messageUntil = 0L

    fun beginCalibration() {
        if (ViewerState.cameraFeed.isPaused(android.os.SystemClock.elapsedRealtime())) {
            tell("Wait for live camera frames before aligning"); return
        }
        val current = (drawable as? BitmapDrawable)?.bitmap ?: run { tell("Connect the camera first"); return }
        frozen = current.copy(Bitmap.Config.ARGB_8888, false)
        sourcePoints.clear(); targetPoints.clear(); stage = 1
        ViewerState.calibrating = true; ViewerState.adjusting = false; ViewerState.optionsVisible = false
        invalidate()
    }

    fun cancelCalibration() {
        stage = 0; frozen?.recycle(); frozen = null
        ViewerState.calibrating = false
        invalidate()
    }

    private fun tell(text: String) { message = text; messageUntil = System.currentTimeMillis() + 4500; invalidate() }

    private fun transform(): List<Float> {
        val s = ViewerState.settings
        if (s.warp.size == 9) return s.warp
        fun tangent(a: Float) = tan(Math.toRadians(a.toDouble())).toFloat()
        val sx = if(s.curved) s.horizontalFov/110f else tangent(s.horizontalFov / 2) / tangent(55f)
        val sy = tangent(s.verticalFov / 2) / tangent(45f)
        val cx = .5f + if(s.curved) s.horizontalOffset/110f else tangent(s.horizontalOffset) / (2 * tangent(55f))
        val cy = .5f - tangent(s.verticalOffset) / (2 * tangent(45f))
        val angle = Math.toRadians(s.roll.toDouble())
        val c = cos(angle).toFloat(); val sn = sin(angle).toFloat()
        return listOf(sx*c, -sy*sn, cx - sx*c/2 + sy*sn/2,
            sx*sn, sy*c, cy - sx*sn/2 - sy*c/2, 0f, 0f, 1f)
    }

    private fun inverseMap(x: Float, y: Float): Pair<Float, Float>? {
        val m = Matrix().apply { setValues(transform().toFloatArray()) }
        val inverse = Matrix()
        if (!m.invert(inverse)) return null
        val pt = floatArrayOf(x, y); inverse.mapPoints(pt)
        return pt[0] to pt[1]
    }

    override fun onDraw(canvas: Canvas) {
        val settings = ViewerState.settings
        val paused = stage == 0 && ViewerState.cameraFeed.isPaused(android.os.SystemClock.elapsedRealtime())
        val bitmap = frozen ?: (drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null && stage != 2) {
            val h = transform()
            val matrix = Matrix().apply { setValues(floatArrayOf(
                h[0]*width/bitmap.width, h[1]*width/bitmap.height, h[2]*width,
                h[3]*height/bitmap.width, h[4]*height/bitmap.height, h[5]*height,
                h[6]/bitmap.width, h[7]/bitmap.height, h[8])) }
            ink.alpha = if (stage == 1) 255 else (settings.opacity * 255 * if(paused) .2f else 1f).toInt()
            ink.isFilterBitmap = true
            canvas.drawBitmap(bitmap, matrix, ink)
            ink.alpha = 255
        }
        ink.color = Color.CYAN; ink.strokeWidth = 3f
        if (ViewerState.adjusting && stage == 0) {
            val corners = unitCorners.map { AlignmentMath.map(transform(), it.first, it.second) }
            ink.style = Paint.Style.STROKE
            val path = Path()
            corners.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.first*width,p.second*height) else path.lineTo(p.first*width,p.second*height) }
            path.close(); canvas.drawPath(path,ink)
            corners.forEach { canvas.drawCircle(it.first*width,it.second*height,22f,ink) }
            ink.style = Paint.Style.FILL
            drawLabel(canvas,"Drag image to move · drag a corner to resize · tap outside to finish")
        }
        if (stage != 0) {
            val count = if (stage == 1) sourcePoints.size else targetPoints.size
            drawLabel(canvas, if (stage == 1) "Hold head steady · select thermal corner ${count+1}/4"
                else "Select matching real-world corner ${count+1}/4 · same order")
            val points = if (stage == 1) sourcePoints.map { AlignmentMath.map(transform(), it.first,it.second) } else targetPoints
            ink.textSize = 28f; ink.color = Color.CYAN
            points.forEachIndexed { i,p ->
                canvas.drawCircle(p.first*width,p.second*height,10f,ink)
                canvas.drawText("${i+1}",p.first*width+15,p.second*height-10,ink)
            }
            // Cancellation remains available throughout the two-stage workflow.
            ink.color = Color.WHITE; ink.textSize = 25f
            canvas.drawText("Cancel",width-110f,height-25f,ink)
        }
        if (System.currentTimeMillis() < messageUntil) drawLabel(canvas,message)
        if (paused) {
            val text = "Camera calibrating or paused…"
            ink.textSize = 23f
            val boxWidth = ink.measureText(text) + 90f
            val left = (width-boxWidth)/2f; val top = height/2f-32f
            ink.color = 0xE6101820.toInt(); ink.style = Paint.Style.FILL
            canvas.drawRoundRect(left,top,left+boxWidth,top+64f,12f,12f,ink)
            ink.color = Color.WHITE
            canvas.drawText(text,left+61f,top+40f,ink)
            ink.style = Paint.Style.STROKE; ink.strokeWidth = 3f
            val phase = (android.os.SystemClock.elapsedRealtime()%1200L)*360f/1200f
            canvas.drawArc(left+20f,top+20f,left+44f,top+44f,phase,250f,false,ink)
            ink.style = Paint.Style.FILL
            postInvalidateDelayed(80)
        }
    }

    private fun drawLabel(canvas: Canvas,text:String) {
        ink.color = 0xCC101820.toInt(); canvas.drawRect(0f,0f,width.toFloat(),58f,ink)
        ink.color = Color.WHITE; ink.textSize = 24f
        canvas.drawText(text,12f,37f,ink)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / width; val y = event.y / height
        if (stage != 0) {
            if (event.action == MotionEvent.ACTION_UP) {
                if (x > .85f && y > .88f) { cancelCalibration(); return true }
                if (stage == 1) {
                    val source = inverseMap(x,y) ?: return true
                    if (source.first !in 0f..1f || source.second !in 0f..1f) { tell("Select a point inside the thermal image"); return true }
                    sourcePoints.add(source)
                    if (sourcePoints.size == 4) stage = 2
                } else {
                    targetPoints.add(x to y)
                    if (targetPoints.size == 4) {
                        val fit = AlignmentMath.fit(sourcePoints,targetPoints)
                        if (fit != null) { ViewerPrefs.save(context,ViewerState.settings.copy(warp=fit)); cancelCalibration(); tell("Alignment saved") }
                        else { targetPoints.clear(); tell("Points could not be fitted. Choose four well-spaced matching corners.") }
                    }
                }
                invalidate()
            }
            return true
        }
        if (!ViewerState.adjusting) {
            if (event.action == MotionEvent.ACTION_UP) performClick()
            return true
        }
        when(event.action) {
            MotionEvent.ACTION_DOWN -> {
                gestureStart=x to y; moved=false
                gestureCorners=unitCorners.map { AlignmentMath.map(transform(),it.first,it.second) }
                corner=gestureCorners.indices.minBy { hypot((gestureCorners[it].first-x)*width,(gestureCorners[it].second-y)*height) }
                val distance=hypot((gestureCorners[corner].first-x)*width,(gestureCorners[corner].second-y)*height)
                if(distance>55f) {
                    val source=inverseMap(x,y)
                    corner=if(source!=null && source.first in 0f..1f && source.second in 0f..1f) -1 else -2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if(corner == -2) return true
                val dx=x-gestureStart.first; val dy=y-gestureStart.second
                if(abs(dx)+abs(dy)>.004f) moved=true
                val destinations=if(corner<0) gestureCorners.map { p -> p.first+dx to p.second+dy }
                else {
                    val anchor=gestureCorners[(corner+2)%4]
                    val old=gestureCorners[corner]
                    if(abs(old.first-anchor.first)<.01f || abs(old.second-anchor.second)<.01f) return true
                    val sx=((old.first+dx-anchor.first)/(old.first-anchor.first)).coerceIn(.2f,4f)
                    val sy=((old.second+dy-anchor.second)/(old.second-anchor.second)).coerceIn(.2f,4f)
                    gestureCorners.map { p -> anchor.first+(p.first-anchor.first)*sx to anchor.second+(p.second-anchor.second)*sy }
                }
                val fit=AlignmentMath.fit(unitCorners,destinations)
                if(fit!=null) ViewerState.settings=ViewerState.settings.copy(warp=fit)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if(corner == -2 && !moved) { ViewerState.adjusting=false; ViewerState.optionsVisible=true }
                ViewerPrefs.save(context); invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        ViewerState.optionsVisible=!ViewerState.optionsVisible
        return true
    }
}
