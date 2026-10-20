// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.remote.SetupAdminRequest
import com.dwprod.librariumscanner.core.session.AccountManager
import com.dwprod.librariumscanner.core.session.ServerUrl
import com.dwprod.librariumscanner.core.session.StoredAccount
import com.dwprod.librariumscanner.core.session.StoredUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives "add a server": probe, then sign in or bootstrap.
 *
 * Ports `AddServerView`'s state machine (`Views/AddServerView.swift:241-334`)
 * in the same order. The probe comes first because a fresh instance has no
 * users to log in as, and the bootstrap endpoint is the only way in; the probe
 * failing is deliberately *not* fatal — it falls through to login so the real
 * error surfaces instead of a probe error.
 */
class ServerConnectViewModel(
    private val accounts: AccountManager,
) : ViewModel() {

    data class UiState(
        val serverUrl: String = "",
        val serverName: String = "",
        val identifier: String = "",
        val password: String = "",
        val setupEmail: String = "",
        val setupDisplayName: String = "",
        val setupConfirmPassword: String = "",
        /** True when the probed server has no admin yet. */
        val setupMode: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val connected: Boolean = false,
    ) {
        /** Port of `canSubmit` (`Views/AddServerView.swift:229-239`). */
        val canSubmit: Boolean
            get() {
                if (busy) return false
                if (serverUrl.isBlank() || identifier.isBlank() || password.isEmpty()) return false
                if (setupMode) {
                    return setupEmail.isNotBlank() && setupConfirmPassword.isNotEmpty()
                }
                return true
            }

        val buttonLabel: String
            get() = when {
                setupMode -> "Create admin & connect"
                else -> "Connect"
            }

        val title: String
            get() = if (setupMode) "Set Up Server" else "Add Server"

        val heroBlurb: String
            get() = if (setupMode) {
                "This server hasn't been set up yet — create the first admin account."
            } else {
                "Connect to your server to get started."
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Tracks the hostname we last injected, so an auto-filled name can be
     * replaced by the next edit while a name the user typed is left alone.
     * Port of `lastAutoName` (`Views/AddServerView.swift:19, 54-59`).
     */
    private var lastAutoName = ""

    fun onServerUrlChange(value: String) {
        val auto = ServerUrl.defaultName(normalizeOrEmpty(value))
        _state.update { current ->
            current.copy(
                serverUrl = value,
                serverName = if (current.serverName.isEmpty() || current.serverName == lastAutoName) {
                    auto
                } else {
                    current.serverName
                },
                // Any URL edit invalidates a prior setup-mode verdict.
                setupMode = false,
                error = null,
            )
        }
        if (_state.value.serverName == auto) lastAutoName = auto
    }

    fun onServerNameChange(value: String) = _state.update { it.copy(serverName = value) }
    fun onIdentifierChange(value: String) = _state.update { it.copy(identifier = value) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }
    fun onSetupEmailChange(value: String) = _state.update { it.copy(setupEmail = value) }
    fun onSetupDisplayNameChange(value: String) = _state.update { it.copy(setupDisplayName = value) }
    fun onSetupConfirmChange(value: String) = _state.update { it.copy(setupConfirmPassword = value) }

    /**
     * Port of `connect()` (`Views/AddServerView.swift:241-261`): validate the
     * URL locally first so a typo never costs a round trip.
     */
    fun connect() {
        val s = _state.value
        if (!s.canSubmit || s.busy) return
        val url = ServerUrl.normalize(s.serverUrl) ?: run {
            _state.update { it.copy(error = "That doesn't look like a valid server address.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            try {
                if (s.setupMode) {
                    bootstrap(url)
                } else {
                    probeThenLogin(url)
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Probe first. A server that answers `initialized = false` flips into setup
     * mode and stops; the user finishes entering the admin details. A probe
     * that fails or says "initialized" proceeds to login.
     */
    private suspend fun probeThenLogin(url: String) {
        val client = accounts.anonymousClient(url)
        val status = runCatching { client.setupStatus() }.getOrNull()
        if (status != null && !status.initialized) {
            _state.update { current ->
                current.copy(
                    setupMode = true,
                    error = null,
                    // Prefill the display name from the identifier, as iOS does.
                    setupDisplayName = current.setupDisplayName.ifEmpty {
                        current.identifier.trim()
                    },
                )
            }
            return
        }

        try {
            val tokens = client.login(_state.value.identifier.trim(), _state.value.password)
            finish(url, tokens.accessToken, tokens.refreshToken, tokens.expiresIn, tokens.user)
        } catch (e: Exception) {
            _state.update { it.copy(error = e.readableMessage()) }
        }
    }

    /** Port of `performBootstrap` (`Views/AddServerView.swift:289-316`). */
    private suspend fun bootstrap(url: String) {
        val s = _state.value
        if (s.password.length < 8) {
            _state.update { it.copy(error = "Password must be at least 8 characters.") }
            return
        }
        if (s.password != s.setupConfirmPassword) {
            _state.update { it.copy(error = "Passwords do not match.") }
            return
        }
        val username = s.identifier.trim()
        val email = s.setupEmail.trim()
        val displayName = s.setupDisplayName.trim().ifEmpty { username }
        try {
            val tokens = accounts.anonymousClient(url).createFirstAdmin(
                SetupAdminRequest(
                    username = username,
                    email = email,
                    displayName = displayName,
                    password = s.password,
                )
            )
            finish(url, tokens.accessToken, tokens.refreshToken, tokens.expiresIn, tokens.user)
        } catch (e: Exception) {
            _state.update { it.copy(error = e.readableMessage()) }
        }
    }

    /**
     * Port of `finishConnection` (`Views/AddServerView.swift:318-334`). The
     * account name falls back to the host, never to an empty string — an
     * unnamed account would be unselectable in the scan flow's library list.
     */
    private suspend fun finish(
        url: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int,
        user: com.dwprod.librariumscanner.core.model.User,
    ) {
        val typedName = _state.value.serverName.trim()
        val account = StoredAccount(
            id = UUID.randomUUID().toString(),
            name = typedName.ifEmpty { ServerUrl.defaultName(url) },
            url = url,
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = StoredUser(
                id = user.id,
                username = user.username,
                email = user.email,
                displayName = user.displayName,
                isInstanceAdmin = user.isInstanceAdmin,
            ),
            // Epoch *seconds*, matching how the session store and the iOS
            // client both record expiry.
            accessTokenExpiresAt = System.currentTimeMillis() / 1000 + expiresIn,
        )
        accounts.add(account)
        _state.update { it.copy(connected = true, error = null) }
    }

    private fun normalizeOrEmpty(raw: String): String = ServerUrl.normalize(raw).orEmpty()

    /**
     * A failed request must not read as a successful one, and it must not hide
     * what went wrong either.
     *
     * The previous form fell back to one catch-all sentence for anything that
     * was not an [ApiException], which turned a diagnosable serialization or
     * programming fault into "Something went wrong connecting to that server."
     * — text that names the wrong subsystem (the server was fine) and gives the
     * user nothing to act on. Now the exception class is named when there is no
     * message, so the first failure is reportable.
     */
    private fun Throwable.readableMessage(): String = when (this) {
        is ApiException -> message ?: "Could not reach that server."
        is kotlinx.serialization.SerializationException ->
            "The server answered in a format this app did not understand. " +
                "Check that the app and the server are the same version."
        is java.net.UnknownHostException ->
            "Could not find that server. Check the address and your connection."
        is java.net.SocketTimeoutException ->
            "The server took too long to answer."
        is javax.net.ssl.SSLException ->
            "Could not make a secure connection to that server."
        else -> message?.takeIf { it.isNotBlank() }
            ?: "${this::class.simpleName ?: "Error"} while connecting."
    }

    class Factory(private val accounts: AccountManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ServerConnectViewModel(accounts) as T
    }
}
