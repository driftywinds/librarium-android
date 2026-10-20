# Librarium Android

A pure-Kotlin Android client for [Librarium](https://github.com/FireBall1725/librarium-api) — the
self-hosted tracker for physical books, manga and comics. It mirrors the feature set and behavior of
the official iOS client on a phone-sized Compose UI, with the barcode scanner as its centerpiece:
point the camera at an ISBN barcode and the book lands on your shelf with its cover, title, authors
and series filled in automatically.

**Server mode only.** The app talks to a real Librarium server (`librarium-api`); there is no demo
data and no offline-only mode.

---

## Features

- **ISBN scan-to-add** — CameraX + ML Kit barcode detection (EAN-13/EAN-8/Code-128, single-shot),
  multi-provider metadata lookup, ownership check against every signed-in server, then add with the
  right media type guessed from the lookup or picked inline. Cancel via an on-screen **Cancel**
  button or the back gesture. After adding, the collection grid refreshes immediately.
- **Collection** — books grid with search, sort, and server-computed browse facets (ownership, read
  status, media type, tags, genres, ratings, …) behind the filter button. 40-per-page incremental
  paging with dedupe.
- **Series & authors** — index panes with their own search, plus detail screens (series grid,
  per-author book list).
- **Book detail** — cover hero, read-status chips (unread / want to read / reading / read), series
  attribution, edit and delete with confirmation.
- **Create / edit book** — the manual path: vocabularies (media types, tags, genres) fetched from
  the server; title, authors, series, media type and status editable.
- **Multi-server session** — connect to one or more Librarium servers; tokens refresh
  single-flight, needs-reauth accounts are flagged, library rows are stamped with the server they
  came from.
- **Hamburger navigation** — a top-left ☰ opens a collapsible left drawer (Books / Series /
  Authors + scan action); the pager and drawer stay in sync. A ＋ FAB on every pane starts a scan.
- **Dark theme** — the iOS app's dark-only token set, ported.

Not built yet (tracked in
[docs/implementation-status.md](docs/implementation-status.md)): loans, shelves/table view, tag
editor, saved views/lists, profile screens, admin, offline reading.

---

## Building

The Gradle toolchain is pinned; see [docs/dependency-versions.md](docs/dependency-versions.md) for
the full matrix and its sources. On Windows:

```powershell
# One-time toolchain bootstrap (JDK 17, Android SDK, Gradle home all vendored)
#   under .tooling/ — see skills/android-build-toolchain/SKILL.md

# Point these at whatever the bootstrap produced on your machine — none of the
# paths below are fixed; they are per-developer and machine-specific.
$env:JAVA_HOME = '<absolute path to your JDK 17 install>'
$env:ANDROID_HOME = '<absolute path to the vendored Android SDK>'
$env:GRADLE_USER_HOME = '<absolute path to a writable Gradle home>'

.\gradlew.bat --no-daemon :app:assembleDebug
```

`minSdk 26`, `targetSdk 36`, `compileSdk 37`. Output lands at
`app/build/outputs/apk/debug/app-debug.apk`. Unit tests: `:app:testDebugUnitTest`.

---

## Project layout

```
app/src/main/java/com/dwprod/librariumscanner/
├── MainActivity.kt            single activity, edge-to-edge, theme root
├── LibrariumApplication.kt    owns the AppContainer
├── core/
│   ├── di/AppContainer.kt     manual DI (no Hilt): clients, session, lookup
│   ├── net/                   OkHttp transport + {"data": T} envelope unwrap
│   ├── remote/                LibrariumApi2 endpoints, BrowseQuery builder
│   ├── model/                 wire models, ISBN lookup result types
│   ├── lookup/                multi-provider ISBN metadata merge
│   ├── scan/                  ownership checks, add-to-library, media-type guess
│   ├── session/               AccountManager, DataStore blob, server URL normalize
│   └── cache/                 JSON disk cache (per-server, key-hashed)
└── ui/
    ├── RootShell.kt           connect ↔ collection switch; scan overlay host
    ├── ScanFlowRoute.kt       scan phases, cancel/back handling, refresh hook
    ├── collection/            grid panes, drawer, series/authors detail
    ├── detail/                book detail (status chips, delete)
    ├── edit/                  create/edit form
    ├── connect/               server address + login/bootstrap
    ├── scan/                  BarcodeCamera, ScanResultView, ScanViewModel
    └── theme/                 dark-only tokens ported from the iOS Theme.swift
```

### Architecture notes

- **Manual DI.** `AppContainer` on the application object; every ViewModel takes the container (or
  its collaborators) through a `Factory`. No Hilt, no KSP, no Room.
- **The API owns the logic.** The client renders server-computed facets, counts and grouping. It
  never re-implements server-side filtering or ranking, and never invents endpoints or field names —
  anything ported from iOS cites `Swift file:line`.
- **Scan is an overlay, not a route.** The camera must survive into the result sheet
  (single-shot latch, cover art), so the scanner is a boolean overlay over the `NavHost`, not a
  navigation destination. Its back handling lives in `ScanFlowRoute`.
- **Shell-scoped view models.** `CollectionViewModel` and `BookEditViewModel` are scoped to the
  shell (not the destination) so facet state survives navigation and delete/save callbacks can
  reach it.
- **Snake-cased JSON.** `LIBRARIUM_JSON` = snake_case + ignoreUnknownKeys + explicitNulls=false;
  digit-adjacent keys carry explicit `@SerialName`.
- **No hard-coded colors.** Composables use `LocalLibrariumTheme` tokens exclusively.

---

## Skills folder

[`skills/`](skills/README.md) holds reusable, task-scoped playbooks for working on this repo — how
to read the iOS reference, the API contract, and the pinned build toolchain. Read
[`skills/README.md`](skills/README.md) before touching server-dependent code.

## Docs

Deep references in [`docs/`](docs): the iOS app architecture, the full scan flow, the API contract,
and the verified dependency matrix. [`docs/implementation-status.md`](docs/implementation-status.md)
is the always-current "where the port stands" page — read it first when resuming.

## Reference and provenance

- The iOS client is checked out read-only at `librarium-ios-main/` (git-ignored) as the
  reference implementation.
- API source dumps live under `.src-cache/` (git-ignored); the curated contract is in `docs/`.

## License

AGPL-3.0-only — every source file carries the header:

```
// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)
```
