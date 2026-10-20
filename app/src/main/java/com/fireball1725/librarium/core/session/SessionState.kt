// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One signed-in connection.
 *
 * Port of `ServerAccount` (`Models/ServerAccount.swift`), trimmed to remote
 * accounts: this build is server-mode only, so there is no Lite/local kind and
 * there are no `local://` synthetic URLs. The subset that matters — identity,
 * tokens, the user they belong to, and the "needs re-auth" state — is preserved.
 */
@Serializable
data class StoredAccount(
    val id: String,
    /** Chosen by the user, falls back to the host. */
    val name: String = "",
    val url: String,
    val accessToken: String = "",
    val refreshToken: String = "",
    val user: StoredUser = StoredUser(),
    /**
     * Epoch seconds when the current access token stops being valid, captured at
     * login/refresh. Null when unknown — legacy accounts, or a clock skew — and
     * treated as "refresh now to find out".
     */
    val accessTokenExpiresAt: Long? = null,
) {
    /**
     * Only the user removes a server: instead of deleting an account on auth
     * failure we surface it here and let them sign back in, because a session
     * that expired is not a server they want gone.
     */
    val needsReauth: Boolean get() = accessToken.isEmpty() || refreshToken.isEmpty()
}

/**
 * The user, as stored per account.
 *
 * Separate from [com.fireball1725.librarium.core.model.User] because that type is
 * the server's decoder; this one is what persistence round-trips and may not
 * break when a server adds a field.
 */
@Serializable
data class StoredUser(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
    val isInstanceAdmin: Boolean = false,
)

/**
 * The whole persisted session state.
 *
 * `accounts` and `accountOrder` are the same list twice on purpose: a
 * `List` does not remember the order the user arranged signed-in servers in, and
 * reordering is a real feature (the primary account drives global lookups).
 */
@Serializable
data class SessionState(
    val accounts: List<StoredAccount> = emptyList(),
    val primaryAccountId: String? = null,
    val activeAccountId: String? = null,
) {
    val primary: StoredAccount?
        get() = accounts.firstOrNull { it.id == primaryAccountId }
            ?: accounts.firstOrNull { it.id == activeAccountId }
            ?: accounts.firstOrNull()
}
