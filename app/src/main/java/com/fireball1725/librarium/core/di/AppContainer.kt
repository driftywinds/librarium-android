// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.di

import android.content.Context
import com.fireball1725.librarium.core.cache.BookCache
import com.fireball1725.librarium.core.cache.JsonCache
import com.fireball1725.librarium.core.lookup.IsbnLookupService
import com.fireball1725.librarium.core.net.defaultOkHttpClient
import com.fireball1725.librarium.core.remote.LibrariumApi2
import com.fireball1725.librarium.core.scan.ScanRepository
import com.fireball1725.librarium.core.session.AccountManager
import com.fireball1725.librarium.core.session.SessionStore

/**
 * Hand-rolled dependency container.
 *
 * The iOS app has no DI framework either: it builds its services in `AppState`
 * and passes them down. That shape is reproduced rather than pulling in a
 * code-generating DI library, which also keeps the annotation-processor surface
 * off the build.
 *
 * `lazy` everywhere so first launch pays only for what it touches.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val okHttp by lazy { defaultOkHttpClient() }

    val sessionStore by lazy { SessionStore(appContext) }

    val accountManager by lazy { AccountManager(sessionStore) }

    val jsonCache by lazy { JsonCache(appContext) }

    val bookCache by lazy { BookCache(jsonCache) }

    val isbnLookupService by lazy { IsbnLookupService() }

    val scanRepository by lazy { ScanRepository(isbnLookupService) }

    /**
     * A client for the primary account — the one global flows (scan, dashboard)
     * use. Null when nobody is signed in.
     */
    suspend fun primaryClient(): LibrariumApi2? =
        accountManager.primary?.let { accountManager.clientFor(it) }

    /**
     * A client for the account the user has open, when signed in and valid.
     * Re-auth state short-circuits: a client whose tokens are gone cannot be
     * refreshed by hooking it up again.
     */
    suspend fun activeClient(): LibrariumApi2? {
        val id = accountManager.snapshot.activeAccountId ?: return primaryClient()
        val account = accountManager.accounts.firstOrNull { it.id == id } ?: return primaryClient()
        if (account.needsReauth) return null
        return accountManager.clientFor(account)
    }
}
