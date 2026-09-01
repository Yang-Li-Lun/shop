package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionStateMachineTest {
    @Test
    fun prioritySwipeBlocksRecognitionAndClicksUntilFinished() {
        val state = ActionStateMachine()
        assertTrue(state.tryStartRecognition())
        assertTrue(state.armPrioritySwipe())
        assertTrue(state.blocksRecognition())
        assertFalse(state.beginClickDelay())
        assertTrue(state.beginSwiping())
        state.gestureFinished()
        assertEquals(AutomationPhase.COOLDOWN, state.phase)
        assertTrue(state.tryStartRecognition())
    }

    @Test
    fun prioritySwipeCannotOverwritePendingOrActiveClick() {
        val pending = ActionStateMachine()
        assertTrue(pending.tryStartRecognition())
        assertTrue(pending.beginClickDelay())
        assertFalse(pending.armPrioritySwipe())
        assertEquals(AutomationPhase.CLICK_DELAY, pending.phase)

        assertTrue(pending.beginClicking())
        assertFalse(pending.armPrioritySwipe())
        assertEquals(AutomationPhase.CLICKING, pending.phase)
    }

    @Test
    fun staleSwipeCancellationCannotCancelNewRecognition() {
        val state = ActionStateMachine()
        assertFalse(state.cancelSwipe())
        assertTrue(state.tryStartRecognition())
        assertFalse(state.cancelSwipe())
        assertEquals(AutomationPhase.RECOGNIZING, state.phase)
    }

    @Test
    fun swipeCancellationOnlyClearsSwipeState() {
        val state = ActionStateMachine()
        assertTrue(state.armPrioritySwipe())
        assertTrue(state.cancelSwipe())
        assertEquals(AutomationPhase.IDLE, state.phase)
        assertFalse(state.cancelSwipe())
    }

    @Test
    fun clickCancellationOnlyClearsClickState() {
        val state = ActionStateMachine()
        assertTrue(state.tryStartRecognition())
        assertTrue(state.beginClickDelay())
        assertTrue(state.cancelClick())
        assertEquals(AutomationPhase.IDLE, state.phase)
        assertFalse(state.cancelClick())

        assertTrue(state.armPrioritySwipe())
        assertFalse(state.cancelClick())
        assertEquals(AutomationPhase.SWIPE_DELAY, state.phase)
    }
}
