package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberMonitorTrackerTest {
    private val stayThreshold = 0.15f
    private val upperLimit = 3f
    private val timeoutMs = 1_000L

    private fun observe(
        tracker: NumberMonitorTracker,
        nowMs: Long,
        observation: NumberMonitorTracker.Observation,
        roiFingerprint: Long,
        priority: Boolean = false,
        generation: Long = 0L,
    ): NumberMonitorAction = tracker.observe(
        nowMs = nowMs,
        observation = observation,
        roiFingerprint = roiFingerprint,
        threshold = stayThreshold,
        upperLimit = upperLimit,
        absenceTimeoutMs = timeoutMs,
        prioritySwipePending = priority,
        generation = generation,
    )

    @Test
    fun sameRoiMissingAfterReliableValueOnlyRequestsFreshObservation() {
        val tracker = NumberMonitorTracker()
        assertEquals(NumberMonitorAction.STAY, observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 7L))

        assertEquals(NumberMonitorAction.REQUEST_FRESH_OBSERVATION, observe(tracker, 100L, NumberMonitorTracker.Observation.Missing, 7L))
        assertEquals(NumberMonitorAction.REQUEST_FRESH_OBSERVATION, observe(tracker, 1_100L, NumberMonitorTracker.Observation.Missing, 7L))
    }

    @Test
    fun sameRoiLowAndHighMisreadsNeverSwipe() {
        val lowTracker = NumberMonitorTracker()
        observe(lowTracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 7L)
        repeat(4) { index ->
            assertEquals(
                NumberMonitorAction.REQUEST_FRESH_OBSERVATION,
                observe(lowTracker, (index + 1) * 250L, NumberMonitorTracker.Observation.Value(0.02), 7L),
            )
        }

        val highTracker = NumberMonitorTracker()
        observe(highTracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 7L)
        repeat(4) { index ->
            assertEquals(
                NumberMonitorAction.REQUEST_FRESH_OBSERVATION,
                observe(highTracker, (index + 1) * 250L, NumberMonitorTracker.Observation.Value(30.0), 7L),
            )
        }
    }

    @Test
    fun changedRoiNeedsThreeStableLowObservationsAcrossFiveHundredMs() {
        val tracker = NumberMonitorTracker()
        observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 7L)

        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.10), 8L))
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 250L, NumberMonitorTracker.Observation.Value(0.105), 8L))
        assertEquals(NumberMonitorAction.SWIPE_LOW, observe(tracker, 500L, NumberMonitorTracker.Observation.Value(0.102), 8L))
        assertEquals(NumberMonitorAction.STAY, observe(tracker, 750L, NumberMonitorTracker.Observation.Value(0.101), 8L))
    }

    @Test
    fun twoLowValuesDirectionChangesAndLargeJumpDoNotTrigger() {
        val tracker = NumberMonitorTracker()
        observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 1L)

        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.10), 2L))
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 250L, NumberMonitorTracker.Observation.Value(0.11), 2L))
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 500L, NumberMonitorTracker.Observation.Value(4.0), 2L))
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 750L, NumberMonitorTracker.Observation.Value(0.10), 2L))
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 1_000L, NumberMonitorTracker.Observation.Value(0.13), 2L))
    }

    @Test
    fun absenceDeadlineRequiresFreshMissingObservation() {
        val tracker = NumberMonitorTracker()
        assertEquals(NumberMonitorAction.START_OR_KEEP_ABSENCE, observe(tracker, 0L, NumberMonitorTracker.Observation.Missing, 1L))
        assertEquals(NumberMonitorAction.REQUEST_FRESH_OBSERVATION, tracker.onAbsenceDeadline(1_000L))
        assertEquals(NumberMonitorAction.SWIPE_ABSENT, observe(tracker, 1_001L, NumberMonitorTracker.Observation.Missing, 2L))
    }

    @Test
    fun timeoutWithoutFreshOcrNeverSwipes() {
        val tracker = NumberMonitorTracker()
        observe(tracker, 0L, NumberMonitorTracker.Observation.Missing, 1L)

        assertEquals(NumberMonitorAction.REQUEST_FRESH_OBSERVATION, tracker.onAbsenceDeadline(1_000L))
        assertEquals(NumberMonitorAction.REQUEST_FRESH_OBSERVATION, tracker.onAbsenceDeadline(1_001L))
    }

    @Test
    fun normalObservationCancelsAbsenceDeadline() {
        val tracker = NumberMonitorTracker()
        observe(tracker, 0L, NumberMonitorTracker.Observation.Missing, 1L)

        assertEquals(NumberMonitorAction.STAY, observe(tracker, 400L, NumberMonitorTracker.Observation.Value(0.20), 2L))
        assertEquals(NumberMonitorAction.STAY, tracker.onAbsenceDeadline(1_500L))
    }

    @Test
    fun priorityPendingSuppressesEveryGeneralNumberAction() {
        val tracker = NumberMonitorTracker()
        val observations = listOf(
            NumberMonitorTracker.Observation.Value(0.02),
            NumberMonitorTracker.Observation.Value(30.0),
            NumberMonitorTracker.Observation.Missing,
        )

        observations.forEachIndexed { index, observation ->
            assertEquals(NumberMonitorAction.STAY, observe(tracker, index * 500L, observation, index.toLong(), priority = true))
        }
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.02), 9L))
    }

    @Test
    fun generationChangesInvalidateOldRiskAndAbsenceEvidence() {
        val riskTracker = NumberMonitorTracker()
        observe(riskTracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 1L, generation = 1L)
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(riskTracker, 0L, NumberMonitorTracker.Observation.Value(0.10), 2L, generation = 1L))
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(riskTracker, 500L, NumberMonitorTracker.Observation.Value(0.10), 2L, generation = 2L))
        assertEquals(NumberMonitorAction.WAIT_FOR_CONFIRMATION, observe(riskTracker, 1_000L, NumberMonitorTracker.Observation.Value(0.10), 2L, generation = 2L))

        val absenceTracker = NumberMonitorTracker()
        observe(absenceTracker, 0L, NumberMonitorTracker.Observation.Missing, 1L, generation = 3L)
        assertEquals(NumberMonitorAction.STAY, absenceTracker.onAbsenceDeadline(2_000L, generation = 4L))
    }

    @Test
    fun thresholdAndUpperLimitEqualityStay() {
        val tracker = NumberMonitorTracker()
        assertEquals(NumberMonitorAction.STAY, observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.15), 1L))
        assertEquals(NumberMonitorAction.STAY, observe(tracker, 100L, NumberMonitorTracker.Observation.Value(3.0), 2L))
    }

    @Test
    fun invalidOnUnchangedReliableRoiOnlyRequestsFreshObservation() {
        val tracker = NumberMonitorTracker()
        observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 7L)
        assertEquals(NumberMonitorAction.REQUEST_FRESH_OBSERVATION,
            observe(tracker, 100L, NumberMonitorTracker.Observation.Invalid, 7L))
        assertEquals(NumberMonitorAction.STAY, tracker.onAbsenceDeadline(2_000L))
    }

    @Test
    fun invalidOnChangedRoiUsesVersion112AbsenceConfirmation() {
        val tracker = NumberMonitorTracker()
        observe(tracker, 0L, NumberMonitorTracker.Observation.Value(0.20), 7L)
        assertEquals(NumberMonitorAction.START_OR_KEEP_ABSENCE,
            observe(tracker, 100L, NumberMonitorTracker.Observation.Invalid, 8L))
        assertEquals(NumberMonitorAction.REQUEST_FRESH_OBSERVATION, tracker.onAbsenceDeadline(1_100L))
        assertEquals(NumberMonitorAction.SWIPE_ABSENT,
            observe(tracker, 1_101L, NumberMonitorTracker.Observation.Invalid, 8L))
    }
}
