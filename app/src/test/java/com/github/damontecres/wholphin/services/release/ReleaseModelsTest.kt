package com.github.damontecres.wholphin.services.release

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseModelsTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `subjects serialize to the companion wire kinds`() {
        val movie = json.encodeToString(ReleaseSubject.Movie(42).toDto())
        val season = json.encodeToString(ReleaseSubject.TvSeason(84, 0, 7).toDto())
        val episode = json.encodeToString(ReleaseSubject.TvEpisode(84, 2, 3, 7).toDto())

        assertEquals("{\"kind\":\"movie\",\"tmdbId\":42}", movie)
        assertEquals(
            "{\"kind\":\"season\",\"tmdbId\":84,\"tvdbId\":7,\"seasonNumber\":0}",
            season,
        )
        assertEquals(
            "{\"kind\":\"episode\",\"tmdbId\":84,\"tvdbId\":7," +
                "\"seasonNumber\":2,\"episodeNumber\":3}",
            episode,
        )
    }

    @Test
    fun `release is selectable when approved or when a soft policy override is allowed`() {
        assertTrue(candidate(approved = true, rejected = false).selectable)
        assertFalse(candidate(approved = false, rejected = false).selectable)
        assertFalse(candidate(approved = true, rejected = true).selectable)
        assertTrue(
            candidate(
                approved = false,
                rejected = true,
                policyOverrideAllowed = true,
            ).selectable,
        )
    }

    @Test
    fun `release token is redacted from string representations`() {
        val secret = "opaque-release-token-that-must-not-leak"
        val candidate = candidate(token = secret)
        val request = SelectReleaseRequest(secret)

        assertFalse(candidate.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertTrue(candidate.toString().contains("<redacted>"))
    }

    @Test
    fun `unknown states remain forward compatible`() {
        assertEquals(ReleaseSearchPhase.UNKNOWN, ReleaseSearchPhase.fromWire("future"))
        assertEquals(AcquisitionPhase.UNKNOWN, AcquisitionPhase.fromWire("future"))
    }

    @Test
    fun `invalid Jellyfin id is not exposed as a UUID`() {
        val acquisition = acquisition(jellyfinItemId = "not-a-uuid")

        assertNull(acquisition.jellyfinUuid)
    }

    @Test
    fun `Jellyfin compact id is exposed as a UUID`() {
        val acquisition = acquisition(jellyfinItemId = "00112233445566778899aabbccddeeff")

        assertEquals(
            "00112233-4455-6677-8899-aabbccddeeff",
            acquisition.jellyfinUuid?.toString(),
        )
    }

    @Test
    fun `server error message is redacted from string representations`() {
        val error = CompanionErrorDto("upstream_error", "magnet-or-passkey", true, "request-1")

        assertFalse(error.toString().contains("magnet-or-passkey"))
        assertTrue(error.toString().contains("message=<redacted>"))
    }
}

internal fun candidate(
    token: String = "opaque-release-token-1234567890123456",
    approved: Boolean = true,
    rejected: Boolean = false,
    policyOverrideAllowed: Boolean = false,
    expiresAt: String = "2099-01-01T00:00:00Z",
): ReleaseCandidate =
    ReleaseCandidate(
        selectionToken = token,
        title = "Example.2026.2160p",
        sizeBytes = 8_000_000_000,
        seeders = 12,
        protocol = "torrent",
        quality = "Bluray-2160p",
        indexer = "Example Indexer",
        approved = approved,
        rejected = rejected,
        policyOverrideAllowed = policyOverrideAllowed,
        rejectionReasons = if (rejected) listOf("Rejected") else emptyList(),
        expiresAt = expiresAt,
    )

internal fun acquisition(
    subject: ReleaseSubject = ReleaseSubject.Movie(42),
    state: String = "queued",
    progress: Double? = null,
    jellyfinItemId: String? = null,
    updatedAt: String = "2026-10-03T12:00:00Z",
): AcquisitionJobDto =
    AcquisitionJobDto(
        id = "acquisition-1",
        subject = subject.toDto(),
        state = state,
        title = "Example.2026.2160p",
        progress = progress,
        jellyfinItemId = jellyfinItemId,
        updatedAt = updatedAt,
    )
