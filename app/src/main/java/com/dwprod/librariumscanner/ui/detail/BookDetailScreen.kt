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
import com.dwprod.librariumscanner.R
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
    var showReview by remember { mutableStateOf(false) }

    // The edit sheet writes through its own routes (fields, cover, edition,
    // series) and the detail screen is not recomposed by that — it is still on
    // the back stack underneath. Re-reading when it comes back to the front is
    // what makes the change visible instead of leaving the old cover and the old
    // edition on screen.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.load()
    }

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
                IconCircleButton(R.drawable.ic_book_edit, "Edit book") {
                    if (book != null && state.libraryId.isNotEmpty()) {
                        onEdit(book, state.libraryId)
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconCircleButton(R.drawable.ic_book_delete, "Delete book") { confirmDelete = true }
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
                                model = book.coverImageUrl(),
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

                // ---- personal: favourite, rating, review ---------------------
                // iOS spreads these across a rate sheet, a review sheet and a
                // context menu; every one of them is the same partial write, so
                // a phone puts them together.
                SectionLabel("Personal")
                val favourite = state.myBook?.isFavorite == true
                val rating = state.myBook?.rating
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(theme.radius.chip))
                            .background(
                                if (favourite) colors.accent.copy(alpha = 0.22f) else colors.appCard
                            )
                            .activate { viewModel.toggleFavourite() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            if (favourite) "♥ Favourite" else "♡ Favourite",
                            color = if (favourite) colors.accent else colors.appText2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row {
                        (0 until 5).forEach { index ->
                            val threshold = (index + 1) * 2.0
                            val filled = (rating ?: 0.0) >= threshold - 1.0
                            Text(
                                "★",
                                color = if (filled) colors.accent else colors.appLineStrong,
                                fontSize = 20.sp,
                                modifier = Modifier
                                    .activate { viewModel.setRating(threshold) }
                                    .padding(horizontal = 2.dp),
                            )
                        }
                    }
                    if (rating != null) {
                        Text(
                            "${rating.toInt()}/10",
                            color = colors.appText3,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .activate { viewModel.setRating(null) }
                                .padding(4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if ((state.myBook?.review ?: "").isNotBlank() ||
                        (state.myBook?.notes ?: "").isNotBlank()
                    ) "Edit review & notes" else "Write a review",
                    color = colors.accentStrong,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .activate { showReview = true }
                        .padding(vertical = 6.dp),
                )

                Spacer(Modifier.height(20.dp))
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

    if (showReview) {
        ReviewDialog(
            initialReview = state.myBook?.review.orEmpty(),
            initialNotes = state.myBook?.notes.orEmpty(),
            onSave = { review, notes ->
                showReview = false
                viewModel.saveReview(review, notes)
            },
            onDismiss = { showReview = false },
        )
    }
}

/**
 * The public review and the private note, kept separate.
 *
 * iOS has a `ReviewSheet` for this; the separation is the point — one is
 * shown to other readers of the library, the other never leaves the account.
 */
@Composable
private fun ReviewDialog(
    initialReview: String,
    initialNotes: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalLibrariumTheme.current.colors
    var review by remember { mutableStateOf(initialReview) }
    var notes by remember { mutableStateOf(initialNotes) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review & notes", color = colors.appText) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = review,
                    onValueChange = { review = it },
                    label = { Text("Public review", color = colors.appText3, fontSize = 12.sp) },
                    minLines = 3,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.appLine,
                        focusedTextColor = colors.appText,
                        unfocusedTextColor = colors.appText,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Private note", color = colors.appText3, fontSize = 12.sp) },
                    minLines = 3,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.appLine,
                        focusedTextColor = colors.appText,
                        unfocusedTextColor = colors.appText,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Text(
                "Save",
                color = colors.accentStrong,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .activate { onSave(review, notes) }
                    .padding(8.dp),
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                color = colors.appText2,
                modifier = Modifier.activate { onDismiss() }.padding(8.dp),
            )
        },
        containerColor = colors.appCard,
    )
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

/**
 * A round button carrying one of the supplied vector icons.
 *
 * The tint is the same `appText2` the glyph buttons use, so the header keeps a
 * single weight whether a control is a symbol or an SVG.
 */
@Composable
private fun IconCircleButton(iconRes: Int, contentDescription: String, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.appCard)
            .activate(onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = contentDescription,
            tint = colors.appText2,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** Small click helper keeping the call sites readable; no semantics attached. */
private fun Modifier.activate(onClick: () -> Unit): Modifier = this.clickable { onClick() }
