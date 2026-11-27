package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.linux.WeTrakrSync.Stamp
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/** A show's poster: WATCHED only when every aired episode was watched; NEW EPISODES when caught up and newer ones aired. */
class SeriesStampTest {
    private val today = LocalDate.of(2026, 11, 25)
    // Two seasons of 8; season 2 airing (episode 6 out, last week).
    private val airing = Metadata.Aired(mapOf(1 to 8, 2 to 8), lastSeason = 2, lastEpisode = 6, lastAirDate = "2026-11-18")
    private fun eps(vararg r: Pair<Int, IntRange>) = r.flatMap { (s, range) -> range.map { s to it } }.toSet()

    @Test fun `one episode watched is Continue, not Watched`() =
        assertEquals(Stamp.CONTINUE, WeTrakrSync.seriesStamp(eps(1 to 1..1), airing, today))

    @Test fun `everything aired watched is Watched`() =
        assertEquals(Stamp.WATCHED, WeTrakrSync.seriesStamp(eps(1 to 1..8, 2 to 1..6), airing, today))

    @Test fun `caught up, the latest weekly episodes are New episodes`() =
        assertEquals(Stamp.NEW_EPISODES, WeTrakrSync.seriesStamp(eps(1 to 1..8, 2 to 1..4), airing, today))

    @Test fun `finished season 1 and season 2 came out is New episodes`() =
        assertEquals(Stamp.NEW_EPISODES, WeTrakrSync.seriesStamp(eps(1 to 1..8), airing, today))

    @Test fun `a gap in the middle is Continue`() =
        assertEquals(Stamp.CONTINUE, WeTrakrSync.seriesStamp(eps(1 to 1..3, 1 to 6..8, 2 to 1..6), airing, today))

    @Test fun `new episodes long ago are Continue`() =
        assertEquals(Stamp.CONTINUE, WeTrakrSync.seriesStamp(eps(1 to 1..8), airing.copy(lastAirDate = "2025-01-01"), today))

    // Dead-End Job: TMDB says 4 episodes aired (weekly), the extension already offers all 8, 1-4 watched.
    private val weekly = Metadata.Aired(mapOf(1 to 8), lastSeason = 1, lastEpisode = 4, lastAirDate = "2026-11-20")

    @Test fun `4 of 8 offered watched is Continue even when TMDB says only 4 aired`() {
        assertEquals(Stamp.WATCHED, WeTrakrSync.seriesStamp(eps(1 to 1..4), weekly, today), "what TMDB alone says")
        assertEquals(Stamp.CONTINUE, WeTrakrSync.seriesStamp(eps(1 to 1..4), WeTrakrSync.withOffered(weekly, 1 to 8), today))
        assertEquals(Stamp.WATCHED, WeTrakrSync.seriesStamp(eps(1 to 1..8), WeTrakrSync.withOffered(weekly, 1 to 8), today))
        // The extension offering fewer than TMDB changes nothing.
        assertEquals(weekly, WeTrakrSync.withOffered(weekly, 1 to 2))
    }
}
