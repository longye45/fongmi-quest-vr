package com.fongmi.android.tv.quest

import org.junit.Assert.*
import org.junit.Test

class CinemaProfileTest {
    @Test fun upgradingImportsExistingRoomSettingsWithoutOverwritingTheOtherRoom() {
        val old = mapOf("room" to "cinema", "width" to 8f, "distance" to 9f,
            "brightness" to .1f, "starlight" to false, "star-brightness" to .8f, "auto-next" to false)
        val cinema = CinemaProfile.restored(old, CinemaRoom.PRIVATE_CINEMA)
        assertEquals(8f, cinema.geometry.width, 0f)
        assertFalse(cinema.appearance.starlight)
        assertEquals(.8f, cinema.appearance.starBrightness, 0f)
        val space = CinemaProfile.restored(old, CinemaRoom.ORBITAL_LOUNGE)
        assertEquals(6f, space.geometry.width, 0f)
        assertEquals(.35f, space.appearance.brightness, 0f)
        assertFalse(cinema.values().containsKey("auto-next"))
    }

    @Test fun alternatingScenesAndRestartingPreservesIndependentProfiles() {
        val cinema = CinemaProfile(CinemaGeometry(8f, 9f), CinemaAppearance(brightness = .1f, starBrightness = .8f),
            CinemaPlacement(calibrated = true, height = .5f, tilt = -15f))
        val space = CinemaProfile(CinemaGeometry(4f, 5f), CinemaAppearance(CinemaRoom.ORBITAL_LOUNGE, .7f, false, false),
            CinemaPlacement(height = -.25f, tilt = 10f))
        val disk = cinema.values() + space.values()
        assertEquals(cinema.normalized(), CinemaProfile.restored(disk, CinemaRoom.PRIVATE_CINEMA))
        assertEquals(space.normalized(), CinemaProfile.restored(disk, CinemaRoom.ORBITAL_LOUNGE))
        assertEquals("space", disk["room"])
    }

    @Test fun malformedPreferencesFallBackWithoutDiscardingValidSettings() {
        val disk = mapOf("profile.cinema.version" to 1, "profile.cinema.width" to Float.NaN,
            "profile.cinema.distance" to 99f, "profile.cinema.brightness" to "invalid",
            "profile.cinema.shadows" to false, "profile.cinema.qw" to 0f,
            "profile.cinema.calibrated" to true, "profile.cinema.tilt" to 500f)
        val restored = CinemaProfile.restored(disk, CinemaRoom.PRIVATE_CINEMA)
        assertEquals(CinemaGeometry(6f, 12f), restored.geometry)
        assertFalse(restored.appearance.shadows)
        assertEquals(.35f, restored.appearance.brightness, 0f)
        assertFalse(restored.placement.calibrated)
        assertEquals(20f, restored.placement.tilt, 0f)
    }
}
