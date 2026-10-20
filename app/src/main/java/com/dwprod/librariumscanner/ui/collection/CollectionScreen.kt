// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dwprod.librariumscanner.R
import com.dwprod.librariumscanner.core.model.Author
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.BookFacets
import com.dwprod.librariumscanner.core.model.FacetValue
import com.dwprod.librariumscanner.core.model.SavedList
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
    onOpenList: (SavedList) -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors

    val books by viewModel.books.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val authors by viewModel.authors.collectAsStateWithLifecycle()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val reloadTick by viewModel.reloadTick.collectAsStateWithLifecycle()
    val surface by viewModel.surface.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val listsSheetOpen by viewModel.listsSheetOpen.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { CollectionSurface.entries.size })
    var drawerOpen by remember { mutableStateOf(false) }
    // The facet picker is a full-screen panel, so it is composed by the shell
    // rather than inside the pane. As a Column sibling *after* a
    // `fillMaxSize()` grid it used to be measured to zero height, which made
    // the filter button look dead.
    var showFilters by remember { mutableStateOf(false) }

    // Back closes whatever this screen opened before it leaves the screen.
    BackHandler(enabled = drawerOpen || showFilters || listsSheetOpen || selection.isNotEmpty()) {
        when {
            listsSheetOpen -> viewModel.closeListsSheet()
            showFilters -> showFilters = false
            drawerOpen -> drawerOpen = false
            else -> viewModel.clearSelection()
        }
    }

    LaunchedEffect(reloadTick, libraries) {
        if (libraries.isNotEmpty()) {
            viewModel.loadBooks(reset = true)
            viewModel.loadSeries()
            viewModel.loadAuthors()
            // Counts on the Lists face are the whole point of that face, and a
            // scan or an add changes them without touching this screen.
            viewModel.loadLists(force = true)
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
                            CollectionSurface.LISTS -> listsTitle(lists)
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
                        selection = selection,
                        listsMessage = lists.message,
                        viewModel = viewModel,
                        onOpenBook = onOpenBook,
                        onOpenFilters = { showFilters = true },
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
                    CollectionSurface.LISTS -> ListsPane(
                        state = lists,
                        onOpenList = onOpenList,
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

        if (showFilters) {
            FilterSheet(
                state = books,
                onToggle = viewModel::toggleFacet,
                onClear = {
                    viewModel.clearFilters()
                    showFilters = false
                },
                onClose = { showFilters = false },
            )
        }

        if (listsSheetOpen && selection.isNotEmpty()) {
            AddToListSheet(
                count = selection.size,
                state = lists,
                onCreate = viewModel::createListAndAdd,
                onPick = viewModel::addSelectionToList,
                onClose = viewModel::closeListsSheet,
            )
        }
    }
}

// ---------------------------------------------------------------------- books

@Composable
private fun BooksPane(
    state: BooksState,
    selection: Set<String>,
    listsMessage: String?,
    viewModel: CollectionViewModel,
    onOpenBook: (Book) -> Unit,
    onOpenFilters: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val gridState = rememberLazyGridState()
    val selectionMode = selection.isNotEmpty()

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
            FilterButton(badge = state.query.activeFilterCount(), onOpen = onOpenFilters)
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

        // ---- selection mode --------------------------------------------
        if (selectionMode) {
            SelectionBar(
                count = selection.size,
                onAddToList = viewModel::openListsSheet,
                onCancel = viewModel::clearSelection,
            )
        }
        if (listsMessage != null) {
            // A list was written into; say so, then get out of the way.
            LaunchedEffect(listsMessage) {
                kotlinx.coroutines.delay(3500)
                viewModel.clearListsMessage()
            }
            MessagePill(listsMessage)
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
                BookCell(
                    book = book,
                    selected = book.id in selection,
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) viewModel.toggleSelection(book.id) else onOpenBook(book)
                    },
                    onLongClick = { viewModel.toggleSelection(book.id) },
                )
            }
        }
    }
}

private fun scopeLabel(state: BooksState): String =
    if (state.query.activeFilterCount() > 0) "${state.query.activeFilterCount()} filter(s) on" else "Collection"

private fun listsTitle(state: ListsState): String {
    val addable = state.listsAddableTo().size
    return when {
        state.loading && !state.loaded -> "Loading…"
        addable == 0 -> "No lists"
        else -> "$addable list" + if (addable == 1) "" else "s"
    }
}

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
                    Icon(
                        painter = painterResource(row.iconRes),
                        contentDescription = null,
                        tint = if (active) colors.accent else colors.appText2,
                        modifier = Modifier.size(20.dp),
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
                Icon(
                    painter = painterResource(R.drawable.ic_scan),
                    contentDescription = null,
                    tint = colors.accentStrong,
                    modifier = Modifier.size(20.dp),
                )
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
    /** Vector drawable converted from the SVG in `assets/`. */
    val iconRes: Int,
    val label: String,
    val surface: CollectionSurface,
)

private val drawerRows = listOf(
    DrawerRow(R.drawable.ic_books, "Books", CollectionSurface.BOOKS),
    DrawerRow(R.drawable.ic_series, "Series", CollectionSurface.SERIES),
    DrawerRow(R.drawable.ic_authors, "Authors", CollectionSurface.AUTHORS),
    DrawerRow(R.drawable.ic_lists, "Lists", CollectionSurface.LISTS),
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BookCell(
    book: Book,
    onClick: () -> Unit,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val colors = LocalLibrariumTheme.current.colors
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // The press that starts a selection has to feel different
                    // from the tap that opens a book, or the sheet arriving is
                    // the only clue anything happened.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.appCard)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, colors.accent, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                ),
        ) {
            if (!book.coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = book.coverImageUrl(),
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
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.accent else Color.Black.copy(alpha = 0.45f))
                        .border(
                            0.5.dp,
                            if (selected) colors.accent else Color.White.copy(alpha = 0.7f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Text("✓", color = Color.White, fontSize = 11.sp)
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

/**
 * The bar that appears once a book is long-pressed: how many are picked, the
 * one action that matters, and the way out.
 */
@Composable
private fun SelectionBar(count: Int, onAddToList: () -> Unit, onCancel: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .background(colors.appCard, RoundedCornerShape(12.dp))
            .border(0.5.dp, colors.appLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count selected",
            color = colors.appText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Add to list",
            color = colors.accentStrong,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onAddToList() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            "Cancel",
            color = colors.appText3,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onCancel() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** The transient "Added 3 books to Favourites" line. */
@Composable
private fun MessagePill(text: String) {
    val colors = LocalLibrariumTheme.current.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .background(colors.good.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", color = colors.good, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = colors.good, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------- lists

/**
 * Files the books picked by long-press into a list.
 *
 * Two doors, because a reader either knows where the books go or is making the
 * place for them now: name a new list (create, then add), or pick one already
 * there. Both land on the same two server calls — `POST /me/lists` and
 * `POST /me/lists/{id}/books/{book_id}` — and neither works for a saved view,
 * which is why the views are listed read-only below the lists.
 */
@Composable
private fun AddToListSheet(
    count: Int,
    state: ListsState,
    onCreate: (String) -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalLibrariumTheme.current.colors
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank() && !state.saving

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onClose() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.appCard)
                // Swallow taps so the scrim behind the panel is not the target.
                .clickable(enabled = false) { }
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Add to a list",
                        color = colors.appText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$count selected",
                        color = colors.appText3,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    "✕",
                    color = colors.appText3,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(8.dp),
                )
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = {
                        Text("Name a new list…", color = colors.appText3, fontSize = 14.sp)
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
                Text(
                    if (state.saving) "Saving…" else "Create",
                    color = if (canCreate) colors.accentStrong else colors.appText3,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = canCreate) { onCreate(name) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                Text(state.error, color = colors.bad, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))
            val addable = state.listsAddableTo()
            val filterOnly = state.savedViews()
            when {
                state.loading && state.lists.isEmpty() ->
                    Text("Loading…", color = colors.appText3, fontSize = 14.sp)
                else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item(key = "lists-header") {
                        SheetSectionLabel("YOUR LISTS")
                    }
                    if (addable.isEmpty() && state.loaded) {
                        item(key = "lists-empty") {
                            Text(
                                "No lists yet — name one above.",
                                color = colors.appText3,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    items(addable, key = { it.id }) { list ->
                        ListRow(list, enabled = !state.saving, onPick = onPick)
                    }

                    // The server's other kind. Listing these is the difference
                    // between "my views are gone" and "a view fills itself and
                    // cannot take picked books"; the note says which.
                    if (filterOnly.isNotEmpty()) {
                        item(key = "views-header") {
                            Spacer(Modifier.height(14.dp))
                            SheetSectionLabel("SAVED VIEWS")
                        }
                        item(key = "views-note") {
                            Text(
                                "A view is a saved filter: it fills itself, so books can't be added by hand.",
                                color = colors.appText3,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        items(filterOnly, key = { it.id }) { view ->
                            ListRow(view, enabled = false, onPick = onPick)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A small all-caps heading inside the sheet. */
@Composable
private fun SheetSectionLabel(text: String) {
    val colors = LocalLibrariumTheme.current.colors
    Text(
        text,
        color = colors.appText3,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** One destination row: the list's name, its size, and the tap target. */
@Composable
private fun ListRow(list: SavedList, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onPick(list.id) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (list.isSmart) colors.appCard else colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                list.name.take(1).uppercase(),
                color = if (list.isSmart) colors.appText3 else colors.accentStrong,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                list.name,
                color = if (enabled) colors.appText else colors.appText2,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (list.isSmart) {
                    "Saved view · ${list.bookCount} ${if (list.bookCount == 1) "book" else "books"}"
                } else {
                    "${list.bookCount} ${if (list.bookCount == 1) "book" else "books"}"
                },
                color = colors.appText3,
                fontSize = 11.sp,
            )
        }
        Text(
            if (enabled) "›" else "filter",
            color = colors.appText3,
            fontSize = if (enabled) 16.sp else 10.sp,
        )
    }
}

private val theme_type_cardTitle = 13.sp

/**
 * The filter button: opens the facet picker (the browse-facets sheet). The
 * accent dot marks that filters are on, like iOS's filter button badge.
 */
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
        Icon(
            painter = painterResource(R.drawable.ic_filter),
            contentDescription = "Filters",
            tint = colors.appText2,
            modifier = Modifier.size(20.dp),
        )
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
                    model = series.coverImageUrl(),
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

// ---------------------------------------------------------------------- lists

/**
 * The Lists face: every hand-picked list with its size, and the way into it.
 *
 * Smart views are not listed here even though the server calls them lists: their
 * membership is a filter rather than a set of books, so tapping one would open a
 * filter query rather than the contents of a list. The add sheet shows them,
 * read-only, next to the lists that do take picks.
 */
@Composable
private fun ListsPane(
    state: ListsState,
    onOpenList: (SavedList) -> Unit,
) {
    val colors = LocalLibrariumTheme.current.colors
    val addable = state.listsAddableTo()

    Column(Modifier.fillMaxSize()) {
        when {
            state.error != null && addable.isEmpty() -> EmptyPane(state.error ?: "")
            !state.loaded && state.loading -> EmptyPane("Loading…")
            addable.isEmpty() -> EmptyPane(
                "No lists yet.\nLong-press books in Collection, then Add to list.",
            )
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(addable, key = { it.id }) { list ->
                    ListCard(list, onClick = { onOpenList(list) })
                }
            }
        }
    }
}

@Composable
private fun ListCard(list: SavedList, onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme_radius_card))
            .background(colors.appCard)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                list.name.take(1).uppercase(),
                color = colors.accentStrong,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                list.name,
                color = colors.appText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${list.bookCount} ${if (list.bookCount == 1) "book" else "books"}",
                color = colors.appText3,
                fontSize = 11.sp,
            )
        }
        Text("›", color = colors.appText3, fontSize = 16.sp)
    }
}