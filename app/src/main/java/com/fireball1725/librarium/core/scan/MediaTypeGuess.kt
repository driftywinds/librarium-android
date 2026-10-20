// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.scan

import com.fireball1725.librarium.core.model.MediaType

/**
 * Pre-select a media type the way the scan sheet does
 * (`RedesignedScanFlow.swift:950-977`).
 *
 * Both halves matter. The category pass lets provider genres drive the pick — a
 * scan whose providers say "Manga" lands on Manga rather than Novel. The name
 * fallback then prefers a plain "Book" or "Novel" **by exact match**, with any
 * partial "…novel" match last so "Graphic Novel" and "Light Novel" do not become
 * the default for an unclassified scan.
 *
 * Falling back to the catalog's first row is only correct because it is a last
 * resort: servers return their catalog alphabetically, so a bare `firstOrNull()`
 * files every scanned book as "Anthology".
 *
 * Lives here rather than in the view model because both the add path and the
 * result sheet need the same answer; two copies would drift.
 */
fun guessMediaTypeId(
    categories: List<String>?,
    types: List<MediaType>,
): String? {
    if (types.isEmpty()) return null

    // Blank categories must be dropped, not carried through: every string
    // contains "", so a provider answering `[""]` would otherwise match the very
    // first media type and file the book as "Anthology".
    val cats = categories.orEmpty()
        .map { it.lowercase() }
        .filter { it.isNotBlank() }

    for (type in types) {
        val name = type.displayName.lowercase()
        if (name.isEmpty()) continue
        if (cats.any { it.contains(name) || name.contains(it) }) return type.id
    }

    types.firstOrNull { it.displayName.lowercase() == "book" }?.let { return it.id }
    types.firstOrNull { it.displayName.lowercase() == "novel" }?.let { return it.id }
    types.firstOrNull { it.displayName.lowercase().contains("novel") }?.let { return it.id }
    return types.first().id
}
