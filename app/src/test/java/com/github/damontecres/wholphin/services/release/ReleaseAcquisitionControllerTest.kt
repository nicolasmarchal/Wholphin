package com.github.damontecres.wholphin.services.release

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseAcquisitionControllerTest {
    @Test
    fun `rehydration supports movie season and episode subjects`() =
        runTest {
            val repository = FakeReleaseRepository()
            val controller = controller(repository)
            val subjects =
                listOf(
                    ReleaseSubject.Movie(1),
                    ReleaseSubject.TvSeason(2, 0, 20),
                    ReleaseSubject.TvEpisode(3, 4, 5, 30),
                )

            subjects.forEach { subject ->
                controller.resume(subject)
                advanceUntilIdle()
                assertEquals(ReleaseWorkflowState.Ready(subject), controller.state.value)
            }

            assertEquals(subjects, repository.rehydratedSubjects)
        }

    @Test
    fun `series process restart restores exact subject returned by the server`() =
        runTest {
            val repository = FakeReleaseRepository()
            repository.rehydratedSeries =
                acquisition(
                    subject = ReleaseSubject.TvEpisode(22, 3, 7, tvdbId = 220),
                    state = "available",
                    jellyfinItemId = "11111111-1111-1111-1111-111111111111",
                )
            val controller = controller(repository)
            var resolved: ReleaseSubject? = null

            controller.resumeLatestSeries(22, onSubjectResolved = { resolved = it })
            advanceUntilIdle()

            assertEquals(ReleaseSubject.TvEpisode(22, 3, 7, 220), resolved)
            assertTrue(controller.state.value is ReleaseWorkflowState.Available)
            assertEquals(listOf(22), repository.rehydratedSeriesIds)
        }

    @Test
    fun `series process restart without a job becomes ready without inventing an acquisition`() =
        runTest {
            val repository = FakeReleaseRepository()
            val controller = controller(repository)
            val fallback = ReleaseSubject.TvSeason(22, 0)

            controller.resumeLatestSeries(22, fallback)
            advanceUntilIdle()

            assertEquals(ReleaseWorkflowState.Ready(fallback), controller.state.value)
        }

    @Test
    fun `completed search exposes API candidates without altering their fields`() =
        runTest {
            val expected = candidate()
            val repository = FakeReleaseRepository()
            repository.startedSearch = { subject -> search(subject, "completed", listOf(expected)) }
            val controller = controller(repository)
            val subject = ReleaseSubject.Movie(42)

            controller.search(subject)
            advanceUntilIdle()

            val state = controller.state.value as ReleaseWorkflowState.Results
            assertSame(expected, state.search.releases.single())
        }

    @Test
    fun `lifecycle resume keeps completed release results instead of replacing them with ready`() =
        runTest {
            val repository = FakeReleaseRepository()
            repository.startedSearch = { subject -> search(subject, "completed", listOf(candidate())) }
            val controller = controller(repository)
            val subject = ReleaseSubject.Movie(42)

            controller.search(subject)
            advanceUntilIdle()
            controller.stopPolling()
            controller.resumeAfterLifecycle(subject)
            advanceUntilIdle()

            assertTrue(controller.state.value is ReleaseWorkflowState.Results)
            assertTrue(repository.rehydratedSubjects.isEmpty())
        }

    @Test
    fun `search polling stops at its configured bound`() =
        runTest {
            val repository = FakeReleaseRepository()
            repository.startedSearch = { subject -> search(subject, "running") }
            repository.polledSearch = { search(ReleaseSubject.Movie(42), "running") }
            val controller =
                controller(
                    repository,
                    searchPolicy =
                        BoundedPollingPolicy(
                            initialDelayMillis = 0,
                            maximumDelayMillis = 0,
                            multiplier = 1.0,
                            maximumAttempts = 2,
                            maximumElapsedMillis = 60_000,
                        ),
                )

            controller.search(ReleaseSubject.Movie(42))
            advanceUntilIdle()

            val state = controller.state.value as ReleaseWorkflowState.Failed
            assertEquals(ReleaseOperationStage.SEARCH, state.stage)
            assertTrue(state.error is ReleaseCompanionException.PollingExhausted)
            assertEquals(2, repository.searchPollCount)
        }

    @Test
    fun `double confirmation sends one acquisition request`() =
        runTest {
            val repository = FakeReleaseRepository()
            repository.acquired = { _, _ ->
                acquisition(
                    state = "available",
                    jellyfinItemId = "11111111-1111-1111-1111-111111111111",
                )
            }
            val controller = controller(repository)
            val selected = candidate()

            controller.confirm(ReleaseSubject.Movie(42), selected)
            controller.confirm(ReleaseSubject.Movie(42), selected)
            advanceUntilIdle()

            assertEquals(1, repository.acquireCalls)
            assertTrue(controller.state.value is ReleaseWorkflowState.Available)
        }

    @Test
    fun `retry after uncertain network failure keeps the idempotency key`() =
        runTest {
            val repository = FakeReleaseRepository()
            val keys = mutableListOf<SensitiveValue>()
            repository.acquired = { _, key ->
                keys += key
                if (keys.size == 1) throw ReleaseCompanionException.Network(IOException("offline"))
                acquisition(
                    state = "available",
                    jellyfinItemId = "11111111-1111-1111-1111-111111111111",
                )
            }
            val controller = controller(repository)
            val selected = candidate()

            controller.confirm(ReleaseSubject.Movie(42), selected)
            advanceUntilIdle()
            assertTrue(controller.state.value is ReleaseWorkflowState.Failed)

            controller.retry(ReleaseSubject.Movie(42))
            advanceUntilIdle()

            assertEquals(2, repository.acquireCalls)
            assertSame(keys[0], keys[1])
            assertTrue(controller.state.value is ReleaseWorkflowState.Available)
        }

    @Test
    fun `tracking network failure retains canonical job for a safe retry`() =
        runTest {
            val repository = FakeReleaseRepository()
            repository.rehydrated = acquisition(state = "downloading")
            repository.polledAcquisition = {
                throw ReleaseCompanionException.Network(IOException("offline"))
            }
            val controller = controller(repository)
            val subject = ReleaseSubject.Movie(42)

            controller.resume(subject)
            advanceUntilIdle()

            val failed = controller.state.value as ReleaseWorkflowState.Failed
            assertEquals(ReleaseOperationStage.TRACKING, failed.stage)
            assertEquals("acquisition-1", failed.lastAcquisition?.id)
        }

    @Test
    fun `unapproved candidate never reaches the repository`() =
        runTest {
            val repository = FakeReleaseRepository()
            val controller = controller(repository)

            controller.confirm(
                ReleaseSubject.Movie(42),
                candidate(approved = false),
            )
            advanceUntilIdle()

            assertEquals(0, repository.acquireCalls)
            val state = controller.state.value as ReleaseWorkflowState.Failed
            assertTrue(state.error is ReleaseCompanionException.SelectionRejected)
        }

    @Test
    fun `malformed candidate expiry starts a fresh search instead of retrying its token`() =
        runTest {
            val repository = FakeReleaseRepository()
            val controller = controller(repository)
            val subject = ReleaseSubject.Movie(42)

            controller.confirm(subject, candidate(expiresAt = "not-an-instant"))
            advanceUntilIdle()

            assertEquals(0, repository.acquireCalls)
            assertTrue(controller.state.value is ReleaseWorkflowState.SearchExpired)

            controller.retry(subject)
            advanceUntilIdle()

            assertEquals(0, repository.acquireCalls)
            assertTrue(controller.state.value is ReleaseWorkflowState.Empty)
        }

    @Test
    fun `available requires a Jellyfin item id`() =
        runTest {
            val repository = FakeReleaseRepository()
            repository.rehydrated = acquisition(state = "available", jellyfinItemId = null)
            val controller = controller(repository)

            controller.resume(ReleaseSubject.Movie(42))
            advanceUntilIdle()

            val state = controller.state.value as ReleaseWorkflowState.Failed
            assertEquals(ReleaseOperationStage.TRACKING, state.stage)
            assertTrue(state.error is ReleaseCompanionException.InvalidResponse)
        }

    @Test
    fun `stopping cancels an in-flight canonical poll`() =
        runTest {
            val repository = FakeReleaseRepository()
            repository.startedSearch = { subject -> search(subject, "running") }
            var pollWasCancelled = false
            repository.polledSearch = {
                try {
                    awaitCancellation()
                } finally {
                    pollWasCancelled = true
                }
            }
            val controller = controller(repository)

            controller.search(ReleaseSubject.Movie(42))
            runCurrent()
            controller.stopPolling()
            runCurrent()

            assertTrue(pollWasCancelled)
        }

    private fun kotlinx.coroutines.test.TestScope.controller(
        repository: FakeReleaseRepository,
        searchPolicy: BoundedPollingPolicy = BoundedPollingPolicy.Search,
    ) = ReleaseAcquisitionController(
        repository = repository,
        scope = this,
        searchPolicy = searchPolicy,
        acquisitionPolicy =
            BoundedPollingPolicy(
                initialDelayMillis = 0,
                maximumDelayMillis = 0,
                multiplier = 1.0,
                maximumAttempts = 10,
                maximumElapsedMillis = 60_000,
            ),
        pollDelay = ReleasePollDelay { },
        monotonicClock = ReleaseMonotonicClock { 0 },
        wallClock = ReleaseClock { Instant.parse("2026-10-03T12:00:00Z") },
        idempotencyKeyFactory = ReleaseIdempotencyKeyFactory { SensitiveValue.of("stable-key-123456") },
    )
}

private class FakeReleaseRepository : ReleaseCompanionRepository {
    val rehydratedSubjects = mutableListOf<ReleaseSubject>()
    val rehydratedSeriesIds = mutableListOf<Int>()
    var rehydrated: AcquisitionJobDto? = null
    var rehydratedSeries: AcquisitionJobDto? = null
    var startedSearch: suspend (ReleaseSubject) -> ReleaseSearchDto = { search(it, "completed") }
    var polledSearch: suspend (String) -> ReleaseSearchDto = {
        search(ReleaseSubject.Movie(42), "completed")
    }
    var acquired: suspend (String, SensitiveValue) -> AcquisitionJobDto = { _, _ -> acquisition() }
    var polledAcquisition: suspend (String) -> AcquisitionJobDto = {
        acquisition(state = "available", jellyfinItemId = "11111111-1111-1111-1111-111111111111")
    }
    var acquireCalls = 0
    var searchPollCount = 0

    override suspend fun capabilities(): CompanionCapabilitiesDto = CompanionCapabilitiesDto(false, false)

    override suspend fun startSearch(subject: ReleaseSubject): ReleaseSearchDto = startedSearch(subject)

    override suspend fun getSearch(searchId: String): ReleaseSearchDto {
        searchPollCount += 1
        return polledSearch(searchId)
    }

    override suspend fun acquire(
        selectionToken: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto {
        acquireCalls += 1
        return acquired(selectionToken, idempotencyKey)
    }

    override suspend fun rehydrate(subject: ReleaseSubject): AcquisitionJobDto? {
        rehydratedSubjects += subject
        return rehydrated
    }

    override suspend fun rehydrateLatestSeries(tmdbId: Int): AcquisitionJobDto? {
        rehydratedSeriesIds += tmdbId
        return rehydratedSeries
    }

    override suspend fun getAcquisition(acquisitionId: String): AcquisitionJobDto = polledAcquisition(acquisitionId)

    override suspend fun cancel(
        acquisitionId: String,
        idempotencyKey: SensitiveValue,
    ): AcquisitionJobDto = acquisition(state = "cancelled")

    override suspend fun clearSession(revokeRemotely: Boolean) = Unit
}

private fun search(
    subject: ReleaseSubject,
    state: String,
    releases: List<ReleaseCandidate> = emptyList(),
): ReleaseSearchDto =
    ReleaseSearchDto(
        searchId = "search-1",
        subject = subject.toDto(),
        state = state,
        expiresAt = "2099-01-01T00:00:00Z",
        releases = releases,
    )
