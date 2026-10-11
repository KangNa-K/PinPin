package com.pinpin.app.ball

/** A monotonic hold deadline; jitter within the existing tolerance does not reset it. */
internal class StationaryArmTracker(private val delayMs: Long, tolerancePx: Float) {
    private val toleranceSquared = tolerancePx * tolerancePx
    private var anchorX = 0f
    private var anchorY = 0f
    private var startedAt: Long? = null

    fun start(x: Float, y: Float, now: Long) {
        anchorX = x
        anchorY = y
        startedAt = now
    }

    fun move(x: Float, y: Float, now: Long): Boolean {
        if (startedAt == null) return false
        val dx = x - anchorX
        val dy = y - anchorY
        if (dx * dx + dy * dy <= toleranceSquared) return false
        start(x, y, now)
        return true
    }

    fun remainingMs(now: Long): Long = startedAt?.let {
        (delayMs - (now - it).coerceAtLeast(0)).coerceAtLeast(0)
    } ?: delayMs

    fun progress(now: Long): Float = if (startedAt == null) 0f else
        (1f - remainingMs(now).toFloat() / delayMs).coerceIn(0f, 1f)

    fun cancel() { startedAt = null }
}
