// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme
import kotlinx.coroutines.launch

/**
 * "Add server" / first-run connect screen.
 *
 * Port of `AddServerView` (`Views/AddServerView.swift`) with the same two
 * sections and the same setup-mode swap. The Lite "Use without a server" path
 * is deliberately absent: this client is server-only.
 */
@Composable
fun ServerConnectScreen(
    viewModel: ServerConnectViewModel,
    onConnected: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.connected) {
        // One-shot: the caller swaps the whole shell, so this screen is gone on
        // the next frame. Kept as a side effect rather than navigation so the
        // session is the single source of truth for which shell is showing.
        LaunchedConnected(onConnected)
    }

    val colors = LocalLibrariumTheme.current.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.appBackground)
            // The keyboard is the reason this screen scrolls at all: the
            // username/password fields sit low, and without the IME inset the
            // scrollable area keeps its full height and they end up underneath
            // the keyboard. Padding first, scrolling inside it, so the fields
            // can move above the keyboard rather than behind it.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(38.dp))

        // ---- Hero ----------------------------------------------------------
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("▤", fontSize = 56.sp, color = colors.accent)
            Spacer(Modifier.height(10.dp))
            Text(
                "Librarium",
                color = colors.appText,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.heroBlurb,
                color = colors.appText2,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            // The build's own version, so "which APK is installed" is
            // answerable from the phone without leaving the app. It is the
            // manifest's versionName, i.e. exactly what a release job stamped.
            Text(
                "v${com.dwprod.librariumscanner.BuildConfig.VERSION_NAME}",
                color = colors.appText3,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(30.dp))

        // ---- Server --------------------------------------------------------
        SectionLabel("SERVER")
        Field(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = "https://librarium.example.com",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(10.dp))
        Field(
            value = state.serverName,
            onValueChange = viewModel::onServerNameChange,
            label = "Server name (optional)",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
        )

        Spacer(Modifier.height(22.dp))

        // ---- Sign in / create admin ---------------------------------------
        SectionLabel(if (state.setupMode) "CREATE ADMIN ACCOUNT" else "SIGN IN")
        Field(
            value = state.identifier,
            onValueChange = viewModel::onIdentifierChange,
            label = if (state.setupMode) "Username" else "Username or email",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
        )

        if (state.setupMode) {
            Spacer(Modifier.height(10.dp))
            Field(
                value = state.setupEmail,
                onValueChange = viewModel::onSetupEmailChange,
                label = "Email",
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(10.dp))
            Field(
                value = state.setupDisplayName,
                onValueChange = viewModel::onSetupDisplayNameChange,
                label = "Display name (optional)",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            )
        }

        Spacer(Modifier.height(10.dp))
        Field(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = if (state.setupMode) "Password (min 8 characters)" else "Password",
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = if (state.setupMode) ImeAction.Next else ImeAction.Go,
        )

        if (state.setupMode) {
            Spacer(Modifier.height(10.dp))
            Field(
                value = state.setupConfirmPassword,
                onValueChange = viewModel::onSetupConfirmChange,
                label = "Confirm password",
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Go,
            )
        }

        // ---- Error ---------------------------------------------------------
        state.error?.let { error ->
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bad.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .border(0.5.dp, colors.bad.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("⚠", color = colors.bad, fontSize = 14.sp)
                Spacer(Modifier.size(8.dp))
                Text(error, color = colors.bad, fontSize = 13.sp)
            }
        }

        // ---- Submit --------------------------------------------------------
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = viewModel::connect,
            enabled = state.canSubmit,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = Color.White,
                disabledContainerColor = colors.accent.copy(alpha = 0.4f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text(state.buttonLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalLibrariumTheme.current.colors
    Text(
        text,
        color = colors.appText3,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    // The keyboard covers the lower half of a phone; a field it covers is a
    // field the reader cannot see what they typed into. `imePadding` has
    // already shrunk the scroll viewport, so asking the field to come into
    // view is what actually moves it above the keyboard.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(label, color = colors.appText3, fontSize = 14.sp) },
        singleLine = true,
        textStyle = TextStyle(color = colors.appText, fontSize = 15.sp),
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.appLine,
            focusedContainerColor = colors.appCard,
            unfocusedContainerColor = colors.appCard,
            cursorColor = colors.accent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focus ->
                if (focus.isFocused) {
                    scope.launch {
                        // After the IME reports its height, not before: the
                        // viewport has not shrunk yet at the instant of focus,
                        // so bringing it into view then scrolls by nothing.
                        kotlinx.coroutines.delay(150)
                        bringIntoView.bringIntoView()
                    }
                }
            },
    )
}

/**
 * Fires [onConnected] once. `LaunchedEffect` on a constant key gives exactly
 * one invocation per composition, which is what a terminal state needs.
 */
@Composable
private fun LaunchedConnected(onConnected: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onConnected() }
}
