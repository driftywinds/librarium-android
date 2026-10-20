// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.lookup

/**
 * Port of `enum ISBNCandidates` (`Services/ISBNCandidates.swift`).
 *
 * Expands a scanned barcode into the set of ISBN-like strings worth trying
 * against a metadata source. Three real-world cases justify the extra work, and
 * all three occur on the shelves this app is for:
 *
 * - **10-digit code** → also derive the ISBN-13. Every ISBN-10 has exactly one
 *   Bookland-978 ISBN-13, and providers are inconsistent about which one they
 *   index under.
 * - **13-digit code starting 978/979** → also derive the ISBN-10, for the same
 *   reason in the other direction. 979 codes have no ISBN-10 equivalent; the
 *   conversion is skipped rather than guessed.
 * - **12-digit UPC-A** → try as-is plus a heuristic Bookland form, because
 *   mass-market paperbacks issued before EAN-13 became standard carry a UPC-A
 *   whose middle nine digits are the ISBN-10 body.
 *
 * Returns the original string verbatim first, then any derived candidates.
 * Dedup is the caller's job and is cheap — the list is at most three long.
 *
 * This is pure logic with no I/O, which is why it is the one part of the scan
 * path that can be tested exhaustively rather than by hand.
 */
object IsbnCandidates {

    /**
     * Regex matching the digits the source keeps: numerals plus `X`/`x`.
     *
     * [expand] works on this filtered form, not on the raw scan, because ML Kit
     * and the keyboard both hand back values with hyphens and spaces: a hand-typed
     * `978-0-306-40615-7` must expand exactly like the scanned bare digits.
     */
    private val KEEP = Regex("[0-9Xx]")

    fun expand(raw: String): List<String> {
        val digits = KEEP.replace(raw, "")
        val out = mutableListOf(raw)

        when (digits.length) {
            10 -> isbn13FromIsbn10(digits.uppercase())?.let(out::add)

            13 -> when {
                digits.startsWith("978") || digits.startsWith("979") ->
                    isbn10FromIsbn13(digits)?.let(out::add)

                digits.startsWith("0") -> {
                    // EAN-13 representation of a UPC-A: a leading "0" plus the 12
                    // UPC digits. Strip the prefix and apply the heuristic to the
                    // underlying 12-digit code.
                    val upc = digits.substring(1)
                    upcAToIsbn13Heuristic(upc)?.let(out::add)
                }
            }

            12 -> upcAToIsbn13Heuristic(digits)?.let(out::add)
        }

        return out
    }

    /**
     * Heuristic conversion of a UPC-A barcode to a guessed ISBN-13.
     *
     * The body is `"978"` plus positions 1..9 of the UPC — that is, **after**
     * the leading number-system digit. The source comment (`ISBNCandidates.swift:60-64`)
     * records that this used to take `prefix(9)`, which includes the leading
     * digit and so shifted every derived ISBN by one place; the heuristic then
     * never matched anything and only cost an extra round-trip. The off-by-one is
     * preserved here in the correct form, not the broken one.
     */
    fun upcAToIsbn13Heuristic(upc: String): String? {
        if (upc.length != 12 || !upc.all { it.isDigit() }) return null
        val body = "978" + upc.substring(1, 10)
        return body + ean13CheckDigit(body)
    }

    /** ISBN-10 → ISBN-13. Bookland prefix `978` + first nine digits + EAN check. */
    fun isbn13FromIsbn10(isbn10: String): String? {
        val body9 = isbn10.take(9)
        if (body9.length != 9 || !body9.all { it.isDigit() }) return null
        val body = "978$body9"
        return body + ean13CheckDigit(body)
    }

    /**
     * ISBN-13 → ISBN-10. Only 978-prefixed codes have an ISBN-10 equivalent;
     * 979 codes are outside the ISBN-10 number space entirely.
     */
    fun isbn10FromIsbn13(isbn13: String): String? {
        if (isbn13.length != 13 || !isbn13.startsWith("978")) return null
        val body = isbn13.substring(3, 12)
        if (body.length != 9 || !body.all { it.isDigit() }) return null
        return body + isbn10CheckDigit(body)
    }

    /**
     * EAN-13 check digit for a 12-digit body: digits weighted ×1/×3 alternately
     * from index 0, then `(10 - sum % 10) % 10`.
     */
    fun ean13CheckDigit(body: String): String {
        if (body.length != 12 || !body.all { it.isDigit() }) return "0"
        var sum = 0
        body.forEachIndexed { i, c ->
            val d = c - '0'
            sum += if (i % 2 == 0) d else d * 3
        }
        return ((10 - sum % 10) % 10).toString()
    }

    /**
     * ISBN-10 check digit for a nine-digit body: digit *i* is weighted `10 - i`,
     * then `(11 - sum % 11) % 11`, with 10 rendered as `X`.
     */
    fun isbn10CheckDigit(body: String): String {
        if (body.length != 9 || !body.all { it.isDigit() }) return "0"
        var sum = 0
        body.forEachIndexed { i, c -> sum += (c - '0') * (10 - i) }
        val check = (11 - sum % 11) % 11
        return if (check == 10) "X" else check.toString()
    }
}
