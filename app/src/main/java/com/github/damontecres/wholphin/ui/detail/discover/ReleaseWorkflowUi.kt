package com.github.damontecres.wholphin.ui.detail.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.model.Season
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.services.release.AcquisitionJobDto
import com.github.damontecres.wholphin.services.release.AcquisitionPhase
import com.github.damontecres.wholphin.services.release.ReleaseCandidate
import com.github.damontecres.wholphin.services.release.ReleaseCompanionException
import com.github.damontecres.wholphin.services.release.ReleaseSubject
import com.github.damontecres.wholphin.services.release.ReleaseWorkflowState
import com.github.damontecres.wholphin.services.release.matchesSubject
import com.github.damontecres.wholphin.services.release.redactedFingerprint
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import java.util.Locale

internal data class ReleaseQualitySection(
    val label: String?,
    val releases: List<ReleaseCandidate>,
)

private data class ReleaseQualityCategory(
    val label: String?,
    val resolutionHeight: Int?,
)

private sealed interface ReleaseResultListItem {
    data class QualityHeader(
        val sectionIndex: Int,
        val label: String?,
    ) : ReleaseResultListItem

    data class ReleaseRow(
        val releaseIndex: Int,
        val release: ReleaseCandidate,
        val isLastInSection: Boolean,
    ) : ReleaseResultListItem
}

private val releaseResolutionPattern =
    Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)(?:[pi])?(?!\d)""")
private val releaseFourKPattern = Regex("""(?:^|[^a-z0-9])4k(?:$|[^a-z0-9])""")

internal fun groupReleasesByQuality(releases: List<ReleaseCandidate>): List<ReleaseQualitySection> =
    releases
        .groupBy { release -> release.quality.toQualityCategory() }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<ReleaseQualityCategory, List<ReleaseCandidate>>> {
                it.key.resolutionHeight ?: -1
            }.thenBy { it.key.label == null }
                .thenBy { it.key.label?.lowercase(Locale.ROOT).orEmpty() },
        ).map { (category, candidates) ->
            ReleaseQualitySection(
                label = category.label,
                releases = candidates.sortedBy(ReleaseCandidate::sizeBytes),
            )
        }

private fun String.toQualityCategory(): ReleaseQualityCategory {
    val original = trim().takeIf(String::isNotEmpty)
    val normalized = original?.lowercase(Locale.ROOT).orEmpty()
    val resolutionHeight =
        when {
            normalized.contains("uhd") || releaseFourKPattern.containsMatchIn(normalized) -> 2160
            else -> releaseResolutionPattern.find(normalized)?.groupValues?.get(1)?.toInt()
        }
    val label =
        when (resolutionHeight) {
            2160 -> "4K"
            null -> original
            else -> "${resolutionHeight}p"
        }
    return ReleaseQualityCategory(
        label = label,
        resolutionHeight = resolutionHeight,
    )
}

private fun List<ReleaseQualitySection>.toResultListItems(): List<ReleaseResultListItem> =
    buildList {
        var releaseIndex = 0
        this@toResultListItems.forEachIndexed { sectionIndex, section ->
            add(ReleaseResultListItem.QualityHeader(sectionIndex, section.label))
            section.releases.forEachIndexed { indexInSection, release ->
                add(
                    ReleaseResultListItem.ReleaseRow(
                        releaseIndex = releaseIndex++,
                        release = release,
                        isLastInSection = indexInSection == section.releases.lastIndex,
                    ),
                )
            }
        }
    }

data class DiscoverPrimaryAction(
    @param:StringRes val title: Int,
    @param:StringRes val icon: Int,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal fun shouldShowCompanionAction(
    enabled: Boolean,
    availability: SeerrAvailability,
    state: ReleaseWorkflowState,
): Boolean =
    enabled &&
        (
            availability == SeerrAvailability.UNKNOWN ||
                availability == SeerrAvailability.PARTIALLY_AVAILABLE ||
                state is ReleaseWorkflowState.Submitting ||
                state is ReleaseWorkflowState.Tracking ||
                state is ReleaseWorkflowState.Available ||
                state is ReleaseWorkflowState.Cancelled ||
                (state is ReleaseWorkflowState.Failed && state.lastAcquisition != null)
        )

@StringRes
internal fun releasePrimaryTitle(
    state: ReleaseWorkflowState,
    series: Boolean,
): Int =
    when (state) {
        is ReleaseWorkflowState.Available -> if (series) R.string.go_to_series else R.string.play

        is ReleaseWorkflowState.Submitting,
        is ReleaseWorkflowState.Tracking,
        is ReleaseWorkflowState.Rehydrating,
        -> R.string.release_progress

        else -> R.string.release_see_releases
    }

@Composable
fun ReleaseCompactStatus(
    state: ReleaseWorkflowState,
    modifier: Modifier = Modifier,
) {
    val acquisition =
        when (state) {
            is ReleaseWorkflowState.Tracking -> state.acquisition
            is ReleaseWorkflowState.Available -> state.acquisition
            is ReleaseWorkflowState.Cancelled -> state.acquisition
            is ReleaseWorkflowState.Failed -> state.lastAcquisition
            else -> null
        }
    if (acquisition == null && state !is ReleaseWorkflowState.Submitting) return

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text =
                acquisition?.let { stringResource(it.phase.labelRes()) }
                    ?: stringResource(R.string.release_sending),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        acquisition?.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { (progress / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(.55f),
            )
        }
        acquisition?.progressSummary()?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        acquisition?.transientErrorRes()?.let { message ->
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun ReleaseWorkflowDialog(
    state: ReleaseWorkflowState,
    subject: ReleaseSubject?,
    canFallback: Boolean,
    restoreSelectionFingerprint: String?,
    onDismissRequest: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (ReleaseCandidate) -> Unit,
    onFallback: () -> Unit,
    onSearchEpisode: (() -> Unit)? = null,
) {
    when (state) {
        ReleaseWorkflowState.Idle,
        is ReleaseWorkflowState.Ready,
        -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_releases_title),
                message = null,
                onDismissRequest = onDismissRequest,
                primaryAction = stringResource(R.string.release_see_releases) to onRetry,
            )
        }

        is ReleaseWorkflowState.Rehydrating,
        is ReleaseWorkflowState.Searching,
        -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_releases_title),
                message =
                    if (state is ReleaseWorkflowState.Rehydrating) {
                        stringResource(R.string.release_restoring)
                    } else {
                        stringResource(R.string.release_searching)
                    },
                onDismissRequest = onDismissRequest,
            )
        }

        is ReleaseWorkflowState.Results -> {
            val compatible =
                state.search.releases.filter { release ->
                    subject?.let(release::matchesSubject) ?: release.selectable
                }
            ReleaseResultsDialog(
                releases = state.search.releases,
                subject = subject,
                restoreSelectionFingerprint = restoreSelectionFingerprint,
                showFallback = canFallback,
                showEpisodeSearch =
                    onSearchEpisode != null &&
                        subject is ReleaseSubject.TvSeason &&
                        compatible.none { it.fullSeason },
                onDismissRequest = onDismissRequest,
                onSelect = onSelect,
                onFallback = onFallback,
                onSearchEpisode = onSearchEpisode,
            )
        }

        is ReleaseWorkflowState.Empty -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_releases_title),
                message = stringResource(R.string.release_no_results),
                onDismissRequest = onDismissRequest,
                primaryAction = stringResource(R.string.retry) to onRetry,
                secondaryAction =
                    if (canFallback) {
                        stringResource(R.string.release_request_automatically) to onFallback
                    } else {
                        null
                    },
                tertiaryAction =
                    onSearchEpisode?.let {
                        stringResource(R.string.release_search_episode) to it
                    },
            )
        }

        is ReleaseWorkflowState.SearchExpired -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_result_expired),
                message = stringResource(R.string.release_result_expired_message),
                onDismissRequest = onDismissRequest,
                primaryAction = stringResource(R.string.retry) to onRetry,
                secondaryAction =
                    if (canFallback) {
                        stringResource(R.string.release_request_automatically) to onFallback
                    } else {
                        null
                    },
            )
        }

        is ReleaseWorkflowState.Submitting -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_progress),
                message = stringResource(R.string.release_sending),
                onDismissRequest = onDismissRequest,
            )
        }

        is ReleaseWorkflowState.Tracking -> {
            ReleaseProgressDialog(
                acquisition = state.acquisition,
                onDismissRequest = onDismissRequest,
            )
        }

        is ReleaseWorkflowState.Available -> {}

        is ReleaseWorkflowState.Cancelled -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_cancelled),
                message = state.acquisition.statusText,
                onDismissRequest = onDismissRequest,
                primaryAction = stringResource(R.string.retry) to onRetry,
            )
        }

        is ReleaseWorkflowState.Failed -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_error),
                message = stringResource(state.error.messageRes()),
                onDismissRequest = onDismissRequest,
                primaryAction = stringResource(R.string.retry) to onRetry,
                secondaryAction =
                    if (canFallback) {
                        stringResource(R.string.release_request_automatically) to onFallback
                    } else {
                        null
                    },
                tertiaryAction =
                    onSearchEpisode?.let {
                        stringResource(R.string.release_search_episode) to it
                    },
            )
        }
    }
}

@Composable
private fun ReleaseResultsDialog(
    releases: List<ReleaseCandidate>,
    subject: ReleaseSubject?,
    restoreSelectionFingerprint: String?,
    showFallback: Boolean,
    showEpisodeSearch: Boolean,
    onDismissRequest: () -> Unit,
    onSelect: (ReleaseCandidate) -> Unit,
    onFallback: () -> Unit,
    onSearchEpisode: (() -> Unit)?,
) {
    val sections = remember(releases) { groupReleasesByQuality(releases) }
    val listItems = remember(sections) { sections.toResultListItems() }
    val orderedReleases =
        remember(listItems) {
            listItems.filterIsInstance<ReleaseResultListItem.ReleaseRow>().map { it.release }
        }
    val focusRequesters = remember(orderedReleases) { List(orderedReleases.size) { FocusRequester() } }
    val firstActionFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(listItems, restoreSelectionFingerprint) {
        val restoredIndex =
            orderedReleases.indexOfFirst {
                it.selectionToken.redactedFingerprint() == restoreSelectionFingerprint
            }
        val firstSelectableIndex =
            orderedReleases.indexOfFirst { candidate ->
                subject?.let(candidate::matchesSubject) ?: candidate.selectable
            }
        // Rejected rows deliberately remain focusable so a D-pad user can inspect the reason.
        // If nothing can be selected, focus the first result so its rejection reason stays
        // reachable even when no fallback button exists.
        val index =
            restoredIndex.takeIf { it >= 0 }
                ?: firstSelectableIndex.takeIf { it >= 0 }
                ?: orderedReleases.indices.firstOrNull()
                ?: -1
        if (index >= 0) {
            val listItemIndex =
                listItems.indexOfFirst { item ->
                    item is ReleaseResultListItem.ReleaseRow && item.releaseIndex == index
                }
            listState.scrollToItem(listItemIndex)
            focusRequesters[index].tryRequestFocus("release-result-$index")
        } else {
            firstActionFocus.tryRequestFocus("release-result-action")
        }
    }

    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 8.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.width(920.dp).padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.release_releases_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
            ) {
                items(
                    items = listItems,
                    key = { item ->
                        when (item) {
                            is ReleaseResultListItem.QualityHeader ->
                                "release-quality-header-${item.sectionIndex}"

                            is ReleaseResultListItem.ReleaseRow ->
                                item.release.selectionToken.redactedFingerprint()
                        }
                    },
                    contentType = { item -> item::class },
                ) { item ->
                    when (item) {
                        is ReleaseResultListItem.QualityHeader -> {
                            Text(
                                text = item.label ?: stringResource(R.string.unknown),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .testTag("release_quality_header_${item.sectionIndex}"),
                            )
                        }

                        is ReleaseResultListItem.ReleaseRow -> {
                            val release = item.release
                            val compatible = subject?.let(release::matchesSubject) ?: release.selectable
                            ReleaseResultRow(
                                release = release,
                                compatible = compatible,
                                modifier =
                                    Modifier
                                        .focusRequester(focusRequesters[item.releaseIndex])
                                        .testTag("release_result_${item.releaseIndex}"),
                                onClick = { onSelect(release) },
                            )
                            if (!item.isLastInSection) HorizontalDivider()
                        }
                    }
                }
            }
            if (showEpisodeSearch || showFallback) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showEpisodeSearch && onSearchEpisode != null) {
                        TextButton(
                            onClick = onSearchEpisode,
                            modifier = Modifier.focusRequester(firstActionFocus),
                        ) {
                            Text(stringResource(R.string.release_search_episode))
                        }
                    }
                    if (showFallback) {
                        TextButton(
                            onClick = onFallback,
                            modifier =
                                if (!showEpisodeSearch) {
                                    Modifier.focusRequester(firstActionFocus)
                                } else {
                                    Modifier
                                },
                        ) {
                            Text(stringResource(R.string.release_request_automatically))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseResultRow(
    release: ReleaseCandidate,
    compatible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val rejection =
        remember(release, compatible, resources) {
            buildList {
                addAll(release.rejectionReasons)
                if (release.rejected && release.rejectionReasons.isEmpty()) {
                    add(resources.getString(R.string.release_rejected))
                }
                if (!release.approved && !release.rejected) {
                    add(resources.getString(R.string.release_not_approved))
                }
                if (!compatible && release.selectable && !release.fullSeason) {
                    add(resources.getString(R.string.release_not_full_season))
                }
            }.joinToString(" · ")
        }
    ListItem(
        selected = false,
        // Rejected rows remain focusable so a D-pad user can scroll through and read their reason.
        enabled = true,
        onClick = { if (compatible) onClick() },
        headlineContent = {
            Text(
                text = release.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text =
                        listOfNotNull(
                            release.quality.takeIf { it.isNotBlank() },
                            release.indexer.takeIf { it.isNotBlank() },
                            release.protocol.takeIf { it.isNotBlank() },
                            release.seeders?.let {
                                resources.getQuantityString(R.plurals.release_seeders, it, it)
                            } ?: resources.getString(R.string.release_seeders_unknown),
                        ).joinToString(" · "),
                )
                if (rejection.isNotBlank()) {
                    Text(
                        text =
                            stringResource(
                                if (release.policyOverrideAllowed) {
                                    R.string.release_policy_override_reason
                                } else {
                                    R.string.release_rejected_reason
                                },
                                rejection,
                            ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = { Text(formatBytes(release.sizeBytes)) },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ReleaseConfirmationDialog(
    release: ReleaseCandidate,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(release.selectionToken.redactedFingerprint()) {
        cancelFocus.tryRequestFocus("release-confirmation-cancel")
    }
    BasicDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 8.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.width(720.dp).padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.release_confirmation_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = release.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    listOfNotNull(
                        stringResource(R.string.release_size_value, formatBytes(release.sizeBytes)),
                        stringResource(R.string.release_quality_value, release.quality),
                        release.seeders?.let {
                            LocalResources.current.getQuantityString(R.plurals.release_seeders, it, it)
                        } ?: stringResource(R.string.release_seeders_unknown),
                    ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (release.policyOverrideAllowed && release.rejectionReasons.isNotEmpty()) {
                Text(
                    text =
                        stringResource(
                            R.string.release_policy_override_confirmation,
                            release.rejectionReasons.joinToString(" · "),
                        ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.align(Alignment.End),
            ) {
                Button(
                    onClick = onCancel,
                    modifier =
                        Modifier
                            .focusRequester(cancelFocus)
                            .testTag("release_confirmation_cancel"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onDownload,
                    modifier = Modifier.testTag("release_confirmation_download"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.release_download))
                }
            }
        }
    }
}

@Composable
fun ReleaseSeasonPickerDialog(
    seasons: List<Season>,
    onDismissRequest: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
) {
    val eligible = remember(seasons) { seasons.filter { it.seasonNumber != null }.sortedBy { it.seasonNumber } }
    ReleaseChoiceDialog(
        title = stringResource(R.string.release_select_season),
        choices =
            eligible.map { season ->
                val number = requireNotNull(season.seasonNumber)
                number to (season.name ?: stringResource(R.string.release_season_number, number))
            },
        onDismissRequest = onDismissRequest,
        onSelected = onSeasonSelected,
    )
}

@Composable
fun ReleaseEpisodePickerDialog(
    state: DataLoadingState<Season>,
    onDismissRequest: () -> Unit,
    onRetry: () -> Unit,
    onEpisodeSelected: (Int) -> Unit,
) {
    when (state) {
        DataLoadingState.Loading,
        DataLoadingState.Pending,
        -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_select_episode),
                message = stringResource(R.string.loading),
                onDismissRequest = onDismissRequest,
            )
        }

        is DataLoadingState.Error -> {
            ReleaseMessageDialog(
                title = stringResource(R.string.release_select_episode),
                message = stringResource(R.string.release_episode_load_error),
                onDismissRequest = onDismissRequest,
                primaryAction = stringResource(R.string.retry) to onRetry,
            )
        }

        is DataLoadingState.Success -> {
            val episodes =
                state.data.episodes
                    .orEmpty()
                    .filter { it.episodeNumber != null }
            ReleaseChoiceDialog(
                title = stringResource(R.string.release_select_episode),
                choices =
                    episodes.map { episode ->
                        val number = requireNotNull(episode.episodeNumber)
                        number to
                            stringResource(
                                R.string.release_episode_label,
                                number,
                                episode.name.orEmpty(),
                            )
                    },
                onDismissRequest = onDismissRequest,
                onSelected = onEpisodeSelected,
            )
        }
    }
}

@Composable
private fun ReleaseChoiceDialog(
    title: String,
    choices: List<Pair<Int, String>>,
    onDismissRequest: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    val requesters = remember(choices) { List(choices.size) { FocusRequester() } }
    LaunchedEffect(choices) { requesters.firstOrNull()?.tryRequestFocus("release-choice-first") }
    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 8.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.width(640.dp).padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (choices.isEmpty()) {
                Text(stringResource(R.string.no_results))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 430.dp)) {
                    itemsIndexed(choices, key = { _, choice -> choice.first }) { index, choice ->
                        ListItem(
                            selected = false,
                            onClick = { onSelected(choice.first) },
                            headlineContent = { Text(choice.second) },
                            modifier = Modifier.fillMaxWidth().focusRequester(requesters[index]),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseProgressDialog(
    acquisition: AcquisitionJobDto,
    onDismissRequest: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.tryRequestFocus("release-progress-close") }
    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 8.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.width(720.dp).padding(24.dp),
        ) {
            Text(stringResource(R.string.release_progress), style = MaterialTheme.typography.headlineSmall)
            Text(acquisition.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(acquisition.phase.labelRes()))
            acquisition.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { (progress / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.release_percentage, progress.coerceIn(0.0, 100.0)))
            }
            acquisition.progressSummary().takeIf { it.isNotBlank() }?.let { Text(it) }
            acquisition.statusText?.takeIf { it.isNotBlank() }?.let { Text(it) }
            acquisition.transientErrorRes()?.let { message ->
                Text(
                    text = stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.align(Alignment.End).focusRequester(closeFocus),
            ) {
                Text(stringResource(R.string.hide))
            }
        }
    }
}

@Composable
private fun ReleaseMessageDialog(
    title: String,
    message: String?,
    onDismissRequest: () -> Unit,
    primaryAction: Pair<String, () -> Unit>? = null,
    secondaryAction: Pair<String, () -> Unit>? = null,
    tertiaryAction: Pair<String, () -> Unit>? = null,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(title, primaryAction, secondaryAction, tertiaryAction) {
        if (primaryAction != null || secondaryAction != null || tertiaryAction != null) {
            firstFocus.tryRequestFocus("release-message-action")
        }
    }
    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 8.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(680.dp).padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            message?.takeIf { it.isNotBlank() }?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOfNotNull(primaryAction, tertiaryAction, secondaryAction).forEachIndexed { index, action ->
                    TextButton(
                        onClick = action.second,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    ) {
                        Text(action.first)
                    }
                }
            }
        }
    }
}

private fun AcquisitionJobDto.progressSummary(): String =
    listOfNotNull(
        if (bytesDownloaded != null && bytesTotal != null) {
            "${formatBytes(bytesDownloaded)} / ${formatBytes(bytesTotal)}"
        } else {
            null
        },
        downloadSpeedBytesPerSecond?.let { "${formatBytes(it)}/s" },
        etaSeconds?.let(::formatEta),
    ).joinToString(" · ")

@StringRes
private fun AcquisitionJobDto.transientErrorRes(): Int? =
    when (error?.code) {
        "arr_unavailable" -> R.string.release_state_arr_unavailable
        "backend_unavailable" -> R.string.release_state_backend_unavailable
        "jellyfin_unavailable" -> R.string.release_state_jellyfin_unavailable
        else -> null
    }

private fun formatEta(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3_600
    val minutes = (safeSeconds % 3_600) / 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%dh %02dmin", hours, minutes)
    } else {
        String.format(Locale.getDefault(), "%dmin", minutes)
    }
}

@StringRes
private fun AcquisitionPhase.labelRes(): Int =
    when (this) {
        AcquisitionPhase.DISPATCHING -> R.string.release_state_dispatching

        AcquisitionPhase.DISPATCH_UNCERTAIN -> R.string.release_state_dispatch_uncertain

        AcquisitionPhase.QUEUED -> R.string.release_state_queued

        AcquisitionPhase.DOWNLOADING -> R.string.release_state_downloading

        AcquisitionPhase.DOWNLOAD_BLOCKED -> R.string.release_state_download_blocked

        AcquisitionPhase.VERIFYING -> R.string.release_state_verifying

        AcquisitionPhase.IMPORTING -> R.string.release_state_importing

        AcquisitionPhase.IMPORT_FAILED -> R.string.release_state_import_failed

        AcquisitionPhase.IMPORTED -> R.string.release_state_imported

        AcquisitionPhase.WAITING_JELLYFIN -> R.string.release_state_waiting_jellyfin

        AcquisitionPhase.AVAILABLE -> R.string.release_state_available

        AcquisitionPhase.CANCELLED -> R.string.release_cancelled

        AcquisitionPhase.ERROR,
        AcquisitionPhase.UNKNOWN,
        -> R.string.release_error
    }

@StringRes
private fun ReleaseCompanionException.messageRes(): Int =
    when (this) {
        is ReleaseCompanionException.Network -> R.string.release_network_error
        is ReleaseCompanionException.Timeout -> R.string.release_timeout_error
        is ReleaseCompanionException.SelectionExpired -> R.string.release_result_expired_message
        is ReleaseCompanionException.AuthenticationRequired -> R.string.release_auth_error
        is ReleaseCompanionException.Forbidden -> R.string.release_forbidden_error
        is ReleaseCompanionException.RateLimited -> R.string.release_rate_limit_error
        is ReleaseCompanionException.UpstreamUnavailable -> R.string.release_upstream_error
        else -> R.string.release_generic_error
    }
