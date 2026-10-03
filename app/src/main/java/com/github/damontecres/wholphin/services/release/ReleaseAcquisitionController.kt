package com.github.damontecres.wholphin.services.release

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlin.math.min

sealed interface ReleaseWorkflowState {
    data object Idle : ReleaseWorkflowState

    data class Rehydrating(
        val subject: ReleaseSubject,
    ) : ReleaseWorkflowState

    data class Ready(
        val subject: ReleaseSubject,
    ) : ReleaseWorkflowState

    data class Searching(
        val subject: ReleaseSubject,
        val latest: ReleaseSearchDto? = null,
    ) : ReleaseWorkflowState

    data class Results(
        val subject: ReleaseSubject,
        val search: ReleaseSearchDto,
    ) : ReleaseWorkflowState

    data class Empty(
        val subject: ReleaseSubject,
        val search: ReleaseSearchDto,
    ) : ReleaseWorkflowState

    data class SearchExpired(
        val subject: ReleaseSubject,
    ) : ReleaseWorkflowState

    data class Submitting(
        val subject: ReleaseSubject,
        val release: ReleaseCandidate,
    ) : ReleaseWorkflowState

    data class Tracking(
        val subject: ReleaseSubject,
        val acquisition: AcquisitionJobDto,
    ) : ReleaseWorkflowState

    data class Available(
        val subject: ReleaseSubject,
        val acquisition: AcquisitionJobDto,
        val jellyfinItemId: String,
    ) : ReleaseWorkflowState

    data class Cancelled(
        val subject: ReleaseSubject,
        val acquisition: AcquisitionJobDto,
    ) : ReleaseWorkflowState

    data class Failed(
        val subject: ReleaseSubject,
        val stage: ReleaseOperationStage,
        val error: ReleaseCompanionException,
        val lastSearch: ReleaseSearchDto? = null,
        val lastAcquisition: AcquisitionJobDto? = null,
    ) : ReleaseWorkflowState
}

data class BoundedPollingPolicy(
    val initialDelayMillis: Long,
    val maximumDelayMillis: Long,
    val multiplier: Double,
    val maximumAttempts: Int,
    val maximumElapsedMillis: Long,
) {
    init {
        require(initialDelayMillis >= 0)
        require(maximumDelayMillis >= initialDelayMillis)
        require(multiplier >= 1.0)
        require(maximumAttempts > 0)
        require(maximumElapsedMillis > 0)
    }

    internal fun nextDelay(current: Long): Long = min(maximumDelayMillis.toDouble(), current * multiplier).toLong()

    companion object {
        val Search =
            BoundedPollingPolicy(
                initialDelayMillis = 1_000,
                maximumDelayMillis = 8_000,
                multiplier = 1.7,
                maximumAttempts = 30,
                maximumElapsedMillis = 120_000,
            )

        val Acquisition =
            BoundedPollingPolicy(
                initialDelayMillis = 2_000,
                maximumDelayMillis = 30_000,
                multiplier = 1.6,
                maximumAttempts = 720,
                maximumElapsedMillis = 6 * 60 * 60 * 1_000L,
            )
    }
}

fun interface ReleasePollDelay {
    suspend fun await(millis: Long)
}

fun interface ReleaseMonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

fun interface ReleaseIdempotencyKeyFactory {
    fun create(): SensitiveValue
}

/**
 * Lifecycle-aware coordinator intended to be owned by a ViewModel.
 *
 * [stopPolling] is called when the detail page is no longer visible. [resume] always rehydrates
 * canonical server state before polling again, so Activity recreation and process restart never
 * depend on an in-memory progress value.
 */
class ReleaseAcquisitionController(
    private val repository: ReleaseCompanionRepository,
    private val scope: CoroutineScope,
    private val searchPolicy: BoundedPollingPolicy = BoundedPollingPolicy.Search,
    private val acquisitionPolicy: BoundedPollingPolicy = BoundedPollingPolicy.Acquisition,
    private val pollDelay: ReleasePollDelay = ReleasePollDelay { delay(it) },
    private val monotonicClock: ReleaseMonotonicClock =
        ReleaseMonotonicClock { System.nanoTime() / 1_000_000L },
    private val wallClock: ReleaseClock = ReleaseClock { Instant.now() },
    private val idempotencyKeyFactory: ReleaseIdempotencyKeyFactory =
        ReleaseIdempotencyKeyFactory { SensitiveValue.of(UUID.randomUUID().toString()) },
) {
    private val stateFlow = MutableStateFlow<ReleaseWorkflowState>(ReleaseWorkflowState.Idle)
    private val operationLock = Any()
    private val idempotencyKeys = mutableMapOf<String, SensitiveValue>()
    private val acceptedSelections = mutableSetOf<String>()

    private var operation: Job? = null
    private var confirmingFingerprint: String? = null
    private var lastSelection: Selection? = null

    val state: StateFlow<ReleaseWorkflowState> = stateFlow.asStateFlow()

    fun resume(subject: ReleaseSubject) {
        launchReplacing {
            stateFlow.value = ReleaseWorkflowState.Rehydrating(subject)
            try {
                val acquisition = repository.rehydrate(subject)
                if (acquisition == null) {
                    stateFlow.value = ReleaseWorkflowState.Ready(subject)
                } else {
                    followAcquisition(subject, acquisition)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ReleaseCompanionException) {
                fail(subject, ReleaseOperationStage.REHYDRATION, failure)
            }
        }
    }

    /** Resumes only the operation that was interrupted by leaving the screen. */
    fun resumeAfterLifecycle(subject: ReleaseSubject) {
        when (val current = stateFlow.value) {
            ReleaseWorkflowState.Idle,
            is ReleaseWorkflowState.Ready,
            is ReleaseWorkflowState.Rehydrating,
            is ReleaseWorkflowState.Tracking,
            -> {
                resume(subject)
            }

            is ReleaseWorkflowState.Searching -> {
                val latest = current.latest
                if (latest == null) {
                    search(subject)
                } else {
                    launchReplacing {
                        try {
                            followSearch(subject, latest)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: ReleaseCompanionException.SelectionExpired) {
                            stateFlow.value = ReleaseWorkflowState.SearchExpired(subject)
                        } catch (failure: ReleaseCompanionException) {
                            fail(
                                subject = subject,
                                stage = ReleaseOperationStage.SEARCH,
                                failure = failure,
                                lastSearch = latest,
                            )
                        }
                    }
                }
            }

            is ReleaseWorkflowState.Submitting -> {
                val fingerprint = current.release.selectionToken.redactedFingerprint()
                val wasAccepted = synchronized(operationLock) { fingerprint in acceptedSelections }
                if (wasAccepted) {
                    resume(subject)
                } else {
                    confirm(subject, current.release)
                }
            }

            is ReleaseWorkflowState.Available,
            is ReleaseWorkflowState.Cancelled,
            is ReleaseWorkflowState.Empty,
            is ReleaseWorkflowState.Failed,
            is ReleaseWorkflowState.Results,
            is ReleaseWorkflowState.SearchExpired,
            -> {}
        }
    }

    /** Retries without changing an uncertain exact selection into a different release. */
    fun retry(subject: ReleaseSubject) {
        when (val current = stateFlow.value) {
            is ReleaseWorkflowState.Failed -> {
                when {
                    current.stage == ReleaseOperationStage.SELECTION &&
                        current.error !is ReleaseCompanionException.SelectionRejected -> {
                        val selection = synchronized(operationLock) { lastSelection }
                        if (selection?.subject == subject) {
                            confirm(subject, selection.release)
                        } else {
                            recoverOrSearch(subject)
                        }
                    }

                    current.lastAcquisition != null &&
                        current.lastAcquisition.phase != AcquisitionPhase.ERROR -> {
                        resume(subject)
                    }

                    current.stage == ReleaseOperationStage.REHYDRATION -> {
                        recoverOrSearch(subject)
                    }

                    else -> {
                        search(subject)
                    }
                }
            }

            is ReleaseWorkflowState.Tracking,
            is ReleaseWorkflowState.Submitting,
            is ReleaseWorkflowState.Rehydrating,
            -> {
                resumeAfterLifecycle(subject)
            }

            else -> {
                search(subject)
            }
        }
    }

    /**
     * Restores the newest season or episode job for a series when process state no longer contains
     * the exact subject. The BFF remains authoritative and filters jobs to the current user.
     *
     * [fallbackSubject] is used only to represent loading/no-result/error in the existing UI state;
     * a recovered job always replaces it with its exact server-provided subject.
     */
    fun resumeLatestSeries(
        tmdbId: Int,
        fallbackSubject: ReleaseSubject.TvSeason = ReleaseSubject.TvSeason(tmdbId, seasonNumber = 0),
        onSubjectResolved: (ReleaseSubject) -> Unit = {},
    ) {
        require(tmdbId > 0) { "tmdbId must be positive" }
        require(fallbackSubject.tmdbId == tmdbId) { "fallback subject must identify the same series" }
        launchReplacing {
            stateFlow.value = ReleaseWorkflowState.Rehydrating(fallbackSubject)
            try {
                val acquisition = repository.rehydrateLatestSeries(tmdbId)
                if (acquisition == null) {
                    stateFlow.value = ReleaseWorkflowState.Ready(fallbackSubject)
                    return@launchReplacing
                }
                val resolved = acquisition.subject.toDomain()
                if (
                    resolved.tmdbId != tmdbId ||
                    resolved is ReleaseSubject.Movie
                ) {
                    throw ReleaseCompanionException.InvalidResponse(
                        "Companion returned a non-series acquisition for series rehydration",
                    )
                }
                onSubjectResolved(resolved)
                followAcquisition(resolved, acquisition)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ReleaseCompanionException) {
                fail(fallbackSubject, ReleaseOperationStage.REHYDRATION, failure)
            } catch (failure: IllegalArgumentException) {
                fail(
                    fallbackSubject,
                    ReleaseOperationStage.REHYDRATION,
                    ReleaseCompanionException.InvalidResponse(
                        "Companion returned an invalid series acquisition subject",
                        failure,
                    ),
                )
            }
        }
    }

    fun search(subject: ReleaseSubject) {
        launchReplacing {
            stateFlow.value = ReleaseWorkflowState.Searching(subject)
            try {
                val search = repository.startSearch(subject)
                followSearch(subject, search)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ReleaseCompanionException.SelectionExpired) {
                stateFlow.value = ReleaseWorkflowState.SearchExpired(subject)
            } catch (failure: ReleaseCompanionException) {
                fail(subject, ReleaseOperationStage.SEARCH, failure)
            }
        }
    }

    /** Duplicate D-pad confirmations share one in-flight operation and one idempotency key. */
    fun confirm(
        subject: ReleaseSubject,
        release: ReleaseCandidate,
    ) {
        val fingerprint = release.selectionToken.redactedFingerprint()
        synchronized(operationLock) {
            if (fingerprint in acceptedSelections || confirmingFingerprint == fingerprint) return
            operation?.cancel()
            confirmingFingerprint = fingerprint
            lastSelection = Selection(subject, release)
            val idempotencyKey =
                idempotencyKeys.getOrPut(fingerprint) { idempotencyKeyFactory.create() }
            operation =
                scope.launch {
                    try {
                        validateSelection(release)
                        stateFlow.value = ReleaseWorkflowState.Submitting(subject, release)
                        val acquisition =
                            repository.acquire(
                                selectionToken = release.selectionToken,
                                idempotencyKey = idempotencyKey,
                            )
                        synchronized(operationLock) { acceptedSelections += fingerprint }
                        followAcquisition(subject, acquisition)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: ReleaseCompanionException.SelectionExpired) {
                        stateFlow.value = ReleaseWorkflowState.SearchExpired(subject)
                    } catch (failure: ReleaseCompanionException) {
                        fail(subject, ReleaseOperationStage.SELECTION, failure)
                    } finally {
                        synchronized(operationLock) {
                            if (confirmingFingerprint == fingerprint) confirmingFingerprint = null
                        }
                    }
                }
        }
    }

    fun cancel(
        subject: ReleaseSubject,
        acquisitionId: String,
    ) {
        launchReplacing {
            val key =
                synchronized(operationLock) {
                    idempotencyKeys.getOrPut("cancel:$acquisitionId") {
                        idempotencyKeyFactory.create()
                    }
                }
            try {
                val acquisition = repository.cancel(acquisitionId, key)
                transitionAcquisition(subject, acquisition)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ReleaseCompanionException) {
                fail(
                    subject = subject,
                    stage = ReleaseOperationStage.CANCELLATION,
                    failure = failure,
                    lastAcquisition = currentAcquisition(),
                )
            }
        }
    }

    /** Cancels delays and in-flight calls. The last canonical state remains displayable. */
    fun stopPolling() {
        synchronized(operationLock) {
            operation?.cancel()
            operation = null
            confirmingFingerprint = null
        }
    }

    private fun launchReplacing(block: suspend () -> Unit) {
        synchronized(operationLock) {
            operation?.cancel()
            operation = scope.launch { block() }
        }
    }

    private suspend fun followSearch(
        subject: ReleaseSubject,
        initial: ReleaseSearchDto,
    ) {
        var latest = initial
        val startedAt = monotonicClock.elapsedRealtimeMillis()
        var delayMillis = searchPolicy.initialDelayMillis
        var attempts = 0
        while (true) {
            when (latest.phase) {
                ReleaseSearchPhase.COMPLETED -> {
                    stateFlow.value =
                        if (latest.releases.isEmpty()) {
                            ReleaseWorkflowState.Empty(subject, latest)
                        } else {
                            ReleaseWorkflowState.Results(subject, latest)
                        }
                    return
                }

                ReleaseSearchPhase.EXPIRED -> {
                    stateFlow.value = ReleaseWorkflowState.SearchExpired(subject)
                    return
                }

                ReleaseSearchPhase.FAILED -> {
                    fail(
                        subject = subject,
                        stage = ReleaseOperationStage.SEARCH,
                        failure = latest.error.toOperationFailure(),
                        lastSearch = latest,
                    )
                    return
                }

                ReleaseSearchPhase.UNKNOWN -> {
                    fail(
                        subject = subject,
                        stage = ReleaseOperationStage.SEARCH,
                        failure =
                            ReleaseCompanionException.InvalidResponse(
                                "Companion returned an unknown release search state",
                            ),
                        lastSearch = latest,
                    )
                    return
                }

                ReleaseSearchPhase.PENDING,
                ReleaseSearchPhase.RUNNING,
                -> {
                    stateFlow.value = ReleaseWorkflowState.Searching(subject, latest)
                }
            }
            if (pollingExhausted(searchPolicy, startedAt, attempts)) {
                fail(
                    subject = subject,
                    stage = ReleaseOperationStage.SEARCH,
                    failure = ReleaseCompanionException.PollingExhausted(attempts),
                    lastSearch = latest,
                )
                return
            }
            pollDelay.await(delayMillis)
            latest = repository.getSearch(latest.searchId)
            attempts += 1
            delayMillis = searchPolicy.nextDelay(delayMillis)
        }
    }

    private suspend fun followAcquisition(
        subject: ReleaseSubject,
        initial: AcquisitionJobDto,
    ) {
        var latest = initial
        val startedAt = monotonicClock.elapsedRealtimeMillis()
        var delayMillis = acquisitionPolicy.initialDelayMillis
        var attempts = 0
        while (true) {
            if (transitionAcquisition(subject, latest)) return
            if (pollingExhausted(acquisitionPolicy, startedAt, attempts)) {
                fail(
                    subject = subject,
                    stage = ReleaseOperationStage.TRACKING,
                    failure = ReleaseCompanionException.PollingExhausted(attempts),
                    lastAcquisition = latest,
                )
                return
            }
            pollDelay.await(delayMillis)
            latest =
                try {
                    repository.getAcquisition(latest.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: ReleaseCompanionException) {
                    fail(
                        subject = subject,
                        stage = ReleaseOperationStage.TRACKING,
                        failure = failure,
                        lastAcquisition = latest,
                    )
                    return
                }
            attempts += 1
            delayMillis = acquisitionPolicy.nextDelay(delayMillis)
        }
    }

    private fun recoverOrSearch(subject: ReleaseSubject) {
        launchReplacing {
            stateFlow.value = ReleaseWorkflowState.Rehydrating(subject)
            try {
                val acquisition = repository.rehydrate(subject)
                if (
                    acquisition != null &&
                    acquisition.phase != AcquisitionPhase.ERROR &&
                    acquisition.phase != AcquisitionPhase.CANCELLED
                ) {
                    followAcquisition(subject, acquisition)
                } else {
                    stateFlow.value = ReleaseWorkflowState.Searching(subject)
                    followSearch(subject, repository.startSearch(subject))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ReleaseCompanionException.SelectionExpired) {
                stateFlow.value = ReleaseWorkflowState.SearchExpired(subject)
            } catch (failure: ReleaseCompanionException) {
                fail(subject, ReleaseOperationStage.REHYDRATION, failure)
            }
        }
    }

    /** Returns true when no further polling is required. */
    private fun transitionAcquisition(
        subject: ReleaseSubject,
        acquisition: AcquisitionJobDto,
    ): Boolean =
        when (acquisition.phase) {
            AcquisitionPhase.AVAILABLE -> {
                val jellyfinItemId =
                    acquisition.jellyfinUuid?.toString()
                        ?: run {
                            fail(
                                subject = subject,
                                stage = ReleaseOperationStage.TRACKING,
                                failure =
                                    ReleaseCompanionException.InvalidResponse(
                                        "Available acquisition has no valid Jellyfin item id",
                                    ),
                                lastAcquisition = acquisition,
                            )
                            return true
                        }
                stateFlow.value =
                    ReleaseWorkflowState.Available(subject, acquisition, jellyfinItemId)
                true
            }

            AcquisitionPhase.CANCELLED -> {
                stateFlow.value = ReleaseWorkflowState.Cancelled(subject, acquisition)
                true
            }

            AcquisitionPhase.ERROR -> {
                fail(
                    subject = subject,
                    stage = ReleaseOperationStage.TRACKING,
                    failure = acquisition.error.toOperationFailure(),
                    lastAcquisition = acquisition,
                )
                true
            }

            AcquisitionPhase.UNKNOWN -> {
                fail(
                    subject = subject,
                    stage = ReleaseOperationStage.TRACKING,
                    failure =
                        ReleaseCompanionException.InvalidResponse(
                            "Companion returned an unknown acquisition state",
                        ),
                    lastAcquisition = acquisition,
                )
                true
            }

            else -> {
                stateFlow.value = ReleaseWorkflowState.Tracking(subject, acquisition)
                false
            }
        }

    private fun validateSelection(release: ReleaseCandidate) {
        if (!release.selectable) {
            throw ReleaseCompanionException.SelectionRejected("release_not_approved")
        }
        val expiresAt =
            release.expiresAtInstant()
                ?: throw ReleaseCompanionException.SelectionExpired(
                    "invalid_release_expiration",
                )
        if (!expiresAt.isAfter(wallClock.now())) {
            throw ReleaseCompanionException.SelectionExpired("release_token_expired")
        }
    }

    private fun pollingExhausted(
        policy: BoundedPollingPolicy,
        startedAt: Long,
        attempts: Int,
    ): Boolean =
        attempts >= policy.maximumAttempts ||
            monotonicClock.elapsedRealtimeMillis() - startedAt >= policy.maximumElapsedMillis

    private fun fail(
        subject: ReleaseSubject,
        stage: ReleaseOperationStage,
        failure: ReleaseCompanionException,
        lastSearch: ReleaseSearchDto? = null,
        lastAcquisition: AcquisitionJobDto? = null,
    ) {
        stateFlow.value =
            ReleaseWorkflowState.Failed(
                subject = subject,
                stage = stage,
                error = failure,
                lastSearch = lastSearch,
                lastAcquisition = lastAcquisition,
            )
    }

    private fun currentAcquisition(): AcquisitionJobDto? =
        when (val current = stateFlow.value) {
            is ReleaseWorkflowState.Tracking -> current.acquisition
            is ReleaseWorkflowState.Available -> current.acquisition
            is ReleaseWorkflowState.Cancelled -> current.acquisition
            is ReleaseWorkflowState.Failed -> current.lastAcquisition
            else -> null
        }
}

private data class Selection(
    val subject: ReleaseSubject,
    val release: ReleaseCandidate,
)

private fun CompanionErrorDto?.toOperationFailure(): ReleaseCompanionException.RemoteOperationFailed =
    ReleaseCompanionException.RemoteOperationFailed(
        errorCode = this?.code,
        retryable = this?.retryable ?: false,
    )
