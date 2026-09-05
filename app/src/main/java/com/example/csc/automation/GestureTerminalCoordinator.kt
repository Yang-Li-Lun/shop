package com.example.csc.automation

enum class GestureKind {
    CLICK,
    SWIPE,
}

enum class GestureTerminalStatus {
    CURRENT,
    STALE,
    NOT_PENDING,
}

data class GestureTerminalResult(
    val kind: GestureKind?,
    val status: GestureTerminalStatus,
)

/**
 * Owns the identity of the one gesture callback currently being watched.
 *
 * The service still owns Android state and the watchdog. This coordinator only makes terminal
 * callback ownership idempotent, so a stale callback cannot release a newer gesture.
 */
class GestureTerminalCoordinator {
    private data class PendingGesture(
        val token: ActionToken,
        val kind: GestureKind,
    )

    private var pending: PendingGesture? = null

    @Synchronized
    fun arm(token: ActionToken, kind: GestureKind): Boolean {
        if (pending != null) return false
        pending = PendingGesture(token, kind)
        return true
    }

    @Synchronized
    fun finish(token: ActionToken, isCurrent: () -> Boolean): GestureTerminalResult {
        val owned = pending ?: return GestureTerminalResult(null, GestureTerminalStatus.NOT_PENDING)
        if (owned.token != token) return GestureTerminalResult(null, GestureTerminalStatus.NOT_PENDING)
        pending = null
        return GestureTerminalResult(
            kind = owned.kind,
            status = if (isCurrent()) GestureTerminalStatus.CURRENT else GestureTerminalStatus.STALE,
        )
    }

    @Synchronized
    fun clear(token: ActionToken? = null): Boolean {
        val owned = pending ?: return false
        if (token != null && owned.token != token) return false
        pending = null
        return true
    }
}
