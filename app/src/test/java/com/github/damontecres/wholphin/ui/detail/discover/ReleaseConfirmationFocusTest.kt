package com.github.damontecres.wholphin.ui.detail.discover

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.services.release.ReleaseCandidate
import com.github.damontecres.wholphin.services.release.ReleaseSearchDto
import com.github.damontecres.wholphin.services.release.ReleaseSubject
import com.github.damontecres.wholphin.services.release.ReleaseWorkflowState
import com.github.damontecres.wholphin.services.release.redactedFingerprint
import com.github.damontecres.wholphin.services.release.toDto
import com.github.damontecres.wholphin.test.TestActivity
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ReleaseConfirmationFocusTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val registerActivityRule =
        object : TestWatcher() {
            override fun starting(description: Description?) {
                val context: Application = ApplicationProvider.getApplicationContext()
                shadowOf(context.packageManager).addOrUpdateActivity(
                    ActivityInfo().apply {
                        name = TestActivity::class.java.name
                        packageName = context.packageName
                    },
                )
            }
        }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<TestActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun `confirmation starts with cautious cancel action focused`() {
        composeRule.setContent {
            WholphinTheme {
                ReleaseConfirmationDialog(
                    release = candidate(),
                    onCancel = {},
                    onDownload = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("release_confirmation_cancel").assertIsFocused()
    }

    @Test
    fun `results restore focus to a release that starts outside the viewport`() {
        val releases = List(30) { index -> candidate(index) }
        val subject = ReleaseSubject.Movie(42)
        val search =
            ReleaseSearchDto(
                searchId = "search-1",
                subject = subject.toDto(),
                state = "completed",
                expiresAt = "2099-01-01T00:00:00Z",
                releases = releases,
            )
        composeRule.setContent {
            WholphinTheme {
                ReleaseWorkflowDialog(
                    state = ReleaseWorkflowState.Results(subject, search),
                    subject = subject,
                    canFallback = true,
                    restoreSelectionFingerprint = releases.last().selectionToken.redactedFingerprint(),
                    onDismissRequest = {},
                    onRetry = {},
                    onSelect = {},
                    onFallback = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("release_result_29").assertIsFocused()
    }

    @Test
    fun `rejected results retain a reachable D-pad focus without fallback`() {
        val release = candidate().copy(approved = false, rejected = true)
        val subject = ReleaseSubject.Movie(42)
        val search =
            ReleaseSearchDto(
                searchId = "search-1",
                subject = subject.toDto(),
                state = "completed",
                expiresAt = "2099-01-01T00:00:00Z",
                releases = listOf(release),
            )
        composeRule.setContent {
            WholphinTheme {
                ReleaseWorkflowDialog(
                    state = ReleaseWorkflowState.Results(subject, search),
                    subject = subject,
                    canFallback = false,
                    restoreSelectionFingerprint = null,
                    onDismissRequest = {},
                    onRetry = {},
                    onSelect = {},
                    onFallback = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("release_result_0").assertIsFocused()
    }

    private fun candidate(index: Int = 0) =
        ReleaseCandidate(
            selectionToken = "opaque-token-$index",
            title = "Example release $index",
            sizeBytes = 1_024,
            seeders = 10,
            protocol = "torrent",
            quality = "1080p",
            indexer = "Example",
            approved = true,
            expiresAt = "2099-01-01T00:00:00Z",
        )
}
