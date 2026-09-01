package com.example.csc.vision

import java.util.ArrayDeque

data class CircleXCalibration(
    val effectiveThreshold: Float,
    val minDiameterRatio: Float,
    val maxDiameterRatio: Float,
    val nearThreshold: Boolean,
)

/** Runtime-only adaptive calibration; user settings remain the authoritative limits. */
class CircleXAutoCalibrator {
    private data class ZoneState(
        val backgroundScores: ArrayDeque<Float> = ArrayDeque(),
        var learnedDiameterRatio: Float? = null,
        var userThreshold: Float? = null,
        var effectiveThreshold: Float? = null,
    )

    private val states = mutableMapOf<String, ZoneState>()

    @Synchronized
    fun calibration(zoneId: String, userThreshold: Float, score: Float? = null): CircleXCalibration {
        val state = states.getOrPut(zoneId) { ZoneState() }
        resetIfUserThresholdChanged(state, userThreshold)
        val threshold = state.effectiveThreshold ?: userThreshold
        val learned = state.learnedDiameterRatio
        return CircleXCalibration(
            effectiveThreshold = threshold,
            minDiameterRatio = learned?.times(0.68f)?.coerceAtLeast(0.10f) ?: 0.16f,
            maxDiameterRatio = learned?.times(1.38f)?.coerceAtMost(0.88f) ?: 0.72f,
            nearThreshold = score != null && score >= threshold - 0.07f && score < threshold + 0.10f,
        )
    }

    @Synchronized
    fun observe(zoneId: String, userThreshold: Float, score: Float, diameterRatio: Float, accepted: Boolean) {
        val state = states.getOrPut(zoneId) { ZoneState() }
        resetIfUserThresholdChanged(state, userThreshold)
        if (accepted) {
            state.learnedDiameterRatio = state.learnedDiameterRatio?.let { it * 0.75f + diameterRatio * 0.25f }
                ?: diameterRatio
        } else if (score < userThreshold) {
            state.backgroundScores.addLast(score)
            while (state.backgroundScores.size > MAX_BACKGROUND_SAMPLES) state.backgroundScores.removeFirst()
            updateEffectiveThreshold(state, userThreshold)
        }
    }

    private fun resetIfUserThresholdChanged(state: ZoneState, userThreshold: Float) {
        if (state.userThreshold == userThreshold) return
        state.userThreshold = userThreshold
        state.effectiveThreshold = userThreshold
        state.backgroundScores.clear()
    }

    private fun updateEffectiveThreshold(state: ZoneState, userThreshold: Float) {
        if (state.backgroundScores.size < MIN_BACKGROUND_SAMPLES) {
            state.effectiveThreshold = userThreshold
            return
        }
        val sorted = state.backgroundScores.sorted()
        val backgroundCeiling = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.lastIndex)]
        val desired = (backgroundCeiling + BACKGROUND_MARGIN).coerceIn(
            (userThreshold - MAX_AUTO_LOWER).coerceAtLeast(0.50f),
            (userThreshold + MAX_AUTO_RAISE).coerceAtMost(0.99f),
        )
        val current = state.effectiveThreshold ?: userThreshold
        // Difficult backgrounds raise the threshold quickly. Easy frames lower it
        // slowly, preventing a short quiet period from making detection over-sensitive.
        val weight = if (desired > current) RAISE_WEIGHT else LOWER_WEIGHT
        state.effectiveThreshold = (current + (desired - current) * weight).coerceIn(0.50f, 0.99f)
    }

    @Synchronized
    fun reset() = states.clear()

    private companion object {
        const val MIN_BACKGROUND_SAMPLES = 16
        const val MAX_BACKGROUND_SAMPLES = 48
        const val BACKGROUND_MARGIN = 0.08f
        const val MAX_AUTO_LOWER = 0.03f
        const val MAX_AUTO_RAISE = 0.05f
        const val RAISE_WEIGHT = 0.35f
        const val LOWER_WEIGHT = 0.12f
    }
}
