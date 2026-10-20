// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for [ServerUrl.normalize], ported case-for-case from
 * `NormalizeServerURLTests.swift`.
 *
 * That helper is small but load-bearing: every onboarding and re-auth flow runs
 * through it, so its behaviour is pinned here too rather than left to whatever
 * the URL parser happens to do.
 */
class ServerUrlTest {

    @Test
    fun prependsHttpsWhenSchemeMissing() {
        assertEquals(
            "https://librarium.example.com",
            ServerUrl.normalize("librarium.example.com"),
        )
    }

    @Test
    fun preservesExistingHttps() {
        assertEquals(
            "https://librarium.example.com",
            ServerUrl.normalize("https://librarium.example.com"),
        )
    }

    @Test
    fun preservesExistingHttpForLocalDev() {
        // Local-dev users on 127.0.0.1 stay on http; only the missing-scheme
        // case is upgraded to https.
        assertEquals(
            "http://localhost:8080",
            ServerUrl.normalize("http://localhost:8080"),
        )
    }

    @Test
    fun stripsTrailingSlash() {
        assertEquals(
            "https://librarium.example.com",
            ServerUrl.normalize("https://librarium.example.com/"),
        )
    }

    @Test
    fun trimsWhitespace() {
        assertEquals(
            "https://librarium.example.com",
            ServerUrl.normalize("  librarium.example.com  "),
        )
    }

    /**
     * The path must survive intact. Trimming *leading* slashes as well turned
     * `/api` into `api`, which then failed to serialise — the original bug this
     * pin exists to prevent.
     */
    @Test
    fun preservesPortAndPath() {
        assertEquals(
            "https://librarium.example.com:8443/api",
            ServerUrl.normalize("https://librarium.example.com:8443/api"),
        )
    }

    @Test
    fun rejectsEmpty() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertNull(ServerUrl.normalize("file:///etc/passwd"))
        assertNull(ServerUrl.normalize("ftp://librarium.example.com"))
        assertNull(ServerUrl.normalize("javascript:alert(1)"))
    }

    @Test
    fun rejectsHostlessInput() {
        assertNull(ServerUrl.normalize("https://"))
    }

    /**
     * The scheme-rejection rule must not swallow a bare `host:port`. Rejecting
     * `javascript:alert(1)` by looking for a scheme-like prefix would also
     * reject `localhost:8080`, which is the address most self-hosted users
     * develop against.
     */
    @Test
    fun acceptsHostWithPortAndNoScheme() {
        assertEquals("https://localhost:8080", ServerUrl.normalize("localhost:8080"))
        assertEquals("https://192.168.1.10:8443", ServerUrl.normalize("192.168.1.10:8443"))
    }

    @Test
    fun defaultNameIsTheHost() {
        assertEquals("librarium.example.com", ServerUrl.defaultName("https://librarium.example.com"))
        assertEquals("localhost:8080", ServerUrl.defaultName("http://localhost:8080"))
    }
}
