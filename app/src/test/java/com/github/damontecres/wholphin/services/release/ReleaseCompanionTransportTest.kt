package com.github.damontecres.wholphin.services.release

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReleaseCompanionTransportTest {
    @Test
    fun `cleartext requires an explicit LAN or VPN policy`() {
        assertThrows(ReleaseCompanionException.InvalidConfiguration::class.java) {
            OkHttpReleaseCompanionTransport(
                baseUrl = "http://companion.lan/",
                baseClient = OkHttpClient(),
            )
        }

        OkHttpReleaseCompanionTransport(
            baseUrl = "http://companion.lan/",
            baseClient = OkHttpClient(),
            originPolicy = CompanionOriginPolicy(allowCleartext = true),
        )
    }

    @Test
    fun `base query and fragment are rejected`() {
        listOf(
            "https://companion.lan/?token=secret",
            "https://companion.lan/#fragment",
        ).forEach { value ->
            assertThrows(ReleaseCompanionException.InvalidConfiguration::class.java) {
                OkHttpReleaseCompanionTransport(value, OkHttpClient())
            }
        }
    }

    @Test
    fun `literal path segments remain on the configured origin`() {
        val transport =
            OkHttpReleaseCompanionTransport(
                baseUrl = "https://companion.lan/api/",
                baseClient = OkHttpClient(),
            )
        val request =
            ReleaseTransportRequest(
                method = ReleaseHttpMethod.GET,
                pathSegments = listOf("v1", "acquisitions", "job id"),
                query = mapOf("kind" to "movie"),
            )

        assertEquals(
            "https://companion.lan/api/v1/acquisitions/job%20id?kind=movie",
            transport.buildUrl(request).toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseTransportRequest(
                method = ReleaseHttpMethod.GET,
                pathSegments = listOf(".."),
            )
        }
    }

    @Test
    fun `diagnostic strings redact credentials bodies and header values`() {
        val request =
            ReleaseTransportRequest(
                method = ReleaseHttpMethod.POST,
                pathSegments = listOf("v1", "acquisitions"),
                jsonBody = "{\"token\":\"release-secret\"}",
                bearerToken = SensitiveValue.of("bearer-secret"),
                idempotencyKey = SensitiveValue.of("idempotency-secret"),
            )
        val response =
            ReleaseTransportResponse(
                statusCode = 401,
                body = "upstream-secret",
                headers = mapOf("X-Secret" to listOf("header-secret")),
            )

        listOf("release-secret", "bearer-secret", "idempotency-secret").forEach {
            assertFalse(request.toString().contains(it))
        }
        assertFalse(response.toString().contains("upstream-secret"))
        assertFalse(response.toString().contains("header-secret"))
        assertTrue(request.toString().contains("<redacted>"))
        assertTrue(response.toString().contains("<redacted>"))
    }

    @Test
    fun `SSE watchdog remains above the companion heartbeat`() {
        assertEquals(35_000L, releaseStreamReadTimeoutMillis(configuredReadTimeoutMillis = 1_000))
        assertEquals(60_000L, releaseStreamReadTimeoutMillis(configuredReadTimeoutMillis = 60_000))
    }

    @Test
    fun `established SSE IO failure is not misclassified as HTTP 200`() {
        val response =
            Response
                .Builder()
                .request(Request.Builder().url("https://companion.lan/events").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody())
                .build()

        response.use {
            val failure = mapReleaseStreamFailure(IOException("connection reset"), response)

            assertTrue(failure is ReleaseCompanionException.Network)
        }
    }
}
