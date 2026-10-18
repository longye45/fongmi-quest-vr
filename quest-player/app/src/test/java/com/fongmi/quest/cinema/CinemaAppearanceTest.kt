package com.fongmi.quest.cinema

import com.fongmi.android.tv.quest.CinemaAppearance
import com.fongmi.android.tv.quest.CinemaGeometry
import com.fongmi.android.tv.quest.CinemaRoom
import org.junit.Assert.*
import org.junit.Test

class CinemaAppearanceTest {
    @Test fun restoresKnownRoomsAndSafelyFallsBackAfterAssetChanges() {
        assertEquals(CinemaRoom.ORBITAL_LOUNGE, CinemaRoom.fromKey("space"))
        assertEquals(CinemaRoom.PRIVATE_CINEMA, CinemaRoom.fromKey("missing"))
        assertEquals(CinemaRoom.PRIVATE_CINEMA, CinemaRoom.fromKey(null))
    }

    @Test fun corruptBrightnessCannotReachTheRenderer() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(.35f, CinemaAppearance(brightness = value).normalized().brightness, 0f)
        }
        assertEquals(0f, CinemaAppearance(brightness = -1f).normalized().brightness, 0f)
        assertEquals(1f, CinemaAppearance(brightness = 2f).normalized().brightness, 0f)
    }

    @Test fun dimmingRetainsSceneAndIndependentLightingToggles() {
        val settings = CinemaAppearance(CinemaRoom.ORBITAL_LOUNGE, .5f, false, true).dimmed(-2f)
        assertEquals(CinemaRoom.ORBITAL_LOUNGE, settings.room)
        assertEquals(0f, settings.brightness, 0f)
        assertFalse(settings.shadows)
        assertTrue(settings.screenGlow)
    }

    @Test fun largestScreenAndItsFrameRemainAboveTheRoomFloor() {
        for (width in listOf(3f, 6f, 10f)) {
            val g = CinemaGeometry.restored(width, 6f)
            assertTrue(g.centerHeight - width * 3.64f / 12f > .3f)
        }
    }
    @Test fun starBrightnessRecoversInvalidSettingsWithoutChangingRoomLighting() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val restored = CinemaAppearance(brightness = .2f, starBrightness = value).normalized()
            assertEquals(.5f, restored.starBrightness, 0f)
            assertEquals(.2f, restored.brightness, 0f)
        }
        assertEquals(0f, CinemaAppearance(starBrightness = -1f).normalized().starBrightness, 0f)
        assertEquals(1f, CinemaAppearance(starBrightness = 2f).normalized().starBrightness, 0f)
    }

    @Test fun starsHideInSpaceAndRetainTheCinemaPreference() {
        val cinema = CinemaAppearance(starlight = true, starBrightness = .7f)
        assertTrue(cinema.starlightVisible)
        val space = cinema.copy(room = CinemaRoom.ORBITAL_LOUNGE)
        assertFalse(space.starlightVisible)
        assertEquals(.7f, space.starBrightness, 0f)
        assertTrue(space.copy(room = CinemaRoom.PRIVATE_CINEMA).starlightVisible)
        assertFalse(cinema.copy(starlight = false).starlightVisible)
        assertFalse(cinema.copy(starBrightness = 0f).starlightVisible)
    }
}
