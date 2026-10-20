// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * subtitle, media type, description, then the vocabularies (tags, genres) and
 * the credits.
 *
 * The form is seeded by whoever opens it — `BookEditViewModel.startEdit` from
 * the detail screen, `startCreate` from anywhere else. It deliberately does not
 * seed itself on composition: the shell-scoped view model is filled *before*
 * navigation, and re-seeding from an `editing` argument that the route does not
 * carry wiped every field the caller had just put in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookEditScreen(
    viewModel: BookEditViewModel,
    onSaved: (Book) -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Vocabularies are a read, not form state: loading them again must not
    // touch what the reader has typed.
    LaunchedEffect(Unit) { viewModel.loadVocabularies() }

    LaunchedEffect(state.saved) {
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
        // Chips wrap: the catalogue has 20 rows on the instance this ships
        // against, and a single `Row` squeezed the tail past the edge and broke
        // the last label into a vertical stack of letters.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.appCard, RoundedCornerShape(theme.radius.pill))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.mediaTypes.isEmpty()) {
                Text(
                    "Loading…",
                    color = colors.appText3,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(4.dp),
                )
            }
            state.mediaTypes.forEach { mt ->
                SelectableChip(
                    label = mt.displayName.ifEmpty { mt.name },
                    selected = state.mediaTypeId == mt.id,
                    onClick = { viewModel.setMediaTypeId(mt.id) },
                )
            }
        }

        FieldLabel("Description")
        Field(
            value = state.description,
            on = viewModel::setDescription,
            hint = "What it is about",
            minLines = 5,
        )

        FieldLabel("Authors")
        if (state.contributors.isEmpty()) {
            Text(
                "No author credited yet.",
                color = colors.appText3,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        state.contributors.forEach { person ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    person.name.ifBlank { "Unknown" },
                    color = colors.appText,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    person.role.ifBlank { "author" },
                    color = colors.appText3,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "✕",
                    color = colors.appText3,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { viewModel.removeContributor(person.contributorId) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        var authorDraft by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = authorDraft,
                onValueChange = { authorDraft = it },
                placeholder = {
                    Text("Add an author…", color = colors.appText3, fontSize = 14.sp)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent.copy(alpha = 0.5f),
                    unfocusedBorderColor = colors.appLine,
                    focusedTextColor = colors.appText,
                    unfocusedTextColor = colors.appText,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val canAdd = authorDraft.isNotBlank() && !state.contributorBusy
            Text(
                if (state.contributorBusy) "…" else "Add",
                color = if (canAdd) colors.accentStrong else colors.appText3,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = canAdd) {
                        viewModel.addContributor(authorDraft)
                        authorDraft = ""
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Text(
            "A name that is not on this server yet is added as a new person.",
            color = colors.appText3,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        FieldLabel("Tags")
        if (state.tags.isEmpty()) {
            Text(
                "No tags on this server yet.",
                color = colors.appText3,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tags.forEach { tag ->
                    SelectableChip(
                        label = tag.name,
                        selected = tag.id in state.selectedTagIds,
                        onClick = { viewModel.toggleTag(tag.id) },
                    )
                }
            }
        }

        FieldLabel("Genres")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.genres.forEach { genre ->
                SelectableChip(
                    label = genre.name,
                    selected = genre.id in state.selectedGenreIds,
                    onClick = { viewModel.toggleGenre(genre.id) },
                )
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error ?: "", color = colors.bad, fontSize = 13.sp)
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

/** One toggle chip: a media type, a tag or a genre. */
@Composable
private fun SelectableChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.25f) else colors.appCard)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) colors.accent else colors.appText2,
            fontSize = 12.sp,
        )
    }
}
