package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecognitionZoneTest {
    @Test
    fun targetPackageMustBeExplicitApplicationId() {
        assertTrue(isValidTargetPackage("com.shopee.tw"))
        org.junit.Assert.assertFalse(isValidTargetPackage(""))
        org.junit.Assert.assertFalse(isValidTargetPackage("shopee"))
        org.junit.Assert.assertFalse(isValidTargetPackage("com.shopee.tw/Activity"))
    }

    @Test
    fun disabledNumberTriggerZoneSurvivesPreferenceEncoding() {
        val stored = AutomationConfig.encodeNumberTriggerZoneId(null)

        assertEquals(null, AutomationConfig.decodeNumberTriggerZoneId(stored))
        assertEquals(null, AutomationConfig.decodeNumberTriggerZoneId(null))
        assertEquals("zone-1", AutomationConfig.decodeNumberTriggerZoneId("zone-1"))
    }

    @Test
    fun normalizedZoneKeepsMultipleTargetTypesAndDropsBlankValues() {
        val zone = RecognitionZone(
            id = "zone-a",
            name = "  右上角  ",
            region = RecognitionRegion(0.6f, 0.1f, 1.2f, 0.4f),
            targets = listOf(
                RecognitionTarget("text-1", TargetMode.TEXT, " 直播 "),
                RecognitionTarget("text-2", TargetMode.TEXT, "   "),
                RecognitionTarget("image-1", TargetMode.IMAGE, " content://image/1 ", " 按鈕圖 "),
            ),
        ).normalized()

        assertEquals("右上角", zone.name)
        assertEquals(2, zone.targets.size)
        assertEquals(listOf(TargetMode.TEXT, TargetMode.IMAGE), zone.targets.map { it.mode })
        assertEquals("直播", zone.targets[0].value)
        assertEquals("按鈕圖", zone.targets[1].label)
        assertEquals(1f, zone.region.right)
    }

    @Test
    fun bundledReturnImagesMigrateToGeometryWithoutChangingZoneBounds() {
        val original = RecognitionZone(
            id = "return-zone",
            name = "返回",
            region = RecognitionRegion(0f, 0f, 0.25f, 0.14f),
            targets = listOf(
                RecognitionTarget("one", TargetMode.IMAGE, "asset://profile_images/return_1.jpg"),
                RecognitionTarget("two", TargetMode.IMAGE, "asset://profile_images/return_5.jpg"),
            ),
        )

        val migrated = AutomationConfig.migrateBundledBackArrowZone(original)

        assertEquals(original.id, migrated.id)
        assertEquals(original.name, migrated.name)
        assertEquals(original.region, migrated.region)
        assertEquals(1, migrated.targets.size)
        assertEquals(TargetMode.BACK_ARROW, migrated.targets.single().mode)
        assertEquals("back_arrow", migrated.targets.single().value)
    }

    @Test
    fun customImageZoneIsNeverRepurposedAsBackArrow() {
        val original = RecognitionZone(
            id = "custom",
            name = "我的返回",
            region = RecognitionRegion.FULL,
            targets = listOf(RecognitionTarget("image", TargetMode.IMAGE, "content://user/back.png")),
        )

        assertEquals(original, AutomationConfig.migrateBundledBackArrowZone(original))
    }

    @Test
    fun originalPhoneReturnZoneMigratesEvenWhenItsImagesUsePersistedContentUris() {
        val ids = listOf(
            "image-1787654347129-4",
            "image-1787654549640-5",
            "image-1787674110892-1",
            "image-1787674110907-2",
            "image-1787760722741-1",
        )
        val original = RecognitionZone(
            id = "zone-1787654151600-1",
            name = "返回",
            region = RecognitionRegion(0f, 0f, 0.247f, 0.134f),
            targets = ids.mapIndexed { index, id ->
                RecognitionTarget(id, TargetMode.IMAGE, "content://media/image/${index + 1}")
            },
        )

        val migrated = AutomationConfig.migrateBundledBackArrowZone(original)

        assertEquals(TargetMode.BACK_ARROW, migrated.targets.single().mode)
        assertEquals(original.region, migrated.region)
    }

    @Test
    fun settingsCountTargetsAcrossMultipleZones() {
        val zones = listOf(
            RecognitionZone(
                "one",
                "區域 1",
                RecognitionRegion.FULL,
                listOf(
                    RecognitionTarget("a", TargetMode.TEXT, "直播"),
                    RecognitionTarget("b", TargetMode.TEXT, "購買"),
                ),
            ),
            RecognitionZone(
                "two",
                "區域 2",
                RecognitionRegion(0f, 0.5f, 1f, 1f),
                listOf(RecognitionTarget("c", TargetMode.IMAGE, "content://image/2")),
            ),
        )
        val settings = AutomationSettings(false, zones, 0.82f, 900L, 3_000L, true)

        assertEquals(2, settings.zones.size)
        assertEquals(3, settings.targetCount)
        assertTrue(settings.zones[0].region.contains(50f, 50f, 100, 100))
    }

    @Test
    fun clicksAreAllowedOnlyInsideZonesThatContainTargets() {
        val settings = AutomationSettings(
            enabled = true,
            zones = listOf(
                RecognitionZone(
                    "active",
                    "有效區域",
                    RecognitionRegion(0.5f, 0f, 1f, 0.5f),
                    listOf(RecognitionTarget("target", TargetMode.TEXT, "確認")),
                ),
                RecognitionZone("empty", "空白區域", RecognitionRegion.FULL, emptyList()),
            ),
            matchThreshold = 0.82f,
            scanIntervalMs = 900L,
            clickCooldownMs = 3_000L,
            showClickMarker = true,
        )

        assertTrue(settings.canClick(75f, 25f, 100, 100))
        org.junit.Assert.assertFalse(settings.canClick(25f, 25f, 100, 100))
        org.junit.Assert.assertFalse(settings.canClick(75f, 75f, 100, 100))
    }

    @Test
    fun recognitionBoundsMustBeFullyInsideConfiguredRegion() {
        val region = RecognitionRegion(0.25f, 0.25f, 0.75f, 0.75f)
        assertTrue(region.containsBounds(30f, 30f, 70f, 70f, 100, 100))
        org.junit.Assert.assertFalse(region.containsBounds(20f, 30f, 70f, 70f, 100, 100))
        org.junit.Assert.assertFalse(region.containsBounds(30f, 30f, 80f, 70f, 100, 100))
    }

    @Test
    fun randomGestureTimingUsesOneGlobalUpperBoundForDelayAndPress() {
        val maximum = 700L
        repeat(100) { seed ->
            val timing = randomGestureTiming(maximum, Random(seed))
            assertTrue(timing.startDelayMs in 0L..maximum)
            assertTrue(timing.pressDurationMs in 40L..maximum)
        }
    }

    @Test
    fun randomClickPointStaysInsideTargetAndConfiguredRegion() {
        val target = ClickBounds(100f, 200f, 300f, 400f)
        val region = RecognitionRegion(0.2f, 0f, 0.8f, 1f)
        repeat(100) { seed ->
            val point = randomClickPoint(target, region, 1_000, 1_000, Random(seed))!!
            assertTrue(point.x in 240f..260f)
            assertTrue(point.y in 280f..320f)
            assertTrue(region.contains(point.x, point.y, 1_000, 1_000))
        }
    }

    @Test
    fun randomClickPointRejectsTargetOutsideConfiguredRegion() {
        val point = randomClickPoint(
            ClickBounds(10f, 10f, 50f, 50f),
            RecognitionRegion(0.5f, 0.5f, 1f, 1f),
            1_000,
            1_000,
            Random(1),
        )
        org.junit.Assert.assertNull(point)
    }

    @Test
    fun circleXSafeBoundsStayTightlyAroundDetectedCenter() {
        val bounds = visualSafeClickBounds(
            centerX = 300f,
            centerY = 500f,
            width = 100f,
            height = 80f,
            halfSizeRatio = 0.06f,
        )
        assertEquals(294f, bounds.left, 0.001f)
        assertEquals(495.2f, bounds.top, 0.001f)
        assertEquals(306f, bounds.right, 0.001f)
        assertEquals(504.8f, bounds.bottom, 0.001f)
        repeat(50) { seed ->
            val point = randomClickPoint(bounds, RecognitionRegion.FULL, 720, 1_440, Random(seed))!!
            assertTrue(point.x in 298.8f..301.2f)
            assertTrue(point.y in 499.04f..500.96f)
        }
    }

    @Test
    fun secondFrameMustMatchPositionAndSize() {
        assertTrue(isSpatiallyConsistentVisualMatch(300f, 500f, 80f, 309f, 507f, 85f))
        org.junit.Assert.assertFalse(isSpatiallyConsistentVisualMatch(300f, 500f, 80f, 340f, 500f, 85f))
        org.junit.Assert.assertFalse(isSpatiallyConsistentVisualMatch(300f, 500f, 80f, 305f, 505f, 120f))
    }

    @Test
    fun visualTargetsOnlyHoldSwipeCountdownAtOrAboveTheirThreshold() {
        org.junit.Assert.assertFalse(shouldHoldSwipeCountdownForVisualTarget(0f, 0.88f))
        org.junit.Assert.assertFalse(shouldHoldSwipeCountdownForVisualTarget(0.879f, 0.88f))
        assertTrue(shouldHoldSwipeCountdownForVisualTarget(0.88f, 0.88f))
        assertTrue(shouldHoldSwipeCountdownForVisualTarget(0.94f, 0.91f))
    }

    @Test
    fun circleXAndBackArrowUseTheirOwnConfiguredThresholds() {
        val settings = AutomationSettings(
            enabled = false,
            zones = emptyList(),
            matchThreshold = 0.75f,
            scanIntervalMs = 900L,
            clickCooldownMs = 3_000L,
            showClickMarker = true,
            circleXThreshold = 0.91f,
            backArrowThreshold = 0.72f,
        )

        assertEquals(0.91f, configuredVisualThreshold(TargetMode.CIRCLE_X, settings), 0.001f)
        assertEquals(0.72f, configuredVisualThreshold(TargetMode.BACK_ARROW, settings), 0.001f)
        assertEquals(0.75f, configuredVisualThreshold(TargetMode.IMAGE, settings), 0.001f)
    }

    @Test
    fun prioritySwipeIgnoresItsTriggerZoneButWaitsForOtherRecognizedZones() {
        org.junit.Assert.assertFalse(hasRecognitionOutsideTriggerZone(emptySet(), "zone-1"))
        org.junit.Assert.assertFalse(hasRecognitionOutsideTriggerZone(setOf("zone-1"), "zone-1"))
        assertTrue(hasRecognitionOutsideTriggerZone(setOf("zone-1", "zone-2"), "zone-1"))
        assertTrue(hasRecognitionOutsideTriggerZone(setOf("zone-2"), "zone-1"))
    }

    @Test
    fun screenshotBoundsAreMappedToGestureDisplayCoordinates() {
        val mapped = mapClickBoundsToScreen(
            ClickBounds(100f, 200f, 300f, 400f),
            sourceWidth = 1_000,
            sourceHeight = 2_000,
            destinationWidth = 500,
            destinationHeight = 1_500,
        )!!
        assertEquals(50f, mapped.left)
        assertEquals(150f, mapped.top)
        assertEquals(150f, mapped.right)
        assertEquals(300f, mapped.bottom)
    }

    @Test
    fun decimalNumberExtractionSupportsDotAndCommaValues() {
        assertEquals(listOf(0.15, 0.2, 3.0), extractDecimalNumbers("門檻 0.15、目前 0,20 / 3"))
        assertEquals(listOf(0.2), extractDecimalNumbers("0 . 2"))
        assertEquals(listOf(0.2), extractDecimalNumbers("0 , 2"))
        assertEquals(0.2, extractSingleDecimalNumber("0 . 2"))
        assertEquals(listOf(-0.02, 3.0), extractDecimalNumbers("-0.02 +3.0"))
        assertEquals(null, extractSingleDecimalNumber("O"))
        assertEquals(null, extractSingleDecimalNumber("S"))
        assertEquals("0.15、0.2、3", formatRecognizedNumbers(listOf(0.15, 0.2, 3.0)))
    }

    @Test
    fun numberMonitorUsesFirstNumberFromCandidateClosestToRegionCenter() {
        val values = selectNumberMonitorValues(
            listOf(
                NumberTextCandidate("領取", centerDistanceSquared = 0.001, area = 100L),
                NumberTextCandidate("價格 99", centerDistanceSquared = 0.08, area = 300L),
                NumberTextCandidate("0 . 2", centerDistanceSquared = 0.01, area = 500L),
                NumberTextCandidate("0.3", centerDistanceSquared = 0.01, area = 900L),
            ),
        )

        assertEquals(listOf(0.2), values)
    }

    @Test
    fun splitDecimalElementsRebuildOneTightNumberToken() {
        val tokens = rebuildNumberTokens(
            listOf(
                NumberTextElement("0", ClickBounds(10f, 10f, 20f, 20f)),
                NumberTextElement(".", ClickBounds(21f, 10f, 24f, 20f)),
                NumberTextElement("2", ClickBounds(25f, 10f, 35f, 20f)),
                NumberTextElement("99", ClickBounds(100f, 10f, 120f, 20f)),
            ),
        )

        assertEquals(listOf("0.2", "99"), tokens.map { it.text })
        assertEquals(10f, tokens.first().bounds.left)
        assertEquals(35f, tokens.first().bounds.right)
    }

    @Test
    fun shortDecimalPointCanJoinDigitsUsingBaselineInsteadOfHeight() {
        val tokens = rebuildNumberTokens(
            listOf(
                NumberTextElement("0", ClickBounds(10f, 10f, 20f, 30f)),
                NumberTextElement(".", ClickBounds(21f, 26f, 24f, 30f)),
                NumberTextElement("2", ClickBounds(25f, 10f, 35f, 30f)),
            ),
        )

        assertEquals(listOf("0.2"), tokens.map { it.text })
    }

    @Test
    fun completedDecimalAndNeighbouringNumberStaySeparate() {
        val tokens = rebuildNumberTokens(
            listOf(
                NumberTextElement("0.2", ClickBounds(10f, 10f, 30f, 30f)),
                NumberTextElement("3", ClickBounds(40f, 10f, 50f, 30f)),
            ),
        )

        assertEquals(listOf("0.2", "3"), tokens.map { it.text })
    }

    @Test
    fun distantOrMisalignedNumberElementsAreNotMerged() {
        val tokens = rebuildNumberTokens(
            listOf(
                NumberTextElement("1", ClickBounds(10f, 10f, 20f, 20f)),
                NumberTextElement("2", ClickBounds(21f, 30f, 31f, 40f)),
            ),
        )

        assertEquals(listOf("1", "2"), tokens.map { it.text })
    }

    @Test
    fun mixedElementWithoutSymbolBoundsIsRejectedAsAmbiguous() {
        val tokens = rebuildNumberTokens(
            listOf(
                NumberTextElement("S0.2", ClickBounds(10f, 10f, 42f, 24f)),
                NumberTextElement("幣0.2", ClickBounds(70f, 10f, 106f, 24f)),
                NumberTextElement("0.2元", ClickBounds(134f, 10f, 170f, 24f)),
            ),
        )

        assertTrue(tokens.isEmpty())
    }

    @Test
    fun mixedSymbolBoxesKeepOnlyTheExactNumericSubstring() {
        val tokens = rebuildNumberTokens(
            listOf(
                NumberTextElement("S", ClickBounds(10f, 10f, 18f, 24f)),
                NumberTextElement("0", ClickBounds(20f, 10f, 28f, 24f)),
                NumberTextElement(".", ClickBounds(29f, 20f, 32f, 24f)),
                NumberTextElement("2", ClickBounds(33f, 10f, 41f, 24f)),
            ),
        )

        assertEquals(listOf("0.2"), tokens.map { it.text })
        assertEquals(20f, tokens.single().bounds.left)
    }

    @Test
    fun candidateContainingTwoNumbersIsNotSilentlyTruncated() {
        assertTrue(
            selectNumberMonitorValues(
                listOf(NumberTextCandidate("0.2 3", centerDistanceSquared = 0.0, area = 100L)),
            ).isEmpty(),
        )
    }

    @Test
    fun numberObservationClassifiesRejectedNumericEvidenceAsInvalid() {
        val observation = classifyNumberObservation(
            values = emptyList(),
            hasNumericText = true,
            invalidReasons = setOf(NumberMonitorTracker.InvalidReason.COLOR_UNCERTAIN),
        )

        assertEquals(
            NumberMonitorTracker.Observation.Invalid(NumberMonitorTracker.InvalidReason.COLOR_UNCERTAIN),
            observation,
        )
    }

    @Test
    fun numberObservationTreatsSuccessfulNoNumberFrameAsMissing() {
        assertEquals(
            NumberMonitorTracker.Observation.Missing,
            classifyNumberObservation(emptyList(), hasNumericText = false, invalidReasons = emptySet()),
        )
    }

    @Test
    fun outsideLineNumberEvidenceDoesNotPolluteEmptyNumberRoi() {
        val evidence = collectNumberRoiEvidence(
            lineText = ".",
            lineBounds = ClickBounds(600f, 360f, 620f, 380f),
            elements = emptyList(),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertFalse(evidence.hasNumericText)
        assertFalse(evidence.missingNumericBounds)
        assertEquals(NumberMonitorTracker.Observation.Missing,
            classifyNumberObservation(emptyList(), evidence.hasNumericText, emptySet()))
    }

    @Test
    fun wideLineDrillsIntoOutsideElementInsteadOfUsingLineText() {
        val evidence = collectNumberRoiEvidence(
            lineText = "0.2",
            lineBounds = ClickBounds(400f, 340f, 700f, 560f),
            elements = listOf(
                NumberTextNode("0.2", ClickBounds(600f, 360f, 630f, 390f)),
            ),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertFalse(evidence.hasNumericText)
        assertFalse(evidence.missingNumericBounds)
        assertEquals(setOf(NumberRoiLocation.OUTSIDE), evidence.numericLocations)
        assertEquals(NumberMonitorTracker.Observation.Missing,
            classifyNumberObservation(emptyList(), evidence.hasNumericText, emptySet()))
    }

    @Test
    fun outsideParentMakesMissingChildBoundsSafeToIgnore() {
        val evidence = collectNumberRoiEvidence(
            lineText = "0.2",
            lineBounds = ClickBounds(400f, 340f, 700f, 560f),
            elements = listOf(
                NumberTextNode("0.2", ClickBounds(600f, 360f, 630f, 390f),
                    children = listOf(NumberTextNode("0.2", bounds = null))),
            ),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertFalse(evidence.hasNumericText)
        assertFalse(evidence.missingNumericBounds)
        assertEquals(setOf(NumberRoiLocation.OUTSIDE), evidence.numericLocations)
    }

    @Test
    fun crossingSymbolIsRetainedAsConservativeInvalidEvidence() {
        val evidence = collectNumberRoiEvidence(
            lineText = "0.2",
            lineBounds = ClickBounds(470f, 340f, 590f, 560f),
            elements = listOf(
                NumberTextNode("0.2", ClickBounds(470f, 360f, 590f, 390f),
                    children = listOf(NumberTextNode("0.2", ClickBounds(560f, 360f, 590f, 390f)))),
            ),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertTrue(evidence.hasNumericText)
        assertTrue(NumberRoiLocation.CROSSING in evidence.numericLocations)
        assertEquals(NumberMonitorTracker.Observation.Invalid(NumberMonitorTracker.InvalidReason.OUTSIDE_ROI),
            classifyNumberObservation(
                values = emptyList(),
                hasNumericText = evidence.hasNumericText,
                invalidReasons = setOf(NumberMonitorTracker.InvalidReason.OUTSIDE_ROI),
            ))
    }

    @Test
    fun inRoiSymbolsReachTokenRebuildThroughTheEvidenceHelper() {
        val evidence = collectNumberRoiEvidence(
            lineText = "0.2",
            lineBounds = ClickBounds(490f, 350f, 570f, 390f),
            elements = listOf(
                NumberTextNode("0.2", ClickBounds(500f, 360f, 540f, 385f),
                    children = listOf(
                        NumberTextNode("0", ClickBounds(500f, 360f, 510f, 385f)),
                        NumberTextNode(".", ClickBounds(511f, 375f, 514f, 385f)),
                        NumberTextNode("2", ClickBounds(515f, 360f, 525f, 385f)),
                    )),
            ),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertEquals(listOf("0.2"), rebuildNumberTokens(evidence.elements).map { it.text })
        assertEquals(setOf(NumberRoiLocation.INSIDE), evidence.numericLocations)
    }

    @Test
    fun unknownSymbolBoundsRemainInvalidInsteadOfBecomingMissing() {
        val evidence = collectNumberRoiEvidence(
            lineText = "0.2",
            lineBounds = ClickBounds(490f, 350f, 560f, 390f),
            elements = listOf(
                NumberTextNode("0.2", ClickBounds(500f, 360f, 540f, 385f),
                    children = listOf(NumberTextNode("0.2", bounds = null))),
            ),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertTrue(evidence.hasNumericText)
        assertTrue(evidence.missingNumericBounds)
        assertTrue(NumberRoiLocation.UNKNOWN in evidence.numericLocations)
        assertEquals(NumberMonitorTracker.Observation.Invalid(NumberMonitorTracker.InvalidReason.MISSING_BOUNDS),
            classifyNumberObservation(emptyList(), evidence.hasNumericText,
                setOf(NumberMonitorTracker.InvalidReason.MISSING_BOUNDS)))
    }

    @Test
    fun isolatedInsidePunctuationIsNotAssumedToBeNumberFailure() {
        val evidence = collectNumberRoiEvidence(
            lineText = ".",
            lineBounds = ClickBounds(500f, 360f, 520f, 380f),
            elements = listOf(NumberTextNode(".", ClickBounds(500f, 360f, 520f, 380f))),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertFalse(evidence.hasNumericText)
        assertEquals(NumberMonitorTracker.Observation.Missing,
            classifyNumberObservation(emptyList(), evidence.hasNumericText, emptySet()))
    }

    @Test
    fun insideTruncatedDecimalRetainsNumericContextAsParseInvalid() {
        val evidence = collectNumberRoiEvidence(
            lineText = "0.",
            lineBounds = ClickBounds(500f, 360f, 530f, 380f),
            elements = listOf(NumberTextNode("0.", ClickBounds(500f, 360f, 530f, 380f))),
            numberRegion = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f),
            bitmapWidth = 720,
            bitmapHeight = 1_560,
        )

        assertTrue(evidence.hasNumericText)
        assertEquals(NumberMonitorTracker.Observation.Invalid(NumberMonitorTracker.InvalidReason.PARSE_AMBIGUOUS),
            classifyNumberObservation(emptyList(), evidence.hasNumericText,
                setOf(NumberMonitorTracker.InvalidReason.PARSE_AMBIGUOUS)))
    }

    @Test
    fun thirtySecondOutsideRoiNoiseStillReachesOneAbsenceDecision() {
        val region = RecognitionRegion(0.68f, 0.22f, 0.795f, 0.35f)
        val tracker = NumberMonitorTracker()
        var swipeCount = 0

        repeat(61) { index ->
            val evidence = collectNumberRoiEvidence(
                lineText = "0.2",
                lineBounds = ClickBounds(600f, 360f, 630f, 390f),
                elements = emptyList(),
                numberRegion = region,
                bitmapWidth = 720,
                bitmapHeight = 1_560,
            )
            assertEquals(NumberMonitorTracker.Observation.Missing,
                classifyNumberObservation(emptyList(), evidence.hasNumericText, emptySet()))
            if (tracker.observe(
                    nowMs = index * 500L,
                    observation = NumberMonitorTracker.Observation.Missing,
                    roiFingerprint = 99L,
                    threshold = 0.15f,
                    upperLimit = 3f,
                    absenceTimeoutMs = 6_000L,
                ) == NumberMonitorTracker.Action.SWIPE_ABSENT
            ) {
                swipeCount++
            }
        }

        assertEquals(1, swipeCount)
    }

    @Test
    fun nonNumericElementSeparatesNearbyNumbersLikeVersion112() {
        val tokens = rebuildNumberTokens(listOf(
            NumberTextElement("0.2", ClickBounds(10f, 10f, 30f, 24f)),
            NumberTextElement("元", ClickBounds(31f, 10f, 40f, 24f)),
            NumberTextElement("3", ClickBounds(41f, 10f, 51f, 24f)),
        ))
        assertEquals(listOf("0.2", "3"), tokens.map { it.text })
    }

    @Test
    fun mixedTextWithoutDigitsDoesNotCreateNumberToken() {
        assertTrue(rebuildNumberTokens(listOf(
            NumberTextElement("倒數", ClickBounds(10f, 10f, 30f, 24f)),
        )).isEmpty())
    }

    @Test
    fun numberColorFilterRequiresMeaningfulCoverageInsteadOfThreePixels() {
        assertFalse(hasSufficientNumberColorCoverage(matches = 3, samples = 1_000))
        assertTrue(hasSufficientNumberColorCoverage(matches = 15, samples = 1_000))
    }

    @Test
    fun numberPriorityStillRunsVisualSafetyScanAtBoundedInterval() {
        assertTrue(shouldRunVisualSafetyScan(true, Long.MIN_VALUE, 10_000L, false))
        // Even if the full scan itself took longer than the interval, the next
        // frame must be a fast number-first pass.
        assertFalse(shouldRunVisualSafetyScan(true, 10_000L, 15_000L, true))
        assertTrue(shouldRunVisualSafetyScan(true, 10_000L, 15_000L, false))
        assertTrue(shouldRunVisualSafetyScan(false, 10_000L, 10_001L, true))
    }

    @Test
    fun outOfRangeNumberRequiresTwoSimilarFramesWithSameSettings() {
        val confirmation = NumberSwipeConfirmation()

        org.junit.Assert.assertFalse(confirmation.observe(0.10, 0.15f, 3f))
        assertTrue(confirmation.observe(0.11, 0.15f, 3f))
        org.junit.Assert.assertFalse(confirmation.observe(0.10, 0.20f, 3f))
        org.junit.Assert.assertFalse(confirmation.observe(0.10, 0.15f, 3f))
        assertTrue(confirmation.observe(0.10, 0.15f, 3f))
    }

    @Test
    fun numberBelowThresholdSwipesWhileHigherNumberStays() {
        assertEquals(NumberMonitorDecision.SWIPE_UP, decideNumberMonitorAction(listOf(0.1), 0.15f))
        assertEquals(NumberMonitorDecision.STAY, decideNumberMonitorAction(listOf(0.1, 0.2), 0.15f))
        assertEquals(NumberMonitorDecision.SWIPE_UP, decideNumberMonitorAction(listOf(1.2), 0.15f, 1.0f))
        assertEquals(NumberMonitorDecision.SWIPE_UP, decideNumberMonitorAction(listOf(0.1), 0.15f, 1.0f))
        assertEquals(NumberMonitorDecision.NO_NUMBERS, decideNumberMonitorAction(emptyList(), 0.15f))
    }

    @Test
    fun randomSwipeAlwaysMovesUpWithSafeRandomDisplacement() {
        repeat(100) { seed ->
            val swipe = randomSwipeSpec(800L, Random(seed))
            assertTrue(swipe.delayMs in 0L..800L)
            assertTrue(swipe.durationMs in 360L..440L)
            assertTrue(swipe.startXRatio in 0.35f..0.65f)
            assertTrue(swipe.endXRatio in 0.22f..0.78f)
            assertTrue(swipe.startYRatio in 0.82f..0.86f)
            assertTrue(swipe.endYRatio in 0.08f..0.12f)
            assertTrue(swipe.endYRatio < swipe.startYRatio)
        }
    }
}
