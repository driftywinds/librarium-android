// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.BookSeriesRef
import com.dwprod.librariumscanner.core.model.MyBook
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.LibrariumApi2
import com.dwprod.librariumscanner.core.remote.UpdateMyBookRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BookDetailState(
    val book: Book? = null,
    val myBook: MyBook? = null,
    /** The library the book was resolved through; edit/delete go here. */
    val libraryId: String = "",
    val series: List<BookSeriesRef> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** "deleted" when the delete went through, else empty. */
    val deleted: Boolean = false,
    /** Read-status patch in flight — the quick actions. */
    val patching: Boolean = false,
)

/**
 * Port of the read path of `RedesignedBookDetailView`'s view model
 * (`RedesignedBookDetailView.swift:975-1120`), to the fields the collection
 * build renders: the book, the reader's row, and the series pointers. Copies,
 * loans, lists and other readers are cut per the build scope, each behind the
 * section that would have rendered it, so restoring them later means restoring
 * the load and the section together.
 */
class BookDetailViewModel(
    private val container: AppContainer,
    private val libraryIdArg: String,
    private val bookIdArg: String,
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailState())
    val state: StateFlow<BookDetailState> = _state.asStateFlow()

    /** Set once the book's owning library is known; the slot the edit screen reuses. */
    fun setLibraryId(id: String) {
        if (id.isNotEmpty() && id != _state.value.libraryId) {
            _state.value = _state.value.copy(libraryId = id)
        }
    }

    init {
        load()
    }

    fun load() {
        val client = container.activeClient()
        if (client == null) {
            _state.value = _state.value.copy(error = "Not signed in.")
            return
        }
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // The per-library route 404s for a book that has slipped out of
                // that library; the library-less route answers for any book the
                // account can see. Try the cheap scoped one, fall through like
                // `BookService.detail` does (RedesignedBookDetailView.swift:995).
                val book = try {
                    if (libraryIdArg.isNotEmpty()) client.book(libraryIdArg, bookIdArg)
                    else client.book(bookIdArg)
                } catch (e: ApiException.NotFound) {
                    client.book(bookIdArg)
                }
                // /books/{id} carries no caller block; the person-scoped row is
                // a second read (`RedesignedBookDetailView.swift:1002-1006`).
                val mine = runCatching { client.myBook(bookIdArg) }.getOrNull()
                val seriesRefs = if (book.libraryId.isNotEmpty()) {
                    runCatching { client.bookSeries(book.libraryId, bookIdArg) }.getOrDefault(emptyList())
                } else emptyList()
                val lib = if (libraryIdArg.isNotEmpty()) libraryIdArg else book.libraryId
                // The cover path is relative to the server origin; stamp it so
                // the hero image can be loaded by Coil (relative URLs 404 as
                // file loads).
                book.serverUrl = client.serverUrl
                _state.value = _state.value.copy(
                    book = book,
                    myBook = mine,
                    libraryId = lib,
                    series = seriesRefs,
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    /**
     * The four quick statuses, exactly the api values the scan flow sends.
     * Rate/favourite/not-review go through the same row, but are cut with the
     * own-review section per scope.
     */
    fun setReadStatus(status: String) {
        val client = container.activeClient() ?: return
        val previous = _state.value.myBook?.readStatus
        // Optimistic like iOS: the chip answers before the wire does, and a
        // failed write rolls it back rather than spinning.
        _state.value = _state.value.copy(
            patching = true,
            myBook = _state.value.myBook?.copy(readStatus = status)
                ?: MyBook(readStatus = status),
        )
        viewModelScope.launch {
            try {
                val updated = client.updateMyBook(bookIdArg, UpdateMyBookRequest(readStatus = status))
                _state.value = _state.value.copy(myBook = updated, patching = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    patching = false,
                    myBook = _state.value.myBook?.copy(readStatus = previous ?: "unread"),
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    fun delete() {
        val client = container.activeClient() ?: return
        val lib = _state.value.libraryId
        if (lib.isEmpty()) {
            _state.value = _state.value.copy(error = "The book's library is unknown; it cannot be deleted.")
            return
        }
        viewModelScope.launch {
            try {
                client.deleteBook(lib, bookIdArg)
                _state.value = _state.value.copy(deleted = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = (e as? ApiException)?.display ?: e.message,
                )
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val libraryId: String,
        private val bookId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BookDetailViewModel(container, libraryId, bookId) as T
    }
}
