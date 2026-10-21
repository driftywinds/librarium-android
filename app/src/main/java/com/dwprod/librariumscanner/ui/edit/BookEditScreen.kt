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

    var showCoverCapture by remember { mutableStateOf(false) }
    // Resolved once per composition; the launcher inside holds the
    // ActivityResult registration, so it must not be created on every tap.
    val pickPhoto = rememberCoverPhotoPicker(
        onPicked = viewModel::uploadCover,
        onFailure = viewModel::setError,
    )

    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(colors.appBackground),
    ) {
    Column(
        Modifier
            .fillMaxSize()
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

        // Cover first: it is what the reader is holding, and on the create path
        // it is also the only field whose result is visible before a save, so
        // the preview belongs where they can see it fill in.
        FieldLabel("Cover")
        // The create path has no server cover to talk about yet, so the section
        // leads with the picked image when there is one; the edit path keeps the
        // status line because its cover genuinely lives on the server.
        val pending = state.pendingCover
        if (pending != null) {
            CoverPreview(
                jpeg = pending,
                modifier = Modifier
                    .height(180.dp)
                    .width(120.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            when {
                state.coverBusy -> "Working…"
                pending != null -> "This cover is set and is uploaded when you save."
                state.hasCover -> "A cover is stored on the server."
                else -> "No cover yet."
            },
            color = colors.appText2,
            fontSize = 13.sp,
        )
        // Actions wrap rather than sitting in one `Row`: there are up to four of
        // them and, with the status line beside them, a narrow phone clipped the
        // tail. They read as one group under the status instead.
        FlowRow(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!state.coverBusy) {
                ActionText("Photograph") { showCoverCapture = true }
                ActionText("Photo library") { pickPhoto() }
                if (pending != null || state.hasCover) {
                    // Before the first save there is nothing on the server to
                    // delete, so this only drops the pick — see `clearCover`.
                    ActionText("Remove") { viewModel.clearCover() }
                }
                // Only meaningful once a book exists: it asks the server to go
                // and fetch metadata and artwork for an id.
                if (state.editingBookId != null) {
                    ActionText("Refresh") { viewModel.refreshMetadata() }
                }
            }
        }

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

        FieldLabel("Series")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SelectableChip(
                label = "None",
                selected = state.selectedSeriesId == null,
                onClick = { viewModel.setSelectedSeries(null) },
            )
            state.seriesOptions.forEach { series ->
                SelectableChip(
                    label = series.name.ifBlank { "Series" },
                    selected = state.selectedSeriesId == series.id,
                    onClick = { viewModel.setSelectedSeries(series.id) },
                )
            }
        }
        if (state.seriesOptions.isEmpty()) {
            Text(
                "No series in this library yet.",
                color = colors.appText3,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.selectedSeriesId != null) {
            Spacer(Modifier.height(8.dp))
            Field(
                value = state.seriesPosition,
                on = viewModel::setSeriesPosition,
                hint = "Volume number in the series (e.g. 3)",
            )
        }

        if (state.edition != null) {
            FieldLabel("Edition")
            Field(value = state.isbn13, on = viewModel::setIsbn13, hint = "ISBN-13")
            Spacer(Modifier.height(8.dp))
            Field(value = state.isbn10, on = viewModel::setIsbn10, hint = "ISBN-10")
            Spacer(Modifier.height(8.dp))
            Field(value = state.publisher, on = viewModel::setPublisher, hint = "Publisher")
            Spacer(Modifier.height(8.dp))
            Field(value = state.language, on = viewModel::setLanguage, hint = "Language (e.g. en)")
            Spacer(Modifier.height(8.dp))
            Field(
                value = state.publishDate,
                on = viewModel::setPublishDate,
                hint = "Publish date (YYYY-MM-DD)",
            )
            Spacer(Modifier.height(8.dp))
            Field(value = state.pageCount, on = viewModel::setPageCount, hint = "Pages")
        }

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
        // Authors differ from tags and lists in one way worth keeping local: the
        // draft lives in this composable because `addContributor` takes the name
        // as an argument instead of reading a form field, so it can be cleared
        // here on submit.
        var authorDraft by remember { mutableStateOf("") }
        InlineAdder(
            value = authorDraft,
            on = { authorDraft = it },
            hint = "Add an author…",
            busy = state.contributorBusy,
            label = "Add",
            onSubmit = {
                viewModel.addContributor(authorDraft)
                authorDraft = ""
            },
        )
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
        // Picking an existing tag is the common case; making one is the reason
        // it goes here rather than on a settings screen. Tags are per-library on
        // the server (`/libraries/{id}/tags`), so a new tag belongs to the
        // library this book is being added to.
        Spacer(Modifier.height(8.dp))
        InlineAdder(
            value = state.newTagName,
            on = viewModel::setNewTagName,
            hint = "New tag…",
            busy = state.tagBusy,
            enabled = state.libraryId.isNotEmpty(),
            onSubmit = viewModel::createTag,
        )

        FieldLabel("Genres")
        if (state.genres.isEmpty()) {
            // Genres are an instance-wide vocabulary and creating one is
            // admin-only (`internal/api/router.go:617-621`: POST/PUT/DELETE are
            // behind `requireAdmin`), so this is a choice and nothing more.
            Text(
                "No genres on this server yet.",
                color = colors.appText3,
                fontSize = 12.sp,
            )
        }
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

        // Lists are offered on the create path only: a book lands in the
        // collection here, while filing an existing book into a list is the
        // collection's multi-select, which already knows the book and can move
        // several at once.
        if (state.editingBookId == null) {
            FieldLabel("Lists")
            if (state.lists.isEmpty()) {
                Text(
                    "No lists yet.",
                    color = colors.appText3,
                    fontSize = 12.sp,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.lists.forEach { list ->
                        SelectableChip(
                            label = list.name.ifBlank { "List" },
                            selected = list.id in state.selectedListIds,
                            onClick = { viewModel.toggleList(list.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            InlineAdder(
                value = state.newListName,
                on = viewModel::setNewListName,
                hint = "New list…",
                busy = state.listBusy,
                onSubmit = viewModel::createList,
            )
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error ?: "", color = colors.bad, fontSize = 13.sp)
        }

        EditionNote(serverUrl = viewModel.serverUrl)

        Spacer(Modifier.height(40.dp))
    }
        if (showCoverCapture) {
            CoverCaptureOverlay(
                onCaptured = { jpeg ->
                    showCoverCapture = false
                    viewModel.uploadCover(jpeg)
                },
                onCancel = { showCoverCapture = false },
            )
        }
    }
}

/**
 * The note under the form: this sheet adds a book, it does not describe a
 * printing.
 *
 * The server models the two separately — a book has `books` rows and one or
 * more `book_editions`, and the edition is what carries ISBN, publisher,
 * language, page count and format (`internal/service/book.go:CreateBook` writes
 * both in one transaction). The web client edits the edition; this form
 * deliberately does not, so the reader is told where to go rather than left to
 * discover that half the fields they expect are missing.
 */
@Composable
private fun EditionNote(serverUrl: String) {
    val colors = LocalLibrariumTheme.current.colors
    if (serverUrl.isBlank()) return
    Text(
        "Edition details — ISBN, publisher, language, page count and format — " +
            "can be filled in or corrected in the web client at $serverUrl.",
        color = colors.appText3,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 20.dp),
    )
}

/** A plain-text button, the form's action affordance. */
@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Text(
        label,
        color = colors.accentStrong,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * "Type a name, make the thing" — the one-line creator shared by tags and
 * lists.
 *
 * Both are a name and nothing else, and both are worth being able to create
 * without leaving the book behind, so the control is a field plus a commit
 * button over the view model's own draft string rather than local state: the
 * in-flight request clears that draft on success, and a `remember`ed value
 * could not be cleared from there.
 */
@Composable
private fun InlineAdder(
    value: String,
    on: (String) -> Unit,
    hint: String,
    busy: Boolean,
    onSubmit: () -> Unit,
    label: String = "Create",
    enabled: Boolean = true,
) {
    val colors = LocalLibrariumTheme.current.colors
    val canSubmit = enabled && value.isNotBlank() && !busy
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = on,
            placeholder = { Text(hint, color = colors.appText3, fontSize = 13.sp) },
            singleLine = true,
            enabled = enabled,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
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
        Text(
            if (busy) "…" else label,
            color = if (canSubmit) colors.accentStrong else colors.appText3,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = canSubmit) { onSubmit() }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
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
