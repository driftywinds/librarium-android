// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dwprod.librariumscanner.core.model.Author
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.BookFacets
import com.dwprod.librariumscanner.core.model.FacetValue
import com.dwprod.librariumscanner.core.model.Series
import com.dwprod.librariumscanner.core.remote.BookSortField
import com.dwprod.librariumscanner.core.remote.BrowseFacet
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * The collection shell: three faces behind a hamburger drawer.
 *
 * Faces live in a pager; the drawer picks which is front. The header (handle,
 * app name, face count) is drawn by this screen: a pushed book covers it, and
 * a control that stays put while the screen under it changes belongs to the
 * wrong screen (`CollectionSegments.swift:26-29`).
 */
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onScan: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenAuthor: (Author) -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors

    val books by viewModel.books.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val authors by viewModel.authors.collectAsStateWithLifecycle()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val reloadTick by viewModel.reloadTick.collectAsStateWithLifecycle()
    val surface by viewModel.surface.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { CollectionSurface.entries.size })
    var drawerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(reloadTick, libraries) {
        if (libraries.isNotEmpty()) {
            viewModel.loadBooks(reset = true)
            viewModel.loadSeries()
            viewModel.loadAuthors()
        }
    }

    // The rail's tick and the pager stay one state: a rail tap pages, a swipe
    // ticks the rail. iOS changes faces by replacing content; on a phone the
    // pager keeps the panes' scroll positions alive across a drawer flip.
    LaunchedEffect(surface) {
        if (pagerState.currentPage != surface.ordinal) {
            pagerState.scrollToPage(surface.ordinal)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.setSurface(CollectionSurface.entries[page])
        }
    }

    Box(Modifier.fillMaxSize().background(colors.appBackground)) {
        Column(Modifier.fillMaxSize()) {
            // ---- drawer handle + title -------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton("☰") { drawerOpen = true }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Librarium",
                        color = colors.appText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when (surface) {
                            CollectionSurface.BOOKS -> countTitle(books)
                            CollectionSurface.SERIES -> "${series.total} series"
                            CollectionSurface.AUTHORS -> "${authors.total} authors"
                        },
                        color = colors.appText3,
                        fontSize = 11.sp,
                    )
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                when (CollectionSurface.entries[page]) {
                    CollectionSurface.BOOKS -> BooksPane(
                        state = books,
                        viewModel = viewModel,
                        onOpenBook = onOpenBook,
                    )
                    CollectionSurface.SERIES -> SeriesPane(
                        state = series,
                        viewModel = viewModel,
                        onOpenSeries = onOpenSeries,
                    )
                    CollectionSurface.AUTHORS -> AuthorsPane(
                        state = authors,
                        viewModel = viewModel,
                        onOpenAuthor = onOpenAuthor,
                    )
                }
            }
        }

        // Scan stays reachable from every pane: it is how the shelf grows.
        CircleButton(
            "＋",
            filled = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp),
            onClick = onScan,
        )

        if (drawerOpen) {
            CollectionDrawer(
                surface = surface,
                onSurface = { s ->
                    viewModel.setSurface(s)
                    drawerOpen = false
                },
                onScan = {
                    drawerOpen = false
                    onScan()
                },
                onClose = { drawerOpen = false },
            )
        }
    }
}

// ---------------------------------------------------------------------- books

@Composable
private fun BooksPane(
    state: BooksState,
    viewModel: CollectionViewModel,
    onOpenBook: (Book) -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val gridState = rememberLazyGridState()
    var showFilters by remember { mutableStateOf(false) }

    // Pre-fetch the next page when the tail comes on screen (iOS does this
    // from `loadMore`, triggered the same way).
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.books.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMoreBooks()
    }

    Column(Modifier.fillMaxSize()) {
        // ---- search + scope header -------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::setBooksSearch,
                placeholder = {
                    Text("Search books…", color = colors.appText3, fontSize = 14.sp)
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
            FilterButton(badge = state.query.activeFilterCount(), onOpen = { showFilters = true })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                scopeLabel(state),
                color = colors.appText3,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
            )
            Text(
                countTitle(state),
                color = colors.appText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.error != null && state.books.isEmpty()) {
            EmptyPane(state.error ?: "")
            return@Column
        }
        if (!state.loaded && state.loading) {
            EmptyPane("Loading…")
            return@Column
        }
        if (state.books.isEmpty()) {
            EmptyPane(
                if (state.query.activeFilterCount() > 0 || state.search.isNotEmpty())
                    "Nothing matches these filters."
                else
                    "Scan a book to start your shelf.",
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = gridState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, bottom = 40.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.books, key = { _, b -> b.id }) { _, book ->
                BookCell(book, onClick = { onOpenBook(book) })
            }
        }

        if (showFilters) {
            FilterSheet(
                state = state,
                onToggle = viewModel::toggleFacet,
                onClear = {
                    viewModel.clearFilters()
                    showFilters = false
                },
                onClose = { showFilters = false },
            )
        }
    }
}

private fun scopeLabel(state: BooksState): String =
    if (state.query.activeFilterCount() > 0) "${state.query.activeFilterCount()} filter(s) on" else "Collection"

private fun countTitle(state: BooksState): String =
    when {
        state.loading && !state.loaded -> "Loading…"
        state.total == 0 -> "No books"
        else -> "${state.total} book" + if (state.total == 1) "" else "s"
    }

@Composable
private fun EmptyPane(text: String) {
    val colors = LocalLibrariumTheme.current.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = colors.appText3, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * The vertical left menu. Drawn as a dimming scrim plus an inset panel rather
 * than a `ModalNavigationDrawer`: the drawer row set is three destinations and
 * one scan action, and a fixed library leaves no side of the scrim to miss.
 *
 * Future destinations (loans, saved views) slot into [drawerRows] without
 * touching the shell.
 */
@Composable
private fun CollectionDrawer(
    surface: CollectionSurface,
    onSurface: (CollectionSurface) -> Unit,
    onScan: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalLibrariumTheme.current.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onClose() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(268.dp)
                .background(colors.appCard)
                .clickable(enabled = false) { /* swallow panel taps */ }
                .padding(vertical = 18.dp),
        ) {
            Text(
                "Librarium",
                color = colors.appText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(18.dp))
            drawerRows.forEach { row ->
                val active = row.surface != null && row.surface == surface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSurface(row.surface) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.icon,
                        color = if (active) colors.accent else colors.appText2,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        row.label,
                        color = if (active) colors.appText else colors.appText2,
                        fontSize = 15.sp,
                       fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            // Scan is an action, not a destination that replaces the pane.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScan() }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⌖", color = colors.accentStrong, fontSize = 18.sp)
                Spacer(Modifier.width(14.dp))
                Text("Scan a book", color = colors.accentStrong, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Signed in · scanning works",
                color = colors.appText3,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
    }
}

/** One destination row of the drawer. */
private data class DrawerRow(
    val icon: String,
    val label: String,
    val surface: CollectionSurface,
)

private val drawerRows = listOf(
    DrawerRow("▤", "Books", CollectionSurface.BOOKS),
    DrawerRow("▦", "Series", CollectionSurface.SERIES),
    DrawerRow("✎", "Authors", CollectionSurface.AUTHORS),
)

/** Round icon button used by the shell header, filter button and scan FAB. */
@Composable
private fun CircleButton(
    label: String,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalLibrariumTheme.current.colors
    Box(
        modifier = modifier
            .size(if (filled) 56.dp else 38.dp)
            .clip(CircleShape)
            .background(if (filled) colors.accent else colors.appCard)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (filled) colors.appBackground else colors.appText2,
            fontSize = if (filled) 22.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One grid cell: cover with title below, like `BookRow`'s grid face. */
@Composable
private fun BookCell(book: Book, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Column(
        modifier = Modifier.clickable { onClick() },
    ) {
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
            if (book.userReadStatus == "read") {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.good),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            color = colors.appText,
            fontSize = theme_type_cardTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        book.primaryAuthor?.let {
            Text(
                it,
                color = colors.appText3,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val theme_type_cardTitle = 13.sp

/** The filter button; badge count matches [BooksState.query.activeFilterCount]. */
@Composable
private fun FilterButton(badge: Int, onOpen: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.appCard)
            .border(0.5.dp, colors.appLine, CircleShape)
            .clickable { onOpen() },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙", color = colors.appText2, fontSize = 18.sp)
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    }
}

// ---------------------------------------------------------------------- facets

/**
 * The full-screen facet picker. Port of `BrowseFilterPanel`
 * (`BrowseFilterSheet.swift`), one section per dimension the server answered
 * with, each value a toggle chip with its count; only the groups that arrived
 * are offered (`BookFacets`' own `isEmpty` rule, `Lookup.kt:103`).
 *
 * Counts for the ticked facet itself are excluded by the server, so a chip the
 * filter would empty cannot be reachabled.
 */
@Composable
private fun FilterSheet(
    state: BooksState,
    onToggle: (parameter: String, value: String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val facets = state.facets ?: BookFacets()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .padding(top = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters", color = colors.appText, fontSize = theme.type.pageTitle / 2, fontWeight = FontWeight.Bold)
            Row {
                Text(
                    "Clear",
                    color = colors.accentStrong,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onClear() }
                        .padding(6.dp),
                )
                Text(
                    "Done",
                    color = colors.accentStrong,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(6.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
        ) {
            BrowseFacet.sheetOrder.forEach {	param ->
                val values: List<FacetValue> = when (param) {
                    BrowseFacet.OWNERSHIP -> facets.ownership
                    BrowseFacet.LIBRARY -> facets.library
                    BrowseFacet.SHELF -> facets.shelf
                    BrowseFacet.LOCATION -> facets.location
                    BrowseFacet.READ_STATUS -> facets.readStatus
                    BrowseFacet.FAVOURITE -> facets.favourite
                    BrowseFacet.RATING -> facets.rating
                    BrowseFacet.MY_RATING -> facets.myRating
                    BrowseFacet.MEDIA_TYPE -> facets.mediaType
                    BrowseFacet.TAG -> facets.tag
                    BrowseFacet.GENRE -> facets.genre
                    else -> emptyList()
                }
                if (values.isEmpty()) return@forEach
                item(key = "header-$param") {
                    Text(
                        facetTitle(param),
                        color = colors.appText3,
                        fontSize = theme.type.sectionLabel,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                item(key = "values-$param") {
                    val selected = state.query.facets[param] ?: emptySet()
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        values.forEach { value ->
                            val ticked = value.value in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(param, value.value) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    facetLabel(param, value),
                                    color = if (ticked) colors.appText else colors.appText2,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${value.count}",
                                    color = colors.appText3,
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(if (ticked) colors.accent else Color.Transparent)
                                        .border(0.5.dp, if (ticked) colors.accent else colors.appLineStrong, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (ticked) {
                                        Text("✓", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a dimension is called, in the same words the iOS panel uses
 * (`BrowseFacet.title`, `BookBrowse.swift:56-74`).
 */
internal fun facetTitle(param: String): String = when (param) {
    BrowseFacet.OWNERSHIP -> "Ownership"
    BrowseFacet.LIBRARY -> "Library"
    BrowseFacet.SHELF -> "List"
    BrowseFacet.LOCATION -> "Shelf"
    BrowseFacet.READ_STATUS -> "Status"
    BrowseFacet.FAVOURITE -> "Favourite"
    BrowseFacet.RATING -> "Rating"
    BrowseFacet.MY_RATING -> "My rating"
    BrowseFacet.MEDIA_TYPE -> "Media type"
    BrowseFacet.TAG -> "Tag"
    BrowseFacet.GENRE -> "Genre"
    else -> param
}

/**
 * What one facet value reads as, for the dimensions whose vocabulary is the
 * reader's rather than the server's (`FacetLabels`, `BookBrowse.swift:524-536`).
 */
internal fun facetLabel(param: String, value: FacetValue): String = when (param) {
    BrowseFacet.OWNERSHIP -> when (value.value) {
        "shelf" -> "On the shelf"
        "wishlist" -> "Wishlist"
        "suggested" -> "Suggested"
        "gap" -> "Missing volume"
        else -> value.label.ifEmpty { value.value }
    }
    BrowseFacet.READ_STATUS -> when (value.value) {
        "read" -> "Read"
        "reading" -> "Reading"
        "did_not_finish" -> "Did not finish"
        "want_to_read" -> "Want to read"
        "unread" -> "Unread"
        else -> value.label.ifEmpty { value.value }
    }
    BrowseFacet.FAVOURITE -> if (value.value == "true") "Favourited" else "Not favourited"
    else -> value.label.ifEmpty { value.value }
}

// --------------------------------------------------------------------- series

/**
 * The series pane: a mosaic like `RedesignedSeriesListView`'s grid, one card
 * per series the reader's libraries answer with, cover left, counts right —
 * never the whole stack of covers, which disguises a 2-of-9 run as a
 * completed one.
 */
@Composable
private fun SeriesPane(
    state: SeriesState,
    viewModel: CollectionViewModel,
    onOpenSeries: (Series) -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::setSeriesSearch,
                placeholder = { Text("Search series…", color = colors.appText3, fontSize = 14.sp) },
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
        }

        when {
            state.error != null && state.series.isEmpty() -> EmptyPane(state.error ?: "")
            !state.loaded && state.loading -> EmptyPane("Loading…")
            state.series.isEmpty() -> EmptyPane("No series yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = 40.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(state.series, key = { _, s -> s.id }) { _, s ->
                    SeriesCell(s, onOpenSeries)
                }
            }
        }
    }
}

@Composable
private fun SeriesCell(series: Series, onClick: (Series) -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme_radius_card))
            .background(colors.appCard)
            .clickable { onClick(series) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.appBackground),
        ) {
            if (!series.coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = series.coverUrl,
                    contentDescription = series.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                series.name,
                color = colors.appText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${series.ownedCount}/${series.totalCount ?: series.ownedCount} owned",
                color = colors.appText3,
                fontSize = 11.sp,
            )
        }
    }
}

private val theme_radius_card = 12.dp

// -------------------------------------------------------------------- authors

/**
 * The authors pane: one row per contributor, with their book count. Tapping
 * filters the books pane to that author (`RedesignedAuthorsListView`), which
 * the collection shell wires by opening the author detail instead.
 */
@Composable
private fun AuthorsPane(
    state: AuthorsState,
    viewModel: CollectionViewModel,
    onOpenAuthor: (Author) -> Unit,
) {
    val colors = LocalLibrariumTheme.current.colors

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::setAuthorsSearch,
                placeholder = { Text("Search authors…", color = colors.appText3, fontSize = 14.sp) },
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
        }

        when {
            state.error != null && state.authors.isEmpty() -> EmptyPane(state.error ?: "")
            !state.loaded && state.loading -> EmptyPane("Loading…")
            state.authors.isEmpty() -> EmptyPane("No authors yet.")
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = 40.dp,
                ),
            ) {
                itemsIndexed(state.authors, key = { _, a -> a.id }) { _, a ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAuthor(a) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.appCard),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                (a.name.takeIf { it.isNotBlank() } ?: "?").take(1),
                                color = colors.accent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.name,
                                color = colors.appText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            a.role?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = colors.appText3, fontSize = 11.sp)
                            }
                        }
                        Text(
                            "${a.bookCount}",
                            color = colors.appText3,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
