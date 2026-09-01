package com.example.csc.automation

import java.util.concurrent.atomic.AtomicReference

enum class AutomationPhase {
    IDLE,
    RECOGNIZING,
    CLICK_DELAY,
    CLICKING,
    SWIPE_DELAY,
    SWIPING,
    COOLDOWN,
}

/** Single source of truth for gesture priority across asynchronous callbacks. */
class ActionStateMachine {
    private val phaseRef = AtomicReference(AutomationPhase.IDLE)

    val phase: AutomationPhase get() = phaseRef.get()

    fun tryStartRecognition(): Boolean =
        phaseRef.compareAndSet(AutomationPhase.IDLE, AutomationPhase.RECOGNIZING) ||
            phaseRef.compareAndSet(AutomationPhase.COOLDOWN, AutomationPhase.RECOGNIZING)

    fun recognitionFinished() {
        phaseRef.compareAndSet(AutomationPhase.RECOGNIZING, AutomationPhase.IDLE)
    }

    fun beginClickDelay(): Boolean {
        while (true) {
            val current = phaseRef.get()
            if (current == AutomationPhase.SWIPE_DELAY || current == AutomationPhase.SWIPING) return false
            if (current == AutomationPhase.CLICK_DELAY || current == AutomationPhase.CLICKING) return false
            if (phaseRef.compareAndSet(current, AutomationPhase.CLICK_DELAY)) return true
        }
    }

    fun beginClicking(): Boolean = phaseRef.compareAndSet(AutomationPhase.CLICK_DELAY, AutomationPhase.CLICKING)

    fun armPrioritySwipe(): Boolean {
        while (true) {
            val current = phaseRef.get()
            if (current == AutomationPhase.CLICK_DELAY || current == AutomationPhase.CLICKING ||
                current == AutomationPhase.SWIPE_DELAY || current == AutomationPhase.SWIPING
            ) {
                return false
            }
            if (phaseRef.compareAndSet(current, AutomationPhase.SWIPE_DELAY)) return true
        }
    }

    fun beginSwiping(): Boolean {
        val current = phaseRef.get()
        return when (current) {
            AutomationPhase.SWIPE_DELAY -> phaseRef.compareAndSet(current, AutomationPhase.SWIPING)
            AutomationPhase.IDLE, AutomationPhase.COOLDOWN, AutomationPhase.RECOGNIZING -> {
                phaseRef.compareAndSet(current, AutomationPhase.SWIPING)
            }
            else -> false
        }
    }

    fun gestureFinished(cooldown: Boolean = true) {
        phaseRef.set(if (cooldown) AutomationPhase.COOLDOWN else AutomationPhase.IDLE)
    }

    fun cancel() {
        phaseRef.set(AutomationPhase.IDLE)
    }

    fun cancelSwipe(): Boolean {
        while (true) {
            val current = phaseRef.get()
            if (current != AutomationPhase.SWIPE_DELAY && current != AutomationPhase.SWIPING) return false
            if (phaseRef.compareAndSet(current, AutomationPhase.IDLE)) return true
        }
    }

    fun cancelClick(): Boolean {
        while (true) {
            val current = phaseRef.get()
            if (current != AutomationPhase.CLICK_DELAY && current != AutomationPhase.CLICKING) return false
            if (phaseRef.compareAndSet(current, AutomationPhase.IDLE)) return true
        }
    }

    fun blocksRecognition(): Boolean = when (phase) {
        AutomationPhase.CLICK_DELAY,
        AutomationPhase.CLICKING,
        AutomationPhase.SWIPE_DELAY,
        AutomationPhase.SWIPING,
        -> true
        else -> false
    }
}
