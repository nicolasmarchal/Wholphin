package com.github.damontecres.wholphin.services.release

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

data class JellyfinCredential(
    /** Stable composite key for the Jellyfin server and user, never the access token itself. */
    val identityKey: String,
    val accessToken: SensitiveValue,
) {
    init {
        require(identityKey.isNotBlank()) { "Jellyfin credential identity must not be blank" }
    }

    override fun toString(): String =
        "JellyfinCredential(identityKey=<redacted>, accessToken=<redacted>)"
}

fun interface JellyfinCredentialProvider {
    /** Returns the current server/user identity and token without persisting or logging either. */
    suspend fun currentCredential(): JellyfinCredential?
}

fun interface ReleaseClock {
    fun now(): Instant
}

interface ReleaseCompanionRepository {
    suspend fun capabilities(): CompanionCapabilitiesDto

    suspend fun startSearch(subject: ReleaseSubject): ReleaseSearchDto

    suspend fun getSearch(searchId: String): ReleaseSearchDto

    suspend fun acquire(
        selectionToken: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto

    suspend fun rehydrate(subject: ReleaseSubject): AcquisitionJobDto?

    suspend fun getAcquisition(acquisitionId: String): AcquisitionJobDto

    suspend fun cancel(
        acquisitionId: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto

    suspend fun clearSession(revokeRemotely: Boolean = true)
}

/**
 * Authenticated facade over [ReleaseCompanionApi].
 *
 * A BFF session exists only in memory. A failed authorized call is retried exactly once after a
 * fresh Jellyfin-to-BFF exchange. Mutating calls remain safe because the same idempotency key is
 * reused, and a 401 is rejected before the operation is authorized.
 */
class DefaultReleaseCompanionRepository(
    private val api: ReleaseCompanionApi,
    private val jellyfinCredentialProvider: JellyfinCredentialProvider,
    private val clock: ReleaseClock = ReleaseClock { Instant.now() },
    private val refreshBeforeExpiry: Duration = Duration.ofMinutes(1),
) : ReleaseCompanionRepository {
    private val sessionMutex = Mutex()

    @Volatile
    private var session: IdentityBoundSession? = null

    init {
        require(!refreshBeforeExpiry.isNegative) { "refreshBeforeExpiry must not be negative" }
    }

    override suspend fun capabilities(): CompanionCapabilitiesDto =
        authenticated { api.getCapabilities(it) }

    override suspend fun startSearch(subject: ReleaseSubject): ReleaseSearchDto =
        authenticated { api.startReleaseSearch(it, subject) }

    override suspend fun getSearch(searchId: String): ReleaseSearchDto =
        authenticated { api.getReleaseSearch(it, searchId) }

    override suspend fun acquire(
        selectionToken: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto =
        authenticated { api.acquireRelease(it, selectionToken, idempotencyKey) }

    override suspend fun rehydrate(subject: ReleaseSubject): AcquisitionJobDto? =
        authenticated { token ->
            api
                .listAcquisitions(
                    sessionToken = token,
                    subject = subject,
                    active = null,
                ).asSequence()
                .filter { it.subject.matches(subject) }
                .maxByOrNull { it.updatedAtInstantOrMinimum() }
        }

    override suspend fun getAcquisition(acquisitionId: String): AcquisitionJobDto =
        authenticated { api.getAcquisition(it, acquisitionId) }

    override suspend fun cancel(
        acquisitionId: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto =
        authenticated { api.cancelAcquisition(it, acquisitionId, idempotencyKey) }

    override suspend fun clearSession(revokeRemotely: Boolean) {
        val previous = sessionMutex.withLock { session.also { session = null } }
        if (revokeRemotely && previous != null) {
            api.revokeSession(previous.session.token)
        }
    }

    private suspend fun <T> authenticated(block: suspend (SensitiveValue) -> T): T {
        val initial = validSession()
        return try {
            block(initial.session.token)
        } catch (_: ReleaseCompanionException.AuthenticationRequired) {
            invalidate(initial)
            val refreshed = validSession(requiredIdentityKey = initial.identityKey)
            block(refreshed.session.token)
        }
    }

    private suspend fun validSession(requiredIdentityKey: String? = null): IdentityBoundSession =
        sessionMutex.withLock {
            val credential =
                jellyfinCredentialProvider.currentCredential()
                    ?: run {
                        session = null
                        throw ReleaseCompanionException.AuthenticationRequired()
                    }
            if (requiredIdentityKey != null && credential.identityKey != requiredIdentityKey) {
                session = null
                throw ReleaseCompanionException.AuthenticationRequired("jellyfin_identity_changed")
            }
            session
                ?.takeIf { it.identityKey == credential.identityKey }
                ?.takeUnless { isExpiring(it.session) }
                ?.let { return@withLock it }

            // Never let a bearer minted for another Jellyfin server/user escape this lock.
            session = null
            IdentityBoundSession(
                identityKey = credential.identityKey,
                session = api.exchangeSession(credential.accessToken),
            ).also { session = it }
        }

    private suspend fun invalidate(expected: IdentityBoundSession) {
        sessionMutex.withLock {
            if (session === expected) session = null
        }
    }

    private fun isExpiring(value: AuthenticatedCompanionSession): Boolean {
        val expiry =
            try {
                Instant.parse(value.expiresAt)
            } catch (failure: Exception) {
                throw ReleaseCompanionException.InvalidResponse(
                    "Companion session has an invalid expiration timestamp",
                    failure,
                )
            }
        return !expiry.isAfter(clock.now().plus(refreshBeforeExpiry))
    }
}

private data class IdentityBoundSession(
    val identityKey: String,
    val session: AuthenticatedCompanionSession,
) {
    override fun toString(): String =
        "IdentityBoundSession(identityKey=<redacted>, session=$session)"
}

private fun ReleaseSubjectDto.matches(subject: ReleaseSubject): Boolean {
    val expected = subject.toDto()
    return kind == expected.kind &&
        tmdbId == expected.tmdbId &&
        seasonNumber == expected.seasonNumber &&
        episodeNumber == expected.episodeNumber
}

private fun AcquisitionJobDto.updatedAtInstantOrMinimum(): Instant =
    runCatching { Instant.parse(updatedAt) }.getOrDefault(Instant.MIN)
