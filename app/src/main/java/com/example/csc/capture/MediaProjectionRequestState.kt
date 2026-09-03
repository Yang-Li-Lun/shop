package com.example.csc.capture

sealed class CaptureRequestState {
    data class Pending(val requestId: Long, val projectionGeneration: Long) : CaptureRequestState()
    data object Busy : CaptureRequestState()
    data object Stopped : CaptureRequestState()
    data object TimedOut : CaptureRequestState()
    data object Completed : CaptureRequestState()
}

/** JVM-testable state rules for the single ImageReader request slot. */
class MediaProjectionRequestTracker {
    private var nextRequestId = 0L
    private var projectionGeneration = 0L
    private var pending: CaptureRequestState.Pending? = null

    @Synchronized
    fun restartProjection(): CaptureRequestState.Pending? {
        val old = pending
        pending = null
        projectionGeneration++
        return old
    }

    @Synchronized
    fun begin(): CaptureRequestState {
        if (pending != null) return CaptureRequestState.Busy
        val request = CaptureRequestState.Pending(++nextRequestId, projectionGeneration)
        pending = request
        return request
    }

    @Synchronized
    fun complete(requestId: Long, requestGeneration: Long): CaptureRequestState {
        val current = pending
        return if (current != null && current.requestId == requestId &&
            current.projectionGeneration == requestGeneration
        ) {
            pending = null
            CaptureRequestState.Completed
        } else {
            CaptureRequestState.Stopped
        }
    }

    @Synchronized
    fun timeout(requestId: Long, requestGeneration: Long): CaptureRequestState =
        if (complete(requestId, requestGeneration) == CaptureRequestState.Completed) {
            CaptureRequestState.TimedOut
        } else {
            CaptureRequestState.Stopped
        }

    @Synchronized
    fun stop(): CaptureRequestState.Pending? = pending.also { pending = null }

    @Synchronized
    fun currentProjectionGeneration(): Long = projectionGeneration
}
