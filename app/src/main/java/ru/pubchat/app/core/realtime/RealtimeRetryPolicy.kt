package ru.pubchat.app.core.realtime

import kotlin.math.min

/**
 * Reconnect only for transport/transient errors. Server-side access control
 * remains authoritative; a rejected session or membership is not retried.
 */
internal object RealtimeRetryPolicy {
    fun delayMillis(attempt: Int, jitter: Double = 0.5): Long {
        val exponential = 1_000L shl attempt.coerceIn(0, 5)
        val base = min(30_000L, exponential)
        // Avoid synchronized reconnect storms when many clients lose connectivity.
        val factor = 0.8 + 0.4 * jitter.coerceIn(0.0, 1.0)
        return (base * factor).toLong()
    }

    fun terminalHttpStatus(status: Int?): Boolean = status in setOf(401, 403, 404)
    fun terminalCloseCode(code: Int): Boolean = code == 4001 || code == 1008
}
