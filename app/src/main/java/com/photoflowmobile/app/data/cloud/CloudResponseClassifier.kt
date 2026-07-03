package com.photoflowmobile.app.data.cloud

/**
 * Result of classifying an HTTP status code from the Cloud API photo-upload endpoint.
 * Extracted as a pure function so it can be unit-tested without Android dependencies.
 */
sealed class UploadOutcome {
    /** Upload accepted (new or idempotent duplicate). */
    object Success : UploadOutcome()
    /** Server returned 409 — photo already exists; treat as success. */
    object AlreadyExists : UploadOutcome()
    /** Transient error; WorkManager should retry with back-off. */
    data class Retryable(val status: Int) : UploadOutcome()
    /** Permanent error; retrying will not help. */
    data class Terminal(val status: Int) : UploadOutcome()
}

/**
 * Maps an HTTP [status] code from the `/photos/upload` endpoint to an [UploadOutcome].
 *
 * Rules:
 *  200, 201  → Success
 *  409       → AlreadyExists (idempotent duplicate; treat as success)
 *  401, 404, 422 → Terminal (auth failure, missing session, bad payload)
 *  5xx       → Retryable (transient server error)
 *  everything else → Terminal (unexpected; retrying is unlikely to help)
 */
fun classifyCloudResponse(status: Int): UploadOutcome = when {
    status in 200..201 -> UploadOutcome.Success
    status == 409      -> UploadOutcome.AlreadyExists
    status == 401      -> UploadOutcome.Terminal(status)
    status == 404      -> UploadOutcome.Terminal(status)
    status == 422      -> UploadOutcome.Terminal(status)
    status >= 500      -> UploadOutcome.Retryable(status)
    else               -> UploadOutcome.Terminal(status)
}
