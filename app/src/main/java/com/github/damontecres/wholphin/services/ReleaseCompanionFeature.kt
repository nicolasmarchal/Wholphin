package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.services.release.ReleaseCompanionException
import com.github.damontecres.wholphin.services.release.ReleaseCompanionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime availability and configuration of the optional release companion.
 *
 * The repository is replaced atomically when the user changes the companion address. Invalid
 * persisted configuration fails closed, while the classic Seerr request flow remains available.
 */
data class ReleaseCompanionConfiguration(
    val baseUrl: String,
    val enabled: Boolean,
)

class ReleaseCompanionFeature private constructor(
    private val buildEnabled: Boolean,
    initialBaseUrl: String,
    private val baseUrlNormalizer: ((String) -> String)?,
    private val repositoryFactory: ((String) -> ReleaseCompanionRepository)?,
    private val connectionTester: (suspend (String) -> Unit)?,
    initialRepository: ReleaseCompanionRepository? = null,
) {
    private data class Snapshot(
        val configuration: ReleaseCompanionConfiguration,
        val repository: ReleaseCompanionRepository?,
    )

    @Volatile
    private var snapshot =
        if (initialRepository != null) {
            Snapshot(
                configuration = ReleaseCompanionConfiguration(initialBaseUrl, enabled = true),
                repository = initialRepository,
            )
        } else {
            createSnapshot(initialBaseUrl)
        }

    private val _configuration = MutableStateFlow(snapshot.configuration)
    val configuration: StateFlow<ReleaseCompanionConfiguration> = _configuration.asStateFlow()

    val enabled: Boolean
        get() = snapshot.configuration.enabled

    val repository: ReleaseCompanionRepository?
        get() = snapshot.repository

    /** Applies a persisted address without letting invalid input break application startup. */
    @Synchronized
    fun configure(baseUrl: String) {
        val next = createSnapshot(baseUrl)
        snapshot = next
        _configuration.value = next.configuration
    }

    /** Validates and probes an address without changing the active repository. */
    suspend fun verifyConnection(baseUrl: String): String {
        check(buildEnabled && baseUrlNormalizer != null && connectionTester != null) {
            "The release companion is disabled in this build"
        }
        val normalized = baseUrlNormalizer.invoke(baseUrl.trim())
        connectionTester.invoke(normalized)
        return normalized
    }

    private fun createSnapshot(baseUrl: String): Snapshot {
        val value = baseUrl.trim()
        if (!buildEnabled || value.isEmpty() || baseUrlNormalizer == null || repositoryFactory == null) {
            return Snapshot(
                configuration = ReleaseCompanionConfiguration(value, enabled = false),
                repository = null,
            )
        }

        return try {
            val normalized = baseUrlNormalizer.invoke(value)
            Snapshot(
                configuration = ReleaseCompanionConfiguration(normalized, enabled = true),
                repository = repositoryFactory.invoke(normalized),
            )
        } catch (_: ReleaseCompanionException.InvalidConfiguration) {
            Snapshot(
                configuration = ReleaseCompanionConfiguration(value, enabled = false),
                repository = null,
            )
        }
    }

    companion object {
        fun disabled(): ReleaseCompanionFeature =
            ReleaseCompanionFeature(
                buildEnabled = false,
                initialBaseUrl = "",
                baseUrlNormalizer = null,
                repositoryFactory = null,
                connectionTester = null,
            )

        fun enabled(repository: ReleaseCompanionRepository): ReleaseCompanionFeature =
            ReleaseCompanionFeature(
                buildEnabled = true,
                initialBaseUrl = "",
                baseUrlNormalizer = null,
                repositoryFactory = null,
                connectionTester = null,
                initialRepository = repository,
            )

        fun configurable(
            buildEnabled: Boolean,
            initialBaseUrl: String,
            baseUrlNormalizer: (String) -> String,
            repositoryFactory: (String) -> ReleaseCompanionRepository,
            connectionTester: suspend (String) -> Unit,
        ): ReleaseCompanionFeature =
            ReleaseCompanionFeature(
                buildEnabled = buildEnabled,
                initialBaseUrl = initialBaseUrl,
                baseUrlNormalizer = baseUrlNormalizer,
                repositoryFactory = repositoryFactory,
                connectionTester = connectionTester,
            )
    }
}
