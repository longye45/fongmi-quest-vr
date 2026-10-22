package com.fongmi.android.tv.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class CinemaPlacementTest {
    private fun near(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, .0001f)
        assertEquals(expected.y, actual.y, .0001f)
        assertEquals(expected.z, actual.z, .0001f)
    }

    @Test fun screenLandsInFrontOfEyesForSeatedReclinedAndSupineViewers() {
        val g = CinemaGeometry()
        for (pitch in listOf(0f, -45f, -90f)) {
            for (yaw in listOf(0f, 90f, -120f)) {
                val viewer = Pose(Vector3(2f, .7f, -3f), Quaternion(pitch, yaw, 12f))
                val placement = CinemaPlacement.recentered(viewer)
                val rig = placement.roomPose(g, viewer)
                near(viewer.t + viewer.forward() * g.distance, (rig * placement.screenPose(g)).t)
                // Repositioning geometry never moves the calibrated eye out of the room pivot.
                near(viewer.t, rig * Vector3(0f, g.centerHeight, 0f))
            }
        }
    }

    @Test fun frameGapFollowsScreenNormalAfterTilting() {
        val g = CinemaGeometry(10f, 3f)
        for (tilt in listOf(-20f, 0f, 20f)) {
            val p = CinemaPlacement(tilt = tilt)
            val screen = p.screenPose(g)
            near(screen.forward() * .08f, p.screenPose(g, true).t - screen.t)
        }
    }

    @Test fun restoredRecliningPoseUsesNewSessionPositionAndYaw() {
        val old = Pose(Vector3(-4f, 1f, 8f), Quaternion(-55f, 35f, 8f))
        val saved = CinemaPlacement.recentered(old)
        val fresh = Pose(Vector3(9f, .8f, -2f), Quaternion(0f, -70f, 0f))
        val restored = saved.restoredAnchor(fresh)
        near(fresh.t, restored.t)
        val relative = fresh.q.removePitchAndRoll().inverse() * restored.q
        assertTrue(relative.isWithinAngleDegrees(saved.orientation(), .1f))
        assertTrue(restored.forward().y > .7f)
    }

    @Test fun invalidSavedRotationsAndOffsetsNeverReachRenderer() {
        val p = CinemaPlacement(true, Float.NaN, 0f, 0f, 0f, Float.POSITIVE_INFINITY, Float.NaN).normalized()
        assertFalse(p.calibrated)
        assertEquals(1f, p.w, 0f)
        assertEquals(0f, p.height, 0f)
        assertEquals(0f, p.tilt, 0f)
        assertFalse(CinemaPlacement(true, 0f, 0f, 0f, 0f).normalized().calibrated)
    }

    @Test fun invalidTrackingPoseCannotBeUsedAsRecenterAnchor() {
        assertFalse(CinemaPlacement.usable(Pose(Vector3(Float.NaN, 0f, 0f))))
        assertFalse(CinemaPlacement.usable(Pose(Vector3(0f), Quaternion(0f, 0f, 0f, 0f))))
        assertTrue(CinemaPlacement.usable(Pose(Vector3(0f, .3f, 0f), Quaternion(-90f, 0f, 0f))))
    }

    @Test fun screenFrameRemainsWithinRoomHeightAtEveryWidthAndHeightLimit() {
        for (width in listOf(3f, 6f, 10f)) {
            val g = CinemaGeometry(width, 6f)
            for (height in listOf(-10f, 10f)) {
                val p = CinemaPlacement(height = height).normalized(g)
                val frameHalfHeight = width * 3.64f / 12f
                assertTrue(g.centerHeight + p.height - frameHalfHeight > .3f)
                assertTrue(g.centerHeight + p.height + frameHalfHeight <= 7.201f)
            }
        }
    }
}
