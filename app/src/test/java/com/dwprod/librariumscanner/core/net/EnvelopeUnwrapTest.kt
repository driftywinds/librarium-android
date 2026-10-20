// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the envelope unwrapper.
 *
 * These exercise the pure shape logic that [LibrariumApi.decodeEnvelope] applies,
 * without an HTTP client, because the bug they cover was invisible to every test
 * we had: `lookup/isbn/{isbn}` answers `{"data": [ … ]}` and the old
 * `inner.jsonObject["data"] ?: …` ordering threw "JsonArray is not a JsonObject"
 * before the guard could run.
 */
class EnvelopeUnwrapTest {

    private val json = Json

    /** Mirrors the corrected logic in `decodeEnvelope`. */
    private fun unwrap(root: JsonElement): JsonElement {
        val inner = (root as JsonObject)["data"]
            ?: throw IllegalStateException("missing data")
        return if (inner is JsonObject && inner.keys.size == 1 && inner.containsKey("data")) {
            inner["data"] ?: inner
        } else {
            inner
        }
    }

    @Test
    fun arrayPayloadIsNotUnwrappedAgain() {
        // The real shape from GET /api/v1/lookup/isbn/{isbn}.
        val root = json.parseToJsonElement(
            """{"data":[{"provider":"hardcover","title":"Dune"}]}"""
        )
        val payload = unwrap(root)
        assertTrue("payload should stay a JsonArray", payload is kotlinx.serialization.json.JsonArray)
        assertEquals(1, (payload as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun emptyArrayPayloadSurvives() {
        // A provider miss answers with `[]`, not null. This is the "No match" path.
        val payload = unwrap(json.parseToJsonElement("""{"data":[]}"""))
        assertEquals(0, (payload as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun doublyWrappedFacetsIsUnwrappedTwice() {
        val payload = unwrap(json.parseToJsonElement("""{"data":{"data":{"x":1}}}"""))
        assertEquals("""{"x":1}""", payload.toString())
    }

    @Test
    fun objectWithSiblingDataKeyIsNotUnwrapped() {
        // A genuine payload may have its own field named `data`; only a
        // single-key object is treated as a second envelope.
        val payload = unwrap(json.parseToJsonElement("""{"data":{"data":"x","count":2}}"""))
        assertEquals("""{"data":"x","count":2}""", payload.toString())
    }
}
