// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.collection

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class AuthorDetailState(
    val books: List<Book> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * An author's page: every book the caller can see with that contributor
 * ticked. Port of `RedesignedAuthorDetailView`'s list section — the header
 * (bio, roles) is cut with the profile work per scope.
 *
 * The wire call is the browse list with `contributor=<id>` — the same
 * endpoint the collection grid reads, narrowed (`RedesignedBrowseView
 * .swift:21-22` opens it the same way). No second endpoint is invented.
 */
class AuthorDetailViewModel(
    private val container: AppContainer,
    private val authorId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthorDetailState())
    val state: kotlinx.coroutines.flow.StateFlow<AuthorDetailState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            val client = container.activeClient() ?: return@launch
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val query = com.dwprod.librariumscanner.core.remote.BrowseQuery(
                    facets = mapOf(
                        com.dwprod.librariumscanner.core.remote.BrowseFacet.CONTRIBUTOR to setOf(authorId),
                    ),
                )
                val page = client.browseBooks(
                    query = query.queryString(),
                    page = 1,
                    perPage = 200,
                    sort = com.dwprod.librariumscanner.core.remote.BookSortField.TITLE_ASC,
                )
                _state.value = AuthorDetailState(books = page.items, loading = false)
            } catch (e: Exception) {
                _state.value = AuthorDetailState(
                    loading = false,
                    error = (e as? com.dwprod.librariumscanner.core.net.ApiException)?.display ?: e.message,
                )
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val authorId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthorDetailViewModel(container, authorId) as T
    }
}

/**
 * The author's books as a simple list of rows — the grid is the collection's
 * face; a list keeps the two visually distinct, as iOS does
 * (`RedesignedAuthorDetailView.swift:120-150`).
 */
@Composable
fun AuthorDetailScreen(
    authorId: String,
    authorName: String,
    onOpenBook: (Book) -> Unit,
    onBack: () -> Unit,
    container: com.dwprod.librariumscanner.core.di.AppContainer,
    viewModel: AuthorDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AuthorDetailViewModel.Factory(container, authorId),
    ),
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(colors.appBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                color = colors.appText2,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onBack() }.padding(6.dp),
            )
        }

        Text(
            authorName,
            color = colors.appText,
            fontSize = theme.type.pageTitle,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${state.books.size} book" + if (state.books.size == 1) "" else "s",
            color = colors.appText3,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Spacer(Modifier.height(14.dp))

        if (state.error != null) {
            Text(
                state.error ?: "",
                color = colors.bad,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }

        when {
            state.loading && state.books.isEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
            ) { Text("Loading…", color = colors.appText3, fontSize = 14.sp) }

            state.books.isEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
            ) { Text("No books visible.", color = colors.appText3, fontSize = 14.sp) }

            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = 40.dp,
                ),
            ) {
                itemsIndexed(state.books, key = { _, b -> b.id }) { _, book ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(theme.radius.pill))
                            .background(colors.appCard)
                            .clickable { onOpenBook(book) }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(colors.appBackground),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                book.title.take(1),
                                color = colors.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                book.title,
                                color = colors.appText,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (book.mediaType.isNotBlank()) {
                                Text(book.mediaType, color = colors.appText3, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
