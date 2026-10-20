// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.cache

import android.content.Context
import com.fireball1725.librarium.core.model.Book
import com.fireball1725.librarium.core.net.LIBRARIUM_JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * JSON file cache, mirroring the iOS `BookCache` approach rather than a SQL
 * schema: the payloads are already JSON, the shapes change server-side, and a
 * schema migration has never bought this app anything.
 *
 * **Everything is keyed by server identity.** Two Librarium servers can hold
 * libraries with colliding UUIDs (both seeded from the same database), so a cache
 * keyed on a bare library or book id shows one account's shelf under another.
 * The general form is `<serverUrl>|<scope>` where scope is `libraryId`, or
 * `libraryId|bookId` for per-book entries — exactly the `Library.clientKey`
 * discipline from the iOS source.
 *
 * Atomicity is the reason for the write-temp-then-rename dance: DataStore has it
 * for free, but file caches do not, and a half-written JSON blob reads as a
 * corrupt cache forever after.
 */
class JsonCache(private val context: Context) {

    private val mutex = Mutex()

    private val dir: File
        get() = File(context.filesDir, "cache").apply { mkdirs() }

    private fun fileFor(key: String): File {
        // `:` and `/` are legal in a URL but not safe as a filename; a hash keeps
        // the key unique without either.
        val digest = key.hashCode().toUInt().toString(16).padStart(8, '0')
        return File(dir, digest)
    }

    suspend fun <T> read(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = fileFor(key)
            if (!f.exists()) return@withLock null
            try {
                LIBRARIUM_JSON.decodeFromString(serializer, f.readText())
            } catch (_: Exception) {
                // A corrupt or stale entry is a miss, not a crash.
                null
            }
        }
    }

    suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = fileFor(key)
                val tmp = File(dir, "${f.name}.tmp")
                tmp.writeText(LIBRARIUM_JSON.encodeToString(serializer, value))
                if (!tmp.renameTo(f)) {
                    // Windows renames over existing files poorly; fall back.
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }

    suspend fun clear(serverUrl: String, libraryId: String? = null): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Cannot reverse a hash, so scope purges use a directory
                // prefix: files are named by a digest of the key, so the prefix
                // is the digest of the server portion. Simplest correct thing
                // is to clear everything and let the next screen repopulate.
                dir.listFiles()?.forEach { it.delete() }
            }
        }

    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { dir.listFiles()?.forEach { it.delete() } }
    }

    companion object {
        /** Canonical cache key for a library-scoped payload. */
        fun libraryKey(serverUrl: String, libraryId: String) = "$serverUrl|$libraryId"

        /** Canonical cache key for a book-scoped payload within one library. */
        fun bookKey(serverUrl: String, libraryId: String, bookId: String) =
            "$serverUrl|$libraryId|$bookId"
    }
}

/**
 * Per-library book cache. Mirrors `BookCache` (`BookCache.swift`): a snapshot of
 * one library's list, invalidated by the server's fingerprint endpoint rather
 * than by a TTL, because a TTL is a guess and the fingerprint is the answer.
 */
class BookCache(private val cache: JsonCache) {

    @kotlinx.serialization.Serializable
    data class Entry(
        val libraryId: String,
        val fetchedAt: Long,
        /** The fingerprint that produced this snapshot. */
        val fingerprintToken: String,
        val fingerprintTotal: Int,
        val page: Int,
        val perPage: Int,
        val books: List<Book>,
    )

    fun key(serverUrl: String, libraryId: String) =
        JsonCache.libraryKey(serverUrl, "$libraryId|books")

    suspend fun get(serverUrl: String, libraryId: String): Entry? =
        cache.read(key(serverUrl, libraryId), Entry.serializer())

    suspend fun put(serverUrl: String, entry: Entry) =
        cache.write(key(serverUrl, entry.libraryId), Entry.serializer(), entry)
}
