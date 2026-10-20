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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.BookSortField
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * One list's books, in the collection's own grid.
 *
 * "The books navigation menu UI but only the books in that list": same cell,
 * same columns, same tap target, so a list reads as a slice of the collection
 * rather than a second way of showing books.
 *
 * The read is the ordinary browse with the list ticked — `shelf=<id>` on
 * `/me/books`, the same parameter the facet sheet sends when a list is chosen
 * there (`internal/api/handlers/books_across.go:parseFacetSelection`). Asking
 * the server for the slice, rather than filtering `GET /me/lists` client-side,
 * is the only version that stays right as the list changes on another device.
 */
@Composable
fun ListDetailScreen(
    listId: String,
    listName: String,
    container: AppContainer,
    onOpenBook: (Book) -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors

    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableStateOf(0) }

    LaunchedEffect(listId) {
        loading = true
        error = null
        val client = container.activeClient()
        if (client == null) {
            error = "Not signed in."
            loading = false
            return@LaunchedEffect
        }
        try {
            // Paged read rather than one huge request: a list is usually small,
            // but "usually" is not a reason to truncate somebody's shelf.
            val collected = mutableListOf<Book>()
            var page = 1
            while (page <= MAX_PAGES) {
                val slice = client.browseBooks("shelf=$listId&", page, PER_PAGE, BookSortField.TITLE_ASC)
                slice.items.forEach { it.serverUrl = client.serverUrl }
                val seen = collected.map { it.id }.toSet()
                collected += slice.items.filterNot { it.id in seen }
                total = slice.total
                if (collected.size >= slice.total || slice.items.isEmpty()) break
                page++
            }
            books = collected
        } catch (e: Exception) {
            error = (e as? ApiException)?.display ?: e.message ?: "Couldn't load this list."
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(colors.appBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onBack)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    listName.ifBlank { "List" },
                    color = colors.appText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        loading && books.isEmpty() -> "Loading…"
                        total == 0 -> "No books"
                        else -> "$total book" + if (total == 1) "" else "s"
                    },
                    color = colors.appText3,
                    fontSize = 11.sp,
                )
            }
        }

        when {
            error != null && books.isEmpty() -> CenterNote(error ?: "")
            loading && books.isEmpty() -> CenterNote("Loading…")
            books.isEmpty() -> CenterNote("No books in this list yet.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 4.dp, bottom = 40.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(books, key = { _, b -> b.id }) { _, book ->
                    BookCell(book = book, onClick = { onOpenBook(book) })
                }
            }
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    val colors = LocalLibrariumTheme.current.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = colors.appText3, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CircleBackButton(onClick: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(colors.appCard)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = colors.appText2, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

private const val PER_PAGE = 200
private const val MAX_PAGES = 10
