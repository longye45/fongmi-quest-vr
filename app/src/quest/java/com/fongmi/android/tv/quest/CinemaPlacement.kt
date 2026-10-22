package com.fongmi.android.tv.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt

/** Saved pitch/roll relative to session yaw; no stale tracking-space position is persisted. */
data class CinemaPlacement(
    val calibrated: Boolean = false,
    val w: Float = 1f, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f,
    val height: Float = 0f,
    val tilt: Float = 0f,
) {
    fun normalized(geometry: CinemaGeometry = CinemaGeometry()): CinemaPlacement {
        val valid = listOf(w, x, y, z).all { it.isFinite() } &&
            listOf(w, x, y, z).all { kotlin.math.abs(it) <= 2f }
        val norm = if (valid) sqrt(w * w + x * x + y * y + z * z) else 0f
        val minHeight = maxOf(-.25f, .35f + geometry.width * 3.64f / 12f - geometry.centerHeight)
        val maxHeight = minOf(1.5f, 7.2f - geometry.centerHeight - geometry.width * 3.64f / 12f).coerceAtLeast(0f)
        return copy(
            calibrated = calibrated && norm > .001f,
            w = if (norm > .001f) w / norm else 1f,
            x = if (norm > .001f) x / norm else 0f,
            y = if (norm > .001f) y / norm else 0f,
            z = if (norm > .001f) z / norm else 0f,
            height = height.takeIf { it.isFinite() }?.coerceIn(minHeight, maxHeight) ?: 0f,
            tilt = tilt.takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 0f,
        )
    }

    fun orientation() = Quaternion(w, x, y, z)

    /** The eye stays inside the same room, even when looking straight up while lying down. */
    fun roomPose(geometry: CinemaGeometry, anchor: Pose): Pose =
        Pose(anchor.t - anchor.q * Vector3(0f, geometry.centerHeight, 0f), anchor.q.copy())

    fun screenPose(geometry: CinemaGeometry, frame: Boolean = false): Pose {
        val q = Quaternion(tilt, 0f, 0f)
        val center = Vector3(0f, geometry.centerHeight + height, geometry.distance)
        return Pose(if (frame) center + q * Vector3(0f, 0f, .08f) else center, q)
    }

    /** Re-anchor to this session's horizontal heading, retaining saved reclining direction. */
    fun restoredAnchor(viewer: Pose) = Pose(viewer.t.copy(), viewer.q.removePitchAndRoll() * orientation())

    companion object {
        fun usable(viewer: Pose): Boolean = listOf(viewer.t.x, viewer.t.y, viewer.t.z,
            viewer.q.w, viewer.q.x, viewer.q.y, viewer.q.z).all { it.isFinite() } &&
            viewer.q.norm().let { it.isFinite() && it > .5f && it < 1.5f }

        fun recentered(viewer: Pose): CinemaPlacement {
            val offset = viewer.q.removePitchAndRoll().inverse() * viewer.q
            return CinemaPlacement(true, offset.w, offset.x, offset.y, offset.z).normalized()
        }
    }
}
