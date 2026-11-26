package io.github.cbird.ht203u

/** A frame gap indicates a pause, not necessarily a confirmed shutter calibration. */
class CameraFeedState {
    @Volatile private var lastFrameAt: Long? = null
    private var signature: Int? = null
    fun frameArrived(nowMs: Long, frameSignature: Int) {
        if (lastFrameAt == null || signature != frameSignature) lastFrameAt = nowMs
        signature = frameSignature
    }
    fun reset() { lastFrameAt = null; signature = null }
    fun isPaused(nowMs: Long): Boolean = lastFrameAt?.let { nowMs - it >= 500L } ?: false
}
