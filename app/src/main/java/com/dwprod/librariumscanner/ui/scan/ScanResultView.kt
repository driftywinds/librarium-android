// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.dwprod.librariumscanner.core.lookup.IsbnLookupService
import com.dwprod.librariumscanner.core.model.IsbnLookupResult
import com.dwprod.librariumscanner.core.model.Library
import com.dwprod.librariumscanner.core.model.MediaType
import com.dwprod.librariumscanner.core.scan.OwnedMatch
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * Result sheet for one scanned barcode.
 *
 * Ports `RedesignedScanResultView` (`RedesignedScanFlow.swift:677`) in the same
 * section order the source defines: cover hero → ownership card → library
 * picker → media/status row → collapsed "more options" → CTA.
 *
 * The media-type/tag/format pickers are inline here rather than in half-sheets:
 * an Android bottom sheet has the same dismissal cost as the enclosing screen,
 * so a compressed inline row is the parity-preserving move, not a simplification
 * of what the user can reach.
 */
@Composable
fun ScanResultView(
    phase: ScanPhase.Result,
    libraries: List<Library>,
    selectedLibraryKey: String?,
    ownership: Map<String, OwnedMatch?>,
    mediaTypes: List<MediaType>,
    adding: Boolean,
    addError: String?,
    onAdd: (readStatus: String?, mediaTypeId: String?) -> Unit,
    onScanAnother: () -> Unit,
    onClose: () -> Unit,
    onManualEntry: () -> Unit,
    onOpenBook: (Library, String) -> Unit,
    onSelectLibrary: (String) -> Unit,
) {
    var selectedStatus by remember { mutableStateOf(ReadStatus.UNREAD) }
    // Null means "no explicit pick yet", so the suggestion can still arrive
    // after the catalog loads without clobbering a chip the user has tapped.
    var pickedMediaTypeId by remember(phase.isbn) { mutableStateOf<String?>(null) }
    val suggestedId = remember(phase.isbn, mediaTypes, phase.found) {
        suggestedMediaTypeId(phase.found, mediaTypes)
    }
    val selectedMediaTypeId = pickedMediaTypeId ?: suggestedId

    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val found = phase.found

    // A misdetected UPC reads as a 12-digit number, so the error view says so
    // rather than "no match" for a barcode that never was an ISBN.
    val looksLikeUpc = phase.isbn.length == 12 && phase.isbn.all(Char::isDigit)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- Top bar -----------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CircleButton("‹") { onScanAnother() }
            CircleButton("✕") { onClose() }
        }

        when {
            found != null -> {
                CoverHero(found)
                OwnershipCard(libraries, ownership, onOpenBook)
                // Selection is a real callback: the default is the first library,
                // but with several servers the reader has to be able to move the
                // target, and this row is the only affordance that does it.
                LibrarySection(libraries, selectedLibraryKey, ownership, onSelectLibrary)
                MediaAndStatusRow(
                    mediaTypes = mediaTypes,
                    selectedStatus = selectedStatus,
                    selectedMediaTypeId = selectedMediaTypeId,
                    onStatusChange = { selectedStatus = it },
                    onMediaTypeChange = { pickedMediaTypeId = it },
                )
                if (addError != null) {
                    AddErrorBanner(addError)
                }
                AddCta(
                    label = ctaLabel(libraries, selectedLibraryKey, ownership),
                    enabled = selectedLibraryKey != null && !adding,
                    adding = adding,
                    onAdd = { onAdd(selectedStatus.apiValue, selectedMediaTypeId) },
                )
                Spacer(Modifier.height(30.dp))
            }
            else -> ErrorView(
                isbn = phase.isbn,
                message = describeOutcome(phase.outcome, phase.isbn),
                looksLikeUpc = looksLikeUpc,
                onScanAnother = onScanAnother,
                onManualEntry = onManualEntry,
            )
        }
    }
}

/**
 * The media type the sheet pre-selects for the book currently on screen.
 *
 * Delegates to the shared rule in `core/scan/MediaTypeGuess.kt`, which the add
 * path also uses, so the highlighted chip and the value actually sent cannot
 * disagree.
 */
internal fun suggestedMediaTypeId(
    found: IsbnLookupResult?,
    mediaTypes: List<MediaType>,
): String? = com.dwprod.librariumscanner.core.scan.guessMediaTypeId(
    categories = found?.categories,
    types = mediaTypes,
)

/** Port of `ctaLabel` (`RedesignedScanFlow.swift:1755-1762`), word for word. */
internal fun ctaLabel(
    libraries: List<Library>,
    selectedLibraryKey: String?,
    ownership: Map<String, OwnedMatch?>,
): String {
    val library = libraries.firstOrNull { it.clientKey == selectedLibraryKey }
        ?: return "Pick a library"
    return when (ownership[library.clientKey]) {
        null -> "Add to ${library.name}"
        else -> "Add another copy to ${library.name}"
    }
}

/**
 * Port of the lookup-failure wording (`RedesignedScanFlow.swift:1781-1795`).
 * The three failure shapes get different words because they have different
 * user actions.
 */
internal fun describeOutcome(
    outcome: IsbnLookupService.LookupOutcome,
    isbn: String,
): String = when (outcome) {
    is IsbnLookupService.LookupOutcome.Success -> ""
    IsbnLookupService.LookupOutcome.NoSourceHasIt ->
        "None of the metadata sources have $isbn. " +
            "The book is probably fine; the sources just don't list it. " +
            "Add it by hand, or ask an admin to enable another source."
    is IsbnLookupService.LookupOutcome.Failed -> outcome.message
}

/** The 4 statuses the iOS scan flow offers, with their exact API values. */enum class ReadStatus(val apiValue: String, val label: String) {
    UNREAD("unread", "Unread"),
    WANT_TO_READ("want_to_read", "Want"),
    READING("reading", "Reading"),
    READ("read", "Read"),
}

@Composable
private fun CircleButton(symbol: String, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.06f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.appLine),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, color = colors.appText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CoverHero(lookup: IsbnLookupResult) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
    ) {
        // Cover art arrives best-effort; the placeholder keeps the sheet from
        // reflowing when a provider has none, which is common for obscure
        // editions and always the case for a manual entry.
        Box(
            modifier = Modifier
                .size(width = 140.dp, height = 210.dp)
                .background(colors.appCard, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (lookup.coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(lookup.coverUrl)
                        .crossfade(true)
                        .build(),
                    // The book's title, not "cover": a screen reader landing
                    // here should hear what the picture is of.
                    contentDescription = lookup.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "No cover",
                    color = colors.appText3,
                    fontSize = theme.type.rowMeta,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(999.dp),
            color = colors.good.copy(alpha = 0.15f),
        ) {
            Text(
                "Match found · ${lookup.providerDisplay}",
                color = colors.good,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        Text(
            lookup.title,
            color = colors.appText,
            fontSize = theme.type.heroTitle,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        // Port of `authorMeta`: authors, then the year plucked from the front
        // of the publish date if it is four digits.
        val parts = buildList {
            addAll(lookup.authors)
            val year = lookup.publishDate.take(4)
            if (year.isNotEmpty() && year.all(Char::isDigit)) add(year)
        }
        Text(
            parts.joinToString(" · "),
            color = colors.appText2,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        // The provenance line: the ISBN the row will be filed under.
        val shown = lookup.isbn13.ifEmpty { lookup.isbn10 }
        Text(
            "ISBN $shown",
            color = colors.appText3,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OwnershipCard(
    libraries: List<Library>,
    ownership: Map<String, OwnedMatch?>,
    onOpenBook: (Library, String) -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val held = libraries.filter { ownership[it.clientKey] != null }
    if (held.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 14.dp)
            .background(colors.good.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(0.5.dp, colors.good.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
    ) {
        Text(
            if (held.size == 1) "You have this" else "You have this in ${held.size} libraries",
            color = colors.appText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 8.dp),
        )
        held.forEachIndexed { idx, library ->
            val match = ownership[library.clientKey] ?: return@forEachIndexed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenBook(library, match.bookId) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    library.name,
                    color = colors.appText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (match.copyCount > 1) {
                    Text(
                        "${match.copyCount} copies",
                        color = colors.appText3,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    "Open ›",
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (idx != held.size - 1) {
                Box(
                    Modifier
                        .padding(start = 14.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.appLine)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun LibrarySection(
    libraries: List<Library>,
    selectedLibraryKey: String?,
    ownership: Map<String, OwnedMatch?>,
    onSelectLibrary: (String) -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Text(
            "ADD TO",
            color = colors.appText3,
            fontSize = theme.type.sectionLabel,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .background(colors.appCard, RoundedCornerShape(14.dp))
                .border(0.5.dp, colors.appLine, RoundedCornerShape(14.dp)),
        ) {
            libraries.forEachIndexed { idx, library ->
                val isSel = library.clientKey == selectedLibraryKey
                val match = ownership[library.clientKey]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLibrary(library.clientKey) }
                        .padding(14.dp),
                ) {
                    // Initial tile: the simple visual anchor the iOS row uses.
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (isSel) colors.accent else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            library.name.take(1).uppercase(),
                            color = if (isSel) Color.White else colors.appText2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            library.name,
                            color = if (isSel) colors.accentStrong else colors.appText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!library.serverName.isEmpty()) {
                            Text(
                                library.serverName,
                                color = colors.appText3,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    when {
                        match != null -> OwnedBadge(match.copyCount)
                        // Still asking: spinner per row, as on iOS.
                        !ownership.containsKey(library.clientKey) ->
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = colors.appText3,
                            )
                        isSel -> Text("✓", color = colors.accentStrong, fontSize = 12.sp)
                    }
                }
                if (idx != libraries.size - 1) {
                    Box(
                        Modifier
                            .padding(start = 54.dp)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(colors.appLine)
                    )
                }
            }
        }
    }
}

@Composable
private fun OwnedBadge(copies: Int) {
    val colors = LocalLibrariumTheme.current.colors
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(999.dp),
        color = colors.good.copy(alpha = 0.15f),
    ) {
        Text(
            "$copies ${if (copies == 1) "Copy" else "Copies"}",
            color = colors.good,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MediaAndStatusRow(
    mediaTypes: List<MediaType>,
    selectedStatus: ReadStatus,
    selectedMediaTypeId: String?,
    onStatusChange: (ReadStatus) -> Unit,
    onMediaTypeChange: (String) -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 14.dp)) {
        Text(
            "MEDIA TYPE",
            color = colors.appText3,
            fontSize = theme.type.sectionLabel,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (mediaTypes.isEmpty()) {
            Text("Loading…", color = colors.appText3, fontSize = 14.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(mediaTypes, key = { it.id }) { type ->
                    val active = selectedMediaTypeId == type.id
                    androidx.compose.material3.Surface(
                        onClick = { onMediaTypeChange(type.id) },
                        shape = RoundedCornerShape(999.dp),
                        color = if (active) colors.accentSoft else Color.White.copy(alpha = 0.06f),
                        border = if (active) null else androidx.compose.foundation.BorderStroke(0.5.dp, colors.appLine),
                    ) {
                        Text(
                            type.displayName,
                            color = if (active) colors.accentStrong else colors.appText2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "STATUS",
            color = colors.appText3,
            fontSize = theme.type.sectionLabel,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.appCard, RoundedCornerShape(10.dp))
                .border(0.5.dp, colors.appLine, RoundedCornerShape(10.dp))
                .padding(2.dp),
        ) {
            ReadStatus.entries.forEach { status ->
                val active = selectedStatus == status
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onStatusChange(status) }
                        .background(
                            if (active) colors.accentSoft else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        status.label,
                        color = if (active) colors.accentStrong else colors.appText3,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Why the last tap on the CTA did not add anything.
 *
 * Sits directly above the CTA so the reason and the retry affordance are one
 * glance apart. Before this existed every refusal — a 400, a 403 on a library
 * the user cannot write to, a dead server — rendered as a button that appeared
 * to do nothing.
 */
@Composable
private fun AddErrorBanner(message: String) {
    val colors = LocalLibrariumTheme.current.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 6.dp)
            .background(colors.bad.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(0.5.dp, colors.bad.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("!", color = colors.bad, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            color = colors.appText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun AddCta(label: String, enabled: Boolean, adding: Boolean, onAdd: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    androidx.compose.material3.Button(
        onClick = onAdd,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = Color.White,
            disabledContainerColor = colors.accent.copy(alpha = 0.4f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp)
            .height(54.dp),
    ) {
        if (adding) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            if (adding) "Adding…" else label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorView(
    isbn: String,
    message: String,
    looksLikeUpc: Boolean,
    onScanAnother: () -> Unit,
    onManualEntry: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(22.dp),
    ) {
        Text("⚠", color = colors.warn, fontSize = 30.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (looksLikeUpc) "Not an ISBN" else "No match",
            color = colors.appText,
            fontSize = theme.type.cardTitle,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (looksLikeUpc) {
                "This looks like a UPC barcode, not an ISBN. Find the ISBN inside the front cover, or search by title instead."
            } else message,
            color = colors.appText3,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Surface(
                onClick = onScanAnother,
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = 0.06f),
            ) {
                Text(
                    "Scan another",
                    color = colors.appText2,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            androidx.compose.material3.Surface(
                onClick = onManualEntry,
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.accent),
            ) {
                Text(
                    "Enter manually",
                    color = colors.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

