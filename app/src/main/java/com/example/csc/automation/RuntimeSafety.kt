package com.example.csc.automation

/** Execution consent belongs to this process only, never to a saved profile or backup. */
internal object RuntimeArming {
    @Volatile var isArmed = false
        private set
    fun setArmed(value: Boolean) { isArmed = value }
}

internal fun normalizeNumberLimits(threshold: Float, upperLimit: Float): Pair<Float, Float> {
    val lower = (if (threshold.isFinite()) threshold else 0.15f).coerceIn(0f, 999_999f)
    val upper = (if (upperLimit.isFinite()) upperLimit else 999_999f).coerceIn(0f, 999_999f)
    return lower to maxOf(lower, upper)
}