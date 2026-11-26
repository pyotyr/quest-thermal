package io.github.cbird.ht203u

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/** H.264/MP4 recorder fed with bitmaps via the encoder's input surface. */
class Recorder(
    reqWidth: Int,
    reqHeight: Int,
    fps: Int,
    private val outFile: File,
    private val log: (String) -> Unit,
) {
    val width = reqWidth and 0x7FFFFFFE   // even dimensions required
    val height = reqHeight and 0x7FFFFFFE

    private val codec: MediaCodec
    private val surface: Surface
    private val muxer: MediaMuxer
    private var track = -1
    private var muxerStarted = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile var running = false
        private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        running = true
        log("recorder started ${width}x$height @ $fps")
    }

    @Synchronized
    fun encodeFrame(bmp: Bitmap) {
        if (!running) return
        try {
            val c = surface.lockCanvas(null)
            try {
                c.drawColor(Color.BLACK)
                c.drawBitmap(bmp, null, Rect(0, 0, width, height), paint)
            } finally {
                surface.unlockCanvasAndPost(c)
            }
            drain(false)
        } catch (t: Throwable) {
            log("rec frame err: $t")
        }
    }

    private fun drain(end: Boolean) {
        if (end) codec.signalEndOfInputStream()
        while (true) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, if (end) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    // when ending, keep trying briefly until EOS shows up
                    return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(track, buf, bufferInfo)
                    }
                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
            }
        }
    }

    @Synchronized
    fun stop(): Boolean {
        if (!running) return false
        running = false
        var ok = true
        try { drain(true) } catch (t: Throwable) { log("rec drain err: $t"); ok = false }
        try { codec.stop(); codec.release() } catch (t: Throwable) { ok = false }
        try { surface.release() } catch (_: Throwable) {}
        try {
            if (muxerStarted) { muxer.stop() }
            muxer.release()
        } catch (t: Throwable) { log("muxer err: $t"); ok = false }
        return ok && muxerStarted
    }
}
