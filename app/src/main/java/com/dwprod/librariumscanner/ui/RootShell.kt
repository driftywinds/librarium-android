// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.model.Author
import com.dwprod.librariumscanner.core.model.Book
import com.dwprod.librariumscanner.core.model.Series
import com.dwprod.librariumscanner.core.session.SessionState
import com.dwprod.librariumscanner.ui.collection.CollectionViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.platform.LocalContext
import com.dwprod.librariumscanner.ui.connect.ServerConnectScreen
import com.dwprod.librariumscanner.ui.connect.ServerConnectViewModel
import com.dwprod.librariumscanner.ui.detail.BookDetailScreen
import com.dwprod.librariumscanner.ui.detail.BookDetailViewModel
import com.dwprod.librariumscanner.ui.edit.BookEditScreen
import com.dwprod.librariumscanner.ui.edit.BookEditViewModel
import com.dwprod.librariumscanner.ui.scan.ScanViewModel
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * The root switch.
 *
 * Port of `ContentView`'s account-driven shell choice
 * (`librarium-ios-main/Librarium/ContentView.swift`): with no usable account
 * there is nothing to render, so connect comes first; with one, the collection
 * shell owns the screen and the scanner is presented over it.
 *
 * The session store is the single source of truth for which branch shows — the
 * connect screen never navigates, it just makes an account exist.
 *
 * ### Scoping the shared collection VM
 *
 * A `viewModel()` inside a `composable()` destination scopes to that
 * destination, so each of {collection, detail, edit} would mint its own
 * CollectionViewModel. The shell-scoped owner via [LocalViewModelStoreOwner]
 * keeps the one instance the pager panes share, exactly as iOS keeps a single
 * view model per collection tab.
 */
@Composable
fun RootShell(container: AppContainer) {
    val session by container.sessionStore.state.collectAsState(initial = SessionState())

    if (session.primary == null) {
        val connectVm: ServerConnectViewModel = viewModel(
            factory = ServerConnectViewModel.Factory(container.accountManager)
        )
        // Edge-to-edge is enabled at the activity, so every shell branch is
        // responsible for clearing the system bars itself.
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            ServerConnectScreen(viewModel = connectVm, onConnected = { /* session flow re-emits */ })
        }
        return
    }

    ShellNavigate(container)
}

/**
 * The signed-in shell: a [NavHost] whose back stack is
 *
 *   collection → book/{id} → edit
 *
 * with scan as a boolean overlay over it, as before — the scan camera's
 * lifecycle is simpler to reason about as the [ScanFlowRoute] branch than as
 * another destination (the camera must not be destroyed for the result sheet's
 * own composition).
 */
@Composable
private fun ShellNavigate(container: AppContainer) {
    val nav = rememberNavController()
    val colors = LocalLibrariumTheme.current.colors
    var scanning by remember { mutableStateOf(false) }
    val scanVm: ScanViewModel = viewModel(factory = ScanViewModel.Factory(container))
    // The edit screen's VM lives at shell scope too: destination-scoped ones
    // would lose the vocabularies on a configuration change mid-form.
    // Also the collection VM is explicitly fetched through a store owner.
    val shellOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current

    val collectionVm: CollectionViewModel = viewModel(
        modelClass = CollectionViewModel::class.java,
        viewModelStoreOwner = shellOwner ?: error("No view model store owner"),
        factory = CollectionViewModel.Factory(container),
    )
    val editVm: BookEditViewModel = viewModel(
        modelClass = BookEditViewModel::class.java,
        viewModelStoreOwner = shellOwner ?: error("No view model store owner"),
        factory = BookEditViewModel.Factory(container),
    )

    // systemBarsPadding keeps the status bar (clock/battery) and the gesture
    // nav bar clear; the dark app background shows behind both.
    Box(Modifier.fillMaxSize().background(colors.appBackground).systemBarsPadding()) {
        NavHost(navController = nav, startDestination = "collection") {
            composable("collection") {
                com.dwprod.librariumscanner.ui.collection.CollectionScreen(
                    viewModel = collectionVm,
                    onScan = {
                        scanVm.resetForNextScan()
                        scanning = true
                    },
                    onOpenBook = { book ->
                        nav.navigate("detail/${book.libraryId}/${book.id}")
                    },
                    onOpenSeries = { series ->
                        nav.navigate("series/${series.id}/${com.dwprod.librariumscanner.core.remote.enc(series.name.ifBlank { "Series" })}/${collectionVm.selectedLibraryId.value ?: ""}")
                    },
                    onOpenAuthor = { author ->
                        nav.navigate("author/${author.id}/${com.dwprod.librariumscanner.core.remote.enc(author.name.ifBlank { "Author" })}")
                    },
                )
            }

            composable("detail/{libraryId}/{bookId}") { entry ->
                val libraryId = entry.arguments?.getString("libraryId") ?: ""
                val bookId = entry.arguments?.getString("bookId") ?: ""
                val detailVm: BookDetailViewModel = viewModel(
                    factory = BookDetailViewModel.Factory(container, libraryId, bookId),
                    key = "detail-$libraryId-$bookId",
                )
                BookDetailScreen(
                    viewModel = detailVm,
                    onEdit = { book, library ->
                        // The edit VM is shell-scoped, so a prior form must not
                        // leak into this one.
                        editVm.startEdit(book, library)
                        nav.navigate("edit")
                    },
                    onDeleted = {
                        collectionVm.onBookDeleted()
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("edit") {
                // The form state was seeded by the caller (detail's edit
                // button); re-seeding here would wipe typed text on rotation.
                BookEditScreen(
                    viewModel = editVm,
                    editing = null,
                    libraryId = "",
                    onSaved = { _ ->
                        collectionVm.tickReload()
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("series/{seriesId}/{seriesName}/{libraryId}") { entry ->
                val id = entry.arguments?.getString("seriesId") ?: ""
                val rawName = entry.arguments?.getString("seriesName") ?: ""
                val lib = entry.arguments?.getString("libraryId") ?: ""
                val displayName = java.net.URLDecoder.decode(rawName.ifBlank { "Series" }, "UTF-8")
                com.dwprod.librariumscanner.ui.collection.SeriesDetailScreen(
                    seriesId = id,
                    seriesName = displayName,
                    libraryId = lib,
                    container = container,
                    onOpenBook = { book -> nav.navigate("detail/${book.libraryId}/${book.id}") },
                    onBack = { nav.popBackStack() },
                )
            }

            composable("author/{authorId}/{authorName}") { entry ->
                val id = entry.arguments?.getString("authorId") ?: ""
                val name = java.net.URLDecoder.decode(
                    entry.arguments?.getString("authorName") ?: "", "UTF-8",
                )
                com.dwprod.librariumscanner.ui.collection.AuthorDetailScreen(
                    authorId = id,
                    authorName = name,
                    container = container,
                    onOpenBook = { book -> nav.navigate("detail/${book.libraryId}/${book.id}") },
                    onBack = { nav.popBackStack() },
                )
            }
        }

        if (scanning) {
            // Back handling and the refresh-on-close live inside
            // ScanFlowRoute, so the gesture and the ✕ behave identically.
            ScanFlowRoute(
                viewModel = scanVm,
                collectionVm = collectionVm,
                onFinish = { scanning = false },
            )
        }
    }
}
