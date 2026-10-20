// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.remote

/**
 * What the reader has ticked, in a form the list, the grouped list and the
 * facet counts all accept.
 *
 * Port of `struct BrowseSelection` (`BookBrowse.swift:248-418`) and its
 * `BrowseFacet` vocabulary (`BookBrowse.swift:24-93`). The facet parameter
 * names are the API's (`own`, `lib`, `status`, `type`, …) and are not
 * re-derived here — building a different word for one of them would silently
 * un-filter one dimension while the other two endpoints kept filtering.
 */
data class BrowseQuery(
    /** Free-text search; sent as `q`. */
    val query: String = "",

    /**
     * Which facet ids are ticked. Keys are [BrowseFacet] wire params ("own",
     * "lib", "tag", …), values the chosen ids/values.
     *
     * [BrowseFacet.OWNERSHIP] defaults to `shelf` (`BookBrowse.swift:252`) —
     * without it the first thing anyone sees is wishlist rows mixed into the
     * books they actually own. Empty means *no* ownership filter, which needs
     * [OWNERSHIP_ANY] if it must survive a round trip through a saved view
     * (out of scope here, but the "absent snaps back to shelf" trap is
     * documented on the selection for the same reason).
     */
    val facets: Map<String, Set<String>> = mapOf(BrowseFacet.OWNERSHIP to setOf("shelf")),
) {
    /** Sorted so the same selection always produces the same URL. */
    fun queryString(): String {
        val q = query.trim()
        val parts = mutableListOf<String>()
        if (q.isNotEmpty()) parts += "q=${enc(q)}"
        for ((param, values) in facets) {
            if (values.isEmpty()) continue
            parts += "$param=${values.sorted().joinToString(",")}"
        }
        // Values are ids and facet words, never query-syntax characters, and
        // `enc`'s escaping would corrupt nothing — but the ones the iOS client
        // sends raw it sends raw. Mutex the same treatment across the pair,
        // because one endpoint decoding both forms differently is the drift
        // this class exists to kill.
        return parts.joinToString("") { "$it&" }
    }

    /**
     * Whether anything beyond default ownership is on — drives the filter
     * button's badge (`BookBrowse.swift:306-315`), counting the same way: the
     * default does not count as "a filter applied".
     */
    fun activeFilterCount(): Int {
        var n = 0
        for ((param, values) in facets) {
            if (values.isEmpty()) continue
            if (param == BrowseFacet.OWNERSHIP && values == setOf("shelf")) continue
            n++
        }
        return n
    }

    /** True when nothing but the stock selection is set (BookBrowse.swift:297-301). */
    fun isDefault(): Boolean =
        query.isBlank() &&
            facets.size == 1 &&
            facets[BrowseFacet.OWNERSHIP].let { it == setOf("shelf") }

    companion object {
        /** The API's sentinel for "ownership deliberately cleared". */
        const val OWNERSHIP_ANY = "any"
    }
}

/** The eleven dimensions `/me/books` answers with (BookBrowse.swift:24-35). */
object BrowseFacet {
    /** Where it is. */
    const val OWNERSHIP = "own"
    const val LIBRARY = "lib"
    const val SHELF = "shelf"
    const val LOCATION = "location"

    /** How you stand with it. */
    const val READ_STATUS = "status"
    const val FAVOURITE = "fav"
    const val RATING = "rating"
    const val MY_RATING = "my_rating"

    /** What it is. */
    const val MEDIA_TYPE = "type"

    /** Open vocabularies — shown last because they never stop growing. */
    const val TAG = "tag"
    const val GENRE = "genre"

    /** Also accepted by the list (contributor ids, `BookBrowse.swift:269`). */
    const val CONTRIBUTOR = "contributor"

    /** The order the filter sheet presents them, copied from the iOS sheet. */
    val sheetOrder: List<String> = listOf(
        OWNERSHIP, LIBRARY, SHELF, LOCATION, READ_STATUS, FAVOURITE,
        RATING, MY_RATING, MEDIA_TYPE, TAG, GENRE,
    )
}
