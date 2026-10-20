// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.fireball1725.librarium.ui.scan.BarcodeCamera
import com.fireball1725.librarium.ui.scan.ScanPhase
import com.fireball1725.librarium.ui.scan.ScanResultView
import com.fireball1725.librarium.ui.scan.ScanViewModel
import com.fireball1725.librarium.ui.theme.LocalLibrariumTheme

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
    onFinish: () -> Unit,
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val selectedKey by viewModel.selectedLibraryKey.collectAsStateWithLifecycle()
    val ownership by viewModel.ownership.collectAsStateWithLifecycle()
    val mediaTypes by viewModel.mediaTypes.collectAsStateWithLifecycle()

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
            is ScanPhase.Scanning -> ScanOverlay("Point at the ISBN barcode")
            // No barcode in hand at this phase, so the hint stands in where the
            // result sheet would name the isbn it is working on.
            is ScanPhase.LookingUp -> ScanOverlay("Looking it up…")
            is ScanPhase.Result -> ScanResultView(
                phase = current,
                libraries = libraries,
                selectedLibraryKey = selectedKey,
                ownership = ownership,
                mediaTypes = mediaTypes,
                onAdd = { status, mediaTypeId ->
                    viewModel.addBook(current.isbn, mediaTypeId, status)
                },
                onScanAnother = viewModel::resetForNextScan,
                onClose = onFinish,
                onManualEntry = { /* surfaced by the no-match screen */ },
                onOpenBook = { _, _ -> onFinish() },
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

