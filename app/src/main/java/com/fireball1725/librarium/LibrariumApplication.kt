package com.fireball1725.librarium

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.fireball1725.librarium.core.di.AppContainer
import okhttp3.OkHttpClient

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

    // One client for every cover load. The factory takes a `Call.Factory`
    // supplier rather than an instance, so building a client inside the lambda
    // would mint a fresh connection pool and thread pool per image request —
    // which is exactly what a scrolling grid of covers cannot afford.
    private val imageHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

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
