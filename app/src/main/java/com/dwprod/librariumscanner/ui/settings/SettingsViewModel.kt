// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.session.StoredAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the settings screen shows about the session and the server behind it. */
data class SettingsState(
    val accounts: List<StoredAccount> = emptyList(),
    val activeId: String? = null,
    val primaryId: String? = null,
    /** `/health`'s version, which needs no scope to read. */
    val serverVersion: String? = null,
    val versionError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The account and server side of the app.
 *
 * The session store owns the truth; this reads a snapshot after every change
 * rather than keeping a copy that could drift. The server version comes from
 * the un-authenticated `/health`, so it is answerable even when the token is
 * the thing in doubt.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val snap = container.accountManager.snapshot
        _state.value = _state.value.copy(
            accounts = snap.accounts,
            activeId = snap.activeAccountId,
            primaryId = snap.primaryAccountId,
        )
        loadVersion()
    }

    private fun loadVersion() {
        val client = container.activeClient() ?: run {
            _state.value = _state.value.copy(
                serverVersion = null,
                versionError = "Not signed in.",
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(serverVersion = null, versionError = null)
            _state.value = try {
                _state.value.copy(serverVersion = client.healthVersion())
            } catch (e: Exception) {
                _state.value.copy(versionError = (e as? ApiException)?.display ?: e.message)
            }
        }
    }

    /** Makes one account the one every screen talks to. */
    fun switchTo(accountId: String, onDone: () -> Unit = {}) {
        if (accountId == _state.value.activeId) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            container.accountManager.setPrimary(accountId)
            container.accountManager.setActive(accountId)
            _state.value = _state.value.copy(busy = false)
            refresh()
            // After the switch, not before: a reload fired first would refetch
            // the account that is on its way out.
            onDone()
        }
    }

    /**
     * Signs out server-side and forgets the account locally.
     *
     * The shell decides what happens next: the session flow loses its primary
     * and swaps to the connect screen, so there is no navigation to get wrong
     * here.
     */
    fun logOut(accountId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            container.accountManager.signOut(accountId)
            _state.value = _state.value.copy(busy = false)
            refresh()
            onDone()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
