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
import com.dwprod.librariumscanner.core.model.ContributorResult
import com.dwprod.librariumscanner.core.model.Genre
import com.dwprod.librariumscanner.core.model.MediaType
import com.dwprod.librariumscanner.core.model.SavedList
import com.dwprod.librariumscanner.core.model.Series
import com.dwprod.librariumscanner.core.model.Tag
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.ContributorInput
import com.dwprod.librariumscanner.core.remote.CreateBookRequest
import com.dwprod.librariumscanner.core.remote.CreateEditionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * People already on the instance who match what is being typed, so a name
     * that exists can be picked rather than retyped.
     *
     * This is a second way in beside the type-a-name field, not a replacement:
     * the search endpoint only answers from two characters and only returns
     * the first ten hits (`internal/service/book.go:112-114`), so a name the
     * search cannot reach is still addable by typing it in full.
     */
    val authorQuery: String = "",
    val authorResults: List<ContributorResult> = emptyList(),
    val authorSearchBusy: Boolean = false,
    /**
     * The last ISBN the instance was asked about, kept so the form can say what
     * the server will do with it before Save rather than after.
     */
    val isbnKnownBook: String? = null,
    val isbnBusy: Boolean = false,
    /** Cover plumbing: whether the book has one, and a write in flight. */
    val hasCover: Boolean = false,
    val coverBusy: Boolean = false,
    /**
     * Cover bytes chosen for a book that does not exist yet.
     *
     * The upload route is `/libraries/{id}/books/{book_id}/cover`, so a cover
     * picked on the create form has nowhere to go until the book has an id.
     * The bytes are parked here — the form shows them as a preview — and
     * [BookEditViewModel.save] uploads them straight after the create lands.
     * On the edit path they are not used: that cover is already stored, so
     * replacing it is immediate.
     */
    val pendingCover: ByteArray? = null,
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
    /**
     * Where a brand-new book lands, and the lists it joins.
     *
     * Only the create path offers these: editing an existing book changes no
     * list membership from this form, because a list is a set of books and the
     * collection's multi-select is where that is edited.
     */
    val lists: List<SavedList> = emptyList(),
    val selectedListIds: Set<String> = emptySet(),
    val newListName: String = "",
    val listBusy: Boolean = false,
    /** New tag being made inline; a tag is a name plus nothing else. */
    val newTagName: String = "",
    val tagBusy: Boolean = false,
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

    /** The debounced author search, cancelled and replaced on every keystroke. */
    private var authorSearchJob: Job? = null

    /** The debounced ISBN probe, same shape as [authorSearchJob]. */
    private var isbnProbeJob: Job? = null

    val mediaTypes: List<MediaType> get() = _state.value.mediaTypes

    /** The account's server origin, for the "edit editions on the web" note. */
    val serverUrl: String get() = container.activeClient()?.serverUrl ?: ""

    fun loadVocabularies() {
        val client = container.activeClient() ?: return
        // Tags have to come from the book's own library, not from `/me/tags`.
        // That endpoint deliberately reaches across every library the caller can
        // read (`internal/api/handlers/me_lookup.go:SearchTags`), but a book's
        // tags are read back scoped to the library holding the copy
        // (`internal/repository/books.go:139`, `tagsScope`), so a cross-library
        // tag id would save and then never appear. `meTags` stays as the
        // fallback for a form with no library yet, where there is nothing to
        // scope to.
        val libraryId = _state.value.libraryId
        viewModelScope.launch {
            val mediaTypes = runCatching { client.mediaTypes() }.getOrDefault(emptyList())
            val tags = runCatching {
                if (libraryId.isEmpty()) client.meTags() else client.libraryTags(libraryId)
            }.getOrDefault(emptyList())
            val genres = runCatching { client.genres() }.getOrDefault(emptyList())
            // Manual lists only: a smart view computes its own books, so a new
            // book cannot be filed into one.
            val lists = runCatching { client.lists().filter { !it.isSmart } }
                .getOrDefault(emptyList())
                .sortedBy { it.name }
            _state.value = _state.value.copy(
                mediaTypes = mediaTypes,
                tags = tags,
                genres = genres,
                lists = lists,
                // `startCreate` can run before the catalog has ever been read
                // (first open of the form on a fresh install), in which case it
                // had nothing to preselect. Fill it in now — but only on the
                // create path: an edit must not quietly change a book's media
                // type just because the server returned an empty id.
                mediaTypeId = if (_state.value.editingBookId == null) {
                    _state.value.mediaTypeId.ifEmpty { mediaTypes.firstOrNull()?.id.orEmpty() }
                } else {
                    _state.value.mediaTypeId
                },
            )
        }
    }

    /** Starts the form empty, for a hand-entered or scanned-in book. */
    fun startCreate(libraryId: String) {
        _state.value = BookFormState(
            libraryId = libraryId,
            // The create body needs a media type and the server answers an
            // empty one with "invalid media_type_id" (`decodeBookRequest`,
            // `internal/api/handlers/books.go:727`), which names the field but
            // not the fix. Preselecting the first of the server's own catalog
            // means the common case never sees that error, and the chips show
            // what was chosen.
            mediaTypeId = _state.value.mediaTypes.firstOrNull()?.id.orEmpty(),
            mediaTypes = _state.value.mediaTypes,
            tags = _state.value.tags,
            genres = _state.value.genres,
            lists = _state.value.lists,
        )
        if (_state.value.mediaTypes.isEmpty()) loadVocabularies()
    }

    fun setLibraryId(libraryId: String) {
        _state.value = _state.value.copy(libraryId = libraryId)
    }

    fun toggleList(id: String) {
        val current = _state.value.selectedListIds
        _state.value = _state.value.copy(
            selectedListIds = if (id in current) current - id else current + id,
        )
    }

    fun setNewListName(name: String) {
        _state.value = _state.value.copy(newListName = name)
    }

    /** Makes a list inline and ticks it, so the new book lands in it. */
    fun createList() {
        val name = _state.value.newListName.trim()
        if (name.isEmpty() || _state.value.listBusy) return
        val client = container.activeClient() ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(listBusy = true, error = null)
            try {
                val made = client.createList(name)
                _state.value = _state.value.copy(
                    listBusy = false,
                    newListName = "",
                    lists = (_state.value.lists + made).sortedBy { it.name },
                    selectedListIds = _state.value.selectedListIds + made.id,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(listBusy = false, error = e.readable())
            }
        }
    }

    fun setNewTagName(name: String) {
        _state.value = _state.value.copy(newTagName = name)
    }

    /** Makes a tag inline and ticks it; the tag vocabulary is per-library. */
    fun createTag() {
        val name = _state.value.newTagName.trim()
        val libraryId = _state.value.libraryId
        if (name.isEmpty() || _state.value.tagBusy || libraryId.isEmpty()) return
        val client = container.activeClient() ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(tagBusy = true, error = null)
            try {
                val made = client.createTag(libraryId, name)
                _state.value = _state.value.copy(
                    tagBusy = false,
                    newTagName = "",
                    tags = _state.value.tags + made,
                    selectedTagIds = _state.value.selectedTagIds + made.id,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(tagBusy = false, error = e.readable())
            }
        }
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

    fun setIsbn10(v: String) {
        _state.value = _state.value.copy(isbn10 = v)
        probeIsbn()
    }

    fun setIsbn13(v: String) {
        _state.value = _state.value.copy(isbn13 = v)
        probeIsbn()
    }

    /**
     * Asks the instance whether an edition with this ISBN is already known.
     *
     * `CreateBook` does not hand back "this was a duplicate": when the code
     * matches an edition anywhere on the server it reuses that book and edition
     * and bumps the copy count for this library
     * (`internal/service/book.go:171-201`), which quietly ignores the title,
     * authors, tags and genres the form was filled with. The reader otherwise
     * discovers that only after the fact, so the form probes while the code is
     * being typed and says what will happen.
     *
     * `GET /libraries/{id}/book-by-isbn/{isbn}` is per-library
     * (`internal/api/router.go:468`), so the probe answers "already in *this*
     * library". A code held in another library on the instance still folds into
     * the existing book on save — that case has no cheap read, and the copy it
     * adds to this library is what the reader meant.
     */
    private fun probeIsbn() {
        isbnProbeJob?.cancel()
        val client = container.activeClient() ?: return
        val s = _state.value
        val code = normaliseIsbn(s.isbn13.ifBlank { s.isbn10 })
        if (code.isEmpty()) {
            // An empty box means "no ISBN": drop the previous book's name, or it
            // would keep describing a code that is no longer on the form.
            _state.value = _state.value.copy(isbnKnownBook = null, isbnBusy = false)
            return
        }
        if (code.length !in ISBN_LENGTHS) return
        isbnProbeJob = viewModelScope.launch {
            // Long enough that typing a code is one request rather than one for
            // every digit, short enough that the note still feels attached to
            // the field. Same budget the collection's search uses.
            delay(350)
            _state.value = _state.value.copy(isbnBusy = true)
            val already = try {
                // Held in a local: `bookByIsbn` answers 404 when this library
                // does not hold the code, which throws.
                client.bookByIsbn(s.libraryId, code)
            } catch (_: Exception) {
                null
            }
            // A keystroke can have moved the field on while the read ran; the
            // answer belongs to the code it was asked about, not to this one.
            if (normaliseIsbn(_state.value.isbn13.ifBlank { _state.value.isbn10 }) != code) return@launch
            _state.value = _state.value.copy(isbnKnownBook = already?.title, isbnBusy = false)
        }
    }

    fun setPublisher(v: String) { _state.value = _state.value.copy(publisher = v) }
    fun setLanguage(v: String) { _state.value = _state.value.copy(language = v) }
    fun setPublishDate(v: String) { _state.value = _state.value.copy(publishDate = v) }
    fun setPageCount(v: String) { _state.value = _state.value.copy(pageCount = v) }

    /**
     * Replaces the cover with a photographed or picked one.
     *
     * On the edit path the bytes go up immediately: the book exists, so the
     * multipart route has an id to name. On the create path there is no id yet,
     * so the bytes are parked as [BookFormState.pendingCover] and the form
     * shows them as a preview until [save] creates the book and uploads them.
     *
     * The bytes are already a JPEG — the camera writes one, and the gallery
     * picker re-encodes — which is what the endpoint's MIME sniff
     * (`internal/api/uploads/sniff.go`) expects.
     */
    fun uploadCover(jpeg: ByteArray) {
        val client = container.activeClient() ?: return
        val s = _state.value
        if (s.libraryId.isEmpty()) {
            // The cover route is library-scoped, so there is nowhere to put the
            // bytes. Say so: the picker has already closed by this point, and
            // silently keeping the old preview reads as the pick having failed.
            _state.value = s.copy(error = "Pick a library before adding a cover.")
            return
        }
        if (s.editingBookId == null) {
            _state.value = s.copy(pendingCover = jpeg, error = null)
            return
        }
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
        // The picked-but-unsent cover is checked first and on its own: on the
        // create path there is no book id at all, so keying off the id would
        // make "Remove" a no-op exactly where it is the only way to undo a pick.
        if (_state.value.pendingCover != null) {
            _state.value = _state.value.copy(pendingCover = null)
            return
        }
        // Held in a local: the smart cast on the nullable field would not
        // survive into the coroutine below.
        val bookId = _state.value.editingBookId ?: return
        val client = container.activeClient() ?: return
        val s = _state.value
        if (s.libraryId.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(coverBusy = true, error = null)
            try {
                client.deleteCover(s.libraryId, bookId)
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

    fun setTitle(v: String) { _state.value = _state.value.copy(title = v) }    fun setSubtitle(v: String) { _state.value = _state.value.copy(subtitle = v) }
    fun setDescription(v: String) { _state.value = _state.value.copy(description = v) }
    fun setMediaTypeId(v: String) { _state.value = _state.value.copy(mediaTypeId = v) }

    /** Surfaces a failure raised outside the request paths, e.g. an unreadable photo. */
    fun setError(message: String?) { _state.value = _state.value.copy(error = message) }

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
                    authorQuery = "",
                    authorResults = emptyList(),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    contributorBusy = false,
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    /**
     * The author search behind the picker: what is typed, and whom the instance
     * answers with.
     *
     * `GET /contributors?q=` is the endpoint that reaches the whole instance —
     * the per-library list (`/libraries/{id}/contributors`) only covers people
     * who already have a book in that library, which is the wrong net for "who
     * is already on the server". It also refuses short queries with an empty
     * array (`internal/api/handlers/books.go:96`) and caps the answer at ten
     * (`internal/service/book.go:112-114`), so the form stays quiet below two
     * characters instead of showing a successful-looking nothing.
     */
    fun setAuthorQuery(text: String) {
        _state.value = _state.value.copy(authorQuery = text)
        authorSearchJob?.cancel()
        val client = container.activeClient() ?: return
        val query = text.trim()
        if (query.length < AUTHOR_QUERY_MIN) {
            _state.value = _state.value.copy(authorResults = emptyList(), authorSearchBusy = false)
            return
        }
        authorSearchJob = viewModelScope.launch {
            // The collection's search budget, for the same reason: one request
            // per name rather than per letter.
            delay(350)
            _state.value = _state.value.copy(authorSearchBusy = true)
            val found = try {
                client.contributors(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<ContributorResult>()
            }
            // People already credited are filtered out: offering the person the
            // form already has is a second "already credited" error waiting to
            // happen.
            val credited = _state.value.contributors.map { it.contributorId }.toSet()
            _state.value = _state.value.copy(
                authorResults = found.filterNot { it.id in credited },
                authorSearchBusy = false,
            )
        }
    }

    /**
     * Credits a person the reader picked from the search rows.
     *
     * No request: the row already carries the contributor id the save body
     * wants, which is the whole point of picking rather than retyping — the
     * retyped spelling would have gone through the find-or-create and risked a
     * second person for one name.
     */
    fun creditAuthor(person: ContributorResult) {
        val current = _state.value.contributors
        if (current.any { it.contributorId == person.id }) {
            _state.value = _state.value.copy(error = "${person.name} is already credited.")
            return
        }
        _state.value = _state.value.copy(
            error = null,
            contributors = current + BookContributor(
                contributorId = person.id,
                name = person.name,
                role = "author",
                displayOrder = current.size,
            ),
            authorQuery = "",
            authorResults = emptyList(),
        )
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
        if (s.mediaTypeId.isEmpty()) {
            // The server says "invalid media_type_id", which does not tell the
            // reader that the fix is a chip tap. Only reachable when the catalog
            // failed to load, so the wording points at that.
            _state.value = s.copy(
                error = "Pick a media type — the list did not load from this server.",
            )
            return
        }
        // Separators are dropped before the length is judged: the phone wants
        // to match what a scan will send, and a scan sends digits only.
        val isbn13 = normaliseIsbn(s.isbn13)
        val isbn10 = normaliseIsbn(s.isbn10)
        if (s.editingBookId == null) {
            // Policed on create only. An edition that predates this form may
            // hold a code the catalogue printed wrong, and refusing to correct
            // that book's title until somebody fixes the ISBN trades one repair
            // for another — the web client owns edition repair, and the note at
            // the foot of the form says so.
            (isbnProblem(isbn13, 13, "The ISBN-13") ?: isbnProblem(isbn10, 10, "The ISBN-10"))?.let { problem ->
                _state.value = s.copy(error = problem)
                return
            }
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
            // The scan flow owns the full inline edition, and an edit goes
            // through its own update route below; what this form adds on the
            // create path is the ISBN — the code that makes the book findable
            // by barcode and that `CreateBook` dedupes on
            // (`internal/service/book.go:171-201`). Without it the form's book
            // has no edition at all, and no code for a later scan to recognise.
            edition = if (s.editingBookId == null && (isbn13.isNotEmpty() || isbn10.isNotEmpty())) {
                CreateEditionRequest(
                    // `parseEditionRequestBody` refuses an empty one ("format
                    // is required", `internal/api/handlers/editions.go:317`),
                    // and the reader typing a code off a physical copy is what
                    // this field is for — the same default the scan flow sends
                    // (ScanRepository.addToLibrary, `format = "physical"`).
                    // A real format choice stays with the web client, which the
                    // note at the foot of the form names.
                    format = "physical",
                    isbn10 = isbn10,
                    isbn13 = isbn13,
                    copyCount = 1,
                    isPrimary = true,
                )
            } else {
                null
            },
        )
        // The saved code is the bare digits, so the boxes stop disagreeing with
        // what went up the moment Save is pressed.
        _state.value = s.copy(saving = true, error = null, isbn13 = isbn13, isbn10 = isbn10)
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
                                    isbn10 = isbn10,
                                    isbn13 = isbn13,
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

                // Cover and list membership are their own routes and both need
                // the book's id, so neither is part of the body above and
                // neither can be rolled back with it. Run unconditionally: on a
                // retry after a partial failure both calls are idempotent —
                // the cover is a straight overwrite, and `AddBook` upserts on
                // `(list_id, book_id)` (`internal/repository/lists.go:AddBook`)
                // — and on the plain edit path both are empty, so they cost
                // nothing.
                val coverMissed = s.pendingCover?.let { jpeg ->
                    runCatching { client.uploadCover(s.libraryId, book.id, jpeg) }
                        .exceptionOrNull()
                }
                val listsMissed = writeLists(client, s, book.id)
                val partial = coverMissed ?: listsMissed

                // `CreateBook` folds a known ISBN into the edition already
                // holding it and answers *that* book, leaving the typed title,
                // authors, tags and genres aside
                // (`internal/service/book.go:171-201`). The probe above warns
                // the reader when this library holds the code, but a code held
                // only in another library is folded in with no earlier signal,
                // so the response itself is the tell: what came back is not
                // what was typed.
                val foldedIn = s.editingBookId == null && book.title.isNotBlank() &&
                    !book.title.equals(title, ignoreCase = true)

                if (partial != null) {
                    // The book itself is on the server. Staying on the form with
                    // a bare error would invite a second Save, and a second
                    // create — which the server rejects as a duplicate, so the
                    // reader would see a failure for work that did land. Flipping
                    // the form to editing that book instead makes Save safe and
                    // retry only the piece that failed.
                    _state.value = _state.value.copy(
                        saving = false,
                        editingBookId = book.id,
                        hasCover = _state.value.hasCover || (coverMissed == null && s.pendingCover != null),
                        pendingCover = if (coverMissed == null) null else s.pendingCover,
                        error = (if (foldedIn) foldInNote(book.title) + " " else "") +
                            "The book was added, but ${partial.readable().lowercaseFirstSentence()} " +
                            "Press Save to try again — it will update this book rather than add another.",
                    )
                    return@launch
                }
                if (followUp != null) {
                    // Fold-in plus a follow-up failure: the note leads with what
                    // the ISBN did, the missing piece follows, and because the
                    // edition fetch is asynchronous the cover/list failure is
                    // what sets the editing id — either way the next Save
                    // updates the folded book instead of creating another.
                    if (foldedIn) loadPrimaryEdition(book.id, s.libraryId)
                    _state.value = _state.value.copy(
                        saving = false,
                        editingBookId = if (foldedIn) book.id else _state.value.editingBookId,
                        error = if (foldedIn) foldInNote(book.title) else followUp,
                    )
                    return@launch
                }
                if (foldedIn) {
                    // Not navigating: popping with a note the reader never sees
                    // would hide that the other fields were not applied, and
                    // `saved` is what the screen watches to dismiss itself.
                    // Staying turns the form into an editor of the book that
                    // actually holds the ISBN, with its copy counted here — and
                    // the edition is fetched so the form really does show that
                    // book rather than claiming to.
                    _state.value = _state.value.copy(
                        saving = false,
                        editingBookId = book.id,
                        hasCover = _state.value.hasCover || (coverMissed == null && s.pendingCover != null),
                        pendingCover = if (coverMissed == null) null else s.pendingCover,
                        error = foldInNote(book.title),
                    )
                    loadPrimaryEdition(book.id, s.libraryId)
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

    /**
     * Files the book into the lists ticked on the form. Returns the first
     * failure, or null when every ticked list took it.
     *
     * A list is a separate resource: `POST /me/lists/{id}/books/{book_id}`
     * (`internal/api/handlers/lists.go:AddBookToList`) takes one book at a
     * time and answers 204, so this is one call per ticked list. Smart lists are
     * excluded from the picker in [loadVocabularies] because the server refuses
     * to add to them by hand (400, "that list fills itself from its filter"),
     * and a reader cannot tell a smart list from a manual one by its name.
     *
     * The first failure is reported but does not stop the rest: a single list
     * that rejects the book should not stop the other ticks landing.
     */
    private suspend fun writeLists(
        client: com.dwprod.librariumscanner.core.remote.LibrariumApi2,
        s: BookFormState,
        bookId: String,
    ): Throwable? {
        var failure: Throwable? = null
        for (listId in s.selectedListIds) {
            runCatching { client.addBookToList(listId, bookId) }
                .onFailure { if (failure == null) failure = it }
        }
        return failure
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

/** The two ISBN lengths, as the server's `FindByISBN` match expects them. */
internal val ISBN_LENGTHS = setOf(10, 13)

/**
 * A short query means "nothing to search on", matching what the server itself
 * does (`internal/api/handlers/books.go:96` answers an empty array under two
 * characters, so firing the request would only return one).
 */
internal const val AUTHOR_QUERY_MIN = 2

/**
 * Strips what a hand-typed ISBN arrives with, keeping the code itself.
 *
 * `FindByISBN` compares the bare column against the bare value
 * (`WHERE isbn_10 = $1 OR isbn_13 = $1`, `internal/repository/editions.go:229`),
 * and a later scan feeds that route digits off a barcode, so a stored
 * "978-0-306-40615-7" would never be found by the book it came from. Separators
 * are therefore dropped rather than stored, and a trailing `x` is upper-cased
 * because the check digit is matched as text.
 */
internal fun normaliseIsbn(raw: String): String =
    raw.filter { it.isDigit() || it == 'x' || it == 'X' }.uppercase()

/**
 * Why this string cannot be an ISBN, or null when it could be.
 *
 * Length only. A failing check digit is usually a catalogue that printed its
 * own code wrong, and refusing it leaves the reader unable to record what is on
 * the book in front of them — while a wrong length is never an ISBN at all and
 * would sit in the column doing nothing except spoiling the next scan's match.
 */
internal fun isbnProblem(isbn: String, length: Int, label: String): String? =
    if (isbn.isNotEmpty() && isbn.length != length) {
        "An ISBN has $length digits — \"$isbn\" has ${isbn.length}. Check it, or leave the box empty."
    } else {
        null
    }


/** A sentence the reader can act on, whoever raised the failure. */
private fun Throwable.readable(): String =
    (this as? ApiException)?.display
        ?: message?.takeIf { it.isNotBlank() }
        ?: "Couldn't save."

/**
 * Drops the capital so a message can follow "but" in the middle of a sentence.
 * Only touches a leading capital that begins a word, so an all-caps first token
 * or a quote is left as written.
 */
private fun String.lowercaseFirstSentence(): String =
    replaceFirstChar { ch: Char -> if (ch.isUpperCase()) ch.lowercaseChar() else ch }

/**
 * What [BookEditViewModel.save] tells the reader when the server answered with
 * an existing book rather than the one the form described.
 *
 * The copy is counted and the cover lands, but the typed title, authors, tags
 * and genres do not — so the note names the book that holds the ISBN instead of
 * reporting a plain success that saved the wrong-looking thing. The form stays
 * open as an editor of that book, so "correct it there" is actionable right
 * here.
 */
internal fun foldInNote(existingTitle: String): String =
    "A book with this ISBN is already on this server as \"$existingTitle\", so a copy of it was " +
        "added to your library — the title, authors, tags and genres you entered were not applied. " +
        "The form now shows that book; edit it here or in the web client, or clear the ISBN to add a separate book."
