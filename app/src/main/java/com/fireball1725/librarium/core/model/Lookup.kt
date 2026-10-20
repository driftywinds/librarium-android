// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Port of `struct ISBNLookupResult` (`Models/Lookup.swift:3-18`).
 *
 * Every string is non-optional on the wire — the server always emits them, empty
 * when unknown — and only `page_count` / `categories` may be absent. Defaults
 * mirror that: a missing string becomes `""` rather than failing the decode, so a
 * provider that omits an empty field cannot take the scan screen down.
 *
 * **`isbn_10` / `isbn_13` need explicit serial names.** The snake-case strategy
 * would render `isbn10`, which is not what any provider or any handler produces.
 */
@Serializable
data class IsbnLookupResult(
    val provider: String = "",
    val providerDisplay: String = "",
    val title: String = "",
    val subtitle: String = "",
    val authors: List<String> = emptyList(),
    val publisher: String = "",
    val publishDate: String = "",
    @SerialName("isbn_10") val isbn10: String = "",
    @SerialName("isbn_13") val isbn13: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val language: String = "",
    val pageCount: Int? = null,
    val categories: List<String>? = null,
) {
    /**
     * Matches `ISBNLookupResult.id` (`Lookup.swift:20-22`): provider plus
     * whichever ISBN is present, preferring the 13. Two providers can return the
     * same printing, and two printings can share a provider, so the pair is the
     * only thing that is actually unique.
     */
    val stableId: String get() = "$provider-${isbn13.ifEmpty { isbn10 }}"
}

/** Port of `struct SeriesLookupResult` (`Lookup.swift:24-40`). */
@Serializable
data class SeriesLookupResult(
    val provider: String = "",
    val providerDisplay: String = "",
    val name: String = "",
    val description: String = "",
    val totalCount: Int? = null,
    val isComplete: Boolean = false,
    val coverUrl: String = "",
    val externalId: String = "",
    val status: String = "",
    val originalLanguage: String = "",
    val publicationYear: Int? = null,
    val demographic: String = "",
    val genres: List<String> = emptyList(),
    val url: String = "",
    val externalSource: String = "",
) {
    val stableId: String get() = "$provider-$externalId"
}

/**
 * Port of `struct FacetValue` (`BookBrowse.swift`).
 *
 * The server supplies both the value and the human label; the client must not
 * prettify a raw enum name into a label, because the server's vocabulary is the
 * one that stays consistent with the web client.
 */
@Serializable
data class FacetValue(
    val value: String,
    val label: String = "",
    val count: Int = 0,
)

/**
 * Port of `struct BookFacets` (`BookBrowse.swift`).
 *
 * Every group defaults to empty rather than being required: a filtered response
 * legitimately omits groups with nothing in them, and the browse sheet renders
 * only the groups that arrived.
 */
@Serializable
data class BookFacets(
    val ownership: List<FacetValue> = emptyList(),
    val library: List<FacetValue> = emptyList(),
    val readStatus: List<FacetValue> = emptyList(),
    val mediaType: List<FacetValue> = emptyList(),
    val genre: List<FacetValue> = emptyList(),
    val tag: List<FacetValue> = emptyList(),
    val shelf: List<FacetValue> = emptyList(),
    val location: List<FacetValue> = emptyList(),
    val rating: List<FacetValue> = emptyList(),
    val myRating: List<FacetValue> = emptyList(),
    val favourite: List<FacetValue> = emptyList(),
) {
    val isEmpty: Boolean
        get() = ownership.isEmpty() && library.isEmpty() && readStatus.isEmpty() &&
            mediaType.isEmpty() && genre.isEmpty() && tag.isEmpty() && shelf.isEmpty() &&
            location.isEmpty() && rating.isEmpty() && myRating.isEmpty() && favourite.isEmpty()
}

/** Port of `struct SeriesFacets` (`SeriesBrowse.swift`). */
@Serializable
data class SeriesFacets(
    val library: List<FacetValue> = emptyList(),
    val mediaType: List<FacetValue> = emptyList(),
    val genre: List<FacetValue> = emptyList(),
    val rating: List<FacetValue> = emptyList(),
    val myRating: List<FacetValue> = emptyList(),
    val status: List<FacetValue> = emptyList(),
    val arcs: List<FacetValue> = emptyList(),
    val reading: List<FacetValue> = emptyList(),
    val tag: List<FacetValue> = emptyList(),
)

/** Port of `struct SeriesIndexPage` (`SeriesBrowse.swift:248-261`). */
@Serializable
data class SeriesIndexPage(
    val items: List<Series> = emptyList(),
    val total: Int = 0,
    val facets: SeriesFacets = SeriesFacets(),
)

/** Port of `struct SeriesGroup` (`BookBrowse.swift`). */
@Serializable
data class SeriesGroup(
    val seriesId: String = "",
    val seriesName: String = "",
    val matched: Int = 0,
    val owned: Int = 0,
    val read: Int = 0,
    val totalCount: Int? = null,
    val coverUrl: String? = null,
)

/** Port of `struct GroupedPage` (`BookBrowse.swift`). */
@Serializable
data class GroupedPage(
    val items: List<GroupedRow> = emptyList(),
    val total: Int = 0,
    val bookTotal: Int = 0,
)

/**
 * One row of the grouped collection view: either a series with its counts, or a
 * loose book. Modelled as the union the server sends rather than two endpoints,
 * because pagination interleaves them.
 */
@Serializable
data class GroupedRow(
    val kind: String = "book",
    val book: Book? = null,
    val series: SeriesGroup? = null,
)

/** Port of `struct Series` (`Models/Series.swift`). */
@Serializable
data class Series(
    val id: String,
    val libraryId: String = "",
    val name: String = "",
    val description: String = "",
    val totalCount: Int? = null,
    val ownedCount: Int = 0,
    val readCount: Int = 0,
    val coverUrl: String? = null,
    val status: String = "",
    val mediaType: String = "",
    val isComplete: Boolean = false,
    val primaryAuthor: String? = null,
    val tags: List<Tag> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val publicationYear: Int? = null,
)

/** Port of `struct Author`/author index rows. */
@Serializable
data class Author(
    val id: String = "",
    val name: String = "",
    val sortName: String = "",
    val bookCount: Int = 0,
    val role: String = "",
)

@Serializable
data class AuthorIndexPage(
    val items: List<Author> = emptyList(),
    val total: Int = 0,
    val roles: List<String> = emptyList(),
)

/** Port of the `/me/counts` response — a bare map of label to number. */
@Serializable
data class CountsResponse(
    val books: Int = 0,
    val series: Int = 0,
    val authors: Int = 0,
    val loans: Int = 0,
    val wishlist: Int = 0,
    val libraries: Int = 0,
)

/** Dashboard: `/api/v1/dashboard/stats`. */
@Serializable
data class DashboardStats(
    val totalBooks: Int = 0,
    val totalSeries: Int = 0,
    val totalAuthors: Int = 0,
    val booksRead: Int = 0,
    val currentlyReading: Int = 0,
    val pagesRead: Int = 0,
    val libraries: Int = 0,
)
