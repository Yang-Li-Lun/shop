package com.example.csc.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSessionTest {
    @Test
    fun changedSettingsOrForegroundInvalidateOldToken() {
        val gate = AutomationSessionGate()
        val first = gate.update("com.shopee.tw", "com.shopee.tw", 1, 4L)
        val token = gate.token("zone-1", "target-1")!!
        assertTrue(gate.isCurrent(token))
        gate.update("com.shopee.tw", "com.shopee.tw", 2, 4L)
        assertFalse(gate.isCurrent(token))
        assertNotEquals(first.generation, gate.update("com.shopee.tw", "other", 2, 4L).generation)
    }

    @Test
    fun invalidationMakesDestroyCallbacksStale() {
        val gate = AutomationSessionGate()
        gate.update("com.shopee.tw", "com.shopee.tw", 1, 0L)
        val token = gate.token()!!
        gate.invalidate()
        assertFalse(gate.isCurrent(token))
    }
}
