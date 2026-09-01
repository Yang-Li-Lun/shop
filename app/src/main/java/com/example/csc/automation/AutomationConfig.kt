package com.example.csc.automation

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

private const val MIN_NUMBER_COLOR_MATCHES = 8
private const val MIN_NUMBER_COLOR_COVERAGE = 0.015f
internal const val NUMBER_PRIORITY_VISUAL_SCAN_INTERVAL_MS = 2_500L

enum class TargetMode { TEXT, IMAGE, CIRCLE_X, BACK_ARROW }

data class AutomationSettings(
    val enabled: Boolean,
    val zones: List<RecognitionZone>,
    val matchThreshold: Float,
    val scanIntervalMs: Long,
    val clickCooldownMs: Long,
    val showClickMarker: Boolean,
    val randomClickMaxMs: Long = 500L,
    val numberMonitorEnabled: Boolean = false,
    val numberMonitorRegion: RecognitionRegion = RecognitionRegion.FULL,
    val numberMonitorThreshold: Float = 0.15f,
    val numberMonitorUpperLimit: Float = 999_999f,
    val numberColorFilterEnabled: Boolean = false,
    val numberColorHex: String = "#FFFFFF",
    val numberColorTolerance: Int = 45,
    val numberAbsenceTimeoutMs: Long = 2_000L,
    val numberTriggerZoneId: String? = null,
    val numberTriggerDelayMs: Long = 0L,
    val circleXThreshold: Float = 0.88f,
    val backArrowThreshold: Float = 0.72f,
    val targetPackage: String = "",
) {
    val targetCount: Int get() = zones.sumOf { it.targets.size }

    fun canClick(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean =
        zones.any { zone ->
            zone.targets.isNotEmpty() && zone.region.contains(x, y, screenWidth, screenHeight)
        }
}

data class GestureTiming(
    val startDelayMs: Long,
    val pressDurationMs: Long,
)

data class ClickBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Converts a bounding box from screenshot pixels to gesture/display pixels. */
internal fun mapClickBoundsToScreen(
    bounds: ClickBounds,
    sourceWidth: Int,
    sourceHeight: Int,
    destinationWidth: Int,
    destinationHeight: Int,
): ClickBounds? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0 || destinationHeight <= 0) return null
    val scaleX = destinationWidth.toFloat() / sourceWidth
    val scaleY = destinationHeight.toFloat() / sourceHeight
    return ClickBounds(
        left = bounds.left * scaleX,
        top = bounds.top * scaleY,
        right = bounds.right * scaleX,
        bottom = bounds.bottom * scaleY,
    )
}

data class ClickPoint(val x: Float, val y: Float)

/** Keeps random taps tightly inside the visually confirmed object. */
internal fun visualSafeClickBounds(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    halfSizeRatio: Float,
): ClickBounds {
    val halfWidth = maxOf(3f, width * halfSizeRatio.coerceIn(0.03f, 0.20f))
    val halfHeight = maxOf(3f, height * halfSizeRatio.coerceIn(0.03f, 0.20f))
    return ClickBounds(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
}

/** Requires two detections to describe the same on-screen object before a tap is allowed. */
internal fun isSpatiallyConsistentVisualMatch(
    firstX: Float,
    firstY: Float,
    firstSize: Float,
    secondX: Float,
    secondY: Float,
    secondSize: Float,
): Boolean {
    if (firstSize <= 0f || secondSize <= 0f) return false
    val distance = sqrt((secondX - firstX) * (secondX - firstX) + (secondY - firstY) * (secondY - firstY))
    val sizeRatio = secondSize / firstSize
    return distance <= maxOf(10f, firstSize * 0.35f) && sizeRatio in 0.72f..1.38f
}

internal fun shouldHoldSwipeCountdownForVisualTarget(score: Float, effectiveThreshold: Float): Boolean =
    score > 0f && score >= effectiveThreshold.coerceIn(0.50f, 0.99f)

internal fun configuredVisualThreshold(mode: TargetMode?, settings: AutomationSettings): Float = when (mode) {
    TargetMode.CIRCLE_X -> settings.circleXThreshold
    TargetMode.BACK_ARROW -> settings.backArrowThreshold
    else -> settings.matchThreshold
}

internal fun hasRecognitionOutsideTriggerZone(recognizedZoneIds: Set<String>, triggerZoneId: String?): Boolean =
    triggerZoneId != null && recognizedZoneIds.any { it != triggerZoneId }

internal fun isValidTargetPackage(value: String): Boolean =
    Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value.trim())

data class SwipeSpec(
    val delayMs: Long,
    val durationMs: Long,
    val startXRatio: Float,
    val startYRatio: Float,
    val endXRatio: Float,
    val endYRatio: Float,
)

internal fun randomSwipeSpec(maximumDelayMs: Long, random: Random = Random.Default): SwipeSpec {
    val safeDelay = maximumDelayMs.coerceIn(100L, 3_000L)
    val startX = 0.35f + random.nextFloat() * 0.30f
    return SwipeSpec(
        delayMs = random.nextLong(safeDelay + 1L),
        durationMs = random.nextLong(360L, 441L),
        startXRatio = startX,
        // Stay clear of bottom navigation, shopping and live-stream controls. Starting
        // on those controls can be interpreted as a tap when the app misses the drag.
        startYRatio = 0.82f + random.nextFloat() * 0.04f,
        endXRatio = (startX + (random.nextFloat() - 0.5f) * 0.20f).coerceIn(0.22f, 0.78f),
        endYRatio = 0.08f + random.nextFloat() * 0.04f,
    )
}

internal fun extractDecimalNumbers(value: String): List<Double> =
    // ML Kit can preserve a decimal separator but insert spaces around it
    // (for example, "0 . 2").  Remove only that whitespace so neighbouring
    // non-numeric text is never joined into a number.
    value.replace(Regex("(?<=\\d)\\s*[.,]\\s*(?=\\d)")) { match ->
        match.value.first { it == '.' || it == ',' }.toString()
    }
        .let { normalized -> Regex("[-+]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)").findAll(normalized) }
        .mapNotNull { match -> match.value.replace(',', '.').toDoubleOrNull() }
        .toList()

internal fun formatRecognizedNumbers(values: List<Double>, maximumItems: Int = 6): String =
    values.distinct().take(maximumItems).joinToString("、") { value ->
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

enum class NumberMonitorDecision { NO_NUMBERS, STAY, SWIPE_UP }

internal fun decideNumberMonitorAction(
    values: List<Double>,
    threshold: Float,
    upperLimit: Float = 999_999f,
): NumberMonitorDecision = when {
    values.isEmpty() -> NumberMonitorDecision.NO_NUMBERS
    values.any { it > upperLimit } -> NumberMonitorDecision.SWIPE_UP
    values.any { it > threshold } -> NumberMonitorDecision.STAY
    values.all { it < threshold } -> NumberMonitorDecision.SWIPE_UP
    else -> NumberMonitorDecision.STAY
}

internal data class NumberTextCandidate(
    val text: String,
    val centerDistanceSquared: Double,
    val area: Long,
)

internal fun selectNumberMonitorValues(candidates: List<NumberTextCandidate>): List<Double> =
    candidates.mapNotNull { candidate ->
        extractDecimalNumbers(candidate.text).firstOrNull()?.let { value -> candidate to value }
    }
        .minWithOrNull(compareBy<Pair<NumberTextCandidate, Double>> { it.first.centerDistanceSquared }
            .thenBy { it.first.area })
        ?.let { listOf(it.second) }
        .orEmpty()

/** Number glyphs must occupy a meaningful part of their own OCR bounds. */
internal fun hasSufficientNumberColorCoverage(matches: Int, samples: Int): Boolean {
    if (samples <= 0) return false
    return matches >= maxOf(MIN_NUMBER_COLOR_MATCHES, (samples * MIN_NUMBER_COLOR_COVERAGE).toInt())
}

/**
 * Number monitoring takes precedence on most frames; visual safety targets still
 * receive a bounded-latency scan so close/back controls are never disabled.
 */
internal fun shouldRunVisualSafetyScan(
    numberMonitorEnabled: Boolean,
    lastScanAt: Long,
    now: Long,
    numberPriorityPassPending: Boolean,
): Boolean = !numberMonitorEnabled || (!numberPriorityPassPending &&
    (lastScanAt <= 0L || now - lastScanAt >= NUMBER_PRIORITY_VISUAL_SCAN_INTERVAL_MS))

internal class NumberSwipeConfirmation(
    private val requiredFrames: Int = 2,
) {
    private var previousValue: Double? = null
    private var previousThreshold: Float? = null
    private var previousUpperLimit: Float? = null
    private var matchingFrames = 0

    fun observe(value: Double, threshold: Float, upperLimit: Float): Boolean {
        val previous = previousValue
        val tolerance = previous?.let { maxOf(0.02, abs(it) * 0.08) } ?: 0.0
        matchingFrames = if (
            previous != null && previousThreshold == threshold && previousUpperLimit == upperLimit &&
            abs(value - previous) <= tolerance
        ) {
            matchingFrames + 1
        } else {
            1
        }
        previousValue = value
        previousThreshold = threshold
        previousUpperLimit = upperLimit
        if (matchingFrames < requiredFrames) return false
        reset()
        return true
    }

    fun reset() {
        previousValue = null
        previousThreshold = null
        previousUpperLimit = null
        matchingFrames = 0
    }
}

internal fun randomClickPoint(
    target: ClickBounds,
    allowedRegion: RecognitionRegion,
    screenWidth: Int,
    screenHeight: Int,
    random: Random = Random.Default,
): ClickPoint? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    val region = allowedRegion.normalized()
    val left = maxOf(target.left, region.left * screenWidth, 1f)
    val top = maxOf(target.top, region.top * screenHeight, 1f)
    val right = minOf(target.right, region.right * screenWidth, screenWidth - 1f)
    val bottom = minOf(target.bottom, region.bottom * screenHeight, screenHeight - 1f)
    if (right <= left || bottom <= top) return null

    // Keep the random point in the central 20% of the detected object. OCR and
    // accessibility bounds can include a little surrounding padding, so this
    // avoids adjacent controls while retaining randomized click positions.
    val horizontalInset = (right - left) * 0.4f
    val verticalInset = (bottom - top) * 0.4f
    val safeLeft = left + horizontalInset
    val safeTop = top + verticalInset
    val safeRight = right - horizontalInset
    val safeBottom = bottom - verticalInset
    return ClickPoint(
        x = if (safeRight > safeLeft) safeLeft + random.nextFloat() * (safeRight - safeLeft) else (left + right) / 2f,
        y = if (safeBottom > safeTop) safeTop + random.nextFloat() * (safeBottom - safeTop) else (top + bottom) / 2f,
    )
}

internal fun randomGestureTiming(
    maximumMs: Long,
    random: Random = Random.Default,
): GestureTiming {
    val safeMaximum = maximumMs.coerceIn(100L, 3_000L)
    return GestureTiming(
        startDelayMs = random.nextLong(safeMaximum + 1L),
        pressDurationMs = random.nextLong(40L, safeMaximum + 1L),
    )
}

data class RecognitionTarget(
    val id: String,
    val mode: TargetMode,
    val value: String,
    val label: String = value,
)

data class RecognitionZone(
    val id: String,
    val name: String,
    val region: RecognitionRegion,
    val targets: List<RecognitionTarget>,
) {
    fun normalized(): RecognitionZone = copy(
        name = name.trim().ifBlank { "未命名區域" },
        region = region.normalized(),
        targets = targets
            .map { it.copy(value = it.value.trim(), label = it.label.trim()) }
            .filter { it.value.isNotBlank() }
            .take(MAX_TARGETS_PER_ZONE),
    )

    companion object {
        const val MAX_TARGETS_PER_ZONE = 20
    }
}

data class RecognitionRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun normalized(): RecognitionRegion {
        val safeLeft = left.coerceIn(0f, 0.95f)
        val safeTop = top.coerceIn(0f, 0.95f)
        val safeRight = right.coerceIn(safeLeft + MIN_SIZE, 1f)
        val safeBottom = bottom.coerceIn(safeTop + MIN_SIZE, 1f)
        return RecognitionRegion(safeLeft, safeTop, safeRight, safeBottom)
    }

    fun contains(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val normalized = normalized()
        val relativeX = x / screenWidth
        val relativeY = y / screenHeight
        return relativeX in normalized.left..normalized.right &&
            relativeY in normalized.top..normalized.bottom
    }

    fun containsBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || right <= left || bottom <= top) return false
        val normalized = normalized()
        return left / screenWidth >= normalized.left &&
            top / screenHeight >= normalized.top &&
            right / screenWidth <= normalized.right &&
            bottom / screenHeight <= normalized.bottom
    }

    /**
     * Allows a control whose label box crosses a zone edge only when a substantial,
     * clickable portion remains inside the configured zone.  The eventual tap is
     * still selected inside this region by [randomClickPoint].
     */
    fun hasSafeClickArea(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || right <= left || bottom <= top) return false
        val normalized = normalized()
        val regionLeft = normalized.left * screenWidth
        val regionTop = normalized.top * screenHeight
        val regionRight = normalized.right * screenWidth
        val regionBottom = normalized.bottom * screenHeight
        val overlapWidth = (minOf(right, regionRight) - maxOf(left, regionLeft)).coerceAtLeast(0f)
        val overlapHeight = (minOf(bottom, regionBottom) - maxOf(top, regionTop)).coerceAtLeast(0f)
        return overlapWidth / (right - left) >= MIN_SAFE_CLICK_OVERLAP_RATIO &&
            overlapHeight / (bottom - top) >= MIN_SAFE_CLICK_OVERLAP_RATIO
    }

    companion object {
        const val MIN_SIZE = 0.05f
        const val MIN_SAFE_CLICK_OVERLAP_RATIO = 0.45f
        val FULL = RecognitionRegion(0f, 0f, 1f, 1f)
    }
}

object AutomationConfig {
    private const val PREFS = "automation"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_MODE = "mode"
    private const val KEY_TEXT = "target_text"
    private const val KEY_URI = "reference_uri"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_CIRCLE_X_THRESHOLD = "circle_x_threshold"
    private const val KEY_BACK_ARROW_THRESHOLD = "back_arrow_threshold"
    private const val KEY_SCAN_INTERVAL = "scan_interval"
    private const val KEY_COOLDOWN = "cooldown"
    private const val KEY_RANDOM_CLICK_MAX = "random_click_max"
    private const val KEY_NUMBER_MONITOR_ENABLED = "number_monitor_enabled"
    private const val KEY_NUMBER_MONITOR_LEFT = "number_monitor_left"
    private const val KEY_NUMBER_MONITOR_TOP = "number_monitor_top"
    private const val KEY_NUMBER_MONITOR_RIGHT = "number_monitor_right"
    private const val KEY_NUMBER_MONITOR_BOTTOM = "number_monitor_bottom"
    private const val KEY_NUMBER_MONITOR_THRESHOLD = "number_monitor_threshold"
    private const val KEY_NUMBER_MONITOR_UPPER_LIMIT = "number_monitor_upper_limit"
    private const val KEY_NUMBER_COLOR_FILTER_ENABLED = "number_color_filter_enabled"
    private const val KEY_NUMBER_COLOR_HEX = "number_color_hex"
    private const val KEY_NUMBER_COLOR_TOLERANCE = "number_color_tolerance"
    private const val KEY_NUMBER_ABSENCE_TIMEOUT = "number_absence_timeout"
    private const val KEY_NUMBER_TRIGGER_ZONE = "number_trigger_zone"
    private const val NO_NUMBER_TRIGGER_ZONE = "__none__"
    private const val KEY_NUMBER_TRIGGER_DELAY = "number_trigger_delay"
    private const val KEY_BUNDLED_PROFILE_SEEDED = "bundled_profile_seeded_v1"
    private const val KEY_REGION_LEFT = "region_left"
    private const val KEY_REGION_TOP = "region_top"
    private const val KEY_REGION_RIGHT = "region_right"
    private const val KEY_REGION_BOTTOM = "region_bottom"
    private const val KEY_SHOW_CLICK_MARKER = "show_click_marker"
    private const val KEY_ZONES = "recognition_zones_v1"

    private val cacheLock = Any()
    private var cachedPreferences: android.content.SharedPreferences? = null
    private var cachedSettings: AutomationSettings? = null

    fun read(context: Context): AutomationSettings {
        seedBundledProfileIfNeeded(context)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(cacheLock) {
            if (cachedPreferences === preferences) {
                cachedSettings?.let { return it }
            }
        }
        val decodedZones = preferences.getString(KEY_ZONES, null)
            ?.let(::decodeZones)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(readLegacyZone(preferences))
        // The bundled profile already contains its native Circle+X target. Existing
        // user zones are a persistence contract and must never be repurposed by index.
        val zones = decodedZones.map(::migrateBundledBackArrowZone)
        if (zones != decodedZones) {
            preferences.edit().putString(KEY_ZONES, encodeZones(zones)).apply()
        }
        val settings = AutomationSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            targetPackage = preferences.getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)
                ?.trim().orEmpty(),
            zones = zones.map(RecognitionZone::normalized).take(MAX_ZONES),
            matchThreshold = preferences.getFloat(KEY_THRESHOLD, 0.82f).coerceIn(0.55f, 0.99f),
            circleXThreshold = preferences.getFloat(KEY_CIRCLE_X_THRESHOLD, 0.88f).coerceIn(0.50f, 0.99f),
            backArrowThreshold = preferences.getFloat(KEY_BACK_ARROW_THRESHOLD, 0.72f).coerceIn(0.50f, 0.99f),
            scanIntervalMs = preferences.getLong(KEY_SCAN_INTERVAL, 900L).coerceIn(500L, 5_000L),
            clickCooldownMs = preferences.getLong(KEY_COOLDOWN, 3_000L).coerceIn(1_000L, 15_000L),
            showClickMarker = preferences.getBoolean(KEY_SHOW_CLICK_MARKER, true),
            randomClickMaxMs = preferences.getLong(KEY_RANDOM_CLICK_MAX, 500L).coerceIn(100L, 3_000L),
            numberMonitorEnabled = preferences.getBoolean(KEY_NUMBER_MONITOR_ENABLED, false),
            numberMonitorRegion = RecognitionRegion(
                preferences.getFloat(KEY_NUMBER_MONITOR_LEFT, 0f),
                preferences.getFloat(KEY_NUMBER_MONITOR_TOP, 0f),
                preferences.getFloat(KEY_NUMBER_MONITOR_RIGHT, 1f),
                preferences.getFloat(KEY_NUMBER_MONITOR_BOTTOM, 1f),
            ).normalized(),
            numberMonitorThreshold = preferences.getFloat(KEY_NUMBER_MONITOR_THRESHOLD, 0.15f)
                .coerceIn(0f, 999_999f),
            numberMonitorUpperLimit = preferences.getFloat(KEY_NUMBER_MONITOR_UPPER_LIMIT, 999_999f)
                .coerceIn(0f, 999_999f),
            numberColorFilterEnabled = preferences.getBoolean(KEY_NUMBER_COLOR_FILTER_ENABLED, false),
            numberColorHex = preferences.getString(KEY_NUMBER_COLOR_HEX, "#FFFFFF") ?: "#FFFFFF",
            numberColorTolerance = preferences.getInt(KEY_NUMBER_COLOR_TOLERANCE, 45).coerceIn(0, 255),
            numberAbsenceTimeoutMs = preferences.getLong(KEY_NUMBER_ABSENCE_TIMEOUT, 2_000L)
                .coerceIn(500L, 30_000L),
            numberTriggerZoneId = decodeNumberTriggerZoneId(
                preferences.getString(KEY_NUMBER_TRIGGER_ZONE, null),
            ),
            numberTriggerDelayMs = preferences.getLong(KEY_NUMBER_TRIGGER_DELAY, 0L)
                .coerceIn(0L, 30_000L),
        )
        synchronized(cacheLock) {
            cachedPreferences = preferences
            cachedSettings = settings
        }
        return settings
    }

    private fun seedBundledProfileIfNeeded(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.contains(KEY_ZONES) || preferences.getBoolean(KEY_BUNDLED_PROFILE_SEEDED, false)) return
        val profile = runCatching {
            context.assets.open("default_profile.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull() ?: return
        val monitor = profile.optJSONObject("numberMonitorRegion") ?: JSONObject()
        val zones = profile.optJSONArray("zones") ?: JSONArray()
        preferences.edit()
            .putBoolean(KEY_ENABLED, profile.optBoolean("enabled", false))
            .putString(KEY_TARGET_PACKAGE, profile.optString("targetPackage", DEFAULT_TARGET_PACKAGE).trim())
            .putString(KEY_ZONES, zones.toString())
            .putFloat(KEY_THRESHOLD, profile.optDouble("matchThreshold", 0.8).toFloat())
            .putFloat(KEY_CIRCLE_X_THRESHOLD, profile.optDouble("circleXThreshold", 0.88).toFloat())
            .putLong(KEY_SCAN_INTERVAL, profile.optLong("scanIntervalMs", 900L))
            .putLong(KEY_COOLDOWN, profile.optLong("clickCooldownMs", 3_000L))
            .putLong(KEY_RANDOM_CLICK_MAX, profile.optLong("randomClickMaxMs", 1_500L))
            .putBoolean(KEY_SHOW_CLICK_MARKER, profile.optBoolean("showClickMarker", true))
            .putBoolean(KEY_NUMBER_MONITOR_ENABLED, profile.optBoolean("numberMonitorEnabled", true))
            .putFloat(KEY_NUMBER_MONITOR_THRESHOLD, profile.optDouble("numberMonitorThreshold", 0.15).toFloat())
            .putFloat(KEY_NUMBER_MONITOR_UPPER_LIMIT, profile.optDouble("numberMonitorUpperLimit", 999999.0).toFloat())
            .putBoolean(KEY_NUMBER_COLOR_FILTER_ENABLED, profile.optBoolean("numberColorFilterEnabled", false))
            .putString(KEY_NUMBER_COLOR_HEX, profile.optString("numberColorHex", "#FFFFFF"))
            .putInt(KEY_NUMBER_COLOR_TOLERANCE, profile.optInt("numberColorTolerance", 45))
            .putLong(KEY_NUMBER_ABSENCE_TIMEOUT, profile.optLong("numberAbsenceTimeoutMs", 4_000L))
            .putString(
                KEY_NUMBER_TRIGGER_ZONE,
                encodeNumberTriggerZoneId(profile.optString("numberTriggerZoneId").ifBlank { null }),
            )
            .putLong(KEY_NUMBER_TRIGGER_DELAY, profile.optLong("numberTriggerDelayMs", 0L))
            .putFloat(KEY_NUMBER_MONITOR_LEFT, monitor.optDouble("left", 0.0).toFloat())
            .putFloat(KEY_NUMBER_MONITOR_TOP, monitor.optDouble("top", 0.0).toFloat())
            .putFloat(KEY_NUMBER_MONITOR_RIGHT, monitor.optDouble("right", 1.0).toFloat())
            .putFloat(KEY_NUMBER_MONITOR_BOTTOM, monitor.optDouble("bottom", 1.0).toFloat())
            .putBoolean(KEY_BUNDLED_PROFILE_SEEDED, true)
            .apply()
    }

    fun update(
        context: Context,
        zones: List<RecognitionZone>,
        targetPackage: String,
        matchThreshold: Float,
        circleXThreshold: Float,
        backArrowThreshold: Float,
        clickCooldownMs: Long,
        showClickMarker: Boolean,
        randomClickMaxMs: Long,
        numberMonitorEnabled: Boolean,
        numberMonitorRegion: RecognitionRegion,
        numberMonitorThreshold: Float,
        numberMonitorUpperLimit: Float,
        numberColorFilterEnabled: Boolean,
        numberColorHex: String,
        numberColorTolerance: Int,
        numberAbsenceTimeoutMs: Long,
        numberTriggerZoneId: String?,
        numberTriggerDelayMs: Long,
    ) {
        val safeZones = zones.map(RecognitionZone::normalized).take(MAX_ZONES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ZONES, encodeZones(safeZones))
            .putString(KEY_TARGET_PACKAGE, targetPackage.trim())
            .putFloat(KEY_THRESHOLD, matchThreshold.coerceIn(0.55f, 0.99f))
            .putFloat(KEY_CIRCLE_X_THRESHOLD, circleXThreshold.coerceIn(0.50f, 0.99f))
            .putFloat(KEY_BACK_ARROW_THRESHOLD, backArrowThreshold.coerceIn(0.50f, 0.99f))
            .putLong(KEY_COOLDOWN, clickCooldownMs.coerceIn(1_000L, 15_000L))
            .putBoolean(KEY_SHOW_CLICK_MARKER, showClickMarker)
            .putLong(KEY_RANDOM_CLICK_MAX, randomClickMaxMs.coerceIn(100L, 3_000L))
            .putBoolean(KEY_NUMBER_MONITOR_ENABLED, numberMonitorEnabled)
            .putFloat(KEY_NUMBER_MONITOR_LEFT, numberMonitorRegion.normalized().left)
            .putFloat(KEY_NUMBER_MONITOR_TOP, numberMonitorRegion.normalized().top)
            .putFloat(KEY_NUMBER_MONITOR_RIGHT, numberMonitorRegion.normalized().right)
            .putFloat(KEY_NUMBER_MONITOR_BOTTOM, numberMonitorRegion.normalized().bottom)
            .putFloat(KEY_NUMBER_MONITOR_THRESHOLD, numberMonitorThreshold.coerceIn(0f, 999_999f))
            .putFloat(KEY_NUMBER_MONITOR_UPPER_LIMIT, numberMonitorUpperLimit.coerceIn(0f, 999_999f))
            .putBoolean(KEY_NUMBER_COLOR_FILTER_ENABLED, numberColorFilterEnabled)
            .putString(KEY_NUMBER_COLOR_HEX, numberColorHex.trim().uppercase().take(9))
            .putInt(KEY_NUMBER_COLOR_TOLERANCE, numberColorTolerance.coerceIn(0, 255))
            .putLong(KEY_NUMBER_ABSENCE_TIMEOUT, numberAbsenceTimeoutMs.coerceIn(500L, 30_000L))
            .putString(KEY_NUMBER_TRIGGER_ZONE, encodeNumberTriggerZoneId(numberTriggerZoneId))
            .putLong(KEY_NUMBER_TRIGGER_DELAY, numberTriggerDelayMs.coerceIn(0L, 30_000L))
            .apply()
        invalidate(context)
    }

    private fun readLegacyZone(preferences: android.content.SharedPreferences): RecognitionZone {
        val region = RecognitionRegion(
            left = preferences.getFloat(KEY_REGION_LEFT, 0f),
            top = preferences.getFloat(KEY_REGION_TOP, 0f),
            right = preferences.getFloat(KEY_REGION_RIGHT, 1f),
            bottom = preferences.getFloat(KEY_REGION_BOTTOM, 1f),
        ).normalized()
        val mode = runCatching {
            TargetMode.valueOf(preferences.getString(KEY_MODE, TargetMode.TEXT.name)!!)
        }.getOrDefault(TargetMode.TEXT)
        val value = when (mode) {
            TargetMode.TEXT -> preferences.getString(KEY_TEXT, "")?.trim().orEmpty()
            TargetMode.IMAGE -> preferences.getString(KEY_URI, null).orEmpty()
            TargetMode.CIRCLE_X -> "circle_x"
            TargetMode.BACK_ARROW -> "back_arrow"
        }
        val label = if (mode == TargetMode.IMAGE) "原有參考圖片" else value
        val targets = if (value.isBlank()) emptyList() else listOf(
            RecognitionTarget("legacy-target", mode, value, label),
        )
        return RecognitionZone("zone-1", "區域 1", region, targets)
    }

    private fun encodeZones(zones: List<RecognitionZone>): String = JSONArray().apply {
        zones.forEach { zone ->
            put(JSONObject().apply {
                put("id", zone.id)
                put("name", zone.name)
                put("left", zone.region.left.toDouble())
                put("top", zone.region.top.toDouble())
                put("right", zone.region.right.toDouble())
                put("bottom", zone.region.bottom.toDouble())
                put("targets", JSONArray().apply {
                    zone.targets.forEach { target ->
                        put(JSONObject().apply {
                            put("id", target.id)
                            put("mode", target.mode.name)
                            put("value", target.value)
                            put("label", target.label)
                        })
                    }
                })
            })
        }
    }.toString()

    private fun decodeZones(raw: String): List<RecognitionZone>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (zoneIndex in 0 until minOf(array.length(), MAX_ZONES)) {
                val item = array.getJSONObject(zoneIndex)
                val targetArray = item.optJSONArray("targets") ?: JSONArray()
                val targets = buildList {
                    for (targetIndex in 0 until minOf(targetArray.length(), RecognitionZone.MAX_TARGETS_PER_ZONE)) {
                        val target = targetArray.getJSONObject(targetIndex)
                        val mode = runCatching { TargetMode.valueOf(target.optString("mode")) }
                            .getOrDefault(TargetMode.TEXT)
                        add(
                            RecognitionTarget(
                                id = target.optString("id", "target-$zoneIndex-$targetIndex"),
                                mode = mode,
                                value = target.optString("value"),
                                label = target.optString("label", target.optString("value")),
                            ),
                        )
                    }
                }
                add(
                    RecognitionZone(
                        id = item.optString("id", "zone-$zoneIndex"),
                        name = item.optString("name", "區域 ${zoneIndex + 1}"),
                        region = RecognitionRegion(
                            item.optDouble("left", 0.0).toFloat(),
                            item.optDouble("top", 0.0).toFloat(),
                            item.optDouble("right", 1.0).toFloat(),
                            item.optDouble("bottom", 1.0).toFloat(),
                        ),
                        targets = targets,
                    ),
                )
            }
        }
    }.getOrNull()

    internal fun migrateBundledBackArrowZone(zone: RecognitionZone): RecognitionZone {
        if (zone.targets.isEmpty()) return zone
        val bundledReturnUris = zone.targets.all { target ->
            target.mode == TargetMode.IMAGE && target.value in BUNDLED_RETURN_IMAGE_URIS
        }
        val originalReturnTargetIds = zone.id == BUNDLED_RETURN_ZONE_ID &&
            zone.targets.all { it.mode == TargetMode.IMAGE } &&
            zone.targets.mapTo(mutableSetOf()) { it.id } == BUNDLED_RETURN_TARGET_IDS
        if (!bundledReturnUris && !originalReturnTargetIds) return zone
        return zone.copy(
            targets = listOf(
                RecognitionTarget(
                    id = "${zone.id}-back-arrow",
                    mode = TargetMode.BACK_ARROW,
                    value = "back_arrow",
                    label = "返回箭頭",
                ),
            ),
        )
    }

    internal fun encodeNumberTriggerZoneId(zoneId: String?): String =
        zoneId ?: NO_NUMBER_TRIGGER_ZONE

    internal fun decodeNumberTriggerZoneId(storedValue: String?): String? = when (storedValue) {
        null, NO_NUMBER_TRIGGER_ZONE -> null
        else -> storedValue
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        invalidate(context)
    }

    private fun invalidate(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(cacheLock) {
            if (cachedPreferences === preferences) cachedSettings = null
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ScreenAutomationService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    const val MAX_ZONES = 8
    const val DEFAULT_TARGET_PACKAGE = "com.shopee.tw"
    private val BUNDLED_RETURN_IMAGE_URIS = (1..5)
        .mapTo(mutableSetOf()) { "asset://profile_images/return_$it.jpg" }
    private const val BUNDLED_RETURN_ZONE_ID = "zone-1787654151600-1"
    private val BUNDLED_RETURN_TARGET_IDS = setOf(
        "image-1787654347129-4",
        "image-1787654549640-5",
        "image-1787674110892-1",
        "image-1787674110907-2",
        "image-1787760722741-1",
    )
}
