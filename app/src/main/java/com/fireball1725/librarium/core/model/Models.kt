// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Port of `struct User` (`Models/User.swift`).
 */
@Serializable
data class User(
    val id: String,
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
    val isInstanceAdmin: Boolean = false,
)

/**
 * Port of `LoginRequest` / `AuthTokens` (`Models/AuthModels.swift`).
 *
 * iOS relies on `.convertToSnakeCase` to reach `access_token`; here the
 * snake-case naming strategy on [com.fireball1725.librarium.core.net.LIBRARIUM_JSON]
 * does the same, because every one of these is a real camel-case boundary.
 */
@Serializable
data class LoginRequest(
    val identifier: String,
    val password: String,
)

@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int = 0,
    val user: User,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/**
 * Port of `struct Library` (`Models/Library.swift`).
 *
 * [serverUrl] and [serverName] are client-only and deliberately absent from the
 * wire — `Library.swift:43` lists the `CodingKeys` and omits them. Marking them
 * `@Transient` reproduces that: they are never sent and never expected back.
 *
 * [clientKey] is the identity used for every piece of per-library client state.
 * The rationale is copied verbatim from the source because it is the single
 * most expensive thing in this app to get wrong: two Librarium servers can
 * legitimately expose libraries with colliding UUIDs (both seeded from the same
 * database), so anything keyed on a bare [id] — list identity, offline caches,
 * per-library preferences — will silently show one account's data under another.
 */
@Serializable
data class Library(
    val id: String,
    val name: String,
    val description: String = "",
    val slug: String = "",
    val ownerId: String = "",
    val isPublic: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val bookCount: Int? = null,
    val readingCount: Int? = null,
    val readCount: Int? = null,
) {
    @Transient
    var serverUrl: String = ""

    @Transient
    var serverName: String = ""

    val clientKey: String get() = "$serverUrl|$id"
}

/**
 * Port of `struct Genre` (`Book.swift:5-14`) — `name` is optional on the wire.
 */
@Serializable
data class Genre(
    val id: String,
    val name: String = "",
)

/**
 * Port of `struct Tag` (`Models/Tag.swift`).
 *
 * The custom decoder there exists because tags embedded in other responses (a
 * series, for instance) omit `library_id` and `created_at`. Defaults do the same
 * job here. [color] falls back to `#6B7280`, a specific grey the source picked so
 * an unstyled tag still reads as a tag rather than as a disabled control.
 */
@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: String = "#6B7280",
    val libraryId: String = "",
    val createdAt: String = "",
)

/** Port of `struct MediaType` (`Models/MediaType.swift`). */
@Serializable
data class MediaType(
    val id: String,
    val name: String,
    val displayName: String = "",
)

/** Port of `struct ContributorResult` (`Models/Contributor.swift`). */
@Serializable
data class ContributorResult(
    val id: String,
    val name: String,
)

/** Port of `struct BookContributor` (`Book.swift:87-92`). */
@Serializable
data class BookContributor(
    val contributorId: String = "",
    val name: String = "",
    val role: String = "",
    val displayOrder: Int = 0,
)

/**
 * Port of `struct BookEdition` (`Book.swift:96-144`).
 *
 * `isbn10` / `isbn13` carry explicit `@SerialName` because `isbn_10` is not a
 * camel-case boundary: the snake-case naming strategy would emit `isbn10` and the
 * server would ignore the field. The Swift source explains the same trap at
 * `Book.swift:136-138` and fixes it with explicit `CodingKeys`.
 */
@Serializable
data class BookEdition(
    val id: String,
    val bookId: String = "",
    val format: String = "physical",
    val language: String = "",
    val editionName: String = "",
    val narrator: String = "",
    val publisher: String = "",
    val publishDate: String? = null,
    @SerialName("isbn_10") val isbn10: String = "",
    @SerialName("isbn_13") val isbn13: String = "",
    val copyCount: Int = 1,
    val description: String = "",
    val durationSeconds: Int? = null,
    val pageCount: Int? = null,
    val isPrimary: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** Port of `struct BookSeriesRef` (`Book.swift:148-152`). */
@Serializable
data class BookSeriesRef(
    val seriesId: String = "",
    val seriesName: String = "",
    val position: Double = 0.0,
)

/**
 * Port of `struct Book` (`Book.swift:18-83`).
 *
 * [libraryId] defaults to `""` rather than being required, because since the m2m
 * refactor a book can live in zero libraries (floating books backing AI
 * suggestions) and the server emits `library_id: null` for those. The source
 * comment says plainly that clients should not depend on it being present.
 *
 * The caller-scoped block ([userReadStatus] onward) is populated only by the
 * books list endpoint (api 26.4.x+), so every one of them is nullable — older
 * servers and offline caches decode without them, and the UI renders rating,
 * progress and lent overlays only when they are present and non-zero.
 *
 * These fields exist **only because the server computed them** and must not be
 * recomputed on the client. That is the "API owns the logic" rule from
 * `librarium-ios-main/AGENTS.md`.
 */
@Serializable
data class Book(
    val id: String,
    val title: String,
    val libraryId: String = "",
    val subtitle: String = "",
    val mediaTypeId: String = "",
    val mediaType: String = "",
    val description: String = "",
    val addedBy: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val contributors: List<BookContributor> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val coverUrl: String? = null,
    val hasCover: Boolean = false,

    /** `read` / `reading` / `did_not_finish` / `want_to_read` / null. */
    val userReadStatus: String? = null,
    /** 1–10 half-star integer; 0 or null means unrated. Not 1–5. */
    val userRating: Int? = null,
    /** 0–100. */
    val userProgressPct: Double? = null,
    val activeLoanCount: Int? = null,

    /**
     * How the caller comes to have this book: `shelf`, `wishlist`, `suggested`,
     * or `gap` for a volume of a series they hold part of that nobody has. Only
     * the cross-library list sends it.
     */
    val ownership: String? = null,

    /** Sent by the cross-library list; used to interleave accounts by release. */
    val publishYear: Int? = null,
) {
    /** First contributor with the `author` role, else the first contributor. */
    val primaryAuthor: String?
        get() = contributors.firstOrNull { it.role.equals("author", true) }?.name
            ?: contributors.firstOrNull()?.name

    val displaySubtitle: String get() = subtitle
}

/**
 * Port of `struct MyBook` (`Book.swift:165-196`).
 *
 * Keyed to the book rather than to a printing: an opinion is about the story, not
 * about which paperback it was read in, so it does not change when a second
 * edition is added.
 *
 * [id] is empty for a book nobody has said anything about. That is deliberate —
 * there is no row yet, and an invented id would have offline edits queued against
 * one that does not exist.
 */
@Serializable
data class MyBook(
    val id: String = "",
    val bookId: String = "",
    val readStatus: String = "unread",
    val rating: Double? = null,
    val isFavorite: Boolean = false,
    val review: String = "",
    val notes: String = "",
    val wants: Boolean = false,
    /**
     * True when the status came from a container the caller has read — an
     * omnibus holding this volume — rather than from anything said about this
     * book. An inherited status carries no rating: a rating is an opinion about
     * the thing rated and never moves through containment.
     */
    val inherited: Boolean = false,
)

/** Someone else's opinion of a book, as the server is willing to share it. */
@Serializable
data class BookReader(
    val userId: String,
    val displayName: String = "",
    val username: String = "",
    val readStatus: String = "",
    val rating: Double? = null,
    val isFavorite: Boolean = false,
    val review: String = "",
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

/**
 * Port of `struct SavedList` (`Models/SavedList.swift`).
 *
 * A shelf and a saved view differ only in how membership is settled: enumerated
 * by hand, or computed from a filter. That is a kind, not a second concept.
 *
 * [filterQuery] reads the nested `{"query": …}` object rather than being a bare
 * key; [SavedListFilter] models that. The nesting is there so the shape can grow
 * into something structured without breaking a client that only reads the query.
 */
@Serializable
data class SavedListFilter(val query: String = "")

@Serializable
data class SavedList(
    val id: String,
    val name: String = "",
    val description: String = "",
    val icon: String = "",
    val color: String = "",
    /** `manual` enumerates its books, `smart` computes them from a filter. */
    val kind: String = "manual",
    val visibility: String = "private",
    val sharedLibraryId: String? = null,
    val bookCount: Int = 0,
    /**
     * Non-empty when this is a list the product ships rather than one someone
     * made. Keyed on a name rather than the row id, which is minted per install
     * and would identify nothing on another server.
     */
    val builtinKey: String = "",
    /** Which page the view lands on: `books`, `series`, or `authors`. */
    val surface: String = "books",
    @SerialName("filter") val filter: SavedListFilter? = null,
) {
    val isSmart: Boolean get() = kind == "smart"
    val isDefault: Boolean get() = builtinKey == "default"
    val filterQuery: String get() = filter?.query ?: ""
}

/** Port of `struct Shelf` (`Models/Shelf.swift`). */
@Serializable
data class Shelf(
    val id: String,
    val libraryId: String = "",
    val name: String = "",
    val description: String = "",
    val color: String = "",
    val icon: String = "",
    val displayOrder: Int = 0,
    val bookCount: Int = 0,
    val tags: List<Tag> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
)

/**
 * Port of `struct Loan` (`Models/Loan.swift`), minus the date arithmetic, which
 * lives in [com.fireball1725.librarium.core.util.LoanDates] because it needs a
 * clock and a locale.
 */
@Serializable
data class Loan(
    val id: String,
    val libraryId: String = "",
    val bookId: String = "",
    val bookTitle: String = "",
    val loanedTo: String = "",
    val loanedAt: String = "",
    val dueDate: String? = null,
    val returnedAt: String? = null,
    val notes: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val isActive: Boolean get() = returnedAt == null
}

/** Port of `struct Copy` (`Models/Copy.swift`). */
@Serializable
data class Copy(
    val id: String,
    val libraryId: String = "",
    val bookId: String = "",
    /**
     * Nil when the printing was never recorded, which is a supported state
     * rather than a gap: a book can be on a shelf without anyone having said
     * which edition it is.
     */
    val editionId: String? = null,
    val condition: String = "",
    val isSigned: Boolean = false,
    val notes: String = "",
    val locationId: String? = null,
    /** Filled by reads that join it, empty on a bare row. */
    val locationName: String = "",
    /** Names the borrower when this copy is out, empty otherwise. */
    val onLoanTo: String = "",
) {
    val isLent: Boolean get() = onLoanTo.isNotEmpty()
}

/** Port of `struct LibraryMember` (`Models/Member.swift`). */
@Serializable
data class LibraryMember(
    val userId: String,
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val roleId: String = "",
    val role: String = "",
    val joinedAt: String = "",
)

/** Port of `struct AdminUser` (`Models/Admin.swift`). */
@Serializable
data class AdminUser(
    val id: String,
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
    val isInstanceAdmin: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: String = "",
    val lastLoginAt: String? = null,
)

/** Port of `struct ProviderStatus` (`Models/Admin.swift`). */
@Serializable
data class ProviderStatus(
    val name: String,
    val displayName: String = "",
    val description: String = "",
    val requiresKey: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val helpText: String? = null,
    val helpUrl: String? = null,
    val enabled: Boolean = false,
    val hasApiKey: Boolean = false,
)

/**
 * Port of `struct BookFingerprint` (`Models/BookFingerprint.swift`).
 *
 * [cacheToken] is the whole point: it changes whenever any book in a library
 * changes, so a cached page can be validated against it instead of re-fetching
 * everything to find out nothing moved.
 */
@Serializable
data class BookFingerprint(
    val total: Int = 0,
    val maxUpdatedAt: String? = null,
) {
    val cacheToken: String get() = "$total|${maxUpdatedAt ?: ""}"
}
