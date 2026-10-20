// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.model.Author
import com.dwprod.librariumscanner.core.model.AuthorIndexPage
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.BookFacets
import com.dwprod.librariumscanner.core.model.FacetValue
import com.dwprod.librariumscanner.core.model.Library
import com.dwprod.librariumscanner.core.model.Series
import com.dwprod.librariumscanner.core.model.SeriesIndexPage
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.BookSortField
import com.dwprod.librariumscanner.core.remote.BrowseQuery
import com.dwprod.librariumscanner.core.remote.SeriesSortField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which face of the collection is on screen — port of `CollectionSurface` (`CollectionSegments.swift:12-22`). */
enum class CollectionSurface(val label: String) {
    BOOKS("Books"),
    SERIES("Series"),
    AUTHORS("Authors"),
}

/**
 * All state for one surface, so switching surfaces keeps each one's scroll,
 * filter and page position without re-fetching what did not change.
 */
data class BooksState(
    val books: List<Book> = emptyList(),
    val total: Int = 0,
    val facets: BookFacets? = null,
    val query: BrowseQuery = BrowseQuery(),
    val sort: BookSortField = BookSortField.TITLE_ASC,
    val loading: Boolean = false,
    /** True once a load has finished; a failed first load retries on next visit. */
    val loaded: Boolean = false,
    val error: String? = null,
    val search: String = "",
)

data class SeriesState(
    val series: List<Series> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val search: String = "",
)

data class AuthorsState(
    val authors: List<Author> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val search: String = "",
)

/**
 * The person-scoped collection, driven by the active account's server.
 *
 * Port of `BrowseViewModel` (`BrowseViewModel.swift`) trimmed to remote mode.
 * One [BrowseQuery] drives both the books list and the facet counts — they take
 * the same parameters because they answer the same question from two sides
 * (`BrowseViewModel.swift:9-12`); a panel that says "Reading 29" over a list
 * that ignores the tick is the failure this pairing prevents.
 */
class CollectionViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _books = MutableStateFlow(BooksState())
    val books: StateFlow<BooksState> = _books.asStateFlow()

    private val _series = MutableStateFlow(SeriesState())
    val series: StateFlow<SeriesState> = _series.asStateFlow()

    private val _authors = MutableStateFlow(AuthorsState())
    val authors: StateFlow<AuthorsState> = _authors.asStateFlow()

    /** Every library of the active account, for the scope label and the facets. */
    private val _libraries = MutableStateFlow<List<Library>>(emptyList())
    val libraries: StateFlow<List<Library>> = _libraries.asStateFlow()

    /** The library whose id no-`lib` facet calls are narrowed by, when picked. */
    private val _selectedLibraryId = MutableStateFlow<String?>(null)
    val selectedLibraryId: StateFlow<String?> = _selectedLibraryId.asStateFlow()

    /** Set when a detail/delete/add changed the collection, so lists refresh. */
    private val _reloadTick = MutableStateFlow(0)
    val reloadTick: StateFlow<Int> = _reloadTick.asStateFlow()

    /** Guards the paged appends so a fast fling does not double-fire one page. */
    private val moreMutex = Mutex()

    /** Which face the pager is on; the drawer rail ticks this. */
    private val _surface = MutableStateFlow(CollectionSurface.BOOKS)
    val surface: StateFlow<CollectionSurface> = _surface.asStateFlow()

    fun setSurface(surface: CollectionSurface) {
        _surface.value = surface
    }

    /** Books-list search debounce task-holder; cancelled on each keystroke. */
    private var searchJob: kotlinx.coroutines.Job? = null

    init {
        loadLibraries()
    }

    fun loadLibraries() {
        viewModelScope.launch {
            val client = container.activeClient() ?: return@launch
            try {
                _libraries.value = client.libraries().sortedBy { it.name }
                _libraries.value.forEach { lib ->
                    lib.serverName = container.accountManager.primary?.name ?: ""
                    lib.serverUrl = client.serverUrl
                }
                _selectedLibraryId.value = _libraries.value.firstOrNull()?.id
            } catch (e: Exception) {
                _books.value = _books.value.copy(error = (e as? ApiException)?.display ?: e.message)
            }
        }
    }

    fun setLibraryScope(libraryId: String?) {
        val lib = libraryId ?: _libraries.value.firstOrNull()?.id
        if (lib == _selectedLibraryId.value) return
        _selectedLibraryId.value = lib
        // The facet call carries lib=<the single library> so counts reflect
        // that library's collection, matching the per-library scope label.
        loadBooks(reset = true)
        loadSeries()
        loadAuthors()
    }

    fun tickReload() {
        _reloadTick.value++
    }

    // ------------------------------------------------------------------ books

    fun setBooksSearch(text: String) {
        _books.value = _books.value.copy(search = text)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Long enough that typing a title is one request rather than one
            // per keystroke, short enough that it still feels like the list is
            // following along. Same budget iOS picked (`BrowseViewModel.swift:159`).
            kotlinx.coroutines.delay(350)
            applySearch()
        }
    }

    private fun applySearch() {
        val text = _books.value.search.trim()
        _books.value = _books.value.copy(
            query = _books.value.query.copy(query = text),
        )
        loadBooks(reset = true)
    }

    fun setBooksSort(sort: BookSortField) {
        if (sort == _books.value.sort) return
        _books.value = _books.value.copy(sort = sort)
        loadBooks(reset = true)
    }

    /** Toggles one facet value; on/off like iOS [BrowseSelection.toggle]. */
    fun toggleFacet(param: String, value: String) {
        val current = _books.value.query
        val values = current.facets[param] ?: emptySet()
        val updated = if (value in values) values - value else values + value
        _books.value = _books.value.copy(
            query = current.copy(facets = current.facets + (param to updated)),
        )
        loadBooks(reset = true)
    }

    fun clearFilters() {
        _books.value = _books.value.copy(query = BrowseQuery())
        loadBooks(reset = true)
    }

    /**
     * Rebuilds the whole list for the current selection, replacing an in-flight
     * page. Cancels by supersession rather than a retained Job: a fresh call
     * simply writes over whatever the slower one left.
     */
    fun loadBooks(reset: Boolean) {
        viewModelScope.launch {
            val client = container.activeClient() ?: return@launch
            val before = _books.value
            _books.value = before.copy(loading = true, error = null)
            if (reset) {
                _books.value = _books.value.copy(books = emptyList(), total = 0)
            }
            val query = _books.value.query
            val sort = _books.value.sort
            val lib = _selectedLibraryId.value
            // The scope goes on the URL like any facet; every dimension the
            // server understands arrives through [BrowseQuery], so nothing is
            // filtered client-side.
            val scoped = if (lib != null) {
                query.copy(facets = query.facets + (com.dwprod.librariumscanner.core.remote.BrowseFacet.LIBRARY to setOf(lib)))
            } else {
                query
            }
            try {
                val page = client.browseBooks(
                    query = scoped.queryString(),
                    page = 1,
                    perPage = PER_PAGE,
                    sort = sort,
                )
                val facets = runCatching { client.browseFacets(scoped.queryString()) }.getOrNull()
                // The server sends cover paths relative to its origin; a grid
                // cell cannot know the origin, so each row carries it (stamped
                // exactly like the scan flow stamps `Library.serverUrl`).
                page.items.forEach { it.serverUrl = client.serverUrl }
                _books.value = _books.value.copy(
                    books = page.items,
                    total = page.total,
                    facets = facets,
                    loading = false,
                    loaded = true,
                    error = null,
                )
            } catch (e: Exception) {
                // Nothing is replaced when the attempt came back empty-handed:
                // a cancelled refresh used to wipe a good list and leave "No
                // books" over a shelf of 1,425 (`BrowseViewModel.swift:102-109`).
                _books.value = before.copy(loading = false, loaded = true,
                    error = (e as? ApiException)?.display ?: e.message)
            }
        }
    }

    fun loadMoreBooks(): Boolean {
        val state = _books.value
        if (state.loading || !state.loaded) return false
        if (state.books.size >= state.total) return false
        val next = (state.books.size / PER_PAGE) + 1
        // The mutex guard makes the fling double-fire land once.
        if (!moreMutex.tryLock()) return false
        viewModelScope.launch {
            try {
                val client = container.activeClient()
                if (client != null) {
                    val before = _books.value
                    _books.value = before.copy(loading = true)
                    val lib = _selectedLibraryId.value
                    val scoped = if (lib != null) {
                        before.query.copy(
                            facets = before.query.facets +
                                (com.dwprod.librariumscanner.core.remote.BrowseFacet.LIBRARY to setOf(lib))
                        )
                    } else {
                        before.query
                    }
                    val page = client.browseBooks(
                        query = scoped.queryString(),
                        page = next,
                        perPage = PER_PAGE,
                        sort = _books.value.sort,
                    )
                    // Same repair as the fresh load: appended pages are
                    // relative-path rows too, and their cells render one sheet
                    // later than the load that fetched them.
                    page.items.forEach { it.serverUrl = client.serverUrl }
                    // By id, not by count. Appending blind puts a book twice in
                    // the grid when a filter and the page budget disagree.
                    val seen = before.books.map { it.id }.toMutableSet()
                    val fresh = page.items.filter { seen.add(it.id) }
                    _books.value = _books.value.copy(
                        books = before.books + fresh,
                        total = maxOf(before.total, page.total),
                        loading = false,
                    )
                }
            } catch (_: Exception) {
                // Append failures keep the page and the guard; the next call
                // retries.
            } finally {
                moreMutex.unlock()
            }
        }
        return true
    }

    // ----------------------------------------------------------------- series

    fun setSeriesSearch(text: String) {
        _series.value = _series.value.copy(search = text)
        viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            if (_series.value.search == text) loadSeries()
        }
    }

    fun loadSeries() {
        viewModelScope.launch {
            val client = container.activeClient() ?: return@launch
            val lib = _selectedLibraryId.value
            if (lib == null) {
                _series.value = _series.value.copy(loaded = true, series = emptyList())
                return@launch
            }
            val before = _series.value
            _series.value = before.copy(loading = true, error = null)
            try {
                val page: SeriesIndexPage = client.browseSeries(
                    query = "q=${com.dwprod.librariumscanner.core.remote.enc(before.search.trim())}&lib=$lib&",
                    page = 1,
                    sort = SeriesSortField.NAME,
                )
                page.items.forEach { it.serverUrl = client.serverUrl }
                _series.value = _series.value.copy(
                    series = page.items,
                    total = page.total,
                    loading = false,
                    loaded = true,
                )
            } catch (e: Exception) {
                _series.value = before.copy(loading = false, loaded = true,
                    error = (e as? ApiException)?.display ?: e.message)
            }
        }
    }

    // ---------------------------------------------------------------- authors

    fun setAuthorsSearch(text: String) {
        _authors.value = _authors.value.copy(search = text)
        viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            if (_authors.value.search == text) loadAuthors()
        }
    }

    fun loadAuthors() {
        viewModelScope.launch {
            val client = container.activeClient() ?: return@launch
            val lib = _selectedLibraryId.value
            if (lib == null) {
                _authors.value = _authors.value.copy(loaded = true, authors = emptyList())
                return@launch
            }
            val before = _authors.value
            _authors.value = before.copy(loading = true, error = null)
            try {
                val page: AuthorIndexPage = client.browseAuthors(
                    libraryId = lib,
                    query = before.search.trim(),
                )
                // The authors index sends book spines with relative cover
                // paths; the author page resolves them the same way.
                page.items.forEach { it.serverUrl = client.serverUrl }
                _authors.value = _authors.value.copy(
                    authors = page.items,
                    total = page.total,
                    loading = false,
                    loaded = true,
                )
            } catch (e: Exception) {
                _authors.value = before.copy(loading = false, loaded = true,
                    error = (e as? ApiException)?.display ?: e.message)
            }
        }
    }

    /**
     * Books by an author, for the author page — a browse with one contributor
     * ticked (`RedesignedBrowseView.swift:21-22`), rather than a second grid.
     */
    suspend fun booksByContributor(contributorId: String, perPage: Int = PER_PAGE): List<Book> {
        val client = container.activeClient() ?: return emptyList()
        val query = BrowseQuery(facets = mapOf(com.dwprod.librariumscanner.core.remote.BrowseFacet.CONTRIBUTOR to setOf(contributorId)))
        return try {
            client.browseBooks(query.queryString(), 1, perPage, BookSortField.TITLE_ASC).items
                .onEach { it.serverUrl = client.serverUrl }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Books of one series, for the series detail — reuse the serif facet. */
    suspend fun booksInSeries(libraryId: String, seriesId: String): List<Book> {
        val client = container.activeClient() ?: return emptyList()
        return try {
            client.seriesBooks(libraryId, seriesId).map { it.book }
                .onEach { it.serverUrl = client.serverUrl }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun onBookDeleted() {
        tickReload()
        loadBooks(reset = true)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CollectionViewModel(container) as T
    }

    private companion object {
        const val PER_PAGE = 40
    }
}
