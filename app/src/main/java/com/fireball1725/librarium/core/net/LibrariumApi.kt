// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.net

import com.fireball1725.librarium.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Port of `enum ClientIdentity` (`APIClient.swift:34-43`).
 *
 * The server refuses first-party clients too old to understand the shapes it
 * produces, answering 426 with a readable message rather than letting them render
 * blank panels that look like data loss. It cannot do that for a client that does
 * not say who it is, so both headers go on every session-bearing request.
 *
 * Two deliberate differences from iOS:
 *
 * 1. `name` is `android`, not `ios`. The server's gate is per-client, so claiming
 *    to be iOS would opt this build into compatibility rules written for a
 *    codebase it does not share.
 * 2. `version` comes from [BuildConfig.VERSION_NAME], read from the manifest, so
 *    it is whatever the build actually produced. iOS reads
 *    `CFBundleShortVersionString` and falls back to `0.0.0-dev`; that fallback is
 *    preserved because the server exempts it from gating, which is the behaviour
 *    a local debug build wants.
 */
object ClientIdentity {
    const val HEADER = "X-Librarium-Client"
    const val VERSION_HEADER = "X-Librarium-Client-Version"
    const val NAME = "android"

    /** `0.0.0-dev` unless the build injected a real version. */
    val VERSION: String = BuildConfig.VERSION_NAME.ifBlank { "0.0.0-dev" }
}

/**
 * Port of `final class APIClient` (`APIClient.swift:54-249`).
 *
 * One instance per server account, exactly as iOS has one per `ServerAccount`.
 * The base URL is trimmed of trailing slashes in [init] to match
 * `baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))` (`:75`).
 *
 * ### Why not Retrofit
 *
 * The iOS client is a small generic transport: four verbs, one envelope, one
 * error mapping. Retrofit would need ~102 declared endpoints plus a wrapper type
 * each to strip `{"data": …}`. The generic form below keeps the envelope, the
 * error taxonomy and the refresh path in a single readable place, which is also
 * where the two traps live.
 *
 * ### The two traps
 *
 * 1. **Refresh must be single-flight.** The iOS architecture doc flags this: if
 *    several requests 401 at once and each fires its own refresh, the server
 *    revokes every session but one. [refreshMutex] serialises refreshes and
 *    re-reads the token under the lock so the losers reuse the winner's token
 *    instead of refreshing again.
 * 2. **Note what is *not* retried.** A 404 is surfaced as [ApiException.NotFound]
 *    rather than retried, because the duplicate-ISBN cascade reads "not found" as
 *    "this library does not own this book yet" — a legitimate answer.
 */
class LibrariumApi(
    baseUrl: String,
    private val http: OkHttpClient,
) {

    /** Server origin, trailing slashes trimmed. Never has a trailing `/`. */
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    /**
     * Current bearer token. Volatile because it is replaced by the refresh path
     * on whichever coroutine the 401 landed on and read by all the others.
     */
    @Volatile
    var token: String? = null

    /**
     * Called when any request gets a 401. Should refresh auth and return the new
     * access token, or null if refresh failed — which logs the caller out
     * upstream. Mirrors `onUnauthorized` (`APIClient.swift:56-60`).
     */
    @Volatile
    var onUnauthorized: (suspend () -> String?)? = null

    private val refreshMutex = Mutex()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun urlFor(path: String): HttpUrl =
        baseUrl.toHttpUrlOrNull()?.newBuilder()?.apply {
            // `path` may already carry a query string (most list endpoints do).
            val q = path.indexOf('?')
            if (q >= 0) {
                addEncodedPathSegments(path.substring(0, q).trimStart('/'))
                encodedQuery(path.substring(q + 1))
            } else {
                addEncodedPathSegments(path.trimStart('/'))
            }
        }?.build() ?: throw ApiException.InvalidUrl()

    private fun Request.Builder.identity(): Request.Builder = apply {
        header(ClientIdentity.HEADER, ClientIdentity.NAME)
        header(ClientIdentity.VERSION_HEADER, ClientIdentity.VERSION)
        token?.let { header("Authorization", "Bearer $it") }
    }

    // ---------------------------------------------------------------- verbs

    /**
     * GET a single object. The body is unwrapped from `{"data": T}`.
     */
    suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        exchange("GET", path, null, serializer)

    suspend inline fun <reified T> get(path: String): T =
        get(path, kotlinx.serialization.serializer<T>())

    suspend fun <T, B> post(path: String, body: B, serializer: KSerializer<T>, bodySerializer: KSerializer<B>): T =
        exchange("POST", path, LIBRARIUM_JSON.encodeToString(bodySerializer, body).toRequestBody(jsonMediaType), serializer)

    suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        post(path, body, kotlinx.serialization.serializer<T>(), kotlinx.serialization.serializer<B>())

    suspend fun <T, B> put(path: String, body: B, serializer: KSerializer<T>, bodySerializer: KSerializer<B>): T =
        exchange("PUT", path, LIBRARIUM_JSON.encodeToString(bodySerializer, body).toRequestBody(jsonMediaType), serializer)

    suspend inline fun <reified T, reified B> put(path: String, body: B): T =
        put(path, body, kotlinx.serialization.serializer<T>(), kotlinx.serialization.serializer<B>())

    suspend fun <T, B> patch(path: String, body: B, serializer: KSerializer<T>, bodySerializer: KSerializer<B>): T =
        exchange("PATCH", path, LIBRARIUM_JSON.encodeToString(bodySerializer, body).toRequestBody(jsonMediaType), serializer)

    suspend inline fun <reified T, reified B> patch(path: String, body: B): T =
        patch(path, body, kotlinx.serialization.serializer<T>(), kotlinx.serialization.serializer<B>())

    suspend fun delete(path: String) = exchangeVoid("DELETE", path, null)

    suspend fun postVoid(path: String) = exchangeVoid("POST", path, null)

    suspend fun <B> postVoid(path: String, body: B, bodySerializer: KSerializer<B>) =
        exchangeVoid("POST", path, LIBRARIUM_JSON.encodeToString(bodySerializer, body).toRequestBody(jsonMediaType))

    suspend inline fun <reified B> postVoid(path: String, body: B) =
        postVoid(path, body, kotlinx.serialization.serializer<B>())

    suspend fun <B> putVoid(path: String, body: B, bodySerializer: KSerializer<B>) =
        exchangeVoid("PUT", path, LIBRARIUM_JSON.encodeToString(bodySerializer, body).toRequestBody(jsonMediaType))

    suspend inline fun <reified B> putVoid(path: String, body: B) =
        putVoid(path, body, kotlinx.serialization.serializer<B>())

    /**
     * Raw text GET, bypassing the envelope.
     *
     * Used by the cover path only: a cover URL is an image, not a JSON document,
     * and `/api/v1/books/{id}/cover` answers with bytes.
     */
    suspend fun getBytes(url: String): ByteArray = executeRaw(
        Request.Builder().url(url.toHttpUrlOrNull() ?: throw ApiException.InvalidUrl())
            .identity().build()
    ).use { it.body?.bytes() ?: ByteArray(0) }
        .also { bytes -> if (bytes.isEmpty()) throw ApiException.NotFound() }

    /**
     * Multipart upload, port of `uploadMultipart` (`APIClient.swift:113-160`).
     *
     * iOS defaults the method to `PUT` and sends a single file field; neither the
     * verb nor the field name is a guess, so neither is defaulted away here.
     */
    suspend fun uploadMultipart(
        path: String,
        method: String = "PUT",
        fieldName: String,
        fileData: ByteArray,
        fileName: String,
        mimeType: String,
        retried: Boolean = false,
    ) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, fileName, fileData.toRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url(urlFor(path))
            .method(method, body)
            .identity()
            .build()

        executeRaw(request).use { response ->
            when (val code = response.code) {
                in 200..299 -> return
                401 -> {
                    if (!retried) {
                        refreshToken()?.let { newToken ->
                            token = newToken
                            uploadMultipart(path, method, fieldName, fileData, fileName, mimeType, retried = true)
                            return
                        }
                    }
                    throw ApiException.Unauthorized()
                }
                404 -> throw ApiException.NotFound()
                else -> throw ApiException.Server(code, response.body?.string())
            }
        }
    }

    // ------------------------------------------------------------- internals

    private suspend fun <T> exchange(
        method: String,
        path: String,
        body: RequestBody?,
        serializer: KSerializer<T>,
    ): T {
        val bytes = exchangeRaw(method, path, body)
        return decodeEnvelope(bytes, serializer, path)
    }

    private suspend fun exchangeVoid(method: String, path: String, body: RequestBody?): Unit {
        exchangeRaw(method, path, body)
        return Unit
    }

    /**
     * Performs the request, maps the status, and retries once through
     * [refreshToken] on a 401. Returns the raw body bytes; the caller decides
     * whether they are an envelope.
     */
    private suspend fun exchangeRaw(method: String, path: String, body: RequestBody?, retried: Boolean = false): ByteArray {
        val request = Request.Builder()
            .url(urlFor(path))
            .method(method, body)
            .identity()
            .build()

        val response = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute()
            }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }

        response.use {
            return when (val code = it.code) {
                in 200..299 -> it.body?.bytes() ?: ByteArray(0)

                401 -> {
                    if (!retried) {
                        refreshToken()?.let { newToken ->
                            token = newToken
                            return exchangeRaw(method, path, body, retried = true)
                        }
                    }
                    throw ApiException.Unauthorized()
                }

                404 -> throw ApiException.NotFound()

                // The server's upgrade gate. Its message is for the user, so keep it.
                426 -> throw ApiException.UpgradeRequired(it.body?.string())

                else -> throw ApiException.Server(code, it.body?.string())
            }
        }
    }

    /** Executes with no status mapping beyond transport errors, for raw byte reads. */
    private suspend fun executeRaw(request: Request) =
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute()
            }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }

    /**
     * Unwraps `{"data": T}` exactly as `raw(_:method:body:)` does
     * (`APIClient.swift:206`), after the doubly-wrapped facets shape.
     */
    private fun <T> decodeEnvelope(bytes: ByteArray, serializer: KSerializer<T>, path: String): T {
        val text = bytes.decodeToString()
        return try {
            val root = LIBRARIUM_JSON.parseToJsonElement(text)
            val inner = root.jsonObject["data"]
                ?: throw ApiException.Decoding(IllegalStateException("missing \"data\" envelope at $path"))

            // `/api/v1/me/books/facets` nests a second envelope. Unwrapping it
            // here rather than at the call site means the one endpoint with a
            // different envelope does not need a different client.
            //
            // The `isEnvelopedObject()` test must come *first*. Writing this as
            // `inner.jsonObject["data"]?.takeIf { inner.isEnvelopedObject() }`
            // looks equivalent but is not: Kotlin evaluates the receiver
            // `inner.jsonObject` before `takeIf` ever runs, and `jsonObject`
            // throws on a `JsonArray`. Most list endpoints — `/lookup/isbn/{isbn}`
            // among them — answer `{"data": [ … ]}`, so that ordering made every
            // list-returning call fail with "JsonArray is not a JsonObject".
            val payload: JsonElement = if (inner.isEnvelopedObject()) {
                // Safe: guarded by the type check inside `isEnvelopedObject`.
                (inner as JsonObject)["data"] ?: inner
            } else {
                inner
            }

            LIBRARIUM_JSON.decodeFromJsonElement(serializer, payload)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.Decoding(e)
        }
    }

    /**
     * True when this `"data"` value is itself `{"data": …}`.
     *
     * Narrowed to a single key on purpose: a legitimate payload may well have a
     * field called `data` alongside others, and unwrapping that would be a bug
     * rather than a convenience.
     */
    private fun JsonElement.isEnvelopedObject(): Boolean =
        this is kotlinx.serialization.json.JsonObject && keys.size == 1 && containsKey("data")

    /**
     * Single-flight refresh. First caller refreshes; the rest wait and then read
     * whatever token is current, so a burst of 401s produces one refresh rather
     * than one per request.
     */
    private suspend fun refreshToken(): String? {
        val refresh = onUnauthorized ?: return null
        val tokenBefore = token
        return refreshMutex.withLock {
            // Another coroutine refreshed while we waited for the lock.
            val current = token
            if (current != null && current != tokenBefore) return@withLock current
            refresh()
        }
    }

    /** Debug label for logs and error text. Host only, no scheme. */
    val displayHost: String get() = baseUrl.toHttpUrlOrNull()?.host ?: baseUrl
}

private fun String.toHttpUrlOrNull(): HttpUrl? =
    try {
        toHttpUrl()
    } catch (_: Exception) {
        null
    }

/**
 * OkHttp client shared by every server account.
 *
 * The timeout is the one value here worth naming: iOS sets `timeoutInterval = 10`
 * on each request (`APIClient.swift:182`) with the reasoning that the default 60 s
 * lets "server is down but network is up" hang long past the point a person gives
 * up, while 5 s starves slow LANs and busy servers on legitimate requests like the
 * search fan-out. That reasoning transfers unchanged, so the same 10 s is used
 * rather than OkHttp's 10 s-per-phase default, which is not the same thing.
 */
fun defaultOkHttpClient(debugLogging: Boolean = BuildConfig.DEBUG): OkHttpClient =
    OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .apply {
            if (debugLogging) {
                addInterceptor(
                    okhttp3.logging.HttpLoggingInterceptor().apply {
                        level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                    }
                )
            }
        }
        .build()

private fun JsonElement.jsonObjectOrNull() = this as? kotlinx.serialization.json.JsonObject

private fun JsonElement.stringOrNull() =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
