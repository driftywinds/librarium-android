// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.session

/**
 * Pure helpers for turning what a user types into a usable server base URL.
 *
 * Ported from `normalizeServerURL` (`Views/AddServerView.swift:349-368`) so the
 * Android client accepts exactly the same inputs and rejects the same ones.
 * Kept free of Android types so it is unit-testable without a device — the iOS
 * suite covers the same function (`NormalizeServerURLTests`).
 */
object ServerUrl {

    /**
     * Normalises a typed server address, or returns null when it cannot be one.
     *
     * - prepends `https://` when no scheme is present
     * - rejects schemes other than http/https
     * - strips *trailing* slashes only
     * - requires a non-empty host
     *
     * The trailing-only strip is deliberate: a leading `/` belongs to any
     * non-empty path, and trimming it turned `/api` into `api`, which then
     * could not be serialised at all. That was a real bug on iOS.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Reject anything that already names a scheme other than http/https,
        // including the no-slashes form (`javascript:alert(1)`, `mailto:x`).
        // Without this check `javascript:alert(1)` gets `https://` prepended and
        // becomes a URL whose host is literally `javascript`: the iOS test for
        // this case caught exactly that shape.
        //
        // A bare `host:port` (`localhost:8080`) must NOT be caught here, so a
        // scheme candidate is only rejected when what follows the colon is not
        // a plain port number.
        if (!trimmed.contains("://")) {
            val colon = trimmed.indexOf(':')
            val slash = trimmed.indexOf('/')
            if (colon != -1 && (slash == -1 || colon < slash)) {
                val candidate = trimmed.substring(0, colon)
                val after = trimmed.substring(colon + 1)
                val looksLikeScheme = candidate.isNotEmpty() &&
                    candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
                val looksLikePort = after.isNotEmpty() && after.takeWhile { it.isDigit() }.length == after.length
                if (looksLikeScheme && !looksLikePort) return null
            }
        }

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        // Manual parse rather than java.net.URI: URI is strict about characters
        // a user may legitimately leave in a self-hosted address, and a
        // lenient parse plus a host check gives the same accept/reject verdict.
        val schemeEnd = withScheme.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = withScheme.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val rest = withScheme.substring(schemeEnd + 3)
        if (rest.isEmpty()) return null

        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it == -1) rest.length else it }
        val authority = rest.substring(0, hostEnd)
        var path = rest.substring(hostEnd)

        // Drop a query/fragment: a base URL carries neither.
        val cutoff = path.indexOfFirst { it == '?' || it == '#' }
        if (cutoff != -1) path = path.substring(0, cutoff)

        while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        if (path == "/") path = ""

        // A host must have at least one character; `http://:8080` has none.
        val hostOnly = authority.substringAfter('@')  // strip any userinfo
            .substringBefore(':')
        if (hostOnly.isEmpty()) return null

        return "$scheme://$authority$path"
    }

    /**
     * The account name to fall back to: the host, or the whole URL when there is
     * no host to take. Port of `ServerAccount.defaultName(for:)`
     * (`Models/ServerAccount.swift:56-59`).
     */
    fun defaultName(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return url
        val rest = url.substring(schemeEnd + 3)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.ifEmpty { url }
    }
}
