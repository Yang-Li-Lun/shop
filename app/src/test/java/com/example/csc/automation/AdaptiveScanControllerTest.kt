package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveScanControllerTest {
    @Test
    fun acceleratesAroundActionsAndBacksOffOnStableFrames() {
        val controller = AdaptiveScanController()
        repeat(9) { controller.observeFrame(123L) }
        assertEquals(800L, controller.nextDelay(500L, 10_000L, false))
        controller.markNearThreshold(10_000L)
        assertEquals(250L, controller.nextDelay(500L, 10_100L, false))
        controller.markGesture(12_000L)
        assertEquals(250L, controller.nextDelay(500L, 12_100L, false))
        assertEquals(2_000L, controller.nextDelay(500L, 12_100L, true))
    }

    @Test
    fun periodicallyProcessesAnUnchangedFingerprint() {
        val controller = AdaptiveScanController()

        assertTrue(controller.observeFrame(123L))
        assertFalse(controller.observeFrame(123L))
        assertFalse(controller.observeFrame(123L))
        assertTrue(controller.observeFrame(123L))
    }

    @Test
    fun numberConfirmationForcesTheNextFrameThroughFingerprintGate() {
        val controller = AdaptiveScanController()
        assertTrue(controller.observeFrame(123L))
        assertFalse(controller.observeFrame(123L))

        controller.requestConfirmation(1_000L)

        assertTrue(controller.observeFrame(123L))
        assertEquals(250L, controller.nextDelay(500L, 1_100L, false))
    }
}
