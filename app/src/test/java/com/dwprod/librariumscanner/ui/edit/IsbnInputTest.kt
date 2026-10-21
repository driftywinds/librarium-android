// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ISBN handling between the reader's typing and the server's columns.
 *
 * The failure this guards is silent and one-way: `FindByISBN` compares the bare
 * column against the bare value (`internal/repository/editions.go:229`), so a
 * code stored with its hyphens is never found by the barcode it came from, and
 * a code stored at the wrong length does nothing except spoil its edition.
 */
class IsbnInputTest {

    @Test
    fun separatorsAreStrippedSoAScanCanMatchWhatWasStored() {
        assertEquals("9780306406157", normaliseIsbn("978-0-306-40615-7"))
        assertEquals("9780306406157", normaliseIsbn("978 0 306 40615 7"))
        assertEquals("9780306406157", normaliseIsbn(" 9780306406157 "))
    }

    @Test
    fun theTenDigitCheckDigitStaysUpperCase() {
        assertEquals("0306406152", normaliseIsbn("0306406152"))
        assertEquals("080442957X", normaliseIsbn("0-8044-2957-x"))
        assertEquals("080442957X", normaliseIsbn("080442957x"))
    }

    @Test
    fun otherCharactersAreDroppedRatherThanStored() {
        // Copy-pasted labelling and non-ASCII hyphens are the realistic cases.
        assertEquals("9780306406157", normaliseIsbn("ISBN: 978-0-306-40615-7"))
        assertEquals("9780306406157", normaliseIsbn("978\u20130\u2013306\u201340615\u20137"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", normaliseIsbn(""))
        assertEquals("", normaliseIsbn("   "))
    }

    @Test
    fun aRightLengthCodeIsAccepted() {
        assertNull(isbnProblem("9780306406157", 13, "The ISBN-13"))
        assertNull(isbnProblem("080442957X", 10, "The ISBN-10"))
        // An empty box means "no ISBN", which is a legitimate choice and not a
        // problem to report.
        assertNull(isbnProblem("", 13, "The ISBN-13"))
    }

    @Test
    fun aWrongLengthCodeIsRefusedWithTheCounts() {
        val problem = isbnProblem("978030640615", 13, "The ISBN-13")
        assertTrue(problem!!.contains("13"))
        assertTrue(problem.contains("12"))
        assertTrue(isbnProblem("03064061", 10, "The ISBN-10") != null)
    }

    @Test
    fun theAuthorSearchRespectsTheServersMinimum() {
        // `internal/api/handlers/books.go:96` — one character is answered with
        // an empty array, so asking would only burn a request.
        assertTrue(AUTHOR_QUERY_MIN == 2)
    }
}
