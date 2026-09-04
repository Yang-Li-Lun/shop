package com.example.csc.automation

import kotlin.math.abs

/**
 * Pure state for the number monitor. It deliberately does not know about Android, handlers or
 * gestures; callers decide how to execute the returned action.
 */
class NumberMonitorTracker {
    enum class Action {
        STAY,
        WAIT_FOR_CONFIRMATION,
        START_OR_KEEP_ABSENCE,
        REQUEST_FRESH_OBSERVATION,
        SWIPE_LOW,
        SWIPE_HIGH,
        SWIPE_ABSENT,
    }

    sealed class Observation {
        data class Value(val value: Double) : Observation()
        object Missing : Observation()
        object Invalid : Observation()
    }

    private enum class RiskDirection { LOW, HIGH }

    private data class LastKnownGood(
        val value: Double,
        val observedAtMs: Long,
        val roiFingerprint: Long,
    )

    private var activeGeneration = 0L
    private var lastKnownGood: LastKnownGood? = null
    private var candidateDirection: RiskDirection? = null
    private var candidateValue: Double? = null
    private var candidateCount = 0
    private var candidateFirstAtMs = 0L
    private var missingStartedAtMs: Long? = null
    private var missingObservations = 0
    private var absenceConfirmationDue = false
    private var swipeRequested = false
    private var lastAbsenceTimeoutMs = 2_000L

    @Synchronized
    fun observe(
        nowMs: Long,
        observation: Observation,
        roiFingerprint: Long,
        threshold: Float,
        upperLimit: Float = 999_999f,
        absenceTimeoutMs: Long = 2_000L,
        prioritySwipePending: Boolean = false,
        generation: Long = 0L,
    ): Action {
        activateGeneration(generation)

        // The click-follow-up swipe owns the gesture channel. Do not let any OCR result mutate a
        // pending general swipe decision; the caller will reset/rebase after that priority run.
        if (prioritySwipePending) return Action.STAY

        val value = (observation as? Observation.Value)?.value
        if (value != null && value.isFinite() &&
            value + NUMBER_BOUNDARY_EPSILON >= threshold &&
            value - NUMBER_BOUNDARY_EPSILON <= upperLimit
        ) {
            lastKnownGood = LastKnownGood(value, nowMs, roiFingerprint)
            clearRiskAndAbsence()
            return Action.STAY
        }

        // A static ROI that was previously accepted is stronger evidence than one bad OCR
        // result. Require a fresh OCR pass, and never start an absence deadline from it.
        if (lastKnownGood?.roiFingerprint == roiFingerprint) {
            clearRiskAndAbsence()
            return Action.REQUEST_FRESH_OBSERVATION
        }

        return when {
            value == null || !value.isFinite() -> observeMissing(nowMs, absenceTimeoutMs)
            value > upperLimit + NUMBER_BOUNDARY_EPSILON -> observeRisk(nowMs, value, RiskDirection.HIGH)
            else -> observeRisk(nowMs, value, RiskDirection.LOW)
        }
    }

    /**
     * Marks the absence deadline as requiring a fresh capture. It intentionally never returns a
     * swipe action: only a subsequent fresh Missing observation can satisfy final confirmation.
     */
    @Synchronized
    fun onAbsenceDeadline(nowMs: Long, generation: Long = activeGeneration): Action {
        activateGeneration(generation)
        val startedAt = missingStartedAtMs ?: return Action.STAY
        val deadline = startedAt + lastAbsenceTimeoutMs
        if (nowMs < deadline) return Action.START_OR_KEEP_ABSENCE
        absenceConfirmationDue = true
        swipeRequested = false
        return Action.REQUEST_FRESH_OBSERVATION
    }

    /** Clears all evidence, including the last-known-good value, after a page/action boundary. */
    @Synchronized
    fun reset() {
        resetEvidence()
        activeGeneration = 0L
    }

    /** Prevents repeated scheduling if a caller has accepted a returned swipe action. */
    @Synchronized
    fun markActionConsumed() {
        resetEvidence()
    }

    private fun observeMissing(nowMs: Long, absenceTimeoutMs: Long): Action {
        lastAbsenceTimeoutMs = absenceTimeoutMs.coerceAtLeast(0L)
        if (missingStartedAtMs == null) {
            missingStartedAtMs = nowMs
            missingObservations = 0
            absenceConfirmationDue = false
        }
        missingObservations++
        candidateDirection = null
        candidateValue = null
        candidateCount = 0

        if (absenceConfirmationDue && missingObservations >= 2) {
            if (swipeRequested) return Action.STAY
            swipeRequested = true
            return Action.SWIPE_ABSENT
        }

        val startedAt = missingStartedAtMs ?: nowMs
        if (!absenceConfirmationDue && nowMs >= startedAt + lastAbsenceTimeoutMs) {
            absenceConfirmationDue = true
            return Action.REQUEST_FRESH_OBSERVATION
        }
        return Action.START_OR_KEEP_ABSENCE
    }

    private fun observeRisk(nowMs: Long, value: Double, direction: RiskDirection): Action {
        missingStartedAtMs = null
        missingObservations = 0
        absenceConfirmationDue = false
        if (swipeRequested) return Action.STAY

        val previousDirection = candidateDirection
        val previousValue = candidateValue
        val tolerance = previousValue?.let { maxOf(0.02, abs(it) * 0.08) } ?: 0.0
        val matches = previousDirection == direction &&
            previousValue != null &&
            abs(value - previousValue) <= tolerance
        if (matches) {
            candidateCount++
        } else {
            candidateDirection = direction
            candidateCount = 1
            candidateFirstAtMs = nowMs
        }
        candidateValue = value

        if (candidateCount < 3 || nowMs - candidateFirstAtMs < 500L) {
            return Action.WAIT_FOR_CONFIRMATION
        }
        swipeRequested = true
        return if (direction == RiskDirection.HIGH) Action.SWIPE_HIGH else Action.SWIPE_LOW
    }

    private fun activateGeneration(generation: Long) {
        if (generation == activeGeneration) return
        resetEvidence()
        activeGeneration = generation
    }

    private fun clearRiskAndAbsence() {
        candidateDirection = null
        candidateValue = null
        candidateCount = 0
        candidateFirstAtMs = 0L
        missingStartedAtMs = null
        missingObservations = 0
        absenceConfirmationDue = false
        swipeRequested = false
    }

    private fun resetEvidence() {
        lastKnownGood = null
        clearRiskAndAbsence()
    }

}

typealias NumberMonitorAction = NumberMonitorTracker.Action
typealias NumberMonitorObservation = NumberMonitorTracker.Observation
