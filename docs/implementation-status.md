# Implementation status — librarium-android

Where the port stands, and what is left. Update this file as features land so
the next session starts from facts rather than re-reading the iOS source.

Last verified: `:app:assembleDebug :app:testDebugUnitTest` green (11 unit tests,
46.6 MB debug APK).

## Done and verified

| Area | Files | Notes |
| --- | --- | --- |
| Build | `app/build.gradle.kts`, `gradle/libs.versions.toml` | AGP 9.4.1, Kotlin 2.4.21, compileSdk 37, minSdk 26. Pinned in `dependency-versions.md`. |
| HTTP + envelope | `core/net/LibrariumApi.kt`, `core/net/Envelope.kt` | Manual OkHttp 5.5 + kotlinx-serialization. `{"data": T}` unwrap, double envelope for `/me/books/facets`, 10 s timeouts, client identity header. |
| API surface | `core/remote/Remote.kt` | `LibrariumApi2`, grouped as the iOS services are. Login/refresh/logout/password, libraries, books, copies, readers, editions, browse facets, lists, admin, setup. |
| Models | `core/model/Models.kt`, `core/model/Lookup.kt` | Includes `Library.clientKey` (`serverUrl|id`) and the `@Transient` server stamping that makes multi-server safe. |
| ISBN lookup | `core/lookup/IsbnLookupService.kt`, `core/lookup/IsbnCandidates.kt` | Field-wise merge with first-non-empty wins; `providerDisplay` = distinct credited providers joined `" + "`. Outcome split kept. |
| Scan + add | `core/scan/ScanRepository.kt`, `ui/scan/*` | CameraX + ML Kit, exactly EAN-13/EAN-8/Code-128, single-shot. Ownership fan-out per library, add with nested edition, cover best-effort, 700 ms beat. |
| Session | `core/session/SessionState.kt`, `SessionStore.kt` | `AccountManager` with `refreshMutex` single-flight refresh; needs-reauth keeps the account row. One JSON blob, not per-field keys. |
| Cache | `core/cache/JsonCache.kt`, `BookCache.kt` | Key-hashed filenames, write-temp-then-rename. |
| DI | `core/di/AppContainer.kt` | Manual container on `LibrariumApplication`. |
| Theme | `ui/theme/Theme.kt`, `ColorTokens.kt` | Dark-only tokens ported from `Theme.swift`; no hard-coded hex in composables. |
| Connect | `ui/connect/*`, `core/session/ServerUrl.kt` | Probe `/setup/status` → setup mode, else login; bootstrap creates the admin and keeps the returned tokens. |
| Shell | `ui/RootShell.kt`, `MainActivity.kt` | No account → connect; otherwise collection with the scanner reachable. |
| Tests | `app/src/test/.../ServerUrlTest.kt` | 11 cases ported from `NormalizeServerURLTests.swift`. |

## Not built yet

Ordered by what unblocks the most.

1. **Collection shell** — the real tabbed shell (Home · Collection · [Scan FAB] ·
   Views · Search) with per-tab preserved stacks. `CollectionPlaceholder` in
   `RootShell.kt` stands in today.
2. **Library screens** — libraries list, library detail, books grid/list.
3. **Book detail** — the largest single screen: metadata, copies, readers,
   editions, status, tags, lists.
4. **Create/edit/delete book** — the manual path the scan flow's "Enter
   manually" should open.
5. **Browse + facets** — facet chips and the filtered book list.
6. **Series and authors** — index pages plus detail.
7. **Search** — `lookupBooks` with the same first-wins dedup as the scan path.
8. **Loans, shelves/table, tag editor, saved views/lists.**
9. **Profile** — servers, avatar push, password change.
10. **Admin** — instance settings and provider toggles.
11. **Offline** — the per-server cache is written; no screen reads from it yet.

## Known debt

- `ui/RootShell.kt` placeholder text names itself as a placeholder; remove it
  when the collection shell lands.
- `Remote.kt`'s `exchange` helper takes an unused `path` parameter (cosmetic).
- No instrumentation tests: everything so far is pure logic, and the camera
  paths need a device or emulator to be worth writing.
- The scan result screen renders media type / status / tags inline rather than
  in sheets. This is a deliberate divergence — see
  `skills/librarium-ios-reference/SKILL.md`.

## Deliberately absent

Server mode only. Lite mode, `LiteMetadataAggregator`, `HardcoverService`,
`OpenLibraryService`, iCloud sync, Shelves screens, Members screens, and an
Admin navigation entry are all out of scope by decision, not by omission.
