package com.github.damontecres.wholphin.services.release

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCompanionClientTest {
    @Test
    fun `session exchange uses only the Jellyfin credential and redacts it`() =
        runTest {
            val transport =
                FakeReleaseTransport(
                    response(
                        201,
                        """
                        {
                          "token":"bff-session-secret",
                          "expiresAt":"2099-01-01T00:00:00Z",
                          "user":{
                            "id":"user-1",
                            "name":"Nicolas",
                            "canRequestMovies":true,
                            "canRequestSeries":true,
                            "canCancel":false
                          }
                        }
                        """.trimIndent(),
                    ),
                )
            val api = DefaultReleaseCompanionApi(transport)

            val session = api.exchangeSession(SensitiveValue.of("jellyfin-secret"))

            val request = transport.requests.single()
            assertEquals(ReleaseHttpMethod.POST, request.method)
            assertEquals(listOf("v1", "session"), request.pathSegments)
            assertEquals("jellyfin-secret", request.jellyfinToken?.reveal())
            assertEquals(null, request.bearerToken)
            assertFalse(session.toString().contains("bff-session-secret"))
        }

    @Test
    fun `series search and exact acquisition match the OpenAPI contract`() =
        runTest {
            val transport =
                FakeReleaseTransport(
                    response(
                        202,
                        searchJson(
                            subject =
                                """{"kind":"episode","tmdbId":9,"tvdbId":19,"seasonNumber":2,"episodeNumber":4}""",
                        ),
                    ),
                    response(202, acquisitionJson("queued")),
                )
            val api = DefaultReleaseCompanionApi(transport)
            val session = SensitiveValue.of("bff-secret")
            val subject = ReleaseSubject.TvEpisode(tmdbId = 9, seasonNumber = 2, episodeNumber = 4, tvdbId = 19)

            api.startReleaseSearch(session, subject)
            api.acquireRelease(
                sessionToken = session,
                selectionToken = "opaque-candidate-token",
                idempotencyKey = SensitiveValue.of("stable-idempotency-key"),
            )

            val searchRequest = transport.requests[0]
            assertEquals(listOf("v1", "releases", "search"), searchRequest.pathSegments)
            assertEquals(
                "{\"kind\":\"episode\",\"tmdbId\":9,\"tvdbId\":19," +
                    "\"seasonNumber\":2,\"episodeNumber\":4}",
                searchRequest.jsonBody,
            )
            val acquisitionRequest = transport.requests[1]
            assertEquals(listOf("v1", "acquisitions"), acquisitionRequest.pathSegments)
            assertEquals("{\"token\":\"opaque-candidate-token\"}", acquisitionRequest.jsonBody)
            assertEquals("stable-idempotency-key", acquisitionRequest.idempotencyKey?.reveal())
            assertFalse(acquisitionRequest.toString().contains("opaque-candidate-token"))
            assertFalse(acquisitionRequest.toString().contains("stable-idempotency-key"))
        }

    @Test
    fun `release response preserves every safe field exposed by the BFF`() =
        runTest {
            val body =
                """
                {
                  "searchId":"search-1",
                  "subject":{"kind":"season","tmdbId":9,"tvdbId":19,"seasonNumber":2},
                  "state":"completed",
                  "expiresAt":"2099-01-01T00:00:00Z",
                  "releases":[{
                    "token":"opaque-token-12345678901234567890",
                    "title":"Show.S02.2160p",
                    "sizeBytes":9000000000,
                    "seeders":31,
                    "protocol":"torrent",
                    "quality":"WEBDL-2160p",
                    "indexer":"Example Indexer",
                    "approved":false,
                    "rejected":true,
                    "rejections":["Upgrade not allowed"],
                    "fullSeason":true,
                    "seasonNumber":2,
                    "episodeNumbers":[1,2,3],
                    "expiresAt":"2099-01-01T00:00:00Z"
                  }]
                }
                """.trimIndent()
            val api = DefaultReleaseCompanionApi(FakeReleaseTransport(response(202, body)))

            val result =
                api
                    .startReleaseSearch(
                        SensitiveValue.of("bff-secret"),
                        ReleaseSubject.TvSeason(9, 2, 19),
                    ).releases
                    .single()

            assertEquals("Show.S02.2160p", result.title)
            assertEquals(9_000_000_000, result.sizeBytes)
            assertEquals(31, result.seeders)
            assertEquals("torrent", result.protocol)
            assertEquals("WEBDL-2160p", result.quality)
            assertEquals("Example Indexer", result.indexer)
            assertFalse(result.approved)
            assertTrue(result.rejected)
            assertEquals(listOf("Upgrade not allowed"), result.rejectionReasons)
            assertTrue(result.fullSeason)
            assertEquals(2, result.seasonNumber)
            assertEquals(listOf(1, 2, 3), result.episodeNumbers)
            assertFalse(result.selectable)
        }

    @Test
    fun `rehydration query distinguishes movies seasons and episodes`() =
        runTest {
            val transport =
                FakeReleaseTransport(
                    response(200, "[]"),
                    response(200, "[]"),
                    response(200, "[]"),
                )
            val api = DefaultReleaseCompanionApi(transport)
            val session = SensitiveValue.of("bff-secret")

            api.listAcquisitions(session, ReleaseSubject.Movie(1))
            api.listAcquisitions(session, ReleaseSubject.TvSeason(2, 0, 20), active = true)
            api.listAcquisitions(session, ReleaseSubject.TvEpisode(3, 4, 5, 30), active = false)

            assertEquals(mapOf("kind" to "movie", "tmdbId" to "1"), transport.requests[0].query)
            assertEquals(
                mapOf("active" to "true", "kind" to "season", "tmdbId" to "2", "seasonNumber" to "0"),
                transport.requests[1].query,
            )
            assertEquals(
                mapOf(
                    "active" to "false",
                    "kind" to "episode",
                    "tmdbId" to "3",
                    "seasonNumber" to "4",
                    "episodeNumber" to "5",
                ),
                transport.requests[2].query,
            )
        }

    @Test
    fun `series rehydration queries the TV namespace without guessing a season`() =
        runTest {
            val transport = FakeReleaseTransport(response(200, "[]"))
            val api = DefaultReleaseCompanionApi(transport)

            api.listSeriesAcquisitions(
                sessionToken = SensitiveValue.of("bff-secret"),
                tmdbId = 123,
                active = false,
            )

            assertEquals(
                mapOf("active" to "false", "mediaType" to "tv", "tmdbId" to "123"),
                transport.requests.single().query,
            )
        }

    @Test
    fun `HTTP errors are typed without retaining response text`() =
        runTest {
            val secretBody =
                """{"error":{"code":"expired_selection","message":"contains secret tracker value","retryable":false}}"""
            val transport = FakeReleaseTransport(response(410, secretBody))
            val api = DefaultReleaseCompanionApi(transport)

            val failure =
                runCatching {
                    api.getReleaseSearch(SensitiveValue.of("bff-secret"), "search-1")
                }.exceptionOrNull()

            assertTrue(failure is ReleaseCompanionException.SelectionExpired)
            assertFalse(failure.toString().contains("secret tracker value"))
        }

    @Test
    fun `rate limit exposes only parsed retry metadata`() =
        runTest {
            val transport =
                FakeReleaseTransport(
                    ReleaseTransportResponse(
                        statusCode = 429,
                        body = """{"error":{"code":"rate_limited","message":"slow down","retryable":true}}""",
                        headers = mapOf("Retry-After" to listOf("17")),
                    ),
                )
            val api = DefaultReleaseCompanionApi(transport)

            val failure =
                runCatching { api.getCapabilities(SensitiveValue.of("bff-secret")) }
                    .exceptionOrNull() as ReleaseCompanionException.RateLimited

            assertEquals(17L, failure.retryAfterSeconds)
            assertEquals("rate_limited", failure.errorCode)
        }
}

private class FakeReleaseTransport(
    vararg responses: ReleaseTransportResponse,
) : ReleaseCompanionTransport {
    private val pending = ArrayDeque(responses.toList())
    val requests = mutableListOf<ReleaseTransportRequest>()

    override suspend fun execute(request: ReleaseTransportRequest): ReleaseTransportResponse {
        requests += request
        return pending.removeFirst()
    }
}

private fun response(
    status: Int,
    body: String,
) = ReleaseTransportResponse(status, body, emptyMap())

private fun searchJson(subject: String): String =
    """
    {
      "searchId":"search-1",
      "subject":$subject,
      "state":"running",
      "expiresAt":"2099-01-01T00:00:00Z",
      "releases":[]
    }
    """.trimIndent()

private fun acquisitionJson(state: String): String =
    """
    {
      "id":"acquisition-1",
      "subject":{"kind":"movie","tmdbId":42},
      "state":"$state",
      "title":"Example.2026.2160p",
      "updatedAt":"2026-10-03T12:00:00Z"
    }
    """.trimIndent()
