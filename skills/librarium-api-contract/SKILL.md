---
name: librarium-api-contract
description: Use when you need to call the Go backend (librarium-api), add a screen that reads server data, or verify an endpoint name, envelope shape, or field name before writing Kotlin.
---

# Librarium API contract

The backend is `github.com/fireball1725/librarium-api` (Go). Every screen in
this app renders server data through `core/remote/Remote.kt` on top of
`core/net/LibrariumApi.kt`. Use this skill before adding an endpoint call.

## When to load this skill

- Adding or changing an API call.
- Debugging a decode failure (usually an envelope or field-name mismatch).
- Verifying whether the server or the client owns some filtering.

## Transport rules (already implemented, do not re-litigate)

- **Base URL:** per-account, stored in the session; requests go to
  `{server}/api/v1/...`.
- **Every 2xx body is an envelope** `{"data": T}`. `LibrariumApi.decodeEnvelope`
  unwraps it. Exceptions:
  - `/me/books/facets` wraps twice (`DoubleEnvelope<T>` → `{"data":{"data":[…]}}`);
    handled inside `decodeEnvelope` via `isEnvelopedObject()`.
  - Endpoints listed below use `{"items": [...]}` or a bare array *inside* the
    envelope.
- **Auth header:** `Authorization: Bearer {access_token}`. On `401`, the caller
  triggers a single-flight refresh (`AccountManager.refreshMutex`); refresh
  failure clears tokens and flags the account `needsReauth` (the account row
  survives so the user can re-enter credentials).
- **Client identity:** requests carry a client header identifying this app
  (`ClientIdentity` in `LibrariumApi.kt`).
- **JSON:** kotlinx-serialization with SnakeCase naming,
  `ignoreUnknownKeys`, `coerceInputValues`, `explicitNulls = false`. Digit-
  adjacent keys need explicit `@SerialName` (`isbn_10`, `isbn_13`) — the
  SnakeCase strategy cannot handle a digit following an underscore.

## Envelope shapes by endpoint family

| Shape | Endpoints |
| --- | --- |
| Bare array `T[]` inside `data` | `/libraries`, `/media-types`, `/genres`, lookup lists |
| `{"items": T[]}` inside `data` | `/me/lists`, `/books/{id}/copies`, `/books/{id}/readers`, `/edition-formats`, `/libraries/{id}/books/{id}/editions` |
| `Paged<T>{items,total,page,per_page}` | `/me/books` |
| Custom pages | `SeriesIndexPage`, `AuthorIndexPage` (series and author indexes) |

## Endpoint quick reference

| Area | Endpoints |
| --- | --- |
| Auth | `POST /auth/login {identifier,password}` → `{access_token,refresh_token,expires_in,user}` (all seconds); `POST /auth/refresh`; `POST /auth/logout`; `PUT /auth/me/password` |
| Lookup | `GET /lookup/isbn/{isbn}` → provider-attributed results; `GET /lookup/books?q=` |
| Libraries | `GET /libraries`; `GET/POST/PATCH/DELETE /libraries/{id}`; `GET /libraries/{id}/books` |
| Books | `GET /me/books` (Paged, filterable); `GET/POST/PATCH/DELETE /books/{id}`; `GET /books/{id}/copies`; `POST /books/{id}/copies`; `GET/PUT /books/{id}/readers` |
| Editions | `GET /libraries/{id}/books/{id}/editions`; `GET /edition-formats` |
| Browse | `GET /me/books/facets` (double envelope); filtered `/me/books?…` |
| Series/Authors | series index + detail; author index + detail (custom pages) |
| Media types/Genres | `GET /media-types`; `GET /genres` |
| Lists/Views | `/me/lists` CRUD; saved views |
| Profile | `GET /auth/me`; avatar push; change password |
| Admin | provider admin `PUT /admin/providers/{name}` (no body → 204) |
| Interaction | `POST /books/{id}/interaction` — a 200-with-empty-body is success; treat decode failures there as swallowed |

## Rules that keep the client honest

1. **Never invent an endpoint or field name.** Verify against
   `../docs/api-contract.md` or the live `librarium-api` source.
2. **Server-side logic stays server-side.** Filtering (facets), ranking,
   ownership computation, ISBN merge rules — all server-owned. The client
   renders.
3. **Cache keys include server identity** (`{serverUrl}|{id}`). Two accounts
   on two servers must never collide.
4. **Timestamps are epoch seconds**, not milliseconds.
5. **Errors map to `ApiException`** (`Unauthorized`, `NotFound`, `Decoding`,
   …); callers branch on the type, not on message text.
