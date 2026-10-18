package com.fongmi.quest.cinema

import com.fongmi.android.tv.quest.CinemaFocusPolicy
import com.fongmi.android.tv.quest.CinemaGeometry
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestTest {
    @Test fun rejectsInvalidSavedGeometryAndBoundsMovement() {
        assertEquals(CinemaGeometry(), CinemaGeometry.restored(Float.NaN, Float.POSITIVE_INFINITY))
        val saved = CinemaGeometry.restored(100f, -2f)
        assertEquals(10f, saved.width, 0f)
        assertEquals(3f, saved.distance, 0f)
        assertEquals(3f, saved.resized(-100f).width, 0f)
        assertEquals(12f, saved.moved(100f).distance, 0f)
    }
    @Test fun carriesFongmiAlternatingHeaders() {
        val headers = PlaybackRequest.headers(arrayOf("Referer", "https://example.test/", "User-Agent", "FongMi"))
        assertEquals("https://example.test/", headers["Referer"])
        assertEquals("FongMi", headers["User-Agent"])
    }

    @Test fun ignoresUnpairedTailAndRejectsHeaderLineBreaks() {
        val headers = PlaybackRequest.headers(arrayOf("Cookie", "session=test", "bad\r\nName", "value", "User-Agent", "a\nb", "orphan"))
        assertEquals(mapOf("Cookie" to "session=test"), headers)
    }

    @Test fun handlesShareBundleAndCaseInsensitiveOverride() {
        val headers = PlaybackRequest.headers(arrayOf("referer", "https://new.test/"), mapOf("Referer" to "https://old.test/"))
        assertEquals(mapOf("referer" to "https://new.test/"), headers)
    }

    @Test fun readsBothIntAndLongMillisecondPositions() {
        assertEquals(90_000L, PlaybackRequest.startPosition(90_000))
        assertEquals(3_000_000_000L, PlaybackRequest.startPosition(3_000_000_000L))
        assertEquals(0L, PlaybackRequest.startPosition(-1))
        assertEquals(0L, PlaybackRequest.startPosition(null))
    }

    @Test fun menuAndOnPauseDoNotErasePendingResume() {
        val policy = CinemaFocusPolicy(true)
        policy.gain()
        policy.resumed()
        policy.lose(true)
        policy.lose(false)
        assertFalse(policy.shouldResume())
        policy.gain()
        assertTrue(policy.shouldResume())
    }

    @Test fun userPausedVideoStaysPausedAfterMenuCloses() {
        val policy = CinemaFocusPolicy(false)
        policy.gain()
        policy.lose(false)
        policy.gain()
        assertFalse(policy.shouldResume())
    }

    @Test fun menuBeforeSurfaceIsReadyStillResumesWhenReady() {
        val policy = CinemaFocusPolicy(true)
        policy.gain()
        policy.lose(false)
        policy.gain()
        assertTrue(policy.shouldResume())
        policy.resumed()
        assertFalse(policy.shouldResume())
    }
}
