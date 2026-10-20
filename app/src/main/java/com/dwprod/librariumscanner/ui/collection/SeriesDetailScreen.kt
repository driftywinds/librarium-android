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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.Series
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What one series answers with: the series row as the index sent it, plus the
 * volumes the reader can see.
 */
data class SeriesDetailState(
    val series: Series? = null,
    val books: List<Book> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Port of `RedesignedSeriesDetailView` trimmed to scope: header (name, owned/
 * read counts) and the volumes grid. Volumes come from the series' own books
 * endpoint; the "add missing volume" affordance is scan-flow territory.
 */
class SeriesDetailViewModel(
    private val container: AppContainer,
    private val libraryId: String,
    private val seriesId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SeriesDetailState())
    val state: kotlinx.coroutines.flow.StateFlow<SeriesDetailState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            val client = container.activeClient() ?: return@launch
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                // A series id is library-scoped on the wire, so the library the
                // caller browsed from is required; without it neither the row
                // nor the volumes can be asked for.
                if (libraryId.isEmpty()) {
                    _state.value = SeriesDetailState(
                        loading = false,
                        error = "Series must be opened from a library.",
                    )
                    return@launch
                }
                val row: Series? = runCatching { client.series(libraryId, seriesId) }.getOrNull()
                val books = runCatching { client.seriesBooks(libraryId, seriesId).map { it.book } }
                    .getOrDefault(emptyList())
                _state.value = SeriesDetailState(series = row, books = books, loading = false)
            } catch (e: Exception) {
                _state.value = SeriesDetailState(
                    loading = false,
                    error = (e as? com.dwprod.librariumscanner.core.net.ApiException)?.display ?: e.message,
                )
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val libraryId: String,
        private val seriesId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SeriesDetailViewModel(container, libraryId, seriesId) as T
    }
}

/**
 * A series' page: header card, then the volumes the reader holds as a grid
 * (`RedesignedSeriesDetailView.swift:154-198`).
 */
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    seriesName: String,
    libraryId: String,
    onOpenBook: (Book) -> Unit,
    onBack: () -> Unit,
    container: com.dwprod.librariumscanner.core.di.AppContainer,
    viewModel: SeriesDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SeriesDetailViewModel.Factory(container, libraryId, seriesId),
    ),
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Fallback title: the index row may not have arrived.
    var headerName by remember(seriesName) { mutableStateOf(seriesName) }

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
            Text(
                headerName,
                color = colors.appText,
                fontSize = theme.type.heroTitle,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        state.series?.let { s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .background(colors.appCard, RoundedCornerShape(theme.radius.pill))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!s.coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = s.coverUrl,
                        contentDescription = s.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        "${s.ownedCount} owned · ${s.readCount} read" +
                            (s.totalCount?.let { " of $it" } ?: ""),
                        color = colors.appText2,
                        fontSize = 13.sp,
                    )
                    if (s.primaryAuthor?.isNotBlank() == true) {
                        Text(s.primaryAuthor ?: "", color = colors.appText3, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (state.error != null) {
            Text(
                state.error ?: "",
                color = colors.bad,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }

        if (state.books.isEmpty() && !state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No volumes visible.", color = colors.appText3, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = 40.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(state.books, key = { _, b -> b.id }) { _, book ->
                    SeriesBookCell(book, onClick = { onOpenBook(book) })
                }
            }
        }
    }
}

@Composable
private fun SeriesBookCell(book: Book, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Column(Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
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
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            color = colors.appText,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
