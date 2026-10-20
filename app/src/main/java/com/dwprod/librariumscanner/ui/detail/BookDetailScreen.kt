// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * The book detail, port of `RedesignedBookDetailView` trimmed to the build's
 * scope: hero (cover + title + author + status), the four quick statuses, the
 * description, series pointers, and edit/delete. Loans, copies, lists, other
 * readers, own review and reading history are the sections that were cut
 * (`RedesignedBookDetailView.swift:421-535` renders them); each lives behind
 * a `// cut:` note where it would have gone.
 */
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onEdit: (Book, String) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val book = state.book
    var confirmDelete by remember { mutableStateOf(false) }

    if (state.deleted) {
        onDeleted()
        return
    }

    Column(Modifier.fillMaxSize().background(colors.appBackground)) {
        // ---- header -------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CircleButton("‹") { onBack() }
            Row {
                CircleButton("✎") {
                    if (book != null && state.libraryId.isNotEmpty()) {
                        onEdit(book, state.libraryId)
                    }
                }
                Spacer(Modifier.width(8.dp))
                CircleButton("🗑") { confirmDelete = true }
            }
        }

        when {
            state.loading && book == null -> CenterNote("Loading…")
            book == null -> CenterNote(state.error ?: "Not found.")
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp),
            ) {
                Spacer(Modifier.height(6.dp))

                // ---- hero ------------------------------------------------
                Row {
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(theme.radius.pill))
                            .background(colors.appCard),
                    ) {
                        if (!book.coverUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = book.coverUrl,
                                contentDescription = book.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    book.title.take(1),
                                    color = colors.appText3,
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Light,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            book.title,
                            color = colors.appText,
                            fontSize = theme.type.heroTitle,
                            fontWeight = FontWeight.Bold,
                        )
                        book.subtitle.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = colors.appText2, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        book.primaryAuthor?.let {
                            Text(it, color = colors.appText2, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (book.mediaType.isNotBlank()) {
                                StatusPill(book.mediaType, colors.accent.copy(alpha = 0.15f), colors.accent)
                            }
                            state.myBook?.readStatus?.takeIf { it != "unread" }?.let { st ->
                                StatusPill(
                                    statusLabel(st),
                                    colors.good.copy(alpha = 0.14f),
                                    colors.good,
                                )
                            }
                            if ((book.activeLoanCount ?: 0) > 0) {
                                StatusPill("Lent out", colors.lent.copy(alpha = 0.16f), colors.lent)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ---- quick statuses — the 4 read-states -----------------------
                SectionLabel("Reading state")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "unread" to "Unread",
                        "want_to_read" to "Want",
                        "reading" to "Reading",
                        "read" to "Read",
                    ).forEach { (apiValue, label) ->
                        val selected = state.myBook?.readStatus == apiValue
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(theme.radius.chip))
                                .background(
                                    if (selected) colors.accent.copy(alpha = 0.22f) else colors.appCard
                                )
                                .activate { viewModel.setReadStatus(apiValue) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                label,
                                color = if (selected) colors.accent else colors.appText2,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ---- description --------------------------------------------
                if (book.description.isNotBlank()) {
                    SectionLabel("Description")
                    Text(
                        book.description,
                        color = colors.appText2,
                        fontSize = theme.type.bodyPara,
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // ---- series -------------------------------------------------
                // cut: edition section (BookEditionsPanel) — restored with copies
                if (state.series.isNotEmpty()) {
                    SectionLabel("Series")
                    state.series.forEach { ref ->
                        Text(
                            "${ref.seriesName}" + if (ref.position > 0) " · #${positionText(ref.position)}" else "",
                            color = colors.accent,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }

                // cut: copies (BookCopiesPanel), lists (BookListsPanel),
                // loans (BookLoansPanel), other readers (OtherReadersPanel),
                // own review (OwnReviewPanel), reading history — per scope

                if (state.error != null) {
                    Text(state.error ?: "", color = colors.bad, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (confirmDelete) {
        // A book deletion is the one destructive call this screen makes; iOS
        // guards it the same way (`RedesignedBookDetailView.swift:394-411`).
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete book?", color = colors.appText) },
            text = { Text(
                "This removes “${book?.title ?: "the book"}” and its copies. This cannot be undone.",
                color = colors.appText2,
            ) },
            confirmButton = {
                Text(
                    "Delete",
                    color = colors.bad,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .activate { confirmDelete = false; viewModel.delete() }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    color = colors.appText2,
                    modifier = Modifier
                        .activate { confirmDelete = false }
                        .padding(8.dp),
                )
            },
            containerColor = colors.appCard,
        )
    }
}

private fun positionText(p: Double): String =
    if (p == p.toLong().toDouble()) p.toLong().toString() else p.toString()

internal fun statusLabel(apiValue: String): String = when (apiValue) {
    "read" -> "Read"
    "reading" -> "Reading"
    "want_to_read" -> "Want to read"
    "unread" -> "Unread"
    else -> apiValue
}

@Composable
private fun SectionLabel(text: String) {
    val theme = LocalLibrariumTheme.current
    Text(
        text.uppercase(),
        color = theme.colors.appText3,
        fontSize = theme.type.sectionLabel,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun StatusPill(text: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = content, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CenterNote(text: String) {
    val colors = LocalLibrariumTheme.current.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = colors.appText3, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun CircleButton(glyph: String, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.appCard)
            .activate(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = colors.appText2, fontSize = 18.sp)
    }
}

/** Small click helper keeping the call sites readable; no semantics attached. */
private fun Modifier.activate(onClick: () -> Unit): Modifier = this.clickable { onClick() }
