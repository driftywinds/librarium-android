// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.fireball1725.librarium.ui.RootShell
import com.fireball1725.librarium.ui.theme.LocalLibrariumTheme
import com.fireball1725.librarium.ui.theme.LibrariumTheme

/**
 * The single activity. Everything above it is Compose state, with the
 * [LibrariumTheme] tokens provided at the root the way the iOS shell roots in
 * `Theme`. A `@Composable LibrariumTheme(...)` wrapper arrives with the shell;
 * the tokens are provided directly here so the name stays owned by the token
 * bundle.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LibrariumApplication).container
        setContent {
            CompositionLocalProvider(LocalLibrariumTheme provides LibrariumTheme()) {
                RootShell(container)
            }
        }
    }
}
