// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.net

/**
 * Port of `enum APIError` in `librarium-ios-main/Librarium/Services/APIClient.swift:3-21`.
 *
 * The cases are kept one-for-one because call sites discriminate on them:
 * `.decodingError` is swallowed in places where a `{"data": null}` answer is a
 * legitimate "no interaction" rather than a failure, and `.notFound` is treated
 * as "absent" by the duplicate-detection cascade rather than as an error.
 */
sealed class ApiException(message: String?) : Exception(message) {

    /** The base URL could not be form-unified into a request URL. */
    class InvalidUrl : ApiException("Invalid server URL — check your server settings.")

    /** Credentials rejected, or refresh failed and the caller must log out. */
    class Unauthorized : ApiException("Invalid credentials.")

    /** 404. Distinct from [Server] so callers can branch on absence. */
    class NotFound : ApiException("Resource not found.")

    /** Any other non-2xx. [body] is the raw response text, shown to the user. */
    class Server(val code: Int, val body: String?) :
        ApiException(body?.takeIf { it.isNotBlank() } ?: "Server error ($code).")

    /** A 2xx whose body did not match the expected shape. */
    class Decoding(cause: Throwable) :
        ApiException("Unexpected server response: ${cause.message}")

    /** Transport-level failure: DNS, TLS, timeout, airplane mode. */
    class Network(cause: Throwable) : ApiException(cause.message ?: "Network error.")

    /**
     * 426 Upgrade Required — the server refuses this client as too old.
     *
     * iOS does not model this separately: it falls into `.serverError(426, msg)`
     * and the message is displayed. It is broken out here only so the build can
     * be told apart from a genuine server fault while developing, without
     * changing what the user sees.
     */
    class UpgradeRequired(body: String?) :
        ApiException(body?.takeIf { it.isNotBlank() } ?: "This app version is no longer supported.")

    /** User-facing text, matching `errorDescription` on the Swift enum. */
    val display: String get() = message ?: "Unknown error"
}
