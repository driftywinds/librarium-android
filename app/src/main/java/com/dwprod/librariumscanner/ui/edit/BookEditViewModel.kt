// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.BookContributor
import com.dwprod.librariumscanner.core.model.BookEdition
import com.dwprod.librariumscanner.core.model.BookSeriesRef
import com.dwprod.librariumscanner.core.model.Genre
import com.dwprod.librariumscanner.core.model.MediaType
import com.dwprod.librariumscanner.core.model.Series
import com.dwprod.librariumscanner.core.model.Tag
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.ContributorInput
import com.dwprod.librariumscanner.core.remote.CreateBookRequest
import com.dwprod.librariumscanner.core.remote.CreateEditionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The form. Strings, so the fields are editable text; ids ride along. */
data class BookFormState(
    val libraryId: String = "",
    val editingBookId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val mediaTypeId: String = "",
    /** Full vocabularies for the pickers. */
    val mediaTypes: List<MediaType> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val genres: List<Genre> = emptyList(),
    /** Row keys currently ticked. */
    val selectedTagIds: Set<String> = emptySet(),
    val selectedGenreIds: Set<String> = emptySet(),
    val contributors: List<BookContributor> = emptyList(),
    /** Set while a typed author name is being resolved or created. */
    val contributorBusy: Boolean = false,
    /** Cover plumbing: whether the book has one, and a write in flight. */
    val hasCover: Boolean = false,
    val coverBusy: Boolean = false,
    /**
     * Series membership. A book can sit on several series, but a picker that
     * changes one at a time is what a phone can express; the first ref is the
     * one the form edits and [seriesRefs] is kept for display.
     */
    val seriesOptions: List<Series> = emptyList(),
    val seriesRefs: List<BookSeriesRef> = emptyList(),
    val selectedSeriesId: String? = null,
    val seriesPosition: String = "",
    /**
     * The primary edition's fields. iOS keeps these off the book sheet and
     * edits them from the detail page; this build folds them in because the
     * edit screen is the only write surface it has.
     */
    val editionId: String? = null,
    /** The row the form edits, kept whole so unused fields survive the write. */
    val edition: BookEdition? = null,
    val isbn10: String = "",
    val isbn13: String = "",
    val publisher: String = "",
    val language: String = "",
    val publishDate: String = "",
    val pageCount: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    /** Set with the saved book when the write lands. */
    val saved: Book? = null,
)

/**
 * The add/edit form, port of the write path of `AddEditBookSheet`
 * (`AddEditBookSheet.swift`) and the request mapping of
 * `BookService.toUpdateRequest` (`BookService.swift:206-219`).
 *
 * Create goes to `POST /libraries/{id}/books` with the digest fields the scan
 * flow sends: title/subtitle/media type/description, contributors sorted the
 * way the sheet keeps them, the ticked tags and genres. Edit sends the same
 * shape without the edition — a book's printings are managed elsewhere, and
 * the round trip would drop one unless the request is careful not to imply it.
 */
class BookEditViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(BookFormState())
    val state: StateFlow<BookFormState> = _state.asStateFlow()

    val mediaTypes: List<MediaType> get() = _state.value.mediaTypes

    fun loadVocabularies() {
        val client = container.activeClient() ?: return
        viewModelScope.launch {
            val mediaTypes = runCatching { client.mediaTypes() }.getOrDefault(emptyList())
            val allTags = runCatching { client.meTags(libraryId = "") }.getOrDefault(emptyList())
            val genres = runCatching { client.genres() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(mediaTypes = mediaTypes, tags = allTags, genres = genres)
        }
    }

    /** Starts the form empty, for a hand-entered or scanned-in book. */
    fun startCreate(libraryId: String) {
        _state.value = BookFormState(
            libraryId = libraryId,
            mediaTypes = _state.value.mediaTypes,
            tags = _state.value.tags,
            genres = _state.value.genres,
        )
    }

    /** Pre-fills from the book being corrected (`AddEditBookSheet.swift:61-87`). */
    fun startEdit(book: Book, libraryId: String) {
        _state.value = BookFormState(
            libraryId = libraryId,
            editingBookId = book.id,
            title = book.title,
            subtitle = book.subtitle,
            description = book.description,
            mediaTypeId = book.mediaTypeId,
            contributors = book.contributors.sortedBy { it.displayOrder },
            tags = _state.value.tags,
            genres = _state.value.genres,
            selectedTagIds = book.tags.map { it.id }.toSet(),
            selectedGenreIds = book.genres.map { it.id }.toSet(),
            hasCover = !book.coverUrl.isNullOrEmpty(),
        )
        loadSeriesContext(book.id, libraryId)
        loadPrimaryEdition(book.id, libraryId)
    }

    /**
     * Series membership and the library's series list, in parallel.
     *
     * The form needs both to answer "which series is this on" and "what else
     * could it be on"; a failure leaves the section empty rather than blocking
     * the rest of the edit.
     */
    private fun loadSeriesContext(bookId: String, libraryId: String) {
        val client = container.activeClient() ?: return
        viewModelScope.launch {
            val refs = runCatching { client.bookSeries(libraryId, bookId) }.getOrDefault(emptyList())
            val options = runCatching { client.series(libraryId).items }.getOrDefault(emptyList())
            _state.value = _state.value.copy(
                seriesRefs = refs,
                seriesOptions = options.sortedBy { it.name },
                selectedSeriesId = refs.firstOrNull()?.seriesId,
                seriesPosition = refs.firstOrNull()?.position?.let { formatVolume(it) } ?: "",
            )
        }
    }

    /** The primary edition's fields, which the form edits in place. */
    private fun loadPrimaryEdition(bookId: String, libraryId: String) {
        val client = container.activeClient() ?: return
        viewModelScope.launch {
            val editions = runCatching { client.editions(libraryId, bookId) }.getOrDefault(emptyList())
            val primary = editions.firstOrNull { it.isPrimary } ?: editions.firstOrNull() ?: return@launch
            _state.value = _state.value.copy(
                editionId = primary.id,
                edition = primary,
                isbn10 = primary.isbn10,
                isbn13 = primary.isbn13,
                publisher = primary.publisher,
                language = primary.language,
                publishDate = primary.publishDate?.trim().orEmpty(),
                pageCount = primary.pageCount?.toString().orEmpty(),
            )
        }
    }

    fun setSelectedSeries(seriesId: String?) {
        _state.value = _state.value.copy(selectedSeriesId = seriesId)
    }

    fun setSeriesPosition(v: String) { _state.value = _state.value.copy(seriesPosition = v) }

    fun setIsbn10(v: String) { _state.value = _state.value.copy(isbn10 = v) }
    fun setIsbn13(v: String) { _state.value = _state.value.copy(isbn13 = v) }
    fun setPublisher(v: String) { _state.value = _state.value.copy(publisher = v) }
    fun setLanguage(v: String) { _state.value = _state.value.copy(language = v) }
    fun setPublishDate(v: String) { _state.value = _state.value.copy(publishDate = v) }
    fun setPageCount(v: String) { _state.value = _state.value.copy(pageCount = v) }

    /**
     * Replaces the cover with a photographed one.
     *
     * The bytes are the camera's own JPEG; the api stores whatever it is given
     * under the book, and re-reading the book is the detail screen's job when
     * this sheet closes.
     */
    fun uploadCover(jpeg: ByteArray) {
        val client = container.activeClient() ?: return
        val s = _state.value
        if (s.editingBookId == null || s.libraryId.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(coverBusy = true, error = null)
            try {
                client.uploadCover(s.libraryId, s.editingBookId, jpeg)
                _state.value = _state.value.copy(coverBusy = false, hasCover = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    coverBusy = false,
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    /** Removes the stored cover; the next metadata fetch may bring one back. */
    fun clearCover() {
        val client = container.activeClient() ?: return
        val s = _state.value
        if (s.editingBookId == null || s.libraryId.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(coverBusy = true, error = null)
            try {
                client.deleteCover(s.libraryId, s.editingBookId)
                _state.value = _state.value.copy(coverBusy = false, hasCover = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    coverBusy = false,
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    /** Queues the server's metadata + cover enrichment for this book. */
    fun refreshMetadata() {
        val client = container.activeClient() ?: return
        val s = _state.value
        if (s.editingBookId == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(coverBusy = true, error = null)
            try {
                client.enrich(s.editingBookId)
                _state.value = _state.value.copy(
                    coverBusy = false,
                    error = null,
                    hasCover = _state.value.hasCover,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    coverBusy = false,
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    fun setTitle(v: String) { _state.value = _state.value.copy(title = v) }
    fun setSubtitle(v: String) { _state.value = _state.value.copy(subtitle = v) }
    fun setDescription(v: String) { _state.value = _state.value.copy(description = v) }
    fun setMediaTypeId(v: String) { _state.value = _state.value.copy(mediaTypeId = v) }

    fun toggleTag(id: String) {
        val cur = _state.value.selectedTagIds
        _state.value = _state.value.copy(selectedTagIds = if (id in cur) cur - id else cur + id)
    }

    fun toggleGenre(id: String) {
        val cur = _state.value.selectedGenreIds
        _state.value = _state.value.copy(selectedGenreIds = if (id in cur) cur - id else cur + id)
    }

    /**
     * Credits an author typed into the form.
     *
     * The create/update body takes contributor **ids**, so a typed name has to
     * become a person first. Exact-name search, then create when nobody on the
     * instance answers to it — verified against the running server: any signed-in
     * caller may create a contributor (`POST /api/v1/contributors` is behind
     * plain auth on 26.9.0), and updating a book with the resulting id returns
     * 200.
     */
    fun addContributor(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || _state.value.contributorBusy) return
        val client = container.activeClient() ?: run {
            _state.value = _state.value.copy(error = "Not signed in.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(contributorBusy = true, error = null)
            try {
                val person = client.contributors(clean)
                    .firstOrNull { it.name.equals(clean, ignoreCase = true) }
                    ?: client.createContributor(clean)
                val current = _state.value.contributors
                if (current.any { it.contributorId == person.id }) {
                    _state.value = _state.value.copy(
                        contributorBusy = false,
                        error = "${person.name} is already credited.",
                    )
                    return@launch
                }
                _state.value = _state.value.copy(
                    contributorBusy = false,
                    contributors = current + BookContributor(
                        contributorId = person.id,
                        name = person.name,
                        role = "author",
                        displayOrder = current.size,
                    ),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    contributorBusy = false,
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    /** Drops a credit and closes the gap its display order leaves behind. */
    fun removeContributor(contributorId: String) {
        val rest = _state.value.contributors
            .filterNot { it.contributorId == contributorId }
            .mapIndexed { index, row -> row.copy(displayOrder = index) }
        _state.value = _state.value.copy(contributors = rest)
    }

    /** Builds and sends exactly the request the mapping above produces. */
    fun save() {
        val client = container.activeClient()
        val s = _state.value
        if (client == null) {
            _state.value = s.copy(error = "Not signed in.")
            return
        }
        if (s.libraryId.isEmpty()) {
            _state.value = s.copy(error = "No library to add to.")
            return
        }
        val title = s.title.trim()
        if (title.isEmpty()) {
            _state.value = s.copy(error = "A book needs a title.")
            return
        }
        val body = CreateBookRequest(
            title = title,
            subtitle = s.subtitle.trim(),
            mediaTypeId = s.mediaTypeId,
            description = s.description.trim(),
            // Display order decides who the "primary" author is on the wire;
            // sorted here so the same edit cannot reorder by hashmap accident
            // (BookService.swift:210-214 sorts identically).
            contributors = s.contributors.sortedBy { it.displayOrder }
                .filter { it.contributorId.isNotEmpty() }
                .map { ContributorInput(it.contributorId, it.role, it.displayOrder) },
            tagIds = s.selectedTagIds.toList(),
            genreIds = s.selectedGenreIds.toList(),
            // Edit sends no edition (BookService.swift:214); create does not
            // either here — the scan flow owns inline editions.
            edition = null,
        )
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val book = if (s.editingBookId != null) {
                    client.updateBook(s.libraryId, s.editingBookId, body)
                } else {
                    client.createBook(s.libraryId, body)
                }

                // The edition and the series live on their own routes: the book
                // body carries neither, so a save that stopped at `updateBook`
                // would look successful and drop both edits on the floor.
                var followUp: String? = null
                if (s.editingBookId != null) {
                    val edition = s.edition
                    if (edition != null) {
                        runCatching {
                            client.updateEdition(
                                s.libraryId,
                                s.editingBookId,
                                edition.id,
                                CreateEditionRequest(
                                    format = edition.format,
                                    language = s.language.trim(),
                                    editionName = edition.editionName,
                                    narrator = edition.narrator,
                                    publisher = s.publisher.trim(),
                                    publishDate = s.publishDate.trim().ifEmpty { null },
                                    isbn10 = s.isbn10.trim(),
                                    isbn13 = s.isbn13.trim(),
                                    description = edition.description,
                                    pageCount = s.pageCount.trim().toIntOrNull(),
                                    copyCount = edition.copyCount,
                                    isPrimary = edition.isPrimary,
                                ),
                            )
                        }.onFailure { followUp = it.readable() }
                    }
                    runCatching { writeSeries(client, s, s.editingBookId) }
                        .onFailure { followUp = followUp ?: it.readable() }
                }
                if (followUp != null) {
                    _state.value = _state.value.copy(saving = false, error = followUp)
                    return@launch
                }
                _state.value = _state.value.copy(saving = false, saved = book)
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, error = e.readable())
            }
        }
    }

    /**
     * Makes the server's series links match the form.
     *
     * Removals first: a book that moves from series A to B is one delete and
     * one upsert, and doing them in the other order would briefly have it on
     * both. An unchanged position is left alone so a save does not rewrite a
     * row nothing about it changed.
     */
    private suspend fun writeSeries(
        client: com.dwprod.librariumscanner.core.remote.LibrariumApi2,
        s: BookFormState,
        bookId: String,
    ) {
        val target = s.selectedSeriesId
        for (ref in s.seriesRefs.filter { it.seriesId != target }) {
            client.removeBookFromSeries(s.libraryId, ref.seriesId, bookId)
        }
        if (target != null) {
            val moved = formatVolumeSafe(s.seriesPosition)
            val existing = s.seriesRefs.firstOrNull { it.seriesId == target }
            if (existing == null || existing.position != moved) {
                client.addBookToSeries(s.libraryId, target, bookId, moved)
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BookEditViewModel(container) as T
    }
}

/** `4.0` reads as "4"; `4.5` keeps its half. */
private fun formatVolume(position: Double): String =
    if (position == position.toLong().toDouble()) position.toLong().toString()
    else String.format(java.util.Locale.US, "%.1f", position)

private fun formatVolumeSafe(text: String): Double = text.trim().toDoubleOrNull() ?: 0.0

/** A sentence the reader can act on, whoever raised the failure. */
private fun Throwable.readable(): String =
    (this as? ApiException)?.display
        ?: message?.takeIf { it.isNotBlank() }
        ?: "Couldn't save."
