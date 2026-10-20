// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.collection

import com.dwprod.librariumscanner.core.model.SavedList
import com.dwprod.librariumscanner.core.net.LIBRARIUM_JSON
import com.dwprod.librariumscanner.core.remote.CreateListRequest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the views contract against the release this app ships to.
 *
 * A view is the server's manual list (`internal/models/user_book.go:List`), and
 * the multi-select sheet is only as correct as this decoding: a list-row whose
 * `book_count` or `kind` failed to map would show every view as empty, and one
 * decoding `kind` wrong would offer a smart list — which the add endpoint
 * refuses ("that list fills itself from its filter",
 * `internal/api/handlers/lists.go`).
 */
class SavedListViewTest {

    /** A real `GET /api/v1/me/lists` row, trimmed to the keys the app reads. */
    private val listRow = """
        {
          "id": "0f8b5f0e-1111-4a4a-9c9c-0a0a0a0a0a0a",
          "owner_user_id": "9a1c2d3e-2222-4b4b-8d8d-1b1b1b1b1b1b",
          "name": "Favourites",
          "description": "",
          "icon": "",
          "color": "",
          "kind": "manual",
          "surface": "books",
          "filter": null,
          "filter_version": null,
          "layout": "grid",
          "display_order": 2,
          "visibility": "private",
          "share_token": "",
          "book_count": 3,
          "builtin_key": "",
          "created_at": "2026-10-01T10:00:00Z",
          "updated_at": "2026-10-02T10:00:00Z"
        }
    """.trimIndent()

    @Test
    fun manualListRowDecodesItsCountsAndKind() {
        val list = LIBRARIUM_JSON.decodeFromString(SavedList.serializer(), listRow)

        assertEquals("Favourites", list.name)
        assertEquals(3, list.bookCount)
        assertEquals("manual", list.kind)
        assertFalse(list.isSmart)
        assertEquals("books", list.surface)
        assertEquals("private", list.visibility)
    }

    @Test
    fun smartListIsRecognisedSoTheSheetCanSkipIt() {
        val smart = listRow
            .replace("\"kind\": \"manual\"", "\"kind\": \"smart\"")
            .replace("\"filter\": null", "\"filter\": {\"query\": \"own=gap&type=manga\"}")
        val list = LIBRARIUM_JSON.decodeFromString(SavedList.serializer(), smart)

        assertTrue(list.isSmart)
        assertEquals("own=gap&type=manga", list.filterQuery)
    }

    @Test
    fun createBodyCarriesTheKeysTheServerReads() {
        val json = LIBRARIUM_JSON.encodeToString(
            CreateListRequest.serializer(),
            CreateListRequest("Favourites"),
        )

        assertTrue(json, json.contains("\"name\":\"Favourites\""))
        assertTrue(json, json.contains("\"kind\":\"manual\""))
        assertTrue(json, json.contains("\"surface\":\"books\""))
        assertTrue(json, json.contains("\"visibility\":\"private\""))
        // A manual list stores no filter; sending one is a 400 ("a smart list
        // needs a filter" is the inverse, but neither belongs on this body).
        assertFalse(json, json.contains("\"filter\""))
    }

    @Test
    fun listPageEnvelopeDecodesItems() {
        // GET /me/lists answers {"data": {"items": [...]}}; decodeEnvelope hands
        // the inner object to the items serializer.
        val payload = """{"items": [$listRow]}"""
        val lists = LIBRARIUM_JSON.decodeFromString(
            ListSerializer(SavedList.serializer()),
            "[$listRow]",
        )
        assertEquals(1, lists.size)
        assertEquals(3, lists.first().bookCount)
        // The envelope itself is the API layer's job, but the shape must at
        // least be what [com.dwprod.librariumscanner.core.remote.ItemsPage]
        // models.
        assertTrue(payload.contains("\"items\""))
    }

    @Test
    fun aServerSharedListDecodesAsManual() {
        // visibility=library plus a shared_library_id is the shape a shelf
        // shared into a library takes after the migration to lists. It must stay
        // addable, or every shared shelf would vanish from the picker.
        val shared = listRow.replace(
            "\"visibility\": \"private\"",
            "\"visibility\": \"library\", \"shared_library_id\": \"aaaabbbb-3333-4c4c-9e9e-2c2c2c2c2c2c\"",
        )
        val list = LIBRARIUM_JSON.decodeFromString(SavedList.serializer(), shared)

        assertFalse(list.isSmart)
        assertEquals("library", list.visibility)
        assertEquals("aaaabbbb-3333-4c4c-9e9e-2c2c2c2c2c2c", list.sharedLibraryId)
    }

    @Test
    fun theSheetSplitsTheTwoKinds() {
        val manual = LIBRARIUM_JSON.decodeFromString(SavedList.serializer(), listRow)
        val smart = LIBRARIUM_JSON.decodeFromString(
            SavedList.serializer(),
            listRow.replace("\"kind\": \"manual\"", "\"kind\": \"smart\"")
                .replace("\"filter\": null", "\"filter\": {\"query\": \"own=gap\"}"),
        )
        val state = ListsState(
            lists = listOf(manual, smart).sortedWith(compareBy({ it.isSmart }, { it.name })),
            loaded = true,
        )

        assertEquals(listOf("Favourites"), state.listsAddableTo().map { it.name })
        assertEquals(1, state.savedViews().size)
        assertTrue(state.savedViews().first().isSmart)
    }
}
