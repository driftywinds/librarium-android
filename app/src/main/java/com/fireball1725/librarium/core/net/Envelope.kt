// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.core.net

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Port of `APIClient.Envelope` (`APIClient.swift:164-166`).
 *
 * Every 2xx body from librarium-api is wrapped as `{"data": <T>}` and unwrapped
 * before the caller sees it. Writing this once is the whole reason a hand-rolled
 * client is worth it here: with Retrofit every one of the ~102 endpoints would
 * need its own envelope wrapper type.
 */
@Serializable
data class Envelope<T>(val data: T)

/**
 * `{"data": {"data": <T>}}` — the shape only `/api/v1/me/books/facets` uses
 * (`MeBrowseService.swift:64-74`). Kept as a named type because an endpoint whose
 * envelope differs from every other endpoint's is exactly the thing that gets
 * "simplified" away by someone who has not read the service.
 */
@Serializable
data class DoubleEnvelope<T>(val data: Envelope<T>)

/**
 * Port of `Paged<T>` (`Models/Library.swift`). `perPage` reaches the wire as
 * `per_page` through [LIBRARIUM_JSON]'s snake-case strategy — no `@SerialName`
 * needed, because `perPage`→`per_page` is a real camel-case boundary.
 */
@Serializable
data class Paged<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 20,
)

/**
 * The shared wire contract, mirroring the encoder/decoder pair at
 * `APIClient.swift:62-72`:
 *
 * ```swift
 * encoder.keyEncodingStrategy = .convertToSnakeCase
 * decoder.keyDecodingStrategy = .convertFromSnakeCase
 * ```
 *
 * The defaults below are chosen so this decoder behaves like Swift's typed
 * `decodeIfPresent` plus a defaulted property, which is how nearly every model in
 * the iOS app reads its optional keys:
 *
 * - [JsonNamingStrategy.SnakeCase] is the analogue of `convertFromSnakeCase`.
 *   Any field where that strategy produces the wrong key carries an explicit
 *   `@SerialName` instead — see [Envelope] users and the edition models, where
 *   `isbn10` must reach the wire as `isbn_10` and the strategy would emit
 *   `isbn10`. Swift's comment on the same problem is in `Book.swift:136-138`.
 * - `coerceInputValues` turns an explicit `null` into the field's default rather
 *   than throwing. Swift's `.decodeIfPresent` has the same effect. Without this a
 *   Go handler that serialises a nil slice as `null` would take a whole screen
 *   down.
 * - `ignoreUnknownKeys` so a newer server can add a field without breaking an
 *   older client. The server gates versions via 426 for *shape* changes; this
 *   covers additive ones.
 * - `explicitNulls = false` so fields left at their defaults are omitted on the
 *   way out, matching Swift's `encodeIfPresent`/optional behaviour.
 */
@OptIn(ExperimentalSerializationApi::class)
val LIBRARIUM_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
}

/**
 * The same contract but without the snake-case strategy.
 *
 * Needed in exactly one place, and for the same reason the iOS app has a
 * counterpart: a book editor that round-trips a dictionary through the shared
 * decoder would double-convert `isbn_10`. The iOS app hits this in
 * `LiteBookWriter.swift:345`; here it is used by the add-book writer when it
 * re-reads its own request body.
 */
val LIBRARIUM_JSON_VERBATIM: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
