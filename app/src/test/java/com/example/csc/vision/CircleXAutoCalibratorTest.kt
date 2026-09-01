package com.example.csc.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleXAutoCalibratorTest {
    @Test
    fun waitsForEnoughBackgroundSamplesBeforeAdapting() {
        val calibrator = CircleXAutoCalibrator()
        repeat(15) { calibrator.observe("x", 0.88f, 0.55f, 0.3f, false) }
        assertEquals(0.88f, calibrator.calibration("x", 0.88f).effectiveThreshold, 0.001f)

        calibrator.observe("x", 0.88f, 0.55f, 0.3f, false)
        val adapted = calibrator.calibration("x", 0.88f, 0.86f)
        assertTrue(adapted.effectiveThreshold in 0.875f..0.88f)
        assertTrue(adapted.nearThreshold)
    }

    @Test
    fun adaptiveThresholdOnlyMovesWithinSafeBounds() {
        val lowBackground = CircleXAutoCalibrator()
        repeat(80) { lowBackground.observe("x", 0.88f, 0.55f, 0.3f, false) }
        assertTrue(lowBackground.calibration("x", 0.88f).effectiveThreshold >= 0.85f)

        val difficultBackground = CircleXAutoCalibrator()
        repeat(80) { difficultBackground.observe("x", 0.88f, 0.87f, 0.3f, false) }
        val raised = difficultBackground.calibration("x", 0.88f).effectiveThreshold
        assertTrue(raised in 0.92f..0.931f)
    }

    @Test
    fun changingUserThresholdClearsRuntimeCalibration() {
        val calibrator = CircleXAutoCalibrator()
        repeat(40) { calibrator.observe("x", 0.88f, 0.87f, 0.3f, false) }
        assertTrue(calibrator.calibration("x", 0.88f).effectiveThreshold > 0.90f)
        assertEquals(0.82f, calibrator.calibration("x", 0.82f).effectiveThreshold, 0.001f)
    }

    @Test
    fun acceptedTargetLearnsSizeWithoutBecomingBackground() {
        val calibrator = CircleXAutoCalibrator()
        calibrator.observe("x", 0.88f, 0.92f, 0.42f, true)
        val after = calibrator.calibration("x", 0.88f)
        assertTrue(after.minDiameterRatio > 0.25f)
        assertTrue(after.maxDiameterRatio < 0.70f)
        assertEquals(0.42f * 0.68f, after.minDiameterRatio, 0.001f)
        assertEquals(0.88f, after.effectiveThreshold, 0.001f)
    }
}
