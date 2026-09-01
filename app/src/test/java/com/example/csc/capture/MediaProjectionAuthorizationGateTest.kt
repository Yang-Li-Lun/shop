package com.example.csc.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProjectionAuthorizationGateTest {
    @Test
    fun grantedRequestStaysBlockedUntilCaptureServiceReportsRunning() {
        val gate = MediaProjectionAuthorizationGate()

        assertTrue(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
        gate.onAuthorizationResult(granted = true)

        assertFalse(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
        gate.onCaptureRunningChanged(captureRunning = true)
        assertFalse(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = true))
    }

    @Test
    fun startTimeoutAllowsOnlyALaterExplicitRetry() {
        val gate = MediaProjectionAuthorizationGate()
        assertTrue(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
        gate.onAuthorizationResult(granted = true)

        assertTrue(gate.onStartTimeout())
        assertFalse(gate.onStartTimeout())
        assertTrue(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
    }

    @Test
    fun cancellationAndDisableLeaveNoPendingRequest() {
        val gate = MediaProjectionAuthorizationGate()
        assertTrue(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
        gate.onAuthorizationResult(granted = false)
        assertTrue(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
        gate.reset()
        assertTrue(gate.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
    }

    @Test
    fun savedRequestStatePreventsDuplicatePromptAfterActivityRecreation() {
        val original = MediaProjectionAuthorizationGate()
        assertTrue(original.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))

        val recreated = MediaProjectionAuthorizationGate()
        recreated.restoreState(original.saveState())

        assertFalse(recreated.tryBeginRequest(enabled = true, accessibilityEnabled = true, captureRunning = false))
    }
}
