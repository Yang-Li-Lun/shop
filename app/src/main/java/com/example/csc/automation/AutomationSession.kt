package com.example.csc.automation

import java.util.concurrent.atomic.AtomicLong

/** Identity carried across asynchronous capture, recognition and gesture work. */
data class AutomationSession(
    val generation: Long,
    val targetPackage: String,
    val foregroundPackage: String?,
    val configSignature: Int,
    val projectionGeneration: Long,
)

data class ActionToken(
    val session: AutomationSession,
    val zoneId: String?,
    val targetId: String?,
    val actionId: Long,
)

enum class ActionResult {
    Completed,
    Rejected,
    Cancelled,
    TimedOut,
    Stale,
}

/** Small synchronized session gate; a changed input invalidates every older token. */
class AutomationSessionGate {
    private val actionIds = AtomicLong(0L)
    private var current: AutomationSession? = null
    private var nextGeneration = 0L

    @Synchronized
    fun update(
        targetPackage: String,
        foregroundPackage: String?,
        configSignature: Int,
        projectionGeneration: Long,
    ): AutomationSession {
        val previous = current
        if (previous == null || previous.targetPackage != targetPackage ||
            previous.foregroundPackage != foregroundPackage ||
            previous.configSignature != configSignature ||
            previous.projectionGeneration != projectionGeneration
        ) {
            nextGeneration++
            current = AutomationSession(
                nextGeneration,
                targetPackage,
                foregroundPackage,
                configSignature,
                projectionGeneration,
            )
        }
        return current!!
    }

    @Synchronized
    fun invalidate(): AutomationSession {
        val previous = current ?: AutomationSession(0L, "", null, 0, 0L)
        nextGeneration++
        return AutomationSession(
            nextGeneration,
            previous.targetPackage,
            previous.foregroundPackage,
            previous.configSignature,
            previous.projectionGeneration,
        ).also { current = it }
    }

    @Synchronized
    fun token(zoneId: String? = null, targetId: String? = null): ActionToken? =
        current?.let { ActionToken(it, zoneId, targetId, actionIds.incrementAndGet()) }

    @Synchronized
    fun isCurrent(session: AutomationSession): Boolean = current == session

    @Synchronized
    fun isCurrent(token: ActionToken): Boolean = current == token.session
}

internal fun AutomationSettings.sessionSignature(): Int = hashCode()
