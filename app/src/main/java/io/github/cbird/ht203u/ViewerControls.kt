package io.github.cbird.ht203u

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale

/** The image canvas and its controls are different spatial panels. */
fun Activity.installViewerControls(image: ImageView, status: TextView, readings: TextView) {
    ViewerPrefs.load(this)
    val buttons=mapOf("photo" to R.id.btnPhoto,"rec" to R.id.btnRec,"log" to R.id.btnLog,
        "probe" to R.id.btnCalibrate,"dump" to R.id.btnRange,"rotate" to R.id.btnRotate,"unit" to R.id.btnUnit)
        .mapValues { findViewById<Button>(it.value) }
    listOf(image,status,readings).forEach { (it.parent as? ViewGroup)?.removeView(it) }
    val root=FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
    root.addView(image,FrameLayout.LayoutParams(-1,-1))
    root.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    root.addView(readings,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { topMargin=64 })
    setContentView(root)
    val handler=Handler(Looper.getMainLooper())
    handler.post(object:Runnable {
        override fun run() {
            if(isDestroyed) return
            while(true) {
                when(val command=ViewerState.commands.poll() ?: break) {
                    "calibrate" -> (image as ThermalImageView).beginCalibration()
                    "cancel" -> (image as ThermalImageView).cancelCalibration()
                    else -> buttons[command]?.performClick()
                }
            }
            image.invalidate()
            handler.postDelayed(this,100)
        }
    })
}

class ControlsActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        ViewerPrefs.load(this)
        setContentView(buildControls())
    }

    private fun buildControls():View {
        fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
        fun save(s:ViewerSettings) = ViewerPrefs.save(this,s)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(6),dp(12),dp(6))
            setBackgroundColor(0xF018202B.toInt())
        }
        val title=Button(this).apply { text="Hide controls · tap the image to reopen"; setOnClickListener { ViewerState.optionsVisible=false } }
        root.addView(title)
        val scroll=ScrollView(this)
        val options=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(options); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun row():LinearLayout {
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            options.addView(HorizontalScrollView(this).apply { addView(line) })
            return line
        }
        fun button(line:LinearLayout,label:String,action:(Button)->Unit) {
            line.addView(Button(this).apply { text=label; setOnClickListener { action(this) } })
        }
        val checks=mutableListOf<CheckBox>()
        fun check(line:LinearLayout,label:String,initial:Boolean,change:(ViewerSettings,Boolean)->ViewerSettings) {
            val box=CheckBox(this).apply { text=label; setTextColor(Color.WHITE); isChecked=initial
                setOnCheckedChangeListener { _,on -> save(change(ViewerState.settings,on)) } }
            line.addView(box); checks.add(box)
        }
        val align=row()
        button(align,"Drag / resize") { ViewerState.adjusting=true; ViewerState.optionsVisible=false }
        button(align,"Match 4 points") { ViewerState.commands.add("calibrate"); ViewerState.optionsVisible=false }
        button(align,"Reset alignment") { save(ViewerState.settings.copy(warp=emptyList(),horizontalFov=45f,
            verticalFov=34f,horizontalOffset=0f,verticalOffset=0f,roll=0f)) }
        val view=row()
        button(view,if(ViewerState.settings.headLocked) "Head lock: ON" else "Head lock: OFF") {
            save(ViewerState.settings.copy(headLocked=!ViewerState.settings.headLocked))
            it.text=if(ViewerState.settings.headLocked) "Head lock: ON" else "Head lock: OFF"
        }
        button(view,if(ViewerState.settings.curved) "Curved" else "Flat") {
            save(ViewerState.settings.copy(curved=!ViewerState.settings.curved)); it.text=if(ViewerState.settings.curved) "Curved" else "Flat"
        }
        button(view,"Clean view") {
            checks.forEach { it.isChecked=false }; save(ViewerState.settings.copy(diagnostics=false))
            ViewerState.optionsVisible=false; ViewerState.adjusting=false
        }
        val markers=row(); val s=ViewerState.settings
        check(markers,"Min",s.showMin) { v,on -> v.copy(showMin=on) }
        check(markers,"Max",s.showMax) { v,on -> v.copy(showMax=on) }
        check(markers,"Spot",s.showCenter) { v,on -> v.copy(showCenter=on) }
        check(markers,"Mean",s.showMean) { v,on -> v.copy(showMean=on) }
        check(markers,"Readings",s.showReadings) { v,on -> v.copy(showReadings=on) }
        val colors=row()
        button(colors,ViewerState.colorMapNames[s.colorMap]) {
            val next=(ViewerState.settings.colorMap+1)%ViewerState.colorMapNames.size
            save(ViewerState.settings.copy(colorMap=next)); it.text=ViewerState.colorMapNames[next]
        }
        button(colors,if(s.histogramContrast) "Histogram" else "Linear") {
            save(ViewerState.settings.copy(histogramContrast=!ViewerState.settings.histogramContrast))
            it.text=if(ViewerState.settings.histogramContrast) "Histogram" else "Linear"
        }
        fun slider(label:String,min:Float,max:Float,step:Float,unit:String,value:Float,
            change:(ViewerSettings,Float)->ViewerSettings) {
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            val text=TextView(this).apply { setTextColor(Color.WHITE); textSize=16f }
            fun update(v:Float) { text.text=String.format(Locale.US,"%s: %.1f%s",label,v,unit) }
            update(value); line.addView(text,LinearLayout.LayoutParams(dp(185),-2))
            line.addView(SeekBar(this).apply {
                this.max=((max-min)/step).toInt(); progress=((value-min)/step).toInt()
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean) {
                        val n=min+p*step; update(n); if(user) save(change(ViewerState.settings,n))
                    }
                    override fun onStartTrackingTouch(bar:SeekBar) { }
                    override fun onStopTrackingTouch(bar:SeekBar) { }
                })
            },LinearLayout.LayoutParams(0,dp(48),1f)); options.addView(line)
        }
        slider("Overall opacity",0f,100f,1f,"%",s.opacity*100) { v,n -> v.copy(opacity=n/100) }
        slider("Grayscale opacity",0f,100f,1f,"%",s.grayscaleOpacity*100) { v,n -> v.copy(grayscaleOpacity=n/100) }
        slider("Red opacity",0f,100f,1f,"%",s.redOpacity*100) { v,n -> v.copy(redOpacity=n/100) }
        slider("Blue opacity",0f,100f,1f,"%",s.blueOpacity*100) { v,n -> v.copy(blueOpacity=n/100) }
        slider("Coldest blue",0f,25f,1f,"%",s.coldPercent) { v,n -> v.copy(coldPercent=n) }
        slider("Hottest red",0f,25f,1f,"%",s.hotPercent) { v,n -> v.copy(hotPercent=n) }
        val advanced=row()
        button(advanced,"Rotate camera") { ViewerState.commands.add("rotate") }
        button(advanced,"Photo") { ViewerState.commands.add("photo") }
        button(advanced,"Record / stop") { ViewerState.commands.add("rec") }
        check(advanced,"Diagnostics",s.diagnostics) { v,on -> v.copy(diagnostics=on) }
        button(advanced,"Log") { ViewerState.commands.add("log") }
        return root
    }
}
