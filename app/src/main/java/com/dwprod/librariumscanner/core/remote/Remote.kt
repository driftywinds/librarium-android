// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.remote

import com.dwprod.librariumscanner.core.model.AdminUser
import com.dwprod.librariumscanner.core.model.AuthorIndexPage
import com.dwprod.librariumscanner.core.model.AuthTokens
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.BookContributor
import com.dwprod.librariumscanner.core.model.BookEdition
import com.dwprod.librariumscanner.core.model.BookFacets
import com.dwprod.librariumscanner.core.model.BookFingerprint
import com.dwprod.librariumscanner.core.model.BookReader
import com.dwprod.librariumscanner.core.model.BookSeriesRef
import com.dwprod.librariumscanner.core.model.ChangePasswordRequest
import com.dwprod.librariumscanner.core.model.ContributorResult
import com.dwprod.librariumscanner.core.model.Copy
import com.dwprod.librariumscanner.core.model.DashboardStats
import com.dwprod.librariumscanner.core.model.GroupedPage
import com.dwprod.librariumscanner.core.model.IsbnLookupResult
import com.dwprod.librariumscanner.core.model.Library
import com.dwprod.librariumscanner.core.model.LibraryMember
import com.dwprod.librariumscanner.core.model.Loan
import com.dwprod.librariumscanner.core.model.LoginRequest
import com.dwprod.librariumscanner.core.model.MediaType
import com.dwprod.librariumscanner.core.model.Genre
import com.dwprod.librariumscanner.core.model.MyBook
import com.dwprod.librariumscanner.core.model.ProviderStatus
import com.dwprod.librariumscanner.core.model.RefreshRequest
import com.dwprod.librariumscanner.core.model.Series
import com.dwprod.librariumscanner.core.model.SeriesIndexPage
import com.dwprod.librariumscanner.core.model.SeriesLookupResult
import com.dwprod.librariumscanner.core.model.Shelf
import com.dwprod.librariumscanner.core.model.SavedList
import com.dwprod.librariumscanner.core.model.Tag
import com.dwprod.librariumscanner.core.model.User
import com.dwprod.librariumscanner.core.net.LIBRARIUM_JSON
import com.dwprod.librariumscanner.core.net.LibrariumApi
import com.dwprod.librariumscanner.core.net.Paged
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.net.URLEncoder

/** Query encoding, matching iOS `addingPercentEncoding(.urlQueryAllowed)`. */
internal fun enc(raw: String): String =
    URLEncoder.encode(raw, Charsets.UTF_8).replace("+", "%20")

/** Serializer for the `{"items": [...]}` collection shape. */
fun <T> ItemsSerializer(item: KSerializer<T>) = ItemsPage.serializer(item)

/**
 * The sorts the books list takes, ported one for one from `BookSortOption`
 * (`BooksViewModel.swift:6-39`) — `field` and `dir` are the wire values the
 * API parses, and the pairs are the API's vocabulary, not a UI concern.
 */
enum class BookSortField(val field: String, val dir: String) {
    TITLE_ASC("title", "asc"),
    TITLE_DESC("title", "desc"),
    AUTHOR_ASC("author", "asc"),
    AUTHOR_DESC("author", "desc"),
    RECENTLY_ADDED("created_at", "desc"),
    OLDEST_ADDED("created_at", "asc"),
    NEWEST_RELEASE("publish_date", "desc"),
    OLDEST_RELEASE("publish_date", "asc"),
}

/**
 * The series index's sorts. The recent field is `publication_year` on the wire
 * and the name sort has no `dir` counterpart the server honours, so only the
 * two the layer actually sends are modelled.
 */
enum class SeriesSortField(val field: String, val dir: String) {
    NAME("name", "asc"),
    RECENT("publication_year", "desc"),
}

// ---------------------------------------------------------------- requests

/**
 * Port of `CreateBookRequest` / `ContributorInput` / `CreateEditionRequest`
 * (`BookService.swift:223-268`).
 */
@kotlinx.serialization.Serializable
data class ContributorInput(
    val contributorId: String,
    val role: String,
    val displayOrder: Int = 0,
)

@kotlinx.serialization.Serializable
data class CreateEditionRequest(
    val format: String = "physical",
    val language: String = "",
    val editionName: String = "",
    val narrator: String = "",
    val publisher: String = "",
    val publishDate: String? = null,
    // isbn_10 / isbn_13 need explicit keys — the snake-case strategy will not
    // insert an underscore before a digit, the same trap as convertToSnakeCase
    // (BookService.swift:255-267 carries an explicit CodingKeys for this).
    val isbn10: String = "",
    val isbn13: String = "",
    val description: String = "",
    val pageCount: Int? = null,
    val copyCount: Int? = null,
    val isPrimary: Boolean = true,
)

@kotlinx.serialization.Serializable
data class CreateBookRequest(
    val title: String,
    val subtitle: String = "",
    val mediaTypeId: String = "",
    val description: String = "",
    val contributors: List<ContributorInput> = emptyList(),
    val tagIds: List<String> = emptyList(),
    val genreIds: List<String> = emptyList(),
    /** Inline edition — included on create; null when updating book fields only. */
    val edition: CreateEditionRequest? = null,
)

/**
 * A partial write of reading state. Every field is optional: an omitted one is
 * left alone, which is the whole reason the endpoint is a partial update.
 *
 * [clearRating] is separate because omitting a rating means "no change", so one
 * nullable field cannot say both "leave it" and "remove it".
 */
@kotlinx.serialization.Serializable
data class UpdateMyBookRequest(
    val readStatus: String? = null,
    val rating: Double? = null,
    val clearRating: Boolean? = null,
    val isFavorite: Boolean? = null,
    val review: String? = null,
    val notes: String? = null,
)

@kotlinx.serialization.Serializable
data class UpdateInteractionRequest(
    val readStatus: String,
    val rating: Double? = null,
    val notes: String = "",
    val review: String = "",
    val dateStarted: String? = null,
    val dateFinished: String? = null,
    val isFavorite: Boolean = false,
)

@kotlinx.serialization.Serializable
data class UpdateProfileRequest(val displayName: String, val email: String)
// ChangePasswordRequest is defined in core.model (Models.kt) so the domain type
// and the wire shape do not drift apart; the LocalAuthService reads it from there.

@kotlinx.serialization.Serializable
data class CreateLibraryRequest(
    val name: String,
    val description: String = "",
    val isPublic: Boolean = false,
)

@kotlinx.serialization.Serializable
data class CreateTagRequest(val name: String)

@kotlinx.serialization.Serializable
data class CreateLoanRequest(
    val bookId: String,
    val loanedTo: String,
    val loanedAt: String,
    val dueDate: String? = null,
    val notes: String = "",
)

/**
 * A loan patch is **replace, not merge**: every field sent overwrites, so the
 * client must always send the complete record. iOS models that by making every
 * field required in `PatchLoanRequest`; keeping this shape stops a partial patch
 * from silently clearing someone's due date.
 */
@kotlinx.serialization.Serializable
data class PatchLoanRequest(
    val loanedTo: String,
    val loanedAt: String,
    val dueDate: String? = null,
    val returnedAt: String? = null,
    val notes: String = "",
)

@kotlinx.serialization.Serializable
data class CreateSeriesRequest(
    val name: String,
    val libraryId: String,
    val description: String = "",
    val totalCount: Int? = null,
    val status: String = "",
)

@kotlinx.serialization.Serializable
data class FetchCoverRequest(val url: String)

@kotlinx.serialization.Serializable
data class AdminUserPatch(val isActive: Boolean)

@kotlinx.serialization.Serializable
data class ProviderPatch(val enabled: Boolean, val apiKey: String? = null)

@kotlinx.serialization.Serializable
data class SetupAdminRequest(
    val username: String,
    val email: String,
    val displayName: String,
    val password: String,
)

@kotlinx.serialization.Serializable
data class WishlistEntryRequest(val bookId: String, val notes: String = "")

// ---------------------------------------------------------------- the facade

/**
 * Every server call, in the order the iOS files group them.
 *
 * One [LibrariumApi] per account is constructed by the caller; this class is
 * stateless over it. Errors surface as
 * [com.dwprod.librariumscanner.core.net.ApiException] — never `null`, which is
 * the contract the iOS services keep.
 */
class LibrariumApi2(private val api: LibrariumApi) {

    // ---------------------------------------------------------------- auth

    /**
     * `POST /api/v1/auth/login` — unauthenticated by construction.
     *
     * Explicit serializers rather than the reified overload: `AuthTokens` is
     * what turns a successful login into a session, and a reified lookup that
     * resolves the wrong way fails at runtime as a plain
     * `SerializationException`, which reads to the user as a connection
     * problem. Naming both types makes the failure impossible.
     */
    suspend fun login(identifier: String, password: String): AuthTokens =
        api.post(
            "/api/v1/auth/login",
            LoginRequest(identifier, password),
            AuthTokens.serializer(),
            LoginRequest.serializer(),
        )

    /** Ends the session server-side, then the tokens are useless anyway. */
    suspend fun logout(): Unit = api.postVoid("/api/v1/auth/logout")

    /** Refreshes the session. Must be called through the single-flight hook. */
    suspend fun refresh(refreshToken: String): AuthTokens =
        api.post(
            "/api/v1/auth/refresh",
            RefreshRequest(refreshToken),
            AuthTokens.serializer(),
            RefreshRequest.serializer(),
        )

    suspend fun updateProfile(displayName: String, email: String): User =
        api.put(
            "/api/v1/auth/me",
            UpdateProfileRequest(displayName, email),
            User.serializer(),
            UpdateProfileRequest.serializer(),
        )

    suspend fun changePassword(current: String, new: String) {
        api.putVoid("/api/v1/auth/me/password", ChangePasswordRequest(current, new))
    }

    // ---------------------------------------------------------------- libraries

    /** Bare array response — the only collection endpoint not enveloped. */
    suspend fun libraries(): List<Library> =
        api.get("/api/v1/libraries", ListSerializer(Library.serializer()))

    suspend fun library(id: String): Library = api.get("/api/v1/libraries/$id")

    suspend fun createLibrary(name: String, description: String, isPublic: Boolean): Library =
        api.post("/api/v1/libraries", CreateLibraryRequest(name, description, isPublic))

    suspend fun updateLibrary(libraryId: String, body: CreateLibraryRequest): Library =
        api.put("/api/v1/libraries/$libraryId", body)

    suspend fun deleteLibrary(libraryId: String) = api.delete("/api/v1/libraries/$libraryId")

    // ---- tags

    /** Bare array. */
    suspend fun libraryTags(libraryId: String): List<Tag> =
        api.get("/api/v1/libraries/$libraryId/tags", ListSerializer(Tag.serializer()))

    suspend fun createTag(libraryId: String, name: String): Tag =
        api.post("/api/v1/libraries/$libraryId/tags", CreateTagRequest(name))

    /** Bare array; the person-scoped form. */
    suspend fun meTags(libraryId: String, query: String = ""): List<Tag> {
        val q = if (query.isEmpty()) "" else "?q=${enc(query)}"
        return api.get("/api/v1/me/tags$q", ListSerializer(Tag.serializer()))
    }

    // ---- members

    suspend fun members(libraryId: String): List<LibraryMember> =
        api.get("/api/v1/libraries/$libraryId/members", ListSerializer(LibraryMember.serializer()))

    suspend fun addMember(libraryId: String, userId: String): LibraryMember =
        api.post("/api/v1/libraries/$libraryId/members", AddMemberRequest(userId))

    suspend fun removeMember(libraryId: String, userId: String) =
        api.delete("/api/v1/libraries/$libraryId/members/$userId")

    // ---------------------------------------------------------------- books

    /**
     * One library's books, asked for through the person-scoped list.
     *
     * `/libraries/{id}/books` is the read path the tier API retires: the server
     * answers the same question from `/me/books` narrowed by `lib`, and it
     * narrows by intersection with what the caller can actually read. Every
     * filter this takes is parsed by the same code on the server, so the two
     * answers match. Only the query string differs.
     */
    suspend fun books(
        libraryId: String,
        query: String = "",
        page: Int = 1,
        perPage: Int = 25,
        tag: String = "",
        typeFilter: String = "",
        sort: String = "",
        sortDir: String = "",
    ): Paged<Book> {
        var path = "/api/v1/me/books?lib=$libraryId&page=$page&per_page=$perPage"
        if (query.isNotEmpty()) path += "&q=${enc(query)}"
        if (tag.isNotEmpty()) path += "&tag=${enc(tag)}"
        if (typeFilter.isNotEmpty()) path += "&type_filter=${enc(typeFilter)}"
        if (sort.isNotEmpty()) path += "&sort=$sort"
        if (sortDir.isNotEmpty()) path += "&sort_dir=$sortDir"
        return api.get(path, Paged.serializer(Book.serializer()))
    }

    suspend fun book(libraryId: String, bookId: String): Book =
        api.get("/api/v1/libraries/$libraryId/books/$bookId")

    /**
     * A book without naming a library. A volume nobody holds belongs to no
     * library, so the per-library route answers 404 for exactly the books a
     * series page needs in order to show what is missing.
     */
    suspend fun book(bookId: String): Book = api.get("/api/v1/books/$bookId")

    suspend fun fingerprint(libraryId: String): BookFingerprint =
        api.get("/api/v1/libraries/$libraryId/books/fingerprint")

    suspend fun bookByIsbn(libraryId: String, isbn: String): Book =
        api.get("/api/v1/libraries/$libraryId/book-by-isbn/$isbn")

    suspend fun createBook(libraryId: String, body: CreateBookRequest): Book =
        api.post("/api/v1/libraries/$libraryId/books", body)

    suspend fun updateBook(libraryId: String, bookId: String, body: CreateBookRequest): Book =
        api.put("/api/v1/libraries/$libraryId/books/$bookId", body)

    suspend fun deleteBook(libraryId: String, bookId: String) =
        api.delete("/api/v1/libraries/$libraryId/books/$bookId")

    /**
     * Queues a library-agnostic metadata + cover enrichment batch. Fire and
     * forget — only the queueing matters.
     */
    suspend fun enrich(bookId: String) {
        api.postVoid("/api/v1/books/$bookId/enrich", EmptyRequest)
    }

    // ---- cover

    suspend fun fetchCover(libraryId: String, bookId: String, url: String) {
        api.postVoid("/api/v1/libraries/$libraryId/books/$bookId/cover/fetch", FetchCoverRequest(url))
    }

    suspend fun deleteCover(libraryId: String, bookId: String) =
        api.delete("/api/v1/libraries/$libraryId/books/$bookId/cover")

    // ---- editions

    /** `{"items": [...]}`. */
    suspend fun editions(libraryId: String, bookId: String): List<BookEdition> =
        api.get("/api/v1/libraries/$libraryId/books/$bookId/editions", ItemsSerializer(BookEdition.serializer())).items

    suspend fun createEdition(libraryId: String, bookId: String, body: CreateEditionRequest): BookEdition =
        api.post("/api/v1/libraries/$libraryId/books/$bookId/editions", body)

    suspend fun updateEdition(libraryId: String, bookId: String, editionId: String, body: CreateEditionRequest): BookEdition =
        api.put("/api/v1/libraries/$libraryId/books/$bookId/editions/$editionId", body)

    // ---- interaction (per-edition, retained for the paths that still speak it)

    /**
     * Returns null when the caller has no interaction row yet: the server
     * answers 200 with a null envelope rather than 404, and the generic decoder
     * cannot tell that from a decode failure, so both read as "nothing yet".
     */
    suspend fun interaction(libraryId: String, bookId: String, editionId: String): UpdateInteractionRequest? =
        try {
            api.get(
                "/api/v1/libraries/$libraryId/books/$bookId/editions/$editionId/my-interaction",
                UpdateInteractionRequest.serializer()
            )
        } catch (_: com.dwprod.librariumscanner.core.net.ApiException.Decoding) {
            null
        }

    suspend fun updateInteraction(
        libraryId: String,
        bookId: String,
        editionId: String,
        body: UpdateInteractionRequest,
    ): UpdateInteractionRequest =
        api.put(
            "/api/v1/libraries/$libraryId/books/$bookId/editions/$editionId/my-interaction",
            body
        )

    // ---- reading state keyed to the work

    /**
     * Never returns null the way the per-edition read did. A book nobody has
     * said anything about comes back as the default rather than a 404, because
     * 404 would make every unread book look like a broken link.
     */
    suspend fun myBook(bookId: String): MyBook = api.get("/api/v1/books/$bookId/me")

    suspend fun updateMyBook(bookId: String, body: UpdateMyBookRequest): MyBook =
        api.put("/api/v1/books/$bookId/me", body)

    /**
     * Everyone who shares a library with the caller and has said something
     * about this book. Private notes are never in here: the server does not
     * select them.
     */
    suspend fun readers(bookId: String): List<BookReader> =
        api.get("/api/v1/books/$bookId/readers", ItemsSerializer(BookReader.serializer())).items

    // ---- shelves / series refs

    suspend fun bookShelves(libraryId: String, bookId: String): List<Shelf> =
        api.get("/api/v1/libraries/$libraryId/books/$bookId/shelves", ListSerializer(Shelf.serializer()))

    suspend fun bookSeries(libraryId: String, bookId: String): List<BookSeriesRef> =
        api.get("/api/v1/libraries/$libraryId/books/$bookId/series", ListSerializer(BookSeriesRef.serializer()))

    // ---- copies

    suspend fun copies(bookId: String): List<Copy> =
        api.get("/api/v1/books/$bookId/copies", ItemsSerializer(Copy.serializer())).items

    suspend fun createCopy(libraryId: String, bookId: String, body: CreateCopyRequest): Copy =
        api.post("/api/v1/libraries/$libraryId/books/$bookId/copies", body)

    suspend fun updateCopy(copyId: String, body: PatchCopyRequest): Copy =
        api.patch("/api/v1/copies/$copyId", body)

    suspend fun deleteCopy(copyId: String) = api.delete("/api/v1/copies/$copyId")

    // ---- contents / containers

    suspend fun contents(bookId: String): ContentPage =
        api.get("/api/v1/books/$bookId/contents", ContentPage.serializer())

    suspend fun containers(bookId: String): ContentPage =
        api.get("/api/v1/books/$bookId/containers", ContentPage.serializer())

    suspend fun addToContainer(containerId: String, containedId: String) {
        api.postVoid("/api/v1/books/$containerId/contents/$containedId", EmptyRequest)
    }

    suspend fun removeFromContainer(containerId: String, containedId: String) {
        api.delete("/api/v1/books/$containerId/contents/$containedId")
    }

    // ---------------------------------------------------------------- loans

    suspend fun loans(libraryId: String): LoanPage =
        api.get("/api/v1/libraries/$libraryId/loans", LoanPage.serializer())

    suspend fun createLoan(libraryId: String, body: CreateLoanRequest): Loan =
        api.post("/api/v1/libraries/$libraryId/loans", body)

    /**
     * Replace, not merge: the server overwrites every field it is sent, so
     * [PatchLoanRequest] is deliberately fully-specified (see its doc).
     */
    suspend fun updateLoan(libraryId: String, loanId: String, body: PatchLoanRequest): Loan =
        api.patch("/api/v1/libraries/$libraryId/loans/$loanId", body)

    suspend fun deleteLoan(libraryId: String, loanId: String) =
        api.delete("/api/v1/libraries/$libraryId/loans/$loanId")

    suspend fun meLoans(): LoanPage = api.get("/api/v1/me/loans", LoanPage.serializer())

    // ---------------------------------------------------------------- series

    suspend fun series(libraryId: String): SeriesListPage =
        api.get("/api/v1/libraries/$libraryId/series", SeriesListPage.serializer())

    suspend fun series(libraryId: String, seriesId: String): Series =
        api.get("/api/v1/libraries/$libraryId/series/$seriesId")

    suspend fun createSeries(libraryId: String, body: CreateSeriesRequest): Series =
        api.post("/api/v1/libraries/$libraryId/series", body)

    suspend fun updateSeries(libraryId: String, seriesId: String, body: CreateSeriesRequest): Series =
        api.put("/api/v1/libraries/$libraryId/series/$seriesId", body)

    suspend fun deleteSeries(libraryId: String, seriesId: String) =
        api.delete("/api/v1/libraries/$libraryId/series/$seriesId")

    suspend fun seriesBooks(libraryId: String, seriesId: String): List<SeriesBook> =
        api.get("/api/v1/libraries/$libraryId/series/$seriesId/books", ListSerializer(SeriesBook.serializer()))

    suspend fun seriesVolumes(libraryId: String, seriesId: String): List<SeriesVolume> =
        api.get("/api/v1/libraries/$libraryId/series/$seriesId/volumes", ListSerializer(SeriesVolume.serializer()))

    suspend fun seriesArcs(libraryId: String, seriesId: String): List<SeriesArc> =
        api.get("/api/v1/libraries/$libraryId/series/$seriesId/arcs", ListSerializer(SeriesArc.serializer()))

    // ---------------------------------------------------------------- me

    /** The `/api/v1/me/books/facets` envelope is doubly nested. */
    suspend fun bookFacets(lib: String?): BookFacets {
        val path = "/api/v1/me/books/facets" + (lib?.let { "?lib=$it" } ?: "")
        return api.get(path, BookFacets.serializer())
    }

    /**
     * The person-scoped book list, narrowed by any of the eleven browse
     * dimensions.
     *
     * Port of `MeBrowseService.books` (`MeBrowseService.swift:31-40`). The
     * parameters go through as one pre-built query string the caller composes
     * with [BrowseQuery], because the facet vocabulary is shared by the list,
     * the grouped list and the counts, and each would otherwise drift:
     * [BrowseQuery.queryString] is that one serialisation, byte for byte the
     * same the three calls send.
     */
    suspend fun browseBooks(query: String, page: Int, perPage: Int, sort: BookSortField): Paged<Book> =
        api.get(
            "/api/v1/me/books?${query}${pageQuery(page, perPage)}sort=${sort.field}&sort_dir=${sort.dir}",
            Paged.serializer(Book.serializer()),
        )

    /**
     * The same list with each run collapsed into one row — port of
     * `MeBrowseService.grouped` (`MeBrowseService.swift:47-56`).
     */
    suspend fun browseGrouped(query: String, page: Int, perPage: Int, sort: BookSortField): GroupedPage =
        api.get(
            "/api/v1/me/books/grouped?${query}${pageQuery(page, perPage)}sort=${sort.field}&sort_dir=${sort.dir}",
            GroupedPage.serializer(),
        )

    /**
     * Counts per facet value, sent with the *same* selection as the list but
     * with that facet excluded from its own count — the server does the
     * excluding (each dimension is counted with its own selection removed so a
     * zero-count value can be offered). Port of `MeBrowseService.facets`
     * (`MeBrowseService.swift:64-74`); the double envelope is handled inside
     * `decodeEnvelope`.
     */
    suspend fun browseFacets(query: String): BookFacets =
        api.get("/api/v1/me/books/facets?$query", BookFacets.serializer())

    /**
     * The person-scoped series index with its own facets — port of the
     * `/me/series/index` call behind `RedesignedSeriesListView`. Takes the
     * whole query string for the same reason [browseBooks] does.
     */
    suspend fun browseSeries(query: String, page: Int, sort: SeriesSortField): SeriesIndexPage =
        api.get(
            "/api/v1/me/series/index?${query}volumes=1&page=$page&sort=${sort.field}&dir=${sort.dir}",
            SeriesIndexPage.serializer(),
        )

    /**
     * The person-scoped author index. No paging on the wire: the server pages
     * it, so the client sends `page` only when it means it (matching
     * `MeBrowseService`'s author call, which sends none of these).
     */
    suspend fun browseAuthors(libraryId: String, query: String = "", role: String = ""): AuthorIndexPage =
        authorsIndex(libraryId, query, role)

    private fun pageQuery(page: Int, perPage: Int): String =
        "page=$page&per_page=$perPage&"

    /** The grouped list already has its own method; keep them apart. */
    suspend fun groupedLegacy(query: String, page: Int): GroupedPage =
        api.get("/api/v1/me/books/grouped?q=$query&page=$page", GroupedPage.serializer())

    /** Bare array. */
    suspend fun suggestions(): List<Series> =
        api.get("/api/v1/me/suggestions", ListSerializer(Series.serializer()))

    suspend fun blockSuggestion(id: String) =
        api.postVoid("/api/v1/me/suggestions/$id/block", EmptyRequest)

    suspend fun setSuggestionStatus(id: String, status: String) {
        api.postVoid("/api/v1/me/suggestions/$id/status", SuggestionStatusRequest(status))
    }

    suspend fun wishlists(): WishlistPage =
        api.get("/api/v1/me/wishlist", WishlistPage.serializer())

    suspend fun addToWishlist(bookId: String, notes: String) {
        api.postVoid("/api/v1/me/wishlist", WishlistEntryRequest(bookId, notes))
    }

    suspend fun removeFromWishlist(entryId: String) {
        api.delete("/api/v1/me/wishlist/$entryId")
    }

    /** The account's curated lists (`{"items"}` shape). */
    suspend fun lists(): List<SavedList> =
        api.get("/api/v1/me/lists", ItemsSerializer(SavedList.serializer())).items

    suspend fun list(id: String): SavedList = api.get("/api/v1/me/lists/$id")

    suspend fun deleteList(id: String) = api.delete("/api/v1/me/lists/$id")

    /** The one `/me/counts` response — a bare map of label to number. */
    suspend fun counts(): Map<String, Int> =
        api.get("/api/v1/me/counts", MapSerializer(String.serializer(), Int.serializer()))

    // ---------------------------------------------------------------- browse

    suspend fun seriesIndex(libraryId: String, query: String = "", page: Int = 1): SeriesIndexPage {
        var path = "/api/v1/me/series/index?lib=$libraryId&volumes=1&page=$page"
        if (query.isNotEmpty()) path += "&q=${enc(query)}"
        return api.get(path, SeriesIndexPage.serializer())
    }

    suspend fun authorsIndex(libraryId: String, query: String = "", role: String = ""): AuthorIndexPage {
        var path = "/api/v1/me/authors/index?lib=$libraryId"
        if (query.isNotEmpty()) path += "&q=${enc(query)}"
        if (role.isNotEmpty()) path += "&role=${enc(role)}"
        return api.get(path, AuthorIndexPage.serializer())
    }

    // ---------------------------------------------------------------- dashboard

    suspend fun currentlyReading(): List<Book> =
        api.get("/api/v1/dashboard/currently-reading", ListSerializer(Book.serializer()))

    suspend fun recentlyFinished(): List<Book> =
        api.get("/api/v1/dashboard/recently-finished", ListSerializer(Book.serializer()))

    suspend fun recentlyAdded(): List<Book> =
        api.get("/api/v1/dashboard/recently-added", ListSerializer(Book.serializer()))

    suspend fun continueSeries(): List<Book> =
        api.get("/api/v1/dashboard/continue-series", ListSerializer(Book.serializer()))

    suspend fun picksOfTheDay(): List<Book> =
        api.get("/api/v1/dashboard/picks-of-the-day", ListSerializer(Book.serializer()))

    suspend fun dashStats(): DashboardStats =
        api.get("/api/v1/dashboard/stats", DashboardStats.serializer())

    // ---------------------------------------------------------------- lookup

    /**
     * Note: no percent-encoding, matching iOS (`LookupService.swift:7`). An ISBN
     * filtered to `[0-9X]` needs none, and encoding it would change the path
     * shape the server's handler was built against.
     */
    suspend fun lookupIsbn(isbn: String): List<IsbnLookupResult> =
        api.get("/api/v1/lookup/isbn/$isbn", ListSerializer(IsbnLookupResult.serializer()))

    suspend fun lookupBooks(query: String): List<IsbnLookupResult> =
        api.get("/api/v1/lookup/books?q=${enc(query)}", ListSerializer(IsbnLookupResult.serializer()))

    suspend fun lookupSeries(query: String): List<SeriesLookupResult> =
        api.get("/api/v1/lookup/series?q=${enc(query)}", ListSerializer(SeriesLookupResult.serializer()))

    // ---------------------------------------------------------------- vocab

    /** Bare array. */
    suspend fun mediaTypes(): List<MediaType> =
        api.get("/api/v1/media-types", ListSerializer(MediaType.serializer()))

    suspend fun genres(): List<Genre> =
        api.get("/api/v1/genres", ListSerializer(Genre.serializer()))

    suspend fun editionFormats(): List<FormatPage> =
        api.get("/api/v1/edition-formats", ItemsSerializer(FormatPage.serializer())).items

    suspend fun contributors(query: String): List<ContributorResult> =
        api.get("/api/v1/contributors?q=${enc(query)}", ListSerializer(ContributorResult.serializer()))

    suspend fun createContributor(name: String): ContributorResult =
        api.post("/api/v1/contributors", CreateContributorRequest(name))

    // ---------------------------------------------------------------- admin

    suspend fun adminUsers(page: Int = 1, perPage: Int = 25): Paged<AdminUser> =
        api.get("/api/v1/admin/users?page=$page&per_page=$perPage", Paged.serializer(AdminUser.serializer()))

    suspend fun createAdminUser(username: String, email: String, password: String): AdminUser =
        api.post("/api/v1/admin/users", CreateAdminUserRequest(username, email, password))

    suspend fun patchAdminUser(userId: String, isActive: Boolean): AdminUser =
        api.patch("/api/v1/admin/users/$userId", AdminUserPatch(isActive))

    suspend fun deleteAdminUser(userId: String) = api.delete("/api/v1/admin/users/$userId")

    suspend fun providers(): List<ProviderStatus> =
        api.get("/api/v1/admin/providers", ListSerializer(ProviderStatus.serializer()))

    suspend fun setProvider(name: String, body: ProviderPatch) {
        api.putVoid("/api/v1/admin/providers/$name", body)
    }

    // ---------------------------------------------------------------- setup

    suspend fun setupStatus(): SetupStatus = api.get("/api/v1/setup/status", SetupStatus.serializer())

    /**
     * Creates the first admin and returns the session, exactly as
     * `SetupService.bootstrapAdmin` does on iOS: the endpoint signs the new
     * admin in, so discarding the tokens would force an immediate re-login.
     */
    suspend fun createFirstAdmin(body: SetupAdminRequest): AuthTokens =
        api.post(
            "/api/v1/setup/admin",
            body,
            AuthTokens.serializer(),
            SetupAdminRequest.serializer(),
        )

    // ---------------------------------------------------------------- books: misc

    suspend fun bookLists(bookId: String): List<SavedList> =
        api.get("/api/v1/books/$bookId/lists", ItemsSerializer(SavedList.serializer())).items
}

// ---------------------------------------------------------------- extras

@kotlinx.serialization.Serializable
data object EmptyRequest

@kotlinx.serialization.Serializable
data class AddMemberRequest(val userId: String)

@kotlinx.serialization.Serializable
data class SuggestionStatusRequest(val status: String)

@kotlinx.serialization.Serializable
data class CreateAdminUserRequest(val username: String, val email: String, val password: String)

@kotlinx.serialization.Serializable
data class CreateContributorRequest(val name: String)

/**
 * `GET /api/v1/setup/status`.
 *
 * The field is `initialized`, and the *false* case is what matters: a server
 * with no users yet needs the bootstrap flow, not a login. This was previously
 * modelled as `needsAdmin`, which is the inverse — reading it would have sent
 * a fresh server's users to a login form that cannot succeed.
 */
@kotlinx.serialization.Serializable
data class SetupStatus(val initialized: Boolean = true)

@kotlinx.serialization.Serializable
data class SeriesVolume(
    val id: String = "",
    val position: Double = 0.0,
    val bookId: String = "",
    val owned: Boolean = false,
)

@kotlinx.serialization.Serializable
data class SeriesArc(
    val id: String = "",
    val name: String = "",
    val ordinal: Int = 0,
)

/** `{"items": [...]}` shape shared by several collection endpoints. */
@kotlinx.serialization.Serializable
data class ItemsPage<T>(val items: List<T> = emptyList())

@kotlinx.serialization.Serializable
data class LoanPage(val items: List<Loan> = emptyList(), val total: Int = 0)

@kotlinx.serialization.Serializable
data class SeriesListPage(val items: List<Series> = emptyList(), val total: Int = 0)

@kotlinx.serialization.Serializable
data class WishlistPage(val items: List<WishlistEntry> = emptyList(), val total: Int = 0)

@kotlinx.serialization.Serializable
data class WishlistEntry(
    val id: String = "",
    val bookId: String = "",
    val notes: String = "",
    val createdAt: String = "",
)

@kotlinx.serialization.Serializable
data class ContentPage(val items: List<ContentRow> = emptyList())

@kotlinx.serialization.Serializable
data class ContentRow(
    val id: String = "",
    val title: String = "",
    val containedId: String = "",
    val position: Int = 0,
)

@kotlinx.serialization.Serializable
data class CreateCopyRequest(
    val editionId: String? = null,
    val condition: String = "",
    val isSigned: Boolean = false,
    val notes: String = "",
)

@kotlinx.serialization.Serializable
data class PatchCopyRequest(
    val editionId: String? = null,
    val condition: String? = null,
    val isSigned: Boolean? = null,
    val notes: String? = null,
)

@kotlinx.serialization.Serializable
data class SeriesBook(val book: Book)

/** `/api/v1/edition-formats` rows — `{"items": [...]}`. */
@kotlinx.serialization.Serializable
data class FormatPage(
    val id: String = "",
    val value: String = "",
    val label: String = "",
)
