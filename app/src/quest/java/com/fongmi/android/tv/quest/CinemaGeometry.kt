package com.fongmi.android.tv.quest

/** Meter-based limits keep the screen in front of the viewer and the controls reachable. */
data class CinemaGeometry(val width: Float = 6f, val distance: Float = 6f) {
    // Keep even the largest 16:9 screen above the newly modeled floor.
    val centerHeight: Float get() = maxOf(2.6f, width * 9f / 32f + .65f)
    fun resized(delta: Float) = copy(width = (width + delta).coerceIn(3f, 10f))
    fun moved(delta: Float) = copy(distance = (distance + delta).coerceIn(3f, 12f))

    companion object {
        fun restored(width: Float, distance: Float) = CinemaGeometry(
            width.takeIf { it.isFinite() }?.coerceIn(3f, 10f) ?: 6f,
            distance.takeIf { it.isFinite() }?.coerceIn(3f, 12f) ?: 6f,
        )
    }
}
