// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.scan

import com.dwprod.librariumscanner.core.net.LIBRARIUM_JSON
import com.dwprod.librariumscanner.core.remote.ContributorInput
import com.dwprod.librariumscanner.core.remote.CreateBookRequest
import com.dwprod.librariumscanner.core.remote.CreateEditionRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the create-book body against the contract of the release this ships
 * against.
 *
 * Two bugs lived exactly here and neither was visible to a compile: a
 * contributor row whose `contributor_id` was empty (the server parses it as a
 * UUID and answers 400 "invalid contributor_id"), and an edition whose ISBN
 * keys were `isbn10`/`isbn13` instead of `isbn_10`/`isbn_13`, which the server
 * silently ignored — leaving every scanned copy without the ISBN that the next
 * scan matches on.
 */
class CreateBookBodyTest {

    private fun body(contributors: List<ContributorInput> = emptyList()) = CreateBookRequest(
        title = "Dune",
        mediaTypeId = "6f1d6f0a-1111-4a4a-9c9c-0a0a0a0a0a0a",
        contributors = contributors,
        edition = CreateEditionRequest(
            format = "physical",
            publisher = "Chilton",
            publishDate = "1965",
            isbn10 = "0441013597",
            isbn13 = "9780441013593",
            pageCount = 412,
            copyCount = 1,
        ),
    )

    @Test
    fun contributorRowsCarryUuidIdsAndNoName() {
        val id = "7c2b5c62-2222-4b4b-8d8d-1b1b1b1b1b1b"
        val json = LIBRARIUM_JSON.encodeToString(
            CreateBookRequest.serializer(),
            body(listOf(ContributorInput(contributorId = id, role = "author", displayOrder = 0))),
        )

        assertTrue(json, json.contains("\"contributor_id\":\"$id\""))
        assertTrue(json, json.contains("\"role\":\"author\""))
        assertTrue(json, json.contains("\"display_order\":0"))
        // A `name` on this release's request struct does not exist; the field is
        // null and must stay off the wire.
        assertFalse(json, json.contains("\"name\":"))
    }

    @Test
    fun editionSendsUnderscoredIsbnKeys() {
        val json = LIBRARIUM_JSON.encodeToString(CreateBookRequest.serializer(), body())

        assertTrue(json, json.contains("\"isbn_10\":\"0441013597\""))
        assertTrue(json, json.contains("\"isbn_13\":\"9780441013593\""))
        assertFalse(json, json.contains("\"isbn10\":"))
        assertFalse(json, json.contains("\"isbn13\":"))
    }

    @Test
    fun mediaTypeAndEditionKeysAreSnakeCase() {
        val json = LIBRARIUM_JSON.encodeToString(CreateBookRequest.serializer(), body())

        assertTrue(json, json.contains("\"media_type_id\":\""))
        assertTrue(json, json.contains("\"publish_date\":\"1965\""))
        assertTrue(json, json.contains("\"page_count\":412"))
        assertTrue(json, json.contains("\"copy_count\":1"))
    }
}
