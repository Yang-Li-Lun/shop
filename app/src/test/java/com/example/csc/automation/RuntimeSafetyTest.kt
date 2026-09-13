package com.example.csc.automation

import org.junit.Assert.*
import org.junit.Test

class RuntimeSafetyTest {
    @Test fun consentMustBeExplicitAndCanBeRevoked() {
        RuntimeArming.setArmed(false)
        assertFalse(RuntimeArming.isArmed)
        RuntimeArming.setArmed(true)
        assertTrue(RuntimeArming.isArmed)
        RuntimeArming.setArmed(false)
        assertFalse(RuntimeArming.isArmed)
    }
    @Test fun oldActionCannotOwnNewSessionWork() {
        val state = ActionStateMachine()
        val gate = AutomationSessionGate()
        gate.update("target", "target", 1, 0)
        val old = gate.token()!!
        state.bindAction(old)
        state.cancel()
        gate.invalidate()
        val fresh = gate.token()!!
        state.bindAction(fresh)
        assertFalse(state.ownsAction(old))
        assertTrue(state.ownsAction(fresh))
    }
    @Test fun invalidLimitsAreFiniteAndOrdered() {
        assertEquals(5f to 5f, normalizeNumberLimits(5f, 3f))
        assertEquals(0f to 0f, normalizeNumberLimits(-5f, -3f))
        assertEquals(0.15f to 999_999f, normalizeNumberLimits(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(3f to 3f, normalizeNumberLimits(3f, 3f))
    }
    @Test fun claimMustBeAnActionNotAStatusOrDetails() {
        listOf("領取成功", "領取完成", "查看領取紀錄", "已完成領取", "獎勵領取詳情",
            "7 分鐘後領取", "08:00 領取", "已領取", "尚未領取").forEach {
            assertFalse(it, isReadyClaimText(it, "領取"))
        }
        listOf("領取", "立即領取", "點擊領取", "馬上領取", " 立即 領取 ").forEach {
            assertTrue(it, isReadyClaimText(it, "領取"))
        }
        assertFalse(isReadyClaimText("任何文字", ""))
    }
    @Test fun lateFrameCannotCompleteNewRecognition() {
        val state = ActionStateMachine()
        val first = state.startRecognitionFrame()!!
        assertNull(state.startRecognitionFrame())
        state.cancel()
        val second = state.startRecognitionFrame()!!
        assertFalse(state.isFrameCurrent(first))
        assertFalse(state.finishRecognitionFrame(first))
        assertTrue(state.isRecognitionInFlight)
        assertEquals(AutomationPhase.RECOGNIZING, state.phase)
        assertTrue(state.finishRecognitionFrame(second))
        assertFalse(state.finishRecognitionFrame(first))
        assertFalse(state.isRecognitionInFlight)
    }
    @Test fun frameCompletionPreservesScheduledGesture() {
        val state = ActionStateMachine()
        val frame = state.startRecognitionFrame()!!
        assertTrue(state.beginClickDelay())
        assertTrue(state.finishRecognitionFrame(frame))
        assertEquals(AutomationPhase.CLICK_DELAY, state.phase)
        assertNull(state.startRecognitionFrame())
        state.cancel()
        assertFalse(state.beginClicking())
    }
}