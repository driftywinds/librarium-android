// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.core.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dwprod.librariumscanner.core.net.ApiException
import com.dwprod.librariumscanner.core.net.LibrariumApi
import com.dwprod.librariumscanner.core.remote.LibrariumApi2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val Context.sessionStore by preferencesDataStore(name = "librarium_session")

/**
 * Persists [SessionState] as JSON behind DataStore.
 *
 * Why one JSON blob rather than a key per field: the state is a list of
 * structured accounts, and splitting it across keys means a partially-written
 * update can pair an account with another account's tokens. Writing the list
 * atomically is the cheaper way to be correct, which is the same argument the
 * iOS source makes for storing `ServerAccountMeta` in one archive.
 */
class SessionStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val key = stringPreferencesKey("session_state_v1")

    val state: Flow<SessionState> = context.sessionStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString(SessionState.serializer(), it) }.getOrNull() }
            ?: SessionState()
    }

    suspend fun current(): SessionState = state.first()

    /** Atomic replace. Every mutation funnels through here. */
    suspend fun update(transform: (SessionState) -> SessionState) {
        context.sessionStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString(SessionState.serializer(), it) }.getOrNull()
            } ?: SessionState()
            prefs[key] = json.encodeToString(SessionState.serializer(), transform(current))
        }
    }
}

/**
 * Owns the signed-in accounts and builds authenticated clients for them.
 *
 * Port of `AppState`'s account responsibilities (`AppState.swift`), minus Lite
 * mode. One instance lives for the process, created in [com.dwprod.librariumscanner.core.di.AppContainer].
 *
 * ### Single-flight refresh
 *
 * The iOS architecture doc flags the failure this prevents: if several requests
 * 401 at once and each fires its own refresh, the server revokes every session
 * but one — a refresh **invalidates the access token it just replaced**, so
 * racing refreshes are lost sessions by construction. [refresh] serialises on
 * [refreshMutex] and re-reads the token under the lock, so every waiter reuses
 * the winner's token rather than refreshing again.
 */
class AccountManager(private val store: SessionStore) {

    /** In-memory snapshot; the flow is the source of truth for the UI. */
    @Volatile
    var snapshot: SessionState = SessionState()
        private set

    private val refreshMutex = Mutex()
    private val okHttp by lazy {
        com.dwprod.librariumscanner.core.net.defaultOkHttpClient()
    }

    suspend fun load() {
        snapshot = store.current()
    }

    val accounts: List<StoredAccount> get() = snapshot.accounts
    val primary: StoredAccount? get() = snapshot.primary

    /** Adds a signed-in account and makes it primary if none was. */
    suspend fun add(account: StoredAccount) {
        store.update { state ->
            val without = state.accounts.filterNot { it.id == account.id }
            SessionState(
                accounts = without + account,
                primaryAccountId = state.primaryAccountId ?: account.id,
                activeAccountId = state.activeAccountId ?: account.id,
            )
        }
        snapshot = store.current()
    }

    suspend fun update(account: StoredAccount) {
        store.update { state ->
            state.copy(accounts = state.accounts.map {
                if (it.id == account.id) account else it
            })
        }
        snapshot = store.current()
    }

    /** Removing the active/primary account promotes the next remaining one. */
    suspend fun remove(accountId: String) {
        store.update { state ->
            val rest = state.accounts.filterNot { it.id == accountId }
            SessionState(
                accounts = rest,
                primaryAccountId = when {
                    state.primaryAccountId != accountId -> state.primaryAccountId
                    else -> rest.firstOrNull()?.id
                },
                activeAccountId = when {
                    state.activeAccountId != accountId -> state.activeAccountId
                    else -> rest.firstOrNull()?.id
                },
            )
        }
        snapshot = store.current()
    }

    /** Chooses which signed-in server the global flows (scan, dashboard) use. */
    suspend fun setPrimary(accountId: String) {
        store.update { it.copy(primaryAccountId = accountId, activeAccountId = it.activeAccountId ?: accountId) }
        snapshot = store.current()
    }

    suspend fun setActive(accountId: String) {
        store.update { it.copy(activeAccountId = accountId) }
        snapshot = store.current()
    }

    /** Clears tokens only — the row stays, named and re-signable. */
    suspend fun markNeedsReauth(accountId: String) {
        store.update { state ->
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) {
                        it.copy(accessToken = "", refreshToken = "")
                    } else it
                }
            )
        }
        snapshot = store.current()
    }

    /** Signs out server-side and forgets the account locally. */
    suspend fun signOut(accountId: String) {
        val account = accounts.firstOrNull { it.id == accountId } ?: return
        runCatching { clientFor(account).logout() }
        remove(accountId)
    }

    /**
     * A client with these credentials attached, and a 401 hook wired to the
     * single-flight refresh.
     *
     * One client per call is fine — OkHttp pools connections under the hood, so
     * this is just a header bundle, not a socket factory.
     */
    fun clientFor(account: StoredAccount): LibrariumApi2 {
        val api = LibrariumApi(account.url, okHttp).apply {
            token = account.accessToken
            onUnauthorized = { refreshTokenFor(account.id) }
        }
        return LibrariumApi2(api)
    }

    /**
     * A client without credentials, for login/setup calls against a known base URL.
     */
    fun anonymousClient(baseUrl: String): LibrariumApi2 =
        LibrariumApi2(LibrariumApi(baseUrl, okHttp))

    /**
     * Single-flight access-token refresh for one account.
     *
     * Returns the new access token, or null when refresh failed — which is where
     * the caller should mark the account [StoredAccount.needsReauth] and let the
     * user sign back in.
     */
    suspend fun refreshTokenFor(accountId: String): String? = try {
        refreshMutex.withLock {
            // Another coroutine may have refreshed while we waited.
            val fresh = accounts.firstOrNull { it.id == accountId } ?: return@withLock null
            val api = LibrariumApi(fresh.url, okHttp)
            val tokens = LibrariumApi2(api).refresh(fresh.refreshToken)
            val now = System.currentTimeMillis() / 1000
            update(
                fresh.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    user = tokens.user.toStored(),
                    // `expires_in` is in seconds; epoch is too.
                    accessTokenExpiresAt = now + tokens.expiresIn,
                )
            )
            tokens.accessToken
        }
    } catch (e: ApiException.Unauthorized) {
        markNeedsReauth(accountId)
        null
    } catch (e: Exception) {
        null
    }
}

/** Server user → persisted user, so the models stay decoupled from the store. */
fun com.dwprod.librariumscanner.core.model.User.toStored() = StoredUser(
    id = id,
    username = username,
    email = email,
    displayName = displayName,
    isInstanceAdmin = isInstanceAdmin,
)

fun StoredUser.toModel() = com.dwprod.librariumscanner.core.model.User(
    id = id,
    username = username,
    email = email,
    displayName = displayName,
    isInstanceAdmin = isInstanceAdmin,
)
