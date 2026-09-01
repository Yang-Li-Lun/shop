package com.example.csc.automation

/** Adjusts scan cadence from screen stability and confidence without changing user settings. */
class AdaptiveScanController {
    private var lastFingerprint: Long? = null
    private var stableFrames = 0
    private var nearThresholdUntilMs = 0L
    private var burstUntilMs = 0L
    private var unchangedFramesSinceRecognition = 0

    @Synchronized
    fun observeFrame(fingerprint: Long): Boolean {
        val changed = lastFingerprint != fingerprint
        stableFrames = if (!changed) stableFrames + 1 else 0
        lastFingerprint = fingerprint
        unchangedFramesSinceRecognition = if (changed) {
            0
        } else {
            unchangedFramesSinceRecognition + 1
        }
        if (changed || unchangedFramesSinceRecognition >= FORCE_RECOGNITION_AFTER_UNCHANGED_FRAMES) {
            unchangedFramesSinceRecognition = 0
            return true
        }
        return false
    }

    @Synchronized
    fun markNearThreshold(nowMs: Long) {
        nearThresholdUntilMs = nowMs + 1_500L
    }

    @Synchronized
    fun markGesture(nowMs: Long) {
        burstUntilMs = nowMs + 1_500L
        stableFrames = 0
        lastFingerprint = null
        unchangedFramesSinceRecognition = 0
    }

    @Synchronized
    fun requestConfirmation(nowMs: Long) {
        lastFingerprint = null
        nearThresholdUntilMs = nowMs + 1_000L
    }

    @Synchronized
    fun reset() {
        lastFingerprint = null
        stableFrames = 0
        nearThresholdUntilMs = 0L
        burstUntilMs = 0L
        unchangedFramesSinceRecognition = 0
    }

    @Synchronized
    fun nextDelay(baseMs: Long, nowMs: Long, ownAppForeground: Boolean): Long = when {
        ownAppForeground -> maxOf(baseMs, 2_000L)
        nowMs < burstUntilMs -> 250L
        nowMs < nearThresholdUntilMs -> 250L
        // Keep idle backoff modest: a target can appear just after a stable frame,
        // so 1.5 seconds made number and text detection feel unresponsive.
        stableFrames >= 8 -> 800L
        stableFrames >= 4 -> 600L
        else -> minOf(baseMs, 500L)
    }

    private companion object {
        const val FORCE_RECOGNITION_AFTER_UNCHANGED_FRAMES = 3
    }
}
