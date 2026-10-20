// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.scan

import com.dwprod.librariumscanner.core.lookup.IsbnLookupService
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.IsbnLookupResult
import com.dwprod.librariumscanner.core.model.Library
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.CreateBookRequest
import com.dwprod.librariumscanner.core.remote.CreateEditionRequest
import com.dwprod.librariumscanner.core.remote.LibrariumApi2
import com.dwprod.librariumscanner.core.remote.UpdateMyBookRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One library's answer to "do you already have this book?".
 *
 * [copyCount] lives on the edition, not the book, and the ownership card uses it
 * to say "2 copies" — which is why a hit costs a second request for editions
 * (`RedesignedScanFlow.swift:1911-1921`). `null` in the map means "asked, none";
 * a missing key means "no answer yet".
 */
data class OwnedMatch(val bookId: String, val copyCount: Int)

/**
 * The scan-to-add feature, server-mode only.
 *
 * Port of the server branches of `RedesignedScanFlow.swift`, with the Lite and
 * on-device provider branches omitted: this build has no Hardcover/OpenLibrary
 * keys to fall back on, and the server already fans out across its own
 * providers. Order matters and is copied, not improvised —
 *
 * 1. lookup, so the result screen always has an [IsbnLookupResult] to show,
 * 2. then per-library ownership checks, concurrently,
 * 3. then add: create the book with an inline primary edition, then a
 *    best-effort cover fetch, then reading status only when it is not "unread".
 */
class ScanRepository(private val lookup: IsbnLookupService) {

    sealed interface AddOutcome {
        /** Book created; [coverFetched] reports whether the server stored its art. */
        data class Added(val book: Book, val coverFetched: Boolean) : AddOutcome
        data class Failure(val message: String) : AddOutcome
    }

    /**
     * Normalised scan: only numerals and `X` survive, uppercased.
     *
     * No check-digit validation, matching the source. A scan with a misread digit
     * is still the best attempt available and is worth sending to the providers.
     */
    fun normalise(raw: String): String =
        raw.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()

    /**
     * True when what was typed reads as an ISBN rather than a title.
     *
     * Port of `isISBN` (`RedesignedScanFlow.swift:561-568`) — only digits, spaces
     * and hyphens, and the right count of them.
     */
    fun isIsbnShape(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.all { it.isDigit() || it == '-' || it == ' ' || it == 'X' || it == 'x' }) return false
        val n = normalise(trimmed).length
        return n == 10 || n == 13
    }

    /** Port of `lookupMetadata`, trimmed to the server path. */
    suspend fun lookupBook(api: LibrariumApi2, isbn: String) = lookup.lookup(api, isbn)

    /** Port of `checkOwnership` (`RedesignedScanFlow.swift:1890-1932`), minus the Lite branch. */
    suspend fun checkOwnership(
        api: LibrariumApi2,
        isbn: String,
        lookupResult: IsbnLookupResult?,
        libraries: List<Library>,
    ): Map<String, OwnedMatch?> {
        val lookupIsbn13 = if (isbn.length == 13) isbn else (lookupResult?.isbn13 ?: "")
        val lookupIsbn10 = if (isbn.length == 10) isbn else (lookupResult?.isbn10 ?: "")
        val candidates = setOf(isbn, lookupIsbn13, lookupIsbn10).filter { it.isNotEmpty() }

        return coroutineScope {
            libraries.map { library ->
                async {
                    for (candidate in candidates) {
                        val book = try {
                            api.bookByIsbn(library.id, candidate)
                        } catch (_: ApiException.NotFound) {
                            continue
                        } catch (_: ApiException) {
                            continue
                        }
                        // The copy count lives on the edition, not the book, so
                        // this is a second call — and only on a hit, the rare case.
                        val editions = try {
                            api.editions(library.id, book.id)
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val copies = editions.firstOrNull { it.isPrimary }?.copyCount
                            ?: editions.firstOrNull()?.copyCount
                            ?: 1
                        return@async library.clientKey to OwnedMatch(book.id, copies)
                    }
                    library.clientKey to null
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * Port of `addToLibrary` (`RedesignedScanFlow.swift:1934-2023`), minus the
     * Lite branch.
     */
    suspend fun addToLibrary(
        api: LibrariumApi2,
        library: Library,
        lookupResult: IsbnLookupResult,
        /**
         * The user's picked media type; null falls back to "" and the caller is
         * expected to have pre-filled it from `/media-types` so the api never
         * gets an empty UUID (which it rejects).
         */
        mediaTypeId: String?,
        readStatus: String?,
        tagIds: List<String> = emptyList(),
        genreIds: List<String> = emptyList(),
        format: String = "physical",
    ): AddOutcome {
        val body = CreateBookRequest(
            title = lookupResult.title,
            subtitle = lookupResult.subtitle,
            mediaTypeId = mediaTypeId ?: "",
            description = lookupResult.description,
            contributors = emptyList(),
            tagIds = tagIds,
            genreIds = genreIds,
            edition = CreateEditionRequest(
                format = format,
                language = lookupResult.language,
                editionName = "",
                narrator = "",
                publisher = lookupResult.publisher,
                publishDate = lookupResult.publishDate.ifEmpty { null },
                isbn10 = lookupResult.isbn10,
                isbn13 = lookupResult.isbn13,
                description = lookupResult.description,
                pageCount = lookupResult.pageCount,
                copyCount = 1,
                isPrimary = true,
            ),
        )

        val book = try {
            api.createBook(library.id, body)
        } catch (e: ApiException) {
            return AddOutcome.Failure(e.display)
        }

        // The lookup's cover lives at the provider. Ask the server to fetch and
        // store it, or the book keeps the image only until this screen goes
        // away. Best effort: a provider that is down or rate-limiting is not a
        // reason to fail the add.
        var coverFetched = false
        if (lookupResult.coverUrl.isNotEmpty()) {
            try {
                api.fetchCover(library.id, book.id, lookupResult.coverUrl)
                coverFetched = true
            } catch (_: Exception) {
            }
        }

        // Reading state belongs to the work, not to a printing. "unread" is the
        // server's default, so sending it would be a round-trip that writes
        // nothing — the source omits it for that reason.
        if (readStatus != null && readStatus != "unread") {
            try {
                api.updateMyBook(book.id, UpdateMyBookRequest(readStatus = readStatus))
            } catch (_: Exception) {
            }
        }

        return AddOutcome.Added(book, coverFetched)
    }
}
