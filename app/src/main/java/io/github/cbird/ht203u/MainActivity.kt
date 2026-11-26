package io.github.cbird.ht203u

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAMERA = 1
        private const val TAG = "ht203u"
        private const val ACTION_USB_PERMISSION = "io.pyotyr.questthermal.USB_PERMISSION"
        private const val QUEST_USB_CAMERA = "horizonos.permission.USB_CAMERA"
    }

    private val traceBuf = ArrayDeque<String>()

    private fun dlog(m: String) {
        android.util.Log.i(TAG, m)
        synchronized(traceBuf) {
            traceBuf.addLast(m)
            while (traceBuf.size > 250) traceBuf.removeFirst()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private lateinit var thermalView: ImageView
    private lateinit var statusText: TextView
    private lateinit var tempText: TextView
    private lateinit var btnUnit: Button

    private var bitmap: Bitmap = Bitmap.createBitmap(Xtherm.WIDTH, Xtherm.HEIGHT, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(Xtherm.PIXELS)
    private val palette = Xtherm.ironPalette()
    private var frameU16 = ShortArray(256 * 520)

    private var usbConn: UsbDeviceConnection? = null
    private var bulk: BulkUvc? = null
    private var usbDevice: UsbDevice? = null
    private var modes: List<BulkUvc.FrameDesc> = emptyList()
    private var modeIdx = 0
    @Volatile private var curW = Xtherm.WIDTH
    @Volatile private var curH = Xtherm.FRAME_HEIGHT
    @Volatile private var radiometricSeen = false

    @Volatile private var fahrenheit = false
    @Volatile private var streaming = false
    @Volatile private var dumpRequested = false
    @Volatile private var rotation = 0 // quarter turns clockwise
    private var rotPixels = IntArray(0)
    private var vcClaimed = false

    // capture
    @Volatile private var photoRequested = false
    @Volatile private var snapRaw: ShortArray? = null
    @Volatile private var recorder: Recorder? = null
    private var recFile: java.io.File? = null
    private var recBitmap: Bitmap? = null
    private lateinit var btnRec: Button
    @Volatile private var framesReceived = 0L
    @Volatile private var lastFrameBytes = 0
    private var receiversRegistered = false

    private var tempTable: FloatArray? = null
    private var frameCount = 0L
    @Volatile private var lastFrameAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (streaming && framesReceived == 0L) {
                if (modeIdx + 1 < modes.size) {
                    modeIdx++
                    startMode("no frames in previous mode")
                } else {
                    statusText.text =
                        "No mode delivered frames (last buffer: $lastFrameBytes B) â€” tap Log and share it"
                }
            }
            if (streaming && lastFrameAt > 0L && System.currentTimeMillis()-lastFrameAt > 1800) {
                status("Waiting for camera…") // Retain the last image during shutter/USB pauses.
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        thermalView = findViewById(R.id.thermalView)
        statusText = findViewById(R.id.statusText)
        tempText = findViewById(R.id.tempText)
        btnUnit = findViewById(R.id.btnUnit)

        findViewById<Button>(R.id.btnHeadLock).apply {
            text = if (ThermalViewMode.headLocked) "Head lock: ON" else "Head lock: OFF"
            setOnClickListener {
                ThermalViewMode.headLocked = !ThermalViewMode.headLocked
                text = if (ThermalViewMode.headLocked) "Head lock: ON" else "Head lock: OFF"
            }
        }

        findViewById<Button>(R.id.btnCalibrate).setOnClickListener { xuProbe() }
        findViewById<Button>(R.id.btnRange).setOnClickListener {
            dumpRequested = true
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        rotation = getPreferences(MODE_PRIVATE).getInt("rotation", ViewerDefaults.CAMERA_ROTATION_QUARTER_TURNS)
        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            rotation = (rotation + 1) % 4
            getPreferences(MODE_PRIVATE).edit().putInt("rotation", rotation).apply()
        }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener {
            photoRequested = true
            status("capturingâ€¦")
        }
        btnRec = findViewById(R.id.btnRec)
        btnRec.setOnClickListener { toggleRecording() }
        btnUnit.setOnClickListener {
            fahrenheit = !fahrenheit
            btnUnit.setText(if (fahrenheit) R.string.unit_f else R.string.unit_c)
        }
        statusText.setOnClickListener { scanForDevice() }
        installViewerControls(thermalView, statusText, tempText)
    }

    private fun missingCameraPermissions(): Array<String> {
        val required = mutableListOf(Manifest.permission.CAMERA)
        // This permission exists on Horizon OS, but not on ordinary Android.
        try {
            packageManager.getPermissionInfo(QUEST_USB_CAMERA, 0)
            required.add(QUEST_USB_CAMERA)
        } catch (_: PackageManager.NameNotFoundException) { }
        return required.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun hasCameraPermission(): Boolean = missingCameraPermissions().isEmpty()

    override fun onStart() {
        super.onStart()
        registerReceivers()
        if (!hasCameraPermission()) {
            status("Camera permission needed (Android requires it for USB cameras)â€¦")
            requestPermissions(missingCameraPermissions(), REQ_CAMERA)
        } else {
            scanForDevice()
        }
        mainHandler.postDelayed(watchdog, 3500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (hasCameraPermission()) {
                scanForDevice()
            } else {
                status("Camera permission denied â€” USB cameras can't work without it. Grant it in App info â†’ Permissions.")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(watchdog)
        recorder?.let { r -> recorder = null; Thread { r.stop() }.start() }
        runOnUiThread { btnRec.setText(R.string.rec) }
        stopAll()
        if (receiversRegistered) {
            unregisterReceiver(usbReceiver)
            receiversRegistered = false
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        receiversRegistered = true
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    dlog("usb permission result: granted=$granted dev=${device?.productName}")
                    if (granted && device != null) {
                        openAndStart(device)
                    } else {
                        status("USB permission denied â€” tap this text to retry, or replug the camera")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    dlog("device attached")
                    if (hasCameraPermission()) scanForDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    dlog("device detached")
                    stopAll()
                    status(getString(R.string.waiting))
                    runOnUiThread { tempText.text = "" }
                }
            }
        }
    }

    private fun hasVideoInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    private fun scanForDevice() {
        if (!hasCameraPermission()) {
            requestPermissions(missingCameraPermissions(), REQ_CAMERA)
            return
        }
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == 0x2bdf && it.productId == 0x0102
        } ?: usbManager.deviceList.values.firstOrNull { hasVideoInterface(it) }
        if (device == null) {
            status("No USB camera found. Connect the thermal camera to the Quest USB-C port, then tap here.")
            return
        }
        dlog("found device vid=%04x pid=%04x name=%s".format(
            device.vendorId, device.productId, device.productName))
        if (usbManager.hasPermission(device)) {
            openAndStart(device)
        } else {
            status("Requesting USB permissionâ€¦")
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            try {
                usbManager.requestPermission(device, pi)
            } catch (e: SecurityException) {
                dlog("USB access denied: ${e.message}")
                status("USB camera access denied. Check Quest app permissions, then tap here.")
            }
        }
    }

    private fun openAndStart(device: UsbDevice) {
        stopAll()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            status("openDevice failed â€” replug and retry")
            dlog("openDevice returned null")
            return
        }
        usbDevice = device
        usbConn = conn
        conn.rawDescriptors?.let { dlog(UsbDesc.summarize(it)) }

        val uvc = BulkUvc(device, conn, ::onBulkFrame, ::dlog)
        bulk = uvc
        val all = uvc.parseDescriptors()
        if (all.isEmpty()) {
            status("No uncompressed frame descriptors found â€” tap Log")
            return
        }
        // Preference: stacked 256x392 first (image+thermal), then the rest
        val prio = mapOf(392 to 0, 196 to 1, 400 to 2, 200 to 3, 192 to 4)
        modes = all.sortedBy { (if (it.width == 256) 0 else 10) + (prio[it.height] ?: 9) }
        dlog("mode order: " + modes.joinToString { "${it.width}x${it.height}" })
        modeIdx = 0
        radiometricSeen = false
        streaming = true
        startMode("initial")
    }

    private fun startMode(reason: String) {
        val uvc = bulk ?: return
        val fd = modes.getOrNull(modeIdx) ?: return
        dlog("startMode ${fd.width}x${fd.height} ($reason)")
        status("Trying mode ${fd.width}x${fd.height}â€¦")
        curW = fd.width
        curH = fd.height
        framesReceived = 0
        frameCount = 0
        tempTable = null
        if (!uvc.start(fd)) {
            status("Mode ${fd.width}x${fd.height} failed to start â€” tap Log")
        }
    }

    /** Read-only walk of the HIKMICRO vendor extension unit (id 10, VC interface 0). */
    private fun xuProbe() {
        val conn = usbConn ?: run { status("No device connected"); return }
        val device = usbDevice ?: return
        status("Probing XU controlsâ€¦")
        Thread {
            // class requests to the VC interface require it to be claimed
            if (!vcClaimed) {
                for (k in 0 until device.interfaceCount) {
                    val itf = device.getInterface(k)
                    if (itf.interfaceClass == 14 && itf.interfaceSubclass == 1) {
                        vcClaimed = conn.claimInterface(itf, true)
                        dlog("claim VC interface #${itf.id} -> $vcClaimed")
                        break
                    }
                }
            }
            dlog("XU probe: unit=10 vcIf=0")
            fun rd(req: Int, sel: Int, len: Int): Pair<Int, ByteArray> {
                val buf = ByteArray(len)
                val r = conn.controlTransfer(0xA1, req, sel shl 8, (10 shl 8), buf, len, 300)
                return r to buf
            }
            for (sel in 1..15) {
                val (ri, info) = rd(0x86, sel, 1)
                val (rl, lenBuf) = rd(0x85, sel, 2)
                val len = if (rl >= 2)
                    (lenBuf[0].toInt() and 0xFF) or ((lenBuf[1].toInt() and 0xFF) shl 8) else 0
                var curHex = ""; var minHex = ""; var maxHex = ""
                if (len in 1..64) {
                    val (rc, cur) = rd(0x81, sel, len)
                    curHex = if (rc > 0) cur.take(rc).joinToString("") { "%02x".format(it) } else "err$rc"
                    val (rmin, mn) = rd(0x82, sel, len)
                    if (rmin > 0) minHex = mn.take(rmin).joinToString("") { "%02x".format(it) }
                    val (rmax, mx) = rd(0x83, sel, len)
                    if (rmax > 0) maxHex = mx.take(rmax).joinToString("") { "%02x".format(it) }
                }
                dlog("XU sel=%d info=%s len=%d cur=%s min=%s max=%s".format(
                    sel, if (ri > 0) "%02x".format(info[0]) else "err$ri", len, curHex, minHex, maxHex))
            }
            status("XU probe done â€” tap Log and share")
        }.start()
    }

    private fun stopAll() {
        ViewerState.cameraFeed.reset()
        streaming = false
        bulk?.close()
        bulk = null
        usbConn?.close()
        usbConn = null
        usbDevice = null
    }

    private fun onBulkFrame(frame: ByteBuffer) {
        try {
            processFrame(frame)
        } catch (t: Throwable) {
            dlog("frame error: $t")
        }
    }

    private fun ensurePixels(n: Int) {
        if (pixels.size < n) pixels = IntArray(n)
    }

    /** Fraction of sampled pixels at [off] that fall inside [lo..hi] (Â±256). */
    private fun inRange(off: Int, lo: Int, hi: Int): Double {
        var hit = 0
        var total = 0
        var i = off
        val end = off + Xtherm.PIXELS
        while (i < end) {
            val v = frameU16[i].toInt() and 0xFFFF
            if (v >= lo - 256 && v <= hi + 256) hit++
            total++
            i += 97
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private fun processFrame(frame: ByteBuffer) {
        lastFrameBytes = frame.remaining()
        if (frameCount == 0L) {
            dlog("first frame: ${frame.remaining()} B in mode ${curW}x${curH}")
        }
        frameCount++

        val nU16 = frame.remaining() / 2
        if (nU16 != curW * curH) {
            if (frameCount % 100L == 1L) dlog("Incomplete frame: $nU16 words, expected ${curW*curH}")
            return
        }
        lastFrameAt = System.currentTimeMillis()
        framesReceived++
        if (frameU16.size < nU16) frameU16 = ShortArray(nU16)
        frame.order(ByteOrder.LITTLE_ENDIAN)
        frame.asShortBuffer().get(frameU16, 0, nU16)
        if (usbDevice?.vendorId != 0x2bdf) {
            ViewerState.cameraFeed.frameArrived(android.os.SystemClock.elapsedRealtime(), frameU16.contentHashCode())
        }

        val w = curW
        val h = if (nU16 % w == 0) nU16 / w else curH

        if (usbDevice?.vendorId == 0x2bdf) {
            val fd = modes.getOrNull(modeIdx)
            val decoded = ThermalDecoder.decode(frameU16.copyOf(nU16), w, h, fd?.fourCC ?: "", true)
            if (frameCount == 1L) {
                dlog("HIK decode: ${fd?.fourCC} ${w}x$h -> ${decoded.source}")
                try { java.io.File(filesDir,"camera-format.txt").writeText("${fd?.fourCC} ${w}x$h ${decoded.source}") } catch (_: Exception) { }
            }
            renderDecoded(decoded)
            return
        }

        // 1) Xtherm-style meta blocks (256-wide layouts only).
        // The meta's raw min/max tells us which block holds the matching raw
        // thermal data â€” HIKMICRO puts a YUY2 display image in one block and
        // the radiometric array in another.
        if (w == 256) {
            var k = 1
            while (k * 196 <= h) {
                val base = (k - 1) * 196
                val meta = Xtherm.parseMeta(frameU16, w * (base + Xtherm.HEIGHT))
                if (meta != null) {
                    val cands = ArrayList<Int>(3)
                    cands.add(w * base)                                            // same block
                    if (w * (base + 196) + Xtherm.PIXELS <= nU16) cands.add(w * (base + 196)) // next block
                    if (base >= 196) cands.add(w * (base - 196))                   // previous block
                    val best = cands.maxByOrNull { inRange(it, meta.minRaw, meta.maxRaw) } ?: (w * base)
                    if (!radiometricSeen) {
                        radiometricSeen = true
                        dlog("xtherm meta in block $k: minRaw=${meta.minRaw} maxRaw=${meta.maxRaw} " +
                                "center=${meta.centerRaw} shutter=%.1f fpa=%.1f emiss=%.2f".format(
                                    meta.tempShutter, meta.tempFpa, meta.emissivity))
                        dlog("image block scores: " + cands.joinToString {
                            "row ${it / w}: %.2f".format(inRange(it, meta.minRaw, meta.maxRaw))
                        } + " -> using row ${best / w}")
                    }
                    if (frameCount % 300L == 2L) logFrameStats(w, h)
                    renderXtherm(meta, best)
                    return
                }
                k++
            }
        }

        // 2) HIKMICRO stacked layout: one block is the rendered YUY2 image
        // (chroma byte pinned to 0x80), the other is raw thermal counts.
        if (w == 256 && h >= 384) {
            fun chromaFrac(rowStart: Int): Double {
                var hit = 0; var total = 0
                var i = w * rowStart
                val end = i + Xtherm.PIXELS
                while (i < end) {
                    if (((frameU16[i].toInt() shr 8) and 0xFF) == 0x80) hit++
                    total++; i += 101
                }
                return hit.toDouble() / total
            }
            val f0 = chromaFrac(0)
            val f1 = chromaFrac(196)
            val rawRow = when {
                f0 < 0.2 && f1 > 0.8 -> 0
                f1 < 0.2 && f0 > 0.8 -> 196
                else -> -1
            }
            if (rawRow >= 0) {
                if (!radiometricSeen) {
                    radiometricSeen = true
                    dlog("HIK layout: raw block at row $rawRow (chroma frac row0=%.2f row196=%.2f)".format(f0, f1))
                    dumpMetaRows(w, h)
                }
                if (dumpRequested) {
                    dumpRequested = false
                    logFrameStats(w, h)
                    dumpMetaRows(w, h)
                    status("meta rows dumped â€” tap Log")
                }
                renderHikRaw(w * rawRow)
                return
            }
        }

        // 3) Stacked frame: bottom half raw Kelvin*64 (TC001 style)
        if (h >= 2 * 190) {
            val rows = h / 2
            val bottom = w * rows
            val count = w * minOf(rows, h - rows)
            var mn = 65535; var mx = 0
            for (i in bottom until bottom + count) {
                val v = frameU16[i].toInt() and 0xFFFF
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mn in 14000..50000 && mx in 14000..50000 && mx > mn + 16) {
                if (!radiometricSeen) {
                    radiometricSeen = true; dlog("K/64 radiometric in bottom half (min=$mn max=$mx)")
                }
                renderK64(bottom, mn, mx, w, minOf(rows, h - rows))
                return
            }
        }

        // 3) unknown â€” luma display + periodic stats
        if (frameCount % 150L == 1L) logFrameStats(w, h)
        val rows = minOf(h, 192)
        ensurePixels(w * rows)
        for (i in 0 until w * rows) {
            val y = frameU16[i].toInt() and 0xFF
            pixels[i] = y
        }
        applyColorMap(w * rows)
        show("video ${w}x$h Â· frame $framesReceived Â· no thermal data recognized yet", "", w, rows)
    }

    private fun renderDecoded(image: ThermalDecoder.Image) {
        // Ignore changing telemetry when testing whether the thermal image is frozen.
        ViewerState.cameraFeed.frameArrived(android.os.SystemClock.elapsedRealtime(), image.values.contentHashCode())
        val w=image.width; val h=image.height
        val normalized=ThermalDecoder.normalize(image.values)
        ensurePixels(normalized.size)
        normalized.copyInto(pixels)
        applyColorMap(normalized.size)
        val minI=image.values.indices.minBy { image.values[it] }
        val maxI=image.values.indices.maxBy { image.values[it] }
        drawMarker(minI%w,minI/w,w,h,0xFF40A0FF.toInt())
        drawMarker(maxI%w,maxI/w,w,h,0xFFFF4040.toInt())
        drawMarker(w/2,h/2,w,h,0xFFFFFFFF.toInt())
        val settings=ViewerState.settings
        val labels=listOfNotNull(
            if(settings.showMean) "Mean relative heat: %.0f%%".format(normalized.average()/255*100) else null,
        ).joinToString("   ")
        show("${image.source} · ${w}x$h · frame $framesReceived",labels,w,h)
    }

    private fun renderXtherm(meta: Xtherm.Meta, imageOff: Int) {
        if (tempTable == null || frameCount % 8 == 0L) {
            tempTable = Xtherm.tempTable(meta, false)
        }
        val table = tempTable ?: return
        val minRaw = meta.minRaw
        val span = (meta.maxRaw - minRaw).coerceAtLeast(1)
        ensurePixels(Xtherm.PIXELS)
        for (i in 0 until Xtherm.PIXELS) {
            val v = frameU16[imageOff + i].toInt() and 0xFFFF
            pixels[i] = ((v - minRaw) * 255 / span).coerceIn(0, 255)
        }
        applyColorMap(Xtherm.PIXELS)
        drawMarker(meta.minX, meta.minY, Xtherm.WIDTH, Xtherm.HEIGHT, 0xFF40A0FF.toInt())
        drawMarker(meta.maxX, meta.maxY, Xtherm.WIDTH, Xtherm.HEIGHT, 0xFFFF4040.toInt())
        drawMarker(Xtherm.WIDTH / 2, Xtherm.HEIGHT / 2, Xtherm.WIDTH, Xtherm.HEIGHT, 0xFFFFFFFF.toInt())
        show(
            "radiometric (xtherm) Â· frame %d Â· shutter %.1fÂ°C".format(framesReceived, meta.tempShutter),
            formatReadings(table[meta.minRaw], table[meta.maxRaw], table[meta.centerRaw], meanTemperature(imageOff, Xtherm.PIXELS) { table[it] }),
            Xtherm.WIDTH, Xtherm.HEIGHT
        )
    }

    /**
     * HIK raw counts -> Â°C. Two-point fit (2026-07-26): ice-water slurry 3.9Â°C
     * @ raw 4405 and rolling boil 100Â°C @ raw 6979 -> 26.8 counts/Â°C.
     * Validated: palm 32.5Â°C, ambient 25.2Â°C. Offset may drift a few Â°C between
     * power cycles until FFC control is mapped.
     */
    private fun hikToC(v: Int): Float = ((v - 4405) * 0.0373349 + 3.9).toFloat()

    private fun renderHikRaw(offset: Int) {
        val w = Xtherm.WIDTH
        val rows = Xtherm.HEIGHT
        var mn = 65535; var mx = 0; var minI = 0; var maxI = 0
        for (i in 0 until w * rows) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            if (v < mn) { mn = v; minI = i }
            if (v > mx) { mx = v; maxI = i }
        }
        val span = (mx - mn).coerceAtLeast(1)
        ensurePixels(w * rows)
        for (i in 0 until w * rows) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            pixels[i] = ((v - mn) * 255 / span).coerceIn(0, 255)
        }
        val center = frameU16[offset + (rows / 2) * w + w / 2].toInt() and 0xFFFF
        if (photoRequested) {
            snapRaw = frameU16.copyOfRange(offset, offset + w * rows)
        }
        applyColorMap(w * rows)
        drawMarker(minI % w, minI / w, w, rows, 0xFF40A0FF.toInt())
        drawMarker(maxI % w, maxI / w, w, rows, 0xFFFF4040.toInt())
        drawMarker(w / 2, rows / 2, w, rows, 0xFFFFFFFF.toInt())
        show(
            "HIK thermal Â· estimated temperatures Â· frame $framesReceived Â· raw $center",
            "Approx. " + formatReadings(hikToC(mn), hikToC(mx), hikToC(center), meanTemperature(offset, w * rows, ::hikToC)),
            w, rows
        )
    }

    private fun dumpMetaRows(w: Int, h: Int) {
        for (row in intArrayOf(192, 193, 194, 195, 388, 389, 390, 391)) {
            if (row >= h) continue
            val off = row * w
            if (off + 32 > frameU16.size) continue
            val hex = (0 until 32).joinToString(" ") {
                "%04x".format(frameU16[off + it].toInt() and 0xFFFF)
            }
            dlog("row $row: $hex")
        }
    }

    private fun renderK64(offset: Int, mn: Int, mx: Int, w: Int, rows: Int) {
        val span = (mx - mn).coerceAtLeast(1)
        val n = w * rows
        var minI = 0; var maxI = 0
        ensurePixels(n)
        for (i in 0 until n) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            if (v == mn) minI = i
            if (v == mx) maxI = i
            pixels[i] = ((v - mn) * 255 / span).coerceIn(0, 255)
        }
        val center = frameU16[offset + (rows / 2) * w + w / 2].toInt() and 0xFFFF
        applyColorMap(w * rows)
        drawMarker(minI % w, minI / w, w, rows, 0xFF40A0FF.toInt())
        drawMarker(maxI % w, maxI / w, w, rows, 0xFFFF4040.toInt())
        drawMarker(w / 2, rows / 2, w, rows, 0xFFFFFFFF.toInt())
        fun k64(v: Int) = (v / 64.0 - 273.15).toFloat()
        show(
            "radiometric (K/64) Â· frame $framesReceived",
            formatReadings(k64(mn), k64(mx), k64(center), meanTemperature(offset, n, ::k64)),
            w, rows
        )
    }

    private fun logFrameStats(w: Int, h: Int) {
        fun stats(rowStart: Int, rows: Int): String {
            var mn = 65535; var mx = 0; var sum = 0L
            val from = rowStart * w
            val to = (rowStart + rows) * w
            for (i in from until to) {
                val v = frameU16[i].toInt() and 0xFFFF
                if (v < mn) mn = v
                if (v > mx) mx = v
                sum += v
            }
            return "rows $rowStart..${rowStart + rows - 1}: min=$mn max=$mx mean=${sum / (to - from)}"
        }
        dlog("frame ${w}x$h stats: " + stats(0, minOf(192, h)) +
                (if (h >= 384) " | " + stats(h / 2, minOf(192, h - h / 2)) else ""))
    }

    private fun show(statusMsg: String, temps: String, w: Int, h: Int) {
        // apply rotation on the worker thread
        val rot = rotation and 3
        val dw: Int; val dh: Int; val src: IntArray
        if (rot == 0) {
            dw = w; dh = h; src = pixels
        } else {
            dw = if (rot == 2) w else h
            dh = if (rot == 2) h else w
            if (rotPixels.size < w * h) rotPixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = pixels[y * w + x]
                    when (rot) {
                        1 -> rotPixels[x * dw + (h - 1 - y)] = v           // 90 CW
                        2 -> rotPixels[(h - 1 - y) * dw + (w - 1 - x)] = v // 180
                        else -> rotPixels[(w - 1 - x) * dw + y] = v        // 270
                    }
                }
            }
            src = rotPixels
        }

        // recording: feed the rotated frame to the encoder (worker thread)
        recorder?.let { rec ->
            if (rec.running) {
                var rb = recBitmap
                if (rb == null || rb.width != dw || rb.height != dh) {
                    rb = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                    recBitmap = rb
                }
                rb.setPixels(src, 0, dw, 0, 0, dw, dh)
                rec.encodeFrame(rb)
            }
        }

        // photo: save a copy of exactly what is displayed + the raw counts
        if (photoRequested) {
            photoRequested = false
            val copy = src.copyOf(dw * dh)
            val raw = snapRaw
            snapRaw = null
            Thread { savePhoto(copy, dw, dh, raw) }.start()
        }

        val displayPixels = src.copyOf(dw * dh)
        runOnUiThread {
            if (bitmap.width != dw || bitmap.height != dh) {
                bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
            }
            bitmap.setPixels(displayPixels, 0, dw, 0, 0, dw, dh)
            thermalView.setImageBitmap(bitmap)
            thermalView.invalidate()
            tempText.text = temps
            statusText.text = statusMsg
            val settings = ViewerState.settings
            tempText.visibility = if (settings.showReadings) android.view.View.VISIBLE else android.view.View.GONE
            statusText.visibility = if (settings.diagnostics) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun fmt(celsius: Float): String =
        if (fahrenheit) "%.1fÂ°F".format(celsius * 9f / 5f + 32f)
        else "%.1fÂ°C".format(celsius)

    private fun applyColorMap(count: Int) {
        val settings = ViewerState.settings
        val histogram = IntArray(256)
        for (i in 0 until count) histogram[pixels[i]]++
        val (cold, hot) = ViewerState.adaptiveThresholds(histogram, settings.coldPercent, settings.hotPercent)
        val contrast = ViewerState.contrastLookup(histogram, settings.histogramContrast)
        for (i in 0 until count) {
            val raw = pixels[i]
            pixels[i] = ViewerState.color(contrast[raw], settings, palette, cold, hot, raw)
        }
    }

    private fun meanTemperature(offset: Int, count: Int, convert: (Int) -> Float): Float {
        if (!ViewerState.settings.showMean) return 0f
        var total = 0.0
        for (i in 0 until count) total += convert(frameU16[offset + i].toInt() and 0xFFFF)
        return (total / count).toFloat()
    }

    private fun formatReadings(min: Float, max: Float, center: Float, mean: Float): String {
        val settings = ViewerState.settings
        return listOfNotNull(
            if (settings.showMin) "Min ${fmt(min)}" else null,
            if (settings.showMax) "Max ${fmt(max)}" else null,
            if (settings.showCenter) "Spot ${fmt(center)}" else null,
            if (settings.showMean) "Mean ${fmt(mean)}" else null,
        ).joinToString("   ")
    }

    private fun drawMarker(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val settings = ViewerState.settings
        if (color == 0xFF40A0FF.toInt() && !settings.showMin) return
        if (color == 0xFFFF4040.toInt() && !settings.showMax) return
        if (color == 0xFFFFFFFF.toInt() && !settings.showCenter) return
        for (d in -3..3) {
            val px = (x + d).coerceIn(0, w - 1)
            val py = (y + d).coerceIn(0, h - 1)
            pixels[y.coerceIn(0, h - 1) * w + px] = color
            pixels[py * w + x.coerceIn(0, w - 1)] = color
        }
    }

    private fun status(msg: String) {
        runOnUiThread { statusText.text = msg; statusText.visibility = android.view.View.VISIBLE }
    }

    private fun timestampName(): String =
        "HT203U_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    private fun toggleRecording() {
        val rec = recorder
        if (rec == null) {
            // record at 4x the current rotated display size
            val dw = if (rotation % 2 == 1) Xtherm.HEIGHT else Xtherm.WIDTH
            val dh = if (rotation % 2 == 1) Xtherm.WIDTH else Xtherm.HEIGHT
            try {
                val f = java.io.File(cacheDir, timestampName() + ".mp4")
                recFile = f
                recorder = Recorder(dw * 4, dh * 4, 25, f, ::dlog)
                runOnUiThread { btnRec.setText(R.string.rec_stop) }
                status("recordingâ€¦")
            } catch (t: Throwable) {
                dlog("recorder start failed: $t")
                status("recording failed to start: ${t.message}")
            }
        } else {
            recorder = null
            runOnUiThread { btnRec.setText(R.string.rec) }
            val src = recFile ?: return
            Thread {
                val ok = rec.stop()
                if (ok && src.length() > 0) {
                    val uri = insertMedia(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        src.name, "video/mp4", "Movies/HT203U")
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        }
                        status("video saved to Movies/HT203U/${src.name}")
                    } else status("video save failed (MediaStore)")
                } else status("recording produced no data")
                src.delete()
            }.start()
        }
    }

    private fun insertMedia(
        collection: android.net.Uri, name: String, mime: String, relPath: String
    ): android.net.Uri? {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
        }
        return try {
            contentResolver.insert(collection, values)
        } catch (t: Throwable) {
            dlog("MediaStore insert failed: $t"); null
        }
    }

    private fun savePhoto(px: IntArray, w: Int, h: Int, raw: ShortArray?) {
        val name = timestampName()
        try {
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val big = Bitmap.createScaledBitmap(bmp, w * 4, h * 4, true)
            val uri = insertMedia(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "$name.png", "image/png", "Pictures/HT203U")
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    big.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            var rawMsg = ""
            if (raw != null && Build.VERSION.SDK_INT >= 29) {
                val ruri = insertMedia(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "$name.raw", "application/octet-stream", "Download/HT203U")
                if (ruri != null) {
                    contentResolver.openOutputStream(ruri)?.use { out ->
                        val bytes = ByteArray(raw.size * 2)
                        for (i in raw.indices) {
                            bytes[i * 2] = (raw[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((raw[i].toInt() shr 8) and 0xFF).toByte()
                        }
                        out.write(bytes)
                    }
                    rawMsg = " + raw counts to Download/HT203U"
                }
            }
            dlog("photo saved: $name.png$rawMsg")
            status("saved $name.png (Pictures/HT203U)$rawMsg")
        } catch (t: Throwable) {
            dlog("photo save failed: $t")
            status("photo save failed: ${t.message}")
        }
    }

    private fun showLog() {
        val sb = StringBuilder()
        sb.append("=== app trace ===\n")
        synchronized(traceBuf) { traceBuf.forEach { sb.append(it).append('\n') } }
        sb.append("\n=== logcat ===\n")
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400"))
            val keys = listOf("usb", "Usb", "USB", TAG, "AndroidRuntime", "bulkuvc")
            proc.inputStream.bufferedReader().readLines()
                .filter { line -> keys.any { line.contains(it) } }
                .takeLast(120)
                .forEach { sb.append(it).append('\n') }
        } catch (t: Throwable) {
            sb.append("logcat read failed: $t\n")
        }
        val text = sb.toString()
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle("Debug log")
                .setMessage(text)
                .setPositiveButton("Share") { _, _ ->
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(i, "Share log"))
                }
                .setNeutralButton("Copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ht203u log", text))
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
}
