package com.example.csc.automation

import org.junit.Assert.*
import org.junit.Test

class NumberImageEvidenceTest {
    @Test fun colorRejectsWhiteAndWrongHueEvenWithLooseRgbTolerance() {
        assertTrue(matchesNumberInk(0xCABC37, 0xCABC37, 60))
        assertTrue(matchesNumberInk(0xB3A532, 0xCABC37, 60))
        assertFalse(matchesNumberInk(0xBBBBBB, 0xCABC37, 60))
        assertFalse(matchesNumberInk(0xCA7037, 0xCABC37, 60))
    }
    @Test fun glyphEvidenceRejectsFlatBackgroundAndSparseFlecks() {
        assertEquals(-1, classifyNumberInk(BooleanArray(100) { true }, 10, 10))
        assertEquals(-1, classifyNumberInk(BooleanArray(100) { it < 4 }, 10, 10))
        assertEquals(1, classifyNumberInk(BooleanArray(100) { it % 10 in 2..3 }, 10, 10))
        assertEquals(-1, classifyNumberInk(BooleanArray(100), 10, 10))
        assertEquals(-1, classifyNumberInk(BooleanArray(100) { it/10 !in 2..7 || it%10 !in 3..6 }, 10, 10))
    }
    @Test fun incompleteAndLostDecimalCannotBecomeInteger() {
        assertNull(extractSingleDecimalNumber("0."))
        assertNull(extractSingleDecimalNumber("02"))
        assertNull(extractSingleDecimalNumber("1.2.3"))
        assertEquals(0.2, extractSingleDecimalNumber("0 . 2"))
        assertEquals(1.2, extractSingleDecimalNumber("1.2"))
        assertEquals(12.0, extractSingleDecimalNumber("12"))
        assertEquals(0.2, extractSingleDecimalNumber("０．２"))
    }
    @Test fun decimalPrefixJoinsFractionAndMalformedTokenIsRetained() {
        val tokens = rebuildNumberTokens(listOf(
            NumberTextElement("0.", ClickBounds(0f, 0f, 12f, 20f)),
            NumberTextElement("2", ClickBounds(13f, 0f, 23f, 20f))))
        assertEquals(listOf("0.2"), tokens.map { it.text })
        assertEquals("0.", rebuildNumberTokens(listOf(NumberTextElement("0.", ClickBounds(0f, 0f, 12f, 20f)))).single().text)
    }
    @Test fun missingPointNeedsRealCompactInkAndDoesNotRewriteInteger() {
        val digits = listOf(NumberTextElement("1", ClickBounds(0f,0f,10f,20f)),
            NumberTextElement("2", ClickBounds(16f,0f,26f,20f)))
        val recovered = recoverNumberDecimalPoints(digits) { x,y -> x in 12..13 && y in 17..18 }
        assertEquals("1.2", rebuildNumberTokens(recovered).single().text)
        assertEquals("12", rebuildNumberTokens(recoverNumberDecimalPoints(digits) { _,_ -> false }).single().text)
        assertEquals("12", rebuildNumberTokens(recoverNumberDecimalPoints(digits) { _,_ -> true }).single().text)
    }
    @Test fun claimCountdownIsNotAReadyButton() {
        assertFalse(isReadyClaimText("7 分鐘後領取", "領取"))
        assertFalse(isReadyClaimText("08:00 領取", "領取"))
        assertFalse(isReadyClaimText("已領取", "領取"))
        assertTrue(isReadyClaimText("立即領取", "領取"))
        assertTrue(isReadyClaimText("領取", "領取"))
    }
    @Test fun rewardWaitsForSixMinuteMinimumAndFreshNumberAndAllowsSevenOrEight() {
        val state = ActionStateMachine()
        assertTrue(state.needsInitialPageSwipe())
        state.rewardPageOpened(1_000)
        state.recordRewardNumber(360_999, true)
        assertFalse(state.canClaimReward(360_999))
        assertTrue(state.canClaimReward(361_000))
        assertFalse(state.canClaimReward(420_000))
        state.recordRewardNumber(420_000, true)
        assertTrue(state.canClaimReward(420_000))
        state.recordRewardNumber(480_000, true)
        assertTrue(state.canClaimReward(480_000))
        state.recordRewardNumber(480_100, false)
        assertFalse(state.canClaimReward(480_100))
        state.rewardPageOpened(481_000)
        assertFalse(state.canClaimReward(481_000))
        state.resetRewardPage()
        assertTrue(state.needsInitialPageSwipe())
    }
}
