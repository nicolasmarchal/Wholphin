package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.services.release.ReleaseCompanionRepository

/**
 * Runtime availability of the optional release companion.
 *
 * The repository is absent when this build flavor disables the feature, no URL was supplied, or
 * the configured origin is invalid. Callers must keep the existing Seerr request flow available
 * whenever [enabled] is false.
 */
data class ReleaseCompanionFeature(
    val enabled: Boolean,
    val repository: ReleaseCompanionRepository?,
) {
    init {
        require(enabled == (repository != null)) {
            "An enabled release companion must have a repository"
        }
    }

    companion object {
        fun disabled(): ReleaseCompanionFeature =
            ReleaseCompanionFeature(
                enabled = false,
                repository = null,
            )

        fun enabled(repository: ReleaseCompanionRepository): ReleaseCompanionFeature =
            ReleaseCompanionFeature(
                enabled = true,
                repository = repository,
            )
    }
}
