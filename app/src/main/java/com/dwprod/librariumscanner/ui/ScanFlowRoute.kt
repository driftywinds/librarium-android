// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dwprod.librariumscanner.ui.collection.CollectionViewModel
import com.dwprod.librariumscanner.ui.scan.BarcodeCamera
import com.dwprod.librariumscanner.ui.scan.ScanPhase
import com.dwprod.librariumscanner.ui.scan.ScanResultView
import com.dwprod.librariumscanner.ui.scan.ScanViewModel
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * Full-screen scan cover, the entry to the whole flow.
 *
 * Ports `ScanFlow`'s phase switch (`RedesignedScanFlow.swift:590`) as a `when`
 * over [ScanPhase]. `.Result` is the only phase that composes a scrollable
 * sheet; `.Scanning`/`.LookingUp` stay full-bleed so the camera keeps running
 * underneath and returning from a result does not re-bind the camera session.
 */
@Composable
fun ScanFlowRoute(
    viewModel: ScanViewModel,
    collectionVm: CollectionViewModel,
    onFinish: () -> Unit,
    /**
     * Leaves the camera for the blank add-book form.
     *
     * A separate callback from [onFinish] because it is a different
     * destination, not just a dismissal: the shell seeds the add/edit form and
     * navigates to it. It still has to close this overlay, which is the shell's
     * job — the scan flow holds no navigation state.
     */
    onAddManually: () -> Unit,
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val selectedKey by viewModel.selectedLibraryKey.collectAsStateWithLifecycle()
    val ownership by viewModel.ownership.collectAsStateWithLifecycle()
    val mediaTypes by viewModel.mediaTypes.collectAsStateWithLifecycle()
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    val addError by viewModel.addError.collectAsStateWithLifecycle()

    // The collection must notice what a scan just added: every close out of
    // the flow (result-sheet ✕, cancel button, back gesture) bumps the reload
    // tick first, then the shell drops the overlay.
    val finish = {
        collectionVm.tickReload()
        onFinish()
    }
    // The back gesture/button cancels out of the whole flow instead of
    // leaving the app — the overlay owns the back edge while it is up.
    BackHandler(enabled = true) { finish() }

    // Leaving for the form also counts as leaving the flow, so it bumps the
    // tick too: a book added in an earlier result sheet, then abandoned by
    // tapping "Add manually", is still a change the collection has to see. The
    // form's own save path bumps it again, which is exactly right — by then
    // there is a second book.
    val leaveForForm = {
        collectionVm.tickReload()
        onAddManually()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        BarcodeCamera(
            modifier = Modifier.fillMaxSize(),
            // The camera stays composed underneath the result sheet, so its
            // single-shot latch would stay armed from the first scan onwards.
            // Keying on "is scanning" re-arms exactly when we return to the
            // camera, and not on the intermediate `LookingUp`/`Result` flips
            // where a re-arm would just let a second frame through.
            resetKey = phase == ScanPhase.Scanning,
            onScan = viewModel::onBarcode,
        )

        when (val current = phase) {
            is ScanPhase.Scanning -> {
                // Cancel is also a back gesture: the shell's BackHandler traps
                // it, so an explicit button keeps the affordance discoverable.
                CameraTopBar(onCancel = finish, onAddManually = leaveForForm)
                ScanOverlay("Point at the ISBN barcode")
            }
            // No barcode in hand at this phase, so the hint stands in where the
            // result sheet would name the isbn it is working on.
            is ScanPhase.LookingUp -> {
                CameraTopBar(onCancel = finish, onAddManually = leaveForForm)
                ScanOverlay("Looking it up…")
            }
            is ScanPhase.Result -> ScanResultView(
                phase = current,
                libraries = libraries,
                selectedLibraryKey = selectedKey,
                ownership = ownership,
                mediaTypes = mediaTypes,
                adding = adding,
                addError = addError,
                onAdd = { status, mediaTypeId ->
                    viewModel.addBook(current.isbn, mediaTypeId, status)
                },
                onScanAnother = viewModel::resetForNextScan,
                onClose = finish,
                onManualIsbn = viewModel::onManualIsbn,
                onOpenBook = { _, _ -> finish() },
                onSelectLibrary = viewModel::selectLibrary,
            )
        }
    }
}

/**
 * The camera keeps running underneath this hint; it is the only interactive
 * affordance the two early phases need.
 */
@Composable
private fun ScanOverlay(text: String) {
    val colors = LocalLibrariumTheme.current.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(bottom = 60.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * The camera phases' top bar: Cancel on the left, "Add manually" on the right.
 *
 * Two different ways off the camera that are easy to confuse, so they are
 * deliberately worded apart:
 *
 *  - Cancel leaves the whole flow (the same thing the back gesture does).
 *  - "Add manually" leaves for the `edit` destination seeded empty by
 *    `BookEditViewModel.startCreate`, which is this build's version of the web
 *    client's blank "new book" form. iOS has the same two exits but puts the
 *    blank form on the books list (`RedesignedBooksView.swift:190`), not on the
 *    camera, because its camera is a sheet with a ✕ top-right
 *    (`RedesignedScanFlow.swift:590`); carrying it here lets a reader who
 *    cannot find a barcode stop scanning and type the book in without backing
 *    out first.
 *
 * The result phase draws its own chrome, so this only ever composes over the
 * live camera.
 */
@Composable
private fun CameraTopBar(onCancel: () -> Unit, onAddManually: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                .clickable { onCancel() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⌫", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                .clickable { onAddManually() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✎", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Add manually",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}


