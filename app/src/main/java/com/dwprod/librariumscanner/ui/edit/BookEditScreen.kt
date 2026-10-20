// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.edit

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * The add/edit form, port of `AddEditBookSheet`'s field order: title,
 * subtitle, media type, description, then the vocabularies (tags, genres).
 * Edition fields are create-time-only on iOS and are owned by the scan flow
 * here, so they do not appear (`AddEditBookSheet.swift:150-205`).
 */
@Composable
fun BookEditScreen(
    viewModel: BookEditViewModel,
    editing: Book?,
    libraryId: String,
    onSaved: (Book) -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(editing, libraryId) {
        viewModel.loadVocabularies()
        if (editing != null) viewModel.startEdit(editing, libraryId)
        else viewModel.startCreate(libraryId)
    }

    androidx.compose.runtime.LaunchedEffect(state.saved) {
        state.saved?.let { onSaved(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                color = colors.appText2,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onBack() }.padding(6.dp),
            )
            Text(
                if (state.editingBookId != null) "Edit book" else "Add book",
                color = colors.appText,
                fontSize = theme.type.heroTitle,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (state.saving) "…" else "Save",
                color = if (state.saving) colors.appText3 else colors.accentStrong,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(enabled = !state.saving) { viewModel.save() }
                    .padding(6.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        FieldLabel("Title")
        Field(value = state.title, on = viewModel::setTitle, hint = "The title on the cover")

        FieldLabel("Subtitle")
        Field(value = state.subtitle, on = viewModel::setSubtitle, hint = "Optional")

        FieldLabel("Media type")
        // Inline chips rather than a picker sheet, per the scan screen's
        // documented parity decision — same control, same vocabulary.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .background(colors.appCard, RoundedCornerShape(theme.radius.pill))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val shown = state.mediaTypes.take(6)
            shown.forEach { mt ->
                val selected = state.mediaTypeId == mt.id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(theme.radius.chip))
                        .background(if (selected) colors.accent.copy(alpha = 0.25f) else colors.appBackground)
                        .clickable { viewModel.setMediaTypeId(mt.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        mt.displayName.ifEmpty { mt.name },
                        color = if (selected) colors.accent else colors.appText2,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        FieldLabel("Description")
        Field(
            value = state.description,
            on = viewModel::setDescription,
            hint = "What it is about",
            minLines = 5,
        )

        FieldLabel("Tags")
        ChipFlow(
            entries = state.tags.map { it.id to (it.name) },
            selected = state.selectedTagIds,
            onToggle = viewModel::toggleTag,
        )

        FieldLabel("Genres")
        ChipFlow(
            entries = state.genres.map { it.id to it.name },
            selected = state.selectedGenreIds,
            onToggle = viewModel::toggleGenre,
        )

        FieldLabel("Authors")
        state.contributors.forEach { c ->
            Text(
                "${c.name} · ${c.role}",
                color = colors.appText2,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Text(
            "Authors are edited on the wire via search; the scan flow fills them in.",
            color = colors.appText3,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (state.error != null) {
            Text(state.error ?: "", color = colors.bad, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    val theme = LocalLibrariumTheme.current
    Text(
        text.uppercase(),
        color = theme.colors.appText3,
        fontSize = theme.type.sectionLabel,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun Field(
    value: String,
    on: (String) -> Unit,
    hint: String,
    minLines: Int = 1,
) {
    val colors = LocalLibrariumTheme.current.colors
    OutlinedTextField(
        value = value,
        onValueChange = on,
        placeholder = { Text(hint, color = colors.appText3, fontSize = 14.sp) },
        singleLine = minLines == 1,
        minLines = minLines,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent.copy(alpha = 0.5f),
            unfocusedBorderColor = colors.appLine,
            focusedTextColor = colors.appText,
            unfocusedTextColor = colors.appText,
            cursorColor = colors.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Horizontal wrap of toggle chips, one per vocabulary value. */
@Composable
private fun ChipFlow(
    entries: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val colors = LocalLibrariumTheme.current.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.chunked(4).forEach { rowEntries ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowEntries.forEach { (id, label) ->
                    val ticked = id in selected
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (ticked) colors.accent.copy(alpha = 0.25f) else colors.appCard)
                            .clickable { onToggle(id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            label,
                            color = if (ticked) colors.accent else colors.appText2,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
