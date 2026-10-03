package com.github.damontecres.wholphin.services.release

/**
 * Stable failures exposed to ViewModels. Raw response bodies are deliberately never retained.
 */
sealed class ReleaseCompanionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class InvalidConfiguration(
        message: String,
        cause: Throwable? = null,
    ) : ReleaseCompanionException(message, cause)

    class OriginViolation(
        message: String,
    ) : ReleaseCompanionException(message)

    class AuthenticationRequired(
        val errorCode: String? = null,
    ) : ReleaseCompanionException("Companion authentication is required")

    class Forbidden(
        val errorCode: String? = null,
    ) : ReleaseCompanionException("The current user is not allowed to perform this operation")

    class NotFound(
        val errorCode: String? = null,
    ) : ReleaseCompanionException("The requested companion resource was not found")

    class Conflict(
        val errorCode: String? = null,
    ) : ReleaseCompanionException("The companion operation conflicts with current state")

    class SelectionExpired(
        val errorCode: String? = null,
    ) : ReleaseCompanionException("The selected release has expired")

    class SelectionRejected(
        val errorCode: String? = null,
    ) : ReleaseCompanionException("The selected release cannot be downloaded")

    class RateLimited(
        val retryAfterSeconds: Long?,
        val errorCode: String? = null,
    ) : ReleaseCompanionException("The companion rate limit was reached")

    class UpstreamUnavailable(
        val statusCode: Int,
        val errorCode: String? = null,
    ) : ReleaseCompanionException("A companion upstream service is unavailable")

    class HttpFailure(
        val statusCode: Int,
        val errorCode: String? = null,
        val retryable: Boolean = statusCode >= 500,
    ) : ReleaseCompanionException("Companion request failed with HTTP $statusCode")

    class RemoteOperationFailed(
        val errorCode: String? = null,
        val retryable: Boolean = false,
    ) : ReleaseCompanionException("The companion operation failed")

    class Network(
        cause: Throwable,
    ) : ReleaseCompanionException("Could not reach the release companion", cause)

    class Timeout(
        cause: Throwable,
    ) : ReleaseCompanionException("The release companion request timed out", cause)

    class InvalidResponse(
        message: String,
        cause: Throwable? = null,
    ) : ReleaseCompanionException(message, cause)

    class ResponseTooLarge(
        val maximumCharacters: Int,
    ) : ReleaseCompanionException("Companion response exceeded the configured size limit")

    class PollingExhausted(
        val attempts: Int,
    ) : ReleaseCompanionException("Companion polling stopped after $attempts attempts")
}

enum class ReleaseOperationStage {
    AUTHENTICATION,
    REHYDRATION,
    SEARCH,
    SELECTION,
    TRACKING,
    CANCELLATION,
}
