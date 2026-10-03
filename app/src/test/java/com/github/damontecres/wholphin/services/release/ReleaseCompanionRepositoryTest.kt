package com.github.damontecres.wholphin.services.release

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReleaseCompanionRepositoryTest {
    @Test
    fun `an unauthorized call exchanges once and retries once`() =
        runTest {
            val api = FakeCompanionApi()
            var capabilityCalls = 0
            api.capabilities = { token ->
                capabilityCalls += 1
                if (token.reveal() == "session-1") {
                    throw ReleaseCompanionException.AuthenticationRequired("expired")
                }
                CompanionCapabilitiesDto(true, true)
            }
            var credentialReads = 0
            val repository =
                repository(api) {
                    credentialReads += 1
                    credential()
                }

            val capabilities = repository.capabilities()

            assertEquals(2, api.exchangeCount)
            assertEquals(2, capabilityCalls)
            assertEquals(2, credentialReads)
            assertEquals(CompanionCapabilitiesDto(true, true), capabilities)
        }

    @Test
    fun `mutating retry reuses exactly the same idempotency key`() =
        runTest {
            val api = FakeCompanionApi()
            val observedKeys = mutableListOf<String>()
            var call = 0
            api.acquire = { _, key ->
                observedKeys += key.reveal()
                call += 1
                if (call == 1) throw ReleaseCompanionException.AuthenticationRequired()
                acquisition()
            }
            val repository = repository(api) { credential() }

            repository.acquire(
                selectionToken = "candidate-token",
                idempotencyKey = SensitiveValue.of("one-stable-key"),
            )

            assertEquals(listOf("one-stable-key", "one-stable-key"), observedKeys)
            assertEquals(2, api.exchangeCount)
        }

    @Test
    fun `rehydration selects latest exact subject and ignores tvdb enrichment`() =
        runTest {
            val requested = ReleaseSubject.TvEpisode(20, 1, 2)
            val api = FakeCompanionApi()
            api.acquisitions =
                listOf(
                    acquisition(
                        subject = ReleaseSubject.TvEpisode(20, 1, 3),
                        updatedAt = "2026-10-03T12:03:00Z",
                    ),
                    acquisition(
                        subject = ReleaseSubject.TvEpisode(20, 1, 2, tvdbId = 99),
                        updatedAt = "2026-10-03T12:02:00Z",
                    ),
                    acquisition(
                        subject = requested,
                        updatedAt = "2026-10-03T12:01:00Z",
                    ),
                )
            val repository = repository(api) { credential() }

            val result = repository.rehydrate(requested)

            assertNotNull(result)
            assertEquals("2026-10-03T12:02:00Z", result?.updatedAt)
            assertEquals(null, api.lastActiveFilter)
            assertEquals(requested, api.lastListedSubject)
        }

    @Test
    fun `valid BFF session is cached only in memory`() =
        runTest {
            val api = FakeCompanionApi()
            val repository = repository(api) { credential() }

            repository.capabilities()
            repository.startSearch(ReleaseSubject.Movie(42))

            assertEquals(1, api.exchangeCount)
        }

    @Test
    fun `BFF session is never reused after Jellyfin server or user changes`() =
        runTest {
            val api = FakeCompanionApi()
            var identity = "server-1:user-1"
            val repository = repository(api) { credential(identity) }

            repository.capabilities()
            identity = "server-1:user-2"
            repository.capabilities()

            assertEquals(2, api.exchangeCount)
        }

    @Test
    fun `unauthorized retry cannot cross a Jellyfin identity change`() =
        runTest {
            val api = FakeCompanionApi()
            var identity = "server-1:user-1"
            var calls = 0
            api.capabilities = {
                calls += 1
                identity = "server-1:user-2"
                throw ReleaseCompanionException.AuthenticationRequired()
            }
            val repository = repository(api) { credential(identity) }

            val failure = runCatching { repository.capabilities() }.exceptionOrNull()

            assertTrue(failure is ReleaseCompanionException.AuthenticationRequired)
            assertEquals(1, calls)
            assertEquals(1, api.exchangeCount)
        }

    private fun repository(
        api: FakeCompanionApi,
        credential: suspend () -> JellyfinCredential?,
    ) = DefaultReleaseCompanionRepository(
        api = api,
        jellyfinCredentialProvider = JellyfinCredentialProvider { credential() },
        clock = ReleaseClock { Instant.parse("2026-10-03T12:00:00Z") },
    )

    private fun credential(identityKey: String = "server-1:user-1") =
        JellyfinCredential(
            identityKey = identityKey,
            accessToken = SensitiveValue.of("jellyfin-token"),
        )
}

private class FakeCompanionApi : ReleaseCompanionApi {
    var exchangeCount = 0
    var capabilities: suspend (SensitiveValue) -> CompanionCapabilitiesDto = {
        CompanionCapabilitiesDto(false, false)
    }
    var acquire: suspend (String, SensitiveValue) -> AcquisitionJobDto = { _, _ -> acquisition() }
    var acquisitions: List<AcquisitionJobDto> = emptyList()
    var lastListedSubject: ReleaseSubject? = null
    var lastActiveFilter: Boolean? = null

    override suspend fun exchangeSession(jellyfinToken: SensitiveValue): AuthenticatedCompanionSession {
        exchangeCount += 1
        return AuthenticatedCompanionSession(
            token = SensitiveValue.of("session-$exchangeCount"),
            expiresAt = "2099-01-01T00:00:00Z",
            user = CompanionSessionUserDto("user-1", "User", true, true, true),
        )
    }

    override suspend fun revokeSession(sessionToken: SensitiveValue) = Unit

    override suspend fun getCapabilities(sessionToken: SensitiveValue): CompanionCapabilitiesDto =
        capabilities(sessionToken)

    override suspend fun startReleaseSearch(
        sessionToken: SensitiveValue,
        subject: ReleaseSubject,
    ): ReleaseSearchDto =
        ReleaseSearchDto(
            searchId = "search-1",
            subject = subject.toDto(),
            state = "completed",
            expiresAt = "2099-01-01T00:00:00Z",
        )

    override suspend fun getReleaseSearch(
        sessionToken: SensitiveValue,
        searchId: String,
    ): ReleaseSearchDto = error("Not needed by this test")

    override suspend fun acquireRelease(
        sessionToken: SensitiveValue,
        selectionToken: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto = acquire(selectionToken, idempotencyKey)

    override suspend fun listAcquisitions(
        sessionToken: SensitiveValue,
        subject: ReleaseSubject,
        active: Boolean?,
    ): List<AcquisitionJobDto> {
        lastListedSubject = subject
        lastActiveFilter = active
        return acquisitions
    }

    override suspend fun getAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
    ): AcquisitionJobDto = error("Not needed by this test")

    override suspend fun cancelAcquisition(
        sessionToken: SensitiveValue,
        acquisitionId: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto = error("Not needed by this test")
}
