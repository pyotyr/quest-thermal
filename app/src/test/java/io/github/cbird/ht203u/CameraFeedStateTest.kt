package io.github.cbird.ht203u

import org.junit.Assert.*
import org.junit.Test

class CameraFeedStateTest {
    @Test fun pauseAppearsAfterHalfSecondAndClearsOnFreshImage() {
        val feed=CameraFeedState()
        assertFalse(feed.isPaused(10000))
        feed.frameArrived(10000,1)
        assertFalse(feed.isPaused(10499))
        assertTrue(feed.isPaused(10500))
        feed.frameArrived(10501,2)
        assertFalse(feed.isPaused(10501))
        feed.reset()
        assertFalse(feed.isPaused(20000))
    }
    @Test fun RepeatedFrozenFramesDoNotHideThePause() {
        val feed=CameraFeedState()
        feed.frameArrived(1000,123)
        feed.frameArrived(1250,123)
        feed.frameArrived(1501,123)
        assertTrue(feed.isPaused(1501))
        feed.frameArrived(1502,124)
        assertFalse(feed.isPaused(1502))
    }
}
