package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTerminalCoordinatorTest {
    private fun token(generation: Long, actionId: Long): ActionToken = ActionToken(
        session = AutomationSession(generation, "com.shopee.tw", "com.shopee.tw", 1, 0L),
        zoneId = "zone-1",
        targetId = null,
        actionId = actionId,
    )

    @Test
    fun staleTerminalReleasesItsOwnClickAndIsIdempotent() {
        val coordinator = GestureTerminalCoordinator()
        val oldToken = token(1L, 1L)

        assertTrue(coordinator.arm(oldToken, GestureKind.CLICK))
        assertEquals(
            GestureTerminalResult(GestureKind.CLICK, GestureTerminalStatus.STALE),
            coordinator.finish(oldToken) { false },
        )
        assertEquals(
            GestureTerminalResult(null, GestureTerminalStatus.NOT_PENDING),
            coordinator.finish(oldToken) { true },
        )
    }

    @Test
    fun lateOldCallbackCannotFinishNewGesture() {
        val coordinator = GestureTerminalCoordinator()
        val oldToken = token(1L, 1L)
        val newToken = token(2L, 2L)

        assertTrue(coordinator.arm(oldToken, GestureKind.SWIPE))
        assertTrue(coordinator.clear(oldToken))
        assertTrue(coordinator.arm(newToken, GestureKind.CLICK))
        assertEquals(
            GestureTerminalResult(null, GestureTerminalStatus.NOT_PENDING),
            coordinator.finish(oldToken) { false },
        )
        assertEquals(
            GestureTerminalResult(GestureKind.CLICK, GestureTerminalStatus.CURRENT),
            coordinator.finish(newToken) { true },
        )
        assertFalse(coordinator.clear(newToken))
    }

    @Test
    fun duplicateArmDoesNotReplaceTheCurrentOwner() {
        val coordinator = GestureTerminalCoordinator()
        val first = token(1L, 1L)
        val second = token(1L, 2L)

        assertTrue(coordinator.arm(first, GestureKind.CLICK))
        assertFalse(coordinator.arm(second, GestureKind.SWIPE))
        assertEquals(
            GestureTerminalResult(GestureKind.CLICK, GestureTerminalStatus.CURRENT),
            coordinator.finish(first) { true },
        )
        assertEquals(
            GestureTerminalResult(null, GestureTerminalStatus.NOT_PENDING),
            coordinator.finish(second) { true },
        )
    }
}
