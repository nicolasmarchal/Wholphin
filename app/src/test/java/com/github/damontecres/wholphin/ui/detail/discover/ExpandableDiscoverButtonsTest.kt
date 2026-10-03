package com.github.damontecres.wholphin.ui.detail.discover

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.test.TestActivity
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ExpandableDiscoverButtonsTest {
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
    fun `classic request and release actions stay separate in D-pad order`() {
        var classicClicks = 0
        var releaseClicks = 0
        setButtons(
            availability = SeerrAvailability.UNKNOWN,
            requestOnClick = { classicClicks++ },
            releaseOnClick = { releaseClicks++ },
        )

        val classic = composeRule.onNodeWithTag(DISCOVER_CLASSIC_ACTION_TAG)
        val release = composeRule.onNodeWithTag(DISCOVER_RELEASE_ACTION_TAG)
        classic.requestFocus().assertIsFocused().press(Key.DirectionCenter)
        composeRule.runOnIdle {
            assertEquals(1, classicClicks)
            assertEquals(0, releaseClicks)
        }

        classic.press(Key.DirectionRight)
        release.assertIsFocused().press(Key.DirectionCenter)
        composeRule.runOnIdle {
            assertEquals(1, classicClicks)
            assertEquals(1, releaseClicks)
        }
    }

    @Test
    fun `partially available series keeps go-to release and request actions reachable`() {
        setButtons(availability = SeerrAvailability.PARTIALLY_AVAILABLE)

        val classic = composeRule.onNodeWithTag(DISCOVER_CLASSIC_ACTION_TAG)
        val release = composeRule.onNodeWithTag(DISCOVER_RELEASE_ACTION_TAG)
        val partialRequest = composeRule.onNodeWithTag(DISCOVER_PARTIAL_REQUEST_ACTION_TAG)

        classic.requestFocus().assertIsFocused().press(Key.DirectionRight)
        release.assertIsFocused().press(Key.DirectionRight)
        partialRequest.assertIsFocused()
    }

    @Test
    fun `classic action remains when release action is absent`() {
        setButtons(
            availability = SeerrAvailability.UNKNOWN,
            releaseOnClick = null,
        )

        composeRule.onNodeWithTag(DISCOVER_CLASSIC_ACTION_TAG).assertExists()
        composeRule.onNodeWithTag(DISCOVER_RELEASE_ACTION_TAG).assertDoesNotExist()
    }

    private fun setButtons(
        availability: SeerrAvailability,
        requestOnClick: () -> Unit = {},
        releaseOnClick: (() -> Unit)? = {},
    ) {
        composeRule.setContent {
            WholphinTheme {
                ExpandableDiscoverButtons(
                    canRequest = true,
                    canCancel = false,
                    availability = availability,
                    trailers = null,
                    requestOnClick = requestOnClick,
                    cancelOnClick = {},
                    goToOnClick = {},
                    moreOnClick = {},
                    trailerOnClick = {},
                    buttonOnFocusChanged = {},
                    releaseAction =
                        releaseOnClick?.let {
                            DiscoverPrimaryAction(
                                title = R.string.release_see_releases,
                                icon = R.string.fa_download,
                                onClick = it,
                            )
                        },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.press(key: Key) =
        performKeyInput {
            pressKey(key)
        }
}
