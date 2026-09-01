package com.example.csc.capture

/** Prevents a granted Android 10 capture request from being reopened while its service starts. */
internal class MediaProjectionAuthorizationGate {
    private var state = State.IDLE

    fun tryBeginRequest(enabled: Boolean, accessibilityEnabled: Boolean, captureRunning: Boolean): Boolean {
        if (!enabled || !accessibilityEnabled || captureRunning || state != State.IDLE) return false
        state = State.REQUESTING
        return true
    }

    fun onAuthorizationResult(granted: Boolean) {
        state = if (granted) State.WAITING_FOR_SERVICE else State.IDLE
    }

    fun onCaptureRunningChanged(captureRunning: Boolean) {
        if (captureRunning) state = State.IDLE
    }

    fun onStartTimeout(): Boolean {
        if (state != State.WAITING_FOR_SERVICE) return false
        state = State.IDLE
        return true
    }

    fun reset() {
        state = State.IDLE
    }

    fun saveState(): String = state.name

    fun restoreState(savedState: String?) {
        state = savedState
            ?.let { value -> runCatching { State.valueOf(value) }.getOrNull() }
            ?: State.IDLE
    }

    private enum class State { IDLE, REQUESTING, WAITING_FOR_SERVICE }
}
