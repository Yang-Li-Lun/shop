package com.example.csc.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProjectionRequestStateTest {
    @Test
    fun secondRequestIsBusyAndTimeoutCompletesOnlyMatchingRequest() {
        val tracker = MediaProjectionRequestTracker()
        val request = tracker.begin() as CaptureRequestState.Pending
        assertEquals(CaptureRequestState.Busy, tracker.begin())
        assertEquals(CaptureRequestState.Stopped, tracker.timeout(request.requestId + 1, request.projectionGeneration))
        assertEquals(CaptureRequestState.TimedOut, tracker.timeout(request.requestId, request.projectionGeneration))
        assertTrue(tracker.begin() is CaptureRequestState.Pending)
    }

    @Test
    fun restartInvalidatesOldGeneration() {
        val tracker = MediaProjectionRequestTracker()
        val old = tracker.begin() as CaptureRequestState.Pending
        tracker.restartProjection()
        assertEquals(CaptureRequestState.Stopped, tracker.complete(old.requestId, old.projectionGeneration))
    }
}
