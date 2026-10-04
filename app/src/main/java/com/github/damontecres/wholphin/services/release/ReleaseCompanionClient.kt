package com.github.damontecres.wholphin.services.release

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AuthenticatedCompanionSession(
    val token: SensitiveValue,
    val expiresAt: String,
    val user: CompanionSessionUserDto,
) {
    override fun toString(): String = "AuthenticatedCompanionSession(token=<redacted>, expiresAt=$expiresAt, user=$user)"
}

interface ReleaseCompanionApi {
    suspend fun exchangeSession(jellyfinToken: SensitiveValue): AuthenticatedCompanionSession

    suspend fun revokeSession(sessionToken: SensitiveValue)

    suspend fun getCapabilities(sessionToken: SensitiveValue): CompanionCapabilitiesDto

    suspend fun startReleaseSearch(
        sessionToken: SensitiveValue,
        subject: ReleaseSubject,
    ): ReleaseSearchDto

    suspend fun getReleaseSearch(
        sessionToken: SensitiveValue,
        searchId: String,
    ): ReleaseSearchDto

    suspend fun acquireRelease(
        sessionToken: SensitiveValue,
        selectionToken: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto

    suspend fun listAcquisitions(
        sessionToken: SensitiveValue,
        subject: ReleaseSubject,
        active: Boolean? = null,
    ): List<AcquisitionJobDto>

    /** Lists every season/episode job for one TMDb series, for process-death rehydration. */
    suspend fun listSeriesAcquisitions(
        sessionToken: SensitiveValue,
        tmdbId: Int,
        active: Boolean? = null,
    ): List<AcquisitionJobDto>

    suspend fun getAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
    ): AcquisitionJobDto

    fun streamAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
    ): Flow<AcquisitionJobDto> =
        flow { throw ReleaseCompanionException.StreamingUnavailable() }

    suspend fun cancelAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto
}

/**
 * Serialization and HTTP status adapter for the deliberately small companion contract.
 *
 * This class is stateless: the caller owns the short-lived BFF session and can therefore clear it
 * as soon as the Jellyfin user changes. Neither raw error bodies nor credentials are retained.
 */
class DefaultReleaseCompanionApi(
    private val transport: ReleaseCompanionTransport,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) : ReleaseCompanionApi {
    override suspend fun exchangeSession(jellyfinToken: SensitiveValue): AuthenticatedCompanionSession {
        val response =
            transport.execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.POST,
                    pathSegments = listOf("v1", "session"),
                    jellyfinToken = jellyfinToken,
                ),
            )
        val dto = response.decodeExpected<CompanionSessionDto>(setOf(201))
        return AuthenticatedCompanionSession(
            token = SensitiveValue.of(dto.token),
            expiresAt = dto.expiresAt,
            user = dto.user,
        )
    }

    override suspend fun revokeSession(sessionToken: SensitiveValue) {
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.DELETE,
                    pathSegments = listOf("v1", "session"),
                    bearerToken = sessionToken,
                ),
            ).requireStatus(setOf(204))
    }

    override suspend fun getCapabilities(sessionToken: SensitiveValue): CompanionCapabilitiesDto =
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.GET,
                    pathSegments = listOf("v1", "capabilities"),
                    bearerToken = sessionToken,
                ),
            ).decodeExpected(setOf(200))

    override suspend fun startReleaseSearch(
        sessionToken: SensitiveValue,
        subject: ReleaseSubject,
    ): ReleaseSearchDto =
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.POST,
                    pathSegments = listOf("v1", "releases", "search"),
                    jsonBody = json.encodeToString(subject.toDto()),
                    bearerToken = sessionToken,
                ),
            ).decodeExpected(setOf(202))

    override suspend fun getReleaseSearch(
        sessionToken: SensitiveValue,
        searchId: String,
    ): ReleaseSearchDto =
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.GET,
                    pathSegments = listOf("v1", "releases", "search", validatedId(searchId)),
                    bearerToken = sessionToken,
                ),
            ).decodeExpected(setOf(200))

    override suspend fun acquireRelease(
        sessionToken: SensitiveValue,
        selectionToken: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto =
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.POST,
                    pathSegments = listOf("v1", "acquisitions"),
                    jsonBody = json.encodeToString(SelectReleaseRequest(selectionToken)),
                    bearerToken = sessionToken,
                    idempotencyKey = idempotencyKey,
                ),
            ).decodeExpected(setOf(200, 202))

    override suspend fun listAcquisitions(
        sessionToken: SensitiveValue,
        subject: ReleaseSubject,
        active: Boolean?,
    ): List<AcquisitionJobDto> =
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.GET,
                    pathSegments = listOf("v1", "acquisitions"),
                    query = subject.toQuery(active),
                    bearerToken = sessionToken,
                ),
            ).decodeExpected(setOf(200))

    override suspend fun listSeriesAcquisitions(
        sessionToken: SensitiveValue,
        tmdbId: Int,
        active: Boolean?,
    ): List<AcquisitionJobDto> {
        require(tmdbId > 0) { "tmdbId must be positive" }
        return transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.GET,
                    pathSegments = listOf("v1", "acquisitions"),
                    query =
                        buildMap {
                            if (active != null) put("active", active.toString())
                            put("mediaType", "tv")
                            put("tmdbId", tmdbId.toString())
                        },
                    bearerToken = sessionToken,
                ),
            ).decodeExpected(setOf(200))
    }

    override suspend fun getAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
    ): AcquisitionJobDto =
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.GET,
                    pathSegments = listOf("v1", "acquisitions", validatedId(acquisitionId)),
                    bearerToken = sessionToken,
                ),
            ).decodeExpected(setOf(200))

    override fun streamAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
    ): Flow<AcquisitionJobDto> =
        transport
            .stream(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.GET,
                    pathSegments =
                        listOf(
                            "v1",
                            "acquisitions",
                            validatedId(acquisitionId),
                            "events",
                        ),
                    bearerToken = sessionToken,
                ),
            ).mapNotNull { event ->
                if (event.type != null && event.type != ACQUISITION_EVENT) {
                    return@mapNotNull null
                }
                decodeEvent<AcquisitionJobDto>(event.data)
            }

    override suspend fun cancelAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto =
        transport
            .execute(
                ReleaseTransportRequest(
                    method = ReleaseHttpMethod.POST,
                    pathSegments =
                        listOf(
                            "v1",
                            "acquisitions",
                            validatedId(acquisitionId),
                            "cancel",
                        ),
                    bearerToken = sessionToken,
                    idempotencyKey = idempotencyKey,
                ),
            ).decodeExpected(setOf(200))

    private fun ReleaseSubject.toQuery(active: Boolean?): Map<String, String> =
        buildMap {
            if (active != null) put("active", active.toString())
            put("kind", toDto().kind.wireValue)
            put("tmdbId", tmdbId.toString())
            when (this@toQuery) {
                is ReleaseSubject.Movie -> {}

                is ReleaseSubject.TvSeason -> {
                    put("seasonNumber", seasonNumber.toString())
                }

                is ReleaseSubject.TvEpisode -> {
                    put("seasonNumber", seasonNumber.toString())
                    put("episodeNumber", episodeNumber.toString())
                }
            }
        }

    private fun validatedId(value: String): String {
        require(value.isNotBlank()) { "Companion resource id must not be blank" }
        return value
    }

    private inline fun <reified T> ReleaseTransportResponse.decodeExpected(expectedStatuses: Set<Int>): T {
        requireStatus(expectedStatuses)
        return try {
            json.decodeFromString(body)
        } catch (_: SerializationException) {
            throw ReleaseCompanionException.InvalidResponse(
                "Companion returned an invalid ${T::class.simpleName} response",
            )
        } catch (_: IllegalArgumentException) {
            throw ReleaseCompanionException.InvalidResponse(
                "Companion returned an inconsistent ${T::class.simpleName} response",
            )
        }
    }

    private inline fun <reified T> decodeEvent(body: String): T =
        try {
            json.decodeFromString(body)
        } catch (_: SerializationException) {
            throw ReleaseCompanionException.InvalidResponse(
                "Companion returned an invalid ${T::class.simpleName} event",
            )
        } catch (_: IllegalArgumentException) {
            throw ReleaseCompanionException.InvalidResponse(
                "Companion returned an inconsistent ${T::class.simpleName} event",
            )
        }

    private fun ReleaseTransportResponse.requireStatus(expectedStatuses: Set<Int>) {
        if (statusCode in expectedStatuses) return
        val error =
            try {
                json.decodeFromString<CompanionErrorEnvelopeDto>(body).error
            } catch (_: Exception) {
                null
            }
        throw mapHttpFailure(this, error)
    }

    private fun mapHttpFailure(
        response: ReleaseTransportResponse,
        error: CompanionErrorDto?,
    ): ReleaseCompanionException =
        when (response.statusCode) {
            401 -> {
                ReleaseCompanionException.AuthenticationRequired(error?.code)
            }

            403 -> {
                ReleaseCompanionException.Forbidden(error?.code)
            }

            404 -> {
                ReleaseCompanionException.NotFound(error?.code)
            }

            409 -> {
                ReleaseCompanionException.Conflict(error?.code)
            }

            410 -> {
                ReleaseCompanionException.SelectionExpired(error?.code)
            }

            422 -> {
                ReleaseCompanionException.SelectionRejected(error?.code)
            }

            429 -> {
                ReleaseCompanionException.RateLimited(
                    retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
                    errorCode = error?.code,
                )
            }

            502, 503, 504 -> {
                ReleaseCompanionException.UpstreamUnavailable(
                    statusCode = response.statusCode,
                    errorCode = error?.code,
                )
            }

            else -> {
                ReleaseCompanionException.HttpFailure(
                    statusCode = response.statusCode,
                    errorCode = error?.code,
                    retryable = error?.retryable ?: (response.statusCode >= 500),
                )
            }
        }

    private companion object {
        const val ACQUISITION_EVENT = "acquisition"
    }
}

internal val ReleaseSubjectKind.wireValue: String
    get() =
        when (this) {
            ReleaseSubjectKind.MOVIE -> "movie"
            ReleaseSubjectKind.TV_SEASON -> "season"
            ReleaseSubjectKind.TV_EPISODE -> "episode"
        }
