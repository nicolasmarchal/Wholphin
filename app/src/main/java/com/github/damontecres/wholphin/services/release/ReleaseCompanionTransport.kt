package com.github.damontecres.wholphin.services.release

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ReleaseHttpMethod {
    GET,
    POST,
    DELETE,
}

/** A secret whose value cannot be disclosed by ordinary string interpolation. */
class SensitiveValue private constructor(
    private val value: String,
) {
    internal fun reveal(): String = value

    override fun toString(): String = "<redacted>"

    companion object {
        fun of(value: String): SensitiveValue {
            require(value.isNotBlank()) { "A sensitive value must not be blank" }
            return SensitiveValue(value)
        }
    }
}

data class ReleaseTransportRequest(
    val method: ReleaseHttpMethod,
    val pathSegments: List<String>,
    val query: Map<String, String> = emptyMap(),
    val jsonBody: String? = null,
    val bearerToken: SensitiveValue? = null,
    val jellyfinToken: SensitiveValue? = null,
    val idempotencyKey: SensitiveValue? = null,
) {
    init {
        require(pathSegments.isNotEmpty()) { "At least one path segment is required" }
        require(pathSegments.none { it.isBlank() || it == "." || it == ".." || '/' in it }) {
            "Path segments must be non-blank literal segments"
        }
        require(query.keys.none { it.isBlank() }) { "Query names must not be blank" }
        require(method == ReleaseHttpMethod.POST || jsonBody == null) {
            "Only POST requests may contain a JSON body"
        }
        require(bearerToken == null || jellyfinToken == null) {
            "A request cannot contain both a BFF session and a Jellyfin credential"
        }
    }

    override fun toString(): String =
        "ReleaseTransportRequest(method=$method, pathSegments=$pathSegments, " +
            "queryNames=${query.keys}, jsonBody=${if (jsonBody == null) "absent" else "<redacted>"}, " +
            "bearerToken=$bearerToken, jellyfinToken=$jellyfinToken, " +
            "idempotencyKey=$idempotencyKey)"
}

data class ReleaseTransportResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun toString(): String =
        "ReleaseTransportResponse(statusCode=$statusCode, body=<redacted>, " +
            "headerNames=${headers.keys})"
}

data class ReleaseTransportEvent(
    val id: String?,
    val type: String?,
    val data: String,
)

fun interface ReleaseCompanionTransport {
    suspend fun execute(request: ReleaseTransportRequest): ReleaseTransportResponse

    /** Opens a cancellable SSE stream. Test and legacy transports fall back to polling. */
    fun stream(request: ReleaseTransportRequest): Flow<ReleaseTransportEvent> =
        flow { throw ReleaseCompanionException.StreamingUnavailable() }
}

data class CompanionOriginPolicy(
    /** Cleartext must be an explicit deployment decision for a LAN or VPN endpoint. */
    val allowCleartext: Boolean = false,
)

data class CompanionHttpTimeouts(
    val connectMillis: Long = 3_000,
    val readMillis: Long = 35_000,
    val writeMillis: Long = 10_000,
    val callMillis: Long = 40_000,
) {
    init {
        require(connectMillis > 0)
        require(readMillis > 0)
        require(writeMillis > 0)
        require(callMillis > 0)
    }
}

/**
 * Dedicated companion transport.
 *
 * Requests are assembled exclusively from literal path segments relative to one validated origin.
 * Redirects are disabled so Jellyfin/BFF credentials can never be forwarded to another host.
 */
class OkHttpReleaseCompanionTransport(
    baseUrl: String,
    baseClient: OkHttpClient,
    originPolicy: CompanionOriginPolicy = CompanionOriginPolicy(),
    timeouts: CompanionHttpTimeouts = CompanionHttpTimeouts(),
    private val maximumResponseCharacters: Int = DEFAULT_MAXIMUM_RESPONSE_CHARACTERS,
) : ReleaseCompanionTransport {
    private val origin: HttpUrl = validateBaseUrl(baseUrl, originPolicy)
    private val client =
        baseClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.writeMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
            .build()
    private val streamClient =
        client
            .newBuilder()
            // Heartbeats reset this timeout; a silent half-open stream becomes reconnectable.
            .readTimeout(releaseStreamReadTimeoutMillis(timeouts.readMillis), TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    init {
        require(maximumResponseCharacters > 0) { "maximumResponseCharacters must be positive" }
    }

    override suspend fun execute(request: ReleaseTransportRequest): ReleaseTransportResponse {
        return executeCall(client.newCall(buildRequest(request, JSON_MEDIA_TYPE)))
    }

    override fun stream(request: ReleaseTransportRequest): Flow<ReleaseTransportEvent> =
        callbackFlow {
            require(request.method == ReleaseHttpMethod.GET) { "SSE requests must use GET" }
            val eventSource =
                EventSources.createFactory(streamClient).newEventSource(
                    buildRequest(request, EVENT_STREAM_MEDIA_TYPE),
                    object : EventSourceListener() {
                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            if (data.length > maximumResponseCharacters) {
                                close(ReleaseCompanionException.ResponseTooLarge(maximumResponseCharacters))
                                eventSource.cancel()
                                return
                            }
                            if (trySend(ReleaseTransportEvent(id = id, type = type, data = data)).isFailure) {
                                close(ReleaseCompanionException.StreamingUnavailable())
                                eventSource.cancel()
                            }
                        }

                        override fun onClosed(eventSource: EventSource) {
                            close()
                        }

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            val failure = mapReleaseStreamFailure(t, response)
                            response?.close()
                            close(failure)
                        }
                    },
                )
            awaitClose { eventSource.cancel() }
        }

    private fun buildRequest(
        request: ReleaseTransportRequest,
        accept: String,
    ): Request {
        val url = buildUrl(request)
        checkSameOrigin(url)
        val builder =
            Request
                .Builder()
                .url(url)
                .header("Accept", accept)

        request.bearerToken?.let {
            builder.header("Authorization", "Bearer ${it.reveal()}")
        }
        request.jellyfinToken?.let {
            builder.header("X-Jellyfin-Token", it.reveal())
        }
        request.idempotencyKey?.let {
            builder.header("Idempotency-Key", it.reveal())
        }

        when (request.method) {
            ReleaseHttpMethod.GET -> builder.get()
            ReleaseHttpMethod.DELETE -> builder.delete()
            ReleaseHttpMethod.POST -> {
                val body = (request.jsonBody ?: "").toRequestBody(JSON_MEDIA_TYPE.toMediaType())
                builder.post(body)
            }
        }
        return builder.build()
    }

    internal fun buildUrl(request: ReleaseTransportRequest): HttpUrl {
        val builder = origin.newBuilder()
        request.pathSegments.forEach(builder::addPathSegment)
        request.query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun checkSameOrigin(url: HttpUrl) {
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) {
            throw ReleaseCompanionException.OriginViolation("Companion request escaped its configured origin")
        }
    }

    private suspend fun executeCall(call: Call): ReleaseTransportResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!continuation.isActive) return
                        val failure =
                            when (e) {
                                is SocketTimeoutException,
                                is InterruptedIOException,
                                -> ReleaseCompanionException.Timeout(e)

                                else -> ReleaseCompanionException.Network(e)
                            }
                        continuation.resumeWithException(failure)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            if (!continuation.isActive) return
                            try {
                                continuation.resume(
                                    ReleaseTransportResponse(
                                        statusCode = response.code,
                                        body = response.body.readBounded(maximumResponseCharacters),
                                        headers = response.headers.toMultimap(),
                                    ),
                                )
                            } catch (failure: Throwable) {
                                continuation.resumeWithException(failure)
                            }
                        }
                    }
                },
            )
        }

    private fun okhttp3.ResponseBody.readBounded(maximumCharacters: Int): String =
        charStream().use { reader ->
            val result = StringBuilder(minOf(maximumCharacters, 8_192))
            val buffer = CharArray(4_096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (result.length + count > maximumCharacters) {
                    throw ReleaseCompanionException.ResponseTooLarge(maximumCharacters)
                }
                result.append(buffer, 0, count)
            }
            result.toString()
        }

    companion object {
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val EVENT_STREAM_MEDIA_TYPE = "text/event-stream"
        private const val DEFAULT_MAXIMUM_RESPONSE_CHARACTERS = 2_000_000
        internal const val DEFAULT_STREAM_READ_TIMEOUT_MILLIS = 35_000L

        internal fun validateBaseUrl(
            value: String,
            policy: CompanionOriginPolicy,
        ): HttpUrl {
            val url =
                value.toHttpUrlOrNull()
                    ?: throw ReleaseCompanionException.InvalidConfiguration(
                        "Companion URL must be an absolute HTTP(S) URL",
                    )
            if (url.scheme != "https" && !(url.scheme == "http" && policy.allowCleartext)) {
                throw ReleaseCompanionException.InvalidConfiguration(
                    "Companion URL must use HTTPS unless cleartext LAN/VPN access is explicitly enabled",
                )
            }
            if (url.query != null || url.fragment != null) {
                throw ReleaseCompanionException.InvalidConfiguration(
                    "Companion URL must not contain a query or fragment",
                )
            }
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw ReleaseCompanionException.InvalidConfiguration(
                    "Companion URL must not contain embedded credentials",
                )
            }
            return url
        }
    }
}

/** OkHttp retains the successful opening response when an established SSE body later fails. */
internal fun mapReleaseStreamFailure(
    failure: Throwable?,
    response: Response?,
): ReleaseCompanionException =
    when {
        failure is SocketTimeoutException || failure is InterruptedIOException -> {
            ReleaseCompanionException.Timeout(failure)
        }

        failure is IOException -> ReleaseCompanionException.Network(failure)
        response != null && !response.isSuccessful -> {
            when (response.code) {
                401 -> ReleaseCompanionException.AuthenticationRequired()
                403 -> ReleaseCompanionException.Forbidden()
                404 -> ReleaseCompanionException.NotFound()
                429 ->
                    ReleaseCompanionException.RateLimited(
                        retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
                    )

                502, 503, 504 ->
                    ReleaseCompanionException.UpstreamUnavailable(
                        statusCode = response.code,
                    )

                else -> ReleaseCompanionException.HttpFailure(response.code)
            }
        }

        else -> ReleaseCompanionException.StreamingUnavailable(failure)
    }

internal fun releaseStreamReadTimeoutMillis(configuredReadTimeoutMillis: Long): Long =
    maxOf(
        configuredReadTimeoutMillis,
        OkHttpReleaseCompanionTransport.DEFAULT_STREAM_READ_TIMEOUT_MILLIS,
    )
