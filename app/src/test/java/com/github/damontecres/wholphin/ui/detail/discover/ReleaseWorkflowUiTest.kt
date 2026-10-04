package com.github.damontecres.wholphin.ui.detail.discover

import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.services.release.AcquisitionJobDto
import com.github.damontecres.wholphin.services.release.ReleaseCandidate
import com.github.damontecres.wholphin.services.release.ReleaseSubject
import com.github.damontecres.wholphin.services.release.ReleaseWorkflowState
import com.github.damontecres.wholphin.services.release.matchesSubject
import com.github.damontecres.wholphin.services.release.toDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWorkflowUiTest {
    @Test
    fun `releases are grouped by descending resolution and sorted by ascending size`() {
        val sections =
            groupReleasesByQuality(
                listOf(
                    candidate(token = "1080-large", quality = "Bluray-1080p", sizeBytes = 8_000),
                    candidate(token = "4k-large", quality = "WEBDL-2160p", sizeBytes = 20_000),
                    candidate(token = "720", quality = "HDTV-720p", sizeBytes = 4_000),
                    candidate(token = "4k-small", quality = "UHD BluRay", sizeBytes = 12_000),
                    candidate(token = "1080-small", quality = "1080P", sizeBytes = 5_000),
                    candidate(token = "dvd", quality = "DVD", sizeBytes = 2_000),
                ),
            )

        assertEquals(listOf("4K", "1080p", "720p", "DVD"), sections.map { it.label })
        assertEquals(
            listOf("4k-small", "4k-large"),
            sections.first().releases.map { it.selectionToken },
        )
        assertEquals(
            listOf("1080-small", "1080-large"),
            sections[1].releases.map { it.selectionToken },
        )
    }

    @Test
    fun `equal sized releases preserve companion ordering within their quality`() {
        val sections =
            groupReleasesByQuality(
                listOf(
                    candidate(token = "first", quality = "Remux-2160p", sizeBytes = 10_000),
                    candidate(token = "second", quality = "4K", sizeBytes = 10_000),
                ),
            )

        assertEquals(listOf("first", "second"), sections.single().releases.map { it.selectionToken })
    }

    @Test
    fun `season search only accepts an approved full season release`() {
        val subject = ReleaseSubject.TvSeason(tmdbId = 10, seasonNumber = 2)

        assertTrue(candidate(fullSeason = true, seasonNumber = 2).matchesSubject(subject))
        assertFalse(candidate(fullSeason = false, seasonNumber = 2).matchesSubject(subject))
        assertFalse(candidate(fullSeason = true, seasonNumber = 3).matchesSubject(subject))
        assertFalse(candidate(fullSeason = true, seasonNumber = 2, approved = false).matchesSubject(subject))
    }

    @Test
    fun `episode search requires coverage of the selected episode when coverage is supplied`() {
        val subject =
            ReleaseSubject.TvEpisode(
                tmdbId = 10,
                seasonNumber = 2,
                episodeNumber = 4,
            )

        assertTrue(candidate(seasonNumber = 2, episodeNumbers = listOf(3, 4, 5)).matchesSubject(subject))
        assertFalse(candidate(seasonNumber = 2, episodeNumbers = listOf(3, 5)).matchesSubject(subject))
        assertFalse(candidate(seasonNumber = 1, episodeNumbers = listOf(4)).matchesSubject(subject))
    }

    @Test
    fun `rejected result is never selectable even when coverage matches`() {
        val subject = ReleaseSubject.TvSeason(tmdbId = 10, seasonNumber = 1)

        assertFalse(
            candidate(
                fullSeason = true,
                seasonNumber = 1,
                rejected = true,
            ).matchesSubject(subject),
        )
    }

    @Test
    fun `existing Jellyfin availability keeps classic action unless companion is tracking`() {
        assertFalse(
            shouldShowCompanionAction(
                enabled = true,
                availability = SeerrAvailability.AVAILABLE,
                state = ReleaseWorkflowState.Ready(ReleaseSubject.Movie(10)),
            ),
        )
        assertTrue(
            shouldShowCompanionAction(
                enabled = true,
                availability = SeerrAvailability.AVAILABLE,
                state =
                    ReleaseWorkflowState.Tracking(
                        subject = ReleaseSubject.Movie(10),
                        acquisition = acquisition(),
                    ),
            ),
        )
        assertFalse(
            shouldShowCompanionAction(
                enabled = false,
                availability = SeerrAvailability.UNKNOWN,
                state = ReleaseWorkflowState.Idle,
            ),
        )
        assertFalse(
            shouldShowCompanionAction(
                enabled = true,
                availability = SeerrAvailability.PROCESSING,
                state = ReleaseWorkflowState.Ready(ReleaseSubject.Movie(10)),
            ),
        )
        assertTrue(
            shouldShowCompanionAction(
                enabled = true,
                availability = SeerrAvailability.PARTIALLY_AVAILABLE,
                state = ReleaseWorkflowState.Ready(ReleaseSubject.TvSeason(10, 1)),
            ),
        )
    }

    private fun candidate(
        token: String = "opaque-token",
        quality: String = "1080p",
        sizeBytes: Long = 1_024,
        fullSeason: Boolean = false,
        seasonNumber: Int? = null,
        episodeNumbers: List<Int> = emptyList(),
        approved: Boolean = true,
        rejected: Boolean = false,
    ) = ReleaseCandidate(
        selectionToken = token,
        title = "Example release",
        sizeBytes = sizeBytes,
        seeders = 10,
        protocol = "torrent",
        quality = quality,
        indexer = "Example",
        approved = approved,
        rejected = rejected,
        fullSeason = fullSeason,
        seasonNumber = seasonNumber,
        episodeNumbers = episodeNumbers,
        expiresAt = "2099-01-01T00:00:00Z",
    )

    private fun acquisition() =
        AcquisitionJobDto(
            id = "job-1",
            subject = ReleaseSubject.Movie(10).toDto(),
            state = "downloading",
            title = "Example release",
            updatedAt = "2026-01-01T00:00:00Z",
        )
}
