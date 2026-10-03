package com.github.damontecres.wholphin.services.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * A media subject understood by the release companion.
 *
 * The sealed hierarchy makes invalid movie/season/episode combinations impossible to construct.
 * [tvdbId] is optional because TMDb is Wholphin's primary discovery identifier, but it should be
 * supplied for series whenever Seerr exposes it so older Sonarr versions never need to guess.
 */
sealed interface ReleaseSubject {
    val tmdbId: Int

    data class Movie(
        override val tmdbId: Int,
    ) : ReleaseSubject {
        init {
            require(tmdbId > 0) { "tmdbId must be positive" }
        }
    }

    data class TvSeason(
        override val tmdbId: Int,
        val seasonNumber: Int,
        val tvdbId: Int? = null,
    ) : ReleaseSubject {
        init {
            require(tmdbId > 0) { "tmdbId must be positive" }
            require(seasonNumber >= 0) { "seasonNumber must not be negative" }
            require(tvdbId == null || tvdbId > 0) { "tvdbId must be positive when present" }
        }
    }

    data class TvEpisode(
        override val tmdbId: Int,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val tvdbId: Int? = null,
    ) : ReleaseSubject {
        init {
            require(tmdbId > 0) { "tmdbId must be positive" }
            require(seasonNumber >= 0) { "seasonNumber must not be negative" }
            require(episodeNumber > 0) { "episodeNumber must be positive" }
            require(tvdbId == null || tvdbId > 0) { "tvdbId must be positive when present" }
        }
    }
}

@Serializable
enum class ReleaseSubjectKind {
    @SerialName("movie")
    MOVIE,

    @SerialName("season")
    TV_SEASON,

    @SerialName("episode")
    TV_EPISODE,
}

@Serializable
data class ReleaseSubjectDto(
    val kind: ReleaseSubjectKind,
    val tmdbId: Int,
    val tvdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    fun toDomain(): ReleaseSubject =
        when (kind) {
            ReleaseSubjectKind.MOVIE -> {
                ReleaseSubject.Movie(tmdbId)
            }

            ReleaseSubjectKind.TV_SEASON -> {
                ReleaseSubject.TvSeason(
                    tmdbId = tmdbId,
                    tvdbId = tvdbId,
                    seasonNumber = requireNotNull(seasonNumber) { "seasonNumber is required for a season" },
                )
            }

            ReleaseSubjectKind.TV_EPISODE -> {
                ReleaseSubject.TvEpisode(
                    tmdbId = tmdbId,
                    tvdbId = tvdbId,
                    seasonNumber = requireNotNull(seasonNumber) { "seasonNumber is required for an episode" },
                    episodeNumber = requireNotNull(episodeNumber) { "episodeNumber is required for an episode" },
                )
            }
        }
}

fun ReleaseSubject.toDto(): ReleaseSubjectDto =
    when (this) {
        is ReleaseSubject.Movie -> {
            ReleaseSubjectDto(
                kind = ReleaseSubjectKind.MOVIE,
                tmdbId = tmdbId,
            )
        }

        is ReleaseSubject.TvSeason -> {
            ReleaseSubjectDto(
                kind = ReleaseSubjectKind.TV_SEASON,
                tmdbId = tmdbId,
                tvdbId = tvdbId,
                seasonNumber = seasonNumber,
            )
        }

        is ReleaseSubject.TvEpisode -> {
            ReleaseSubjectDto(
                kind = ReleaseSubjectKind.TV_EPISODE,
                tmdbId = tmdbId,
                tvdbId = tvdbId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        }
    }

internal fun ReleaseSubject.seasonNumberOrNull(): Int? =
    when (this) {
        is ReleaseSubject.Movie -> null
        is ReleaseSubject.TvSeason -> seasonNumber
        is ReleaseSubject.TvEpisode -> seasonNumber
    }

/** Client-side defence in depth; the BFF repeats these exact coverage checks authoritatively. */
internal fun ReleaseCandidate.matchesSubject(subject: ReleaseSubject): Boolean =
    selectable &&
        when (subject) {
            is ReleaseSubject.Movie -> {
                true
            }

            is ReleaseSubject.TvSeason -> {
                fullSeason && (seasonNumber == null || seasonNumber == subject.seasonNumber)
            }

            is ReleaseSubject.TvEpisode -> {
                (seasonNumber == null || seasonNumber == subject.seasonNumber) &&
                    (episodeNumbers.isEmpty() || subject.episodeNumber in episodeNumbers)
            }
        }

/**
 * One release candidate returned by the companion.
 *
 * [selectionToken] is intentionally opaque. Its value is redacted from [toString] so accidental
 * structured logging cannot disclose a tracker credential indirectly.
 */
@Serializable
data class ReleaseCandidate(
    @SerialName("token")
    val selectionToken: String,
    val title: String,
    val sizeBytes: Long,
    val seeders: Int? = null,
    val protocol: String,
    val quality: String,
    val indexer: String,
    val approved: Boolean,
    val rejected: Boolean = false,
    @SerialName("rejections")
    val rejectionReasons: List<String> = emptyList(),
    val fullSeason: Boolean = false,
    val seasonNumber: Int? = null,
    val episodeNumbers: List<Int> = emptyList(),
    val expiresAt: String,
) {
    val selectable: Boolean get() = approved && !rejected

    fun expiresAtInstant(): Instant? =
        try {
            Instant.parse(expiresAt)
        } catch (_: DateTimeParseException) {
            null
        }

    override fun toString(): String =
        "ReleaseCandidate(selectionToken=<redacted>, title=$title, sizeBytes=$sizeBytes, " +
            "seeders=$seeders, quality=$quality, indexer=$indexer, protocol=$protocol, " +
            "approved=$approved, rejected=$rejected, rejectionReasons=$rejectionReasons, " +
            "fullSeason=$fullSeason, seasonNumber=$seasonNumber, " +
            "episodeNumbers=$episodeNumbers, expiresAt=$expiresAt)"
}

enum class ReleaseSearchPhase {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    EXPIRED,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String): ReleaseSearchPhase =
            when (value) {
                "pending" -> PENDING
                "running" -> RUNNING
                "completed" -> COMPLETED
                "failed" -> FAILED
                "expired" -> EXPIRED
                else -> UNKNOWN
            }
    }
}

@Serializable
data class ReleaseSearchDto(
    val searchId: String,
    val subject: ReleaseSubjectDto,
    val state: String,
    val expiresAt: String,
    val releases: List<ReleaseCandidate> = emptyList(),
    val error: CompanionErrorDto? = null,
) {
    val phase: ReleaseSearchPhase get() = ReleaseSearchPhase.fromWire(state)
}

enum class AcquisitionPhase {
    DISPATCHING,
    DISPATCH_UNCERTAIN,
    QUEUED,
    DOWNLOADING,
    DOWNLOAD_BLOCKED,
    VERIFYING,
    IMPORTING,
    IMPORT_FAILED,
    IMPORTED,
    WAITING_JELLYFIN,
    AVAILABLE,
    CANCELLED,
    ERROR,
    UNKNOWN,
    ;

    val terminal: Boolean
        get() = this == AVAILABLE || this == CANCELLED || this == ERROR

    companion object {
        fun fromWire(value: String): AcquisitionPhase =
            when (value) {
                "dispatching" -> DISPATCHING
                "dispatch_uncertain" -> DISPATCH_UNCERTAIN
                "queued" -> QUEUED
                "downloading" -> DOWNLOADING
                "download_blocked" -> DOWNLOAD_BLOCKED
                "verifying" -> VERIFYING
                "importing" -> IMPORTING
                "import_failed" -> IMPORT_FAILED
                "imported" -> IMPORTED
                "waiting_jellyfin" -> WAITING_JELLYFIN
                "available" -> AVAILABLE
                "cancelled" -> CANCELLED
                "error" -> ERROR
                else -> UNKNOWN
            }
    }
}

@Serializable
data class AcquisitionJobDto(
    val id: String,
    val subject: ReleaseSubjectDto,
    val state: String,
    val title: String,
    val progress: Double? = null,
    val bytesDownloaded: Long? = null,
    val bytesTotal: Long? = null,
    val downloadSpeedBytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val statusText: String? = null,
    val progressSource: String? = null,
    val error: CompanionErrorDto? = null,
    val jellyfinItemId: String? = null,
    val updatedAt: String,
) {
    val phase: AcquisitionPhase get() = AcquisitionPhase.fromWire(state)

    val jellyfinUuid: UUID?
        get() = jellyfinItemId?.toUUIDOrNull()
}

@Serializable
data class SelectReleaseRequest(
    @SerialName("token")
    val selectionToken: String,
) {
    override fun toString(): String = "SelectReleaseRequest(selectionToken=<redacted>)"
}

@Serializable
data class CompanionErrorDto(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val requestId: String? = null,
) {
    override fun toString(): String =
        "CompanionErrorDto(code=$code, message=<redacted>, retryable=$retryable, " +
            "requestId=$requestId)"
}

@Serializable
data class CompanionErrorEnvelopeDto(
    val error: CompanionErrorDto,
)

@Serializable
data class CompanionSessionUserDto(
    val id: String,
    val name: String,
    val canRequestMovies: Boolean,
    val canRequestSeries: Boolean,
    val canCancel: Boolean,
)

@Serializable
data class CompanionSessionDto(
    val token: String,
    val expiresAt: String,
    val user: CompanionSessionUserDto,
) {
    override fun toString(): String = "CompanionSessionDto(token=<redacted>, expiresAt=$expiresAt, user=$user)"
}

@Serializable
data class CompanionCapabilitiesDto(
    val hasQbittorrentMetrics: Boolean,
    val supportsCancellation: Boolean,
)

/** A non-reversible local identifier suitable for indexing an opaque token. */
internal fun String.redactedFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
