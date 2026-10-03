package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.services.release.ReleaseCompanionException
import com.github.damontecres.wholphin.services.release.ReleaseCompanionRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReleaseCompanionFeatureTest {
    @Test
    fun `verified address can replace the active repository`() =
        runTest {
            val repositories = mutableMapOf<String, ReleaseCompanionRepository>()
            var testedUrl: String? = null
            val feature =
                ReleaseCompanionFeature.configurable(
                    buildEnabled = true,
                    initialBaseUrl = "https://first.example",
                    baseUrlNormalizer = ::normalize,
                    repositoryFactory = { url ->
                        repositories.getOrPut(url) { mockk(relaxed = true) }
                    },
                    connectionTester = { testedUrl = it },
                )

            assertTrue(feature.enabled)
            assertEquals("https://first.example/", feature.configuration.value.baseUrl)
            assertSame(repositories.getValue("https://first.example/"), feature.repository)

            val normalized = feature.verifyConnection(" https://second.example ")
            assertEquals("https://second.example/", normalized)
            assertEquals(normalized, testedUrl)

            feature.configure(normalized)
            assertEquals(normalized, feature.configuration.value.baseUrl)
            assertSame(repositories.getValue(normalized), feature.repository)
        }

    @Test
    fun `invalid persisted address disables only the companion feature`() {
        val feature =
            ReleaseCompanionFeature.configurable(
                buildEnabled = true,
                initialBaseUrl = "not-a-url",
                baseUrlNormalizer = ::normalize,
                repositoryFactory = { mockk(relaxed = true) },
                connectionTester = {},
            )

        assertFalse(feature.enabled)
        assertNull(feature.repository)
        assertEquals("not-a-url", feature.configuration.value.baseUrl)
    }

    @Test
    fun `failed probe leaves the active address unchanged`() =
        runTest {
            val feature =
                ReleaseCompanionFeature.configurable(
                    buildEnabled = true,
                    initialBaseUrl = "https://working.example",
                    baseUrlNormalizer = ::normalize,
                    repositoryFactory = { mockk(relaxed = true) },
                    connectionTester = {
                        throw ReleaseCompanionException.Network(IOException("offline"))
                    },
                )

            var failed = false
            try {
                feature.verifyConnection("https://offline.example")
            } catch (_: ReleaseCompanionException.Network) {
                failed = true
            }

            assertTrue(failed)
            assertTrue(feature.enabled)
            assertEquals("https://working.example/", feature.configuration.value.baseUrl)
        }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("https://")) {
            throw ReleaseCompanionException.InvalidConfiguration("HTTPS URL required")
        }
        return trimmed.trimEnd('/') + "/"
    }
}
