package com.github.damontecres.wholphin.ui.detail.discover

import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.services.release.AcquisitionJobDto
import com.github.damontecres.wholphin.services.release.ReleaseCandidate
import com.github.damontecres.wholphin.services.release.ReleaseSubject
import com.github.damontecres.wholphin.services.release.ReleaseWorkflowState
import com.github.damontecres.wholphin.services.release.matchesSubject
import com.github.damontecres.wholphin.services.release.toDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseWorkflowUiTest {
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
        fullSeason: Boolean = false,
        seasonNumber: Int? = null,
        episodeNumbers: List<Int> = emptyList(),
        approved: Boolean = true,
        rejected: Boolean = false,
    ) = ReleaseCandidate(
        selectionToken = "opaque-token",
        title = "Example release",
        sizeBytes = 1_024,
        seeders = 10,
        protocol = "torrent",
        quality = "1080p",
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
