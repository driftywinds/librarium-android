// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.session.SessionState
import com.dwprod.librariumscanner.ui.connect.ServerConnectScreen
import com.dwprod.librariumscanner.ui.connect.ServerConnectViewModel
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
 */
@Composable
fun RootShell(container: AppContainer) {
    // DataStore is a cold Flow, so the shell needs its own starting value: an
    // empty session renders the connect screen for the frame before the real
    // state arrives, which is the correct default anyway (no account yet).
    val session by container.sessionStore.state
        .collectAsStateWithLifecycle(initialValue = SessionState())
    val colors = LocalLibrariumTheme.current.colors

    // Primary, falling back to any account — the same chain `SessionState`
    // exposes, because a user who removed their primary server still has a
    // library they can scan into.
    val active = session.primary

    if (active == null) {
        val connectVm: ServerConnectViewModel = viewModel(
            factory = ServerConnectViewModel.Factory(container.accountManager)
        )
        ServerConnectScreen(viewModel = connectVm, onConnected = { /* session flow re-emits */ })
        return
    }

    var scanning by remember { mutableStateOf(false) }
    val scanVm: ScanViewModel = viewModel(factory = ScanViewModel.Factory(container))

    Box(Modifier.fillMaxSize().background(colors.appBackground)) {
        if (scanning) {
            ScanFlowRoute(viewModel = scanVm, onFinish = { scanning = false })
        } else {
            CollectionPlaceholder(
                onScan = {
                    scanVm.resetForNextScan()
                    scanning = true
                },
            )
        }
    }
}

/**
 * Stands in for the collection shell until the browse/library screens land.
 * It exists so the scan flow is reachable end to end from a real launch.
 */
@Composable
private fun CollectionPlaceholder(onScan: () -> Unit) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Text("▤", fontSize = 44.sp, color = colors.accent)
        Spacer(Modifier.height(12.dp))
        Text(
            "Your collection",
            color = colors.appText,
            fontSize = theme.type.pageTitle,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The library and browse screens are still to come. Scanning works today.",
            color = colors.appText3,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onScan,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = Color.White,
            ),
            modifier = Modifier.height(52.dp),
        ) {
            Text("Scan a book", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
