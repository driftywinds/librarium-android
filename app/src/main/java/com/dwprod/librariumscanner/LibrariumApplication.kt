// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.session.StoredAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Application entry point.
 *
 * Coil 3 does not ship a network fetcher in `coil-compose`: without
 * `coil-network-okhttp` on the classpath — and registered here — every remote
 * `cover_url` fails to load and the composable just shows its placeholder,
 * with no error unless something asks for it. Registering the shared
 * [OkHttpNetworkFetcherFactory] also means cover loads reuse one connection
 * pool rather than opening a second HTTP stack beside the API client.
 */
class LibrariumApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    /**
     * Boots the persisted session into memory before any screen asks for it.
     *
     * `AccountManager.snapshot` starts empty and is otherwise only refreshed by
     * the mutating calls (add/update/remove). Nothing ever called [AccountManager.load],
     * so after an install-over an existing build every synchronous reader —
     * `AppContainer.activeClient()`, the scan flow's usable-account list — saw
     * an empty snapshot and declared the user signed out, even though the
     * DataStore blob held a valid session. The shell screen reads the DataStore
     * flow directly, which is why the shell showed signed-in while the scan
     * page did not.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One client for every cover load. The factory takes a `Call.Factory`
     * supplier rather than an instance, so building a client inside the lambda
     * would mint a fresh connection pool and thread pool per image request —
     * which is exactly what a scrolling grid of covers cannot afford.
     */
    private val imageHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(CoverAuthInterceptor(::accounts))
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Fire-and-forget: the first frames are the connect screen or the
        // shell's own flow-driven render; both re-read once the snapshot
        // lands. By the time a scan or a client call runs, it is populated.
        appScope.launch { container.accountManager.load() }
    }

    /**
     * Current signed-in accounts, read through the lambda so the interceptor
     * does not pin the container construction order.
     */
    private fun accounts(): List<StoredAccount> =
        if (::container.isInitialized) container.accountManager.accounts else emptyList()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageHttpClient },
                    )
                )
            }
            // A short crossfade keeps the sheet from flashing when the cover
            // arrives a beat after the text, which is the normal case over a
            // slow provider.
            .crossfade(true)
            .build()
}

/**
 * Attaches the signed-in account's bearer token to image requests aimed at
 * that account's server.
 *
 * `GET /api/v1/books/{book_id}/cover` sits behind `requireAuth` on the server,
 * and provider-hosted covers (OpenLibrary, Google Books) must stay token-free —
 * so the match is by host, not by path. Without this, server-relative cover
 * URLs answered 401 and the composable kept its grey placeholder forever.
 */
private class CoverAuthInterceptor(
    private val accounts: () -> List<StoredAccount>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host.isEmpty()) return chain.proceed(request)
        // Only an https scheme qualifies as an app server candidate; provider
        // covers are also https, so the host comparison below does the work.
        val account = accounts().firstOrNull { stored ->
            stored.url.toHttpUrlOrNull()?.host == host
        } ?: return chain.proceed(request)
        val token = account.accessToken
        if (token.isNullOrEmpty()) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }
}
