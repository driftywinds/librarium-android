// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the media-type pre-selection ported from `guessMediaTypeID`
 * (`RedesignedScanFlow.swift:950-977`).
 *
 * The catalog used here is the real one a stock Librarium server returns, in the
 * order it returns it: alphabetical. That ordering is the whole point — a bare
 * `firstOrNull()` fallback silently files every scanned book as "Anthology",
 * which is wrong in a way no test with a hand-picked two-item list would catch.
 */
class MediaTypeGuessTest {

    private data class T(val id: String, val displayName: String)

    /** Mirrors `ScanViewModel.guessMediaTypeId`, kept here so the rule is testable. */
    private fun guess(categories: List<String>?, types: List<T>): String? {
        if (types.isEmpty()) return null
        val cats = categories.orEmpty().map { it.lowercase() }.filter { it.isNotBlank() }
        for (t in types) {
            val name = t.displayName.lowercase()
            if (name.isEmpty()) continue
            if (cats.any { it.contains(name) || name.contains(it) }) return t.id
        }
        types.firstOrNull { it.displayName.lowercase() == "book" }?.let { return it.id }
        types.firstOrNull { it.displayName.lowercase() == "novel" }?.let { return it.id }
        types.firstOrNull { it.displayName.lowercase().contains("novel") }?.let { return it.id }
        return types.first().id
    }

    /** The stock remote-api catalog, alphabetical as served. */
    private val catalogue = listOf(
        T("a", "Anthology"), T("b", "Appliance Manual"), T("c", "Art Book"),
        T("d", "Comic Book"), T("e", "Datasheet"), T("f", "Graphic Novel"),
        T("g", "Light Novel"), T("h", "Magazine"), T("i", "Manga"),
        T("j", "Manhua"), T("k", "Manhwa"), T("l", "Non-Fiction"),
        T("m", "Novel"), T("n", "RPG Sourcebook"), T("o", "Short Story Collection"),
        T("p", "Specification"), T("q", "Technical Manual"), T("r", "Textbook"),
        T("s", "User Guide"), T("t", "White Paper"),
    )

    @Test
    fun unclassifiedScanPrefersPlainNovelOverAlphabeticalFirst() {
        // The regression: must not be "Anthology" just because it sorts first.
        assertEquals("m", guess(null, catalogue))
    }

    @Test
    fun categoriesDriveThePick() {
        assertEquals("i", guess(listOf("Manga"), catalogue))
        // "comics & graphic novels".contains("graphic novel") is true, and
        // "Graphic Novel" sorts before "Comic Book" in the catalog, so that is
        // the match — the substring rule means the longest-fitting name wins by
        // catalog order, not the most specific one. Asserting the real behaviour
        // rather than the desirable one keeps this test honest.
        assertEquals("f", guess(listOf("Comics & Graphic Novels"), catalogue))
    }

    @Test
    fun partialNovelMatchesAreTriedLast() {
        // With no plain "Novel", "Graphic Novel" should win over "Light Novel"
        // only by catalog order, and never beat an exact match when one exists.
        val noPlainNovel = catalogue.filter { it.displayName != "Novel" }
        assertEquals("f", guess(null, noPlainNovel))
        assertEquals("t", guess(null, listOf(T("t", "White Paper"))))
    }

    @Test
    fun bookExactMatchBeatsNovel() {
        val withBook = catalogue + T("u", "Book")
        assertEquals("u", guess(null, withBook))
    }

    @Test
    fun emptyCatalogueYieldsNull() {
        assertNull(guess(listOf("Manga"), emptyList()))
    }

    @Test
    fun blankCategoryDoesNotMatchEverything() {
        // An empty category string contains every name, so the blank must be
        // skipped by the `name.isEmpty()` guard rather than matching "White Paper".
        assertEquals("m", guess(listOf(""), catalogue))
    }
}
