// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.lookup

import com.dwprod.librariumscanner.core.model.IsbnLookupResult
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.LibrariumApi2
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Resolves a scanned barcode to book metadata.
 *
 * This is the **server-mode** half of the iOS scan flow: the api fans out across
 * its own provider set server-side and returns `Envelope<[ISBNLookupResult]>`.
 * iOS's on-device providers (Hardcover, Open Library) exist for Lite installs
 * that have no server; this build is server-mode only, so there is nothing on the
 * device to fan out to and the cascade is one tier, not two. Keeping the
 * [LookupOutcome] split anyway means "no server has this" is still a distinct
 * user-visible thing from "we could not reach the server", which is the distinction
 * the source calls out (`RedesignedScanFlow.swift:2069-2077`).
 *
 * Merging follows `LiteMetadataAggregator.merge` exactly, because the server can
 * return several provider answers for one ISBN and taking one whole answer threw
 * away what the others knew (librarium-ios-033: Hardcover wins on title but
 * carries no categories, so every book scanned against a server had no genres).
 */
class IsbnLookupService {

    sealed interface LookupOutcome {
        /** Metadata resolved and merged. */
        data class Success(val result: IsbnLookupResult) : LookupOutcome

        /** Asked and answered; nobody had the ISBN. */
        data object NoSourceHasIt : LookupOutcome

        /** Could not ask anyone — offline, refused, or no account signed in. */
        data class Failed(val message: String) : LookupOutcome
    }

    /**
     * Port of `lookupMetadata` (`RedesignedScanFlow.swift:2084-2139`), trimmed to
     * the server path.
     *
     * A result is only worth keeping if it carries a title: a row whose title is
     * empty is a provider admitting it does not know the book, not content.
     */
    suspend fun lookup(api: LibrariumApi2, isbn: String): LookupOutcome = try {
        val results = api.lookupIsbn(isbn)
        val named = results.filter { it.title.isNotEmpty() }
        if (named.isNotEmpty()) {
            LookupOutcome.Success(merge(named)!!)
        } else {
            LookupOutcome.NoSourceHasIt
        }
    } catch (e: ApiException) {
        LookupOutcome.Failed(e.display)
    }

    /**
     * Free-text search across the server's providers, for a book whose barcode is
     * missing or unreadable. Same result shape as an ISBN lookup, so a chosen row
     * can go straight into the scan result screen.
     *
     * A row is only worth showing if it carries an ISBN: the screen it opens is
     * keyed on one. Several providers answer the same book, so the first to claim
     * an ISBN keeps the row (`RedesignedScanFlow.swift:534-541`).
     */
    suspend fun search(api: LibrariumApi2, query: String): List<IsbnLookupResult> = try {
        val raw = api.lookupBooks(query)
        var seen = mutableSetOf<String>()
        raw.filter { result ->
            val key = result.isbn13.ifEmpty { result.isbn10 }
            key.isNotEmpty() && seen.add(key)
        }
    } catch (e: ApiException) {
        emptyList()
    }

    /**
     * Merge candidates into one result, taking the first non-empty value for each
     * field. Input order is provider priority order, so the higher-priority
     * source wins any field it can answer and the rest fill the gaps. Open
     * Library rarely has a description; Hardcover usually does — the union beats
     * either source alone.
     *
     * `providerDisplay` is rewritten to credit every source that contributed, so
     * the result sheet can say where the incoming values came from.
     *
     * Port of `LiteMetadataAggregator.merge` (`LiteMetadataProvider.swift:124-155`).
     */
    fun merge(results: List<IsbnLookupResult>): IsbnLookupResult? {
        val first = results.firstOrNull() ?: return null
        fun String?.or(firstNonEmpty: () -> String) = when {
            this == null -> ""
            else -> this
        }
        fun pick(select: (IsbnLookupResult) -> String): String =
            results.firstOrNull { select(it).isNotEmpty() }?.let(select) ?: ""

        val credited = results.map { it.providerDisplay }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" + ")

        return IsbnLookupResult(
            provider = first.provider,
            providerDisplay = credited.ifEmpty { first.providerDisplay },
            title = pick { it.title },
            subtitle = pick { it.subtitle },
            authors = results.firstOrNull { it.authors.isNotEmpty() }?.authors ?: emptyList(),
            publisher = pick { it.publisher },
            publishDate = pick { it.publishDate },
            isbn10 = pick { it.isbn10 },
            isbn13 = pick { it.isbn13 },
            description = pick { it.description },
            coverUrl = pick { it.coverUrl },
            language = pick { it.language },
            pageCount = results.firstOrNull { (it.pageCount ?: 0) > 0 }?.pageCount,
            categories = results.firstOrNull { !(it.categories.isNullOrEmpty()) }?.categories,
        )
    }
}
