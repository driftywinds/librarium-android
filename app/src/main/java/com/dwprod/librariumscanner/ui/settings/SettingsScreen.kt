// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dwprod.librariumscanner.core.session.StoredAccount
import com.dwprod.librariumscanner.ui.theme.LocalLibrariumTheme

/**
 * Account and server, from the drawer.
 *
 * One screen rather than iOS's profile tab: this build has no profile
 * surfaces, and the four things a reader needs to answer here — who am I, to
 * which server, on which release, and how do I leave — fit on one page.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    val theme = LocalLibrariumTheme.current
    val colors = theme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf<StoredAccount?>(null) }

    val active = state.accounts.firstOrNull { it.id == state.activeId }
        ?: state.accounts.firstOrNull { it.id == state.primaryId }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                "Settings",
                color = colors.appText,
                fontSize = theme.type.heroTitle,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(48.dp))
        }

        Spacer(Modifier.height(18.dp))

        // ---- who and where ------------------------------------------------
        SectionLabel("Account")
        Card {
            Text(
                active?.user?.displayName?.takeIf { it.isNotBlank() }
                    ?: active?.user?.username?.takeIf { it.isNotBlank() }
                    ?: "Not signed in",
                color = colors.appText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            active?.user?.username?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text("@$it", color = colors.appText3, fontSize = 12.sp)
            }
        }

        SectionLabel("Server")
        Card {
            Text(
                active?.name?.ifBlank { hostOf(active.url) } ?: "—",
                color = colors.appText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(active?.url.orEmpty().ifBlank { "—" }, color = colors.appText3, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Version", color = colors.appText3, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        state.serverVersion != null -> state.serverVersion ?: ""
                        state.versionError != null -> state.versionError ?: ""
                        else -> "Checking…"
                    },
                    color = if (state.versionError != null) colors.bad else colors.appText2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // ---- every signed-in server ---------------------------------------
        if (state.accounts.size > 1) {
            SectionLabel("Servers")
            state.accounts.forEach { account ->
                ServerRow(
                    account = account,
                    active = account.id == state.activeId || account.id == state.primaryId,
                    onUse = {
                        viewModel.switchTo(account.id, onDone = onAccountsChanged)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Add another server", colors.accentStrong) { onAddServer() }
            if (active != null) {
                Pill("Log out", colors.bad) { confirmLogout = active }
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error ?: "", color = colors.bad, fontSize = 12.sp)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Librarium for Android · v${com.dwprod.librariumscanner.BuildConfig.VERSION_NAME}",
            color = colors.appText3,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(40.dp))
    }

    confirmLogout?.let { account ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLogout = null },
            title = { Text("Log out of “${account.name}”?", color = colors.appText) },
            text = {
                Text(
                    "The session is ended on the server and the account is removed from this phone. Downloaded data is dropped.",
                    color = colors.appText2,
                )
            },
            confirmButton = {
                Text(
                    "Log out",
                    color = colors.bad,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            confirmLogout = null
                            viewModel.logOut(account.id, onDone = onAccountsChanged)
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    color = colors.appText2,
                    modifier = Modifier.clickable { confirmLogout = null }.padding(8.dp),
                )
            },
            containerColor = colors.appCard,
        )
    }
}

@Composable
private fun ServerRow(account: StoredAccount, active: Boolean, onUse: () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) colors.accent.copy(alpha = 0.16f) else colors.appCard)
            .clickable(enabled = !active) { onUse() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                account.name.ifBlank { hostOf(account.url) },
                color = colors.appText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(account.url, color = colors.appText3, fontSize = 11.sp)
        }
        if (active) {
            Text("Active", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text("Use", color = colors.accentStrong, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = LocalLibrariumTheme.current.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.appCard)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun Pill(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        color = tint,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    val theme = LocalLibrariumTheme.current
    Text(
        text.uppercase(),
        color = theme.colors.appText3,
        fontSize = theme.type.sectionLabel,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** Host only: the scheme and path are noise in a two-line label. */
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').ifBlank { url }
