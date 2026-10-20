# librarium-ios — Architecture & Feature Inventory (Android port brief)

Source of truth: `librarium-ios-main` (Swift 6 / SwiftUI, iOS 26 minimum, Xcode 26.4).
All paths are relative to `librarium-ios-main/`. Line numbers refer to this checkout.

Purpose: a Kotlin/Compose developer must be able to rebuild this app against the same
Go backend (`librarium-api`) without reading Swift. Everything below was read out of the
source. Where the source does not say something, it is listed in §10 rather than guessed.

Two editions share one codebase and one data model (`librarium-ios-main/AGENTS.md:26-29`):

* **Self-hosted** — the app is a renderer for a Librarium server. "The API owns the logic":
  filtering, ranking, faceting and natural-sort keys are computed server-side and must not be
  reimplemented on the client. One documented exception: `Librarium/Services/LocalBrowse.swift`
  for Lite libraries, which have no server.
* **Lite** — everything on-device (SwiftData), no `librarium-api` at all. Lite accounts
  get a synthetic server URL `local://<account-uuid>` (`Librarium/Models/ServerAccount.swift:64-66`).
  The repo's own documentation describes iCloud sync as part of Lite, but there is no CloudKit code
  or entitlements file in this checkout — see §10 item 9 before designing around it.

An install can hold **both** at once: `AppState.accounts` is a mixed list of `.remote` and `.local`
accounts, and almost every screen branches on which kind it was handed.

---

## 1. App topology

### 1.1 Entry point

| Layer | File | Notes |
| --- | --- | --- |
| `@main` | `Librarium/LibrariumApp.swift:6` | `@State private var appState = AppState()`, builds the SwiftData `ModelContainer`, prints the registered editorial font families in DEBUG (`:34-45`) |
| SwiftData schema | `Librarium/LibrariumApp.swift:13-25` | 11 model types: `PersistedInteraction`, `PendingSyncOp`, `PersistedLibrary`, `PersistedBook`, `PersistedSeries`, `PersistedSeriesEntry`, `PersistedSeriesArc`, `PersistedBookEditions`, `PersistedBookShelves`, `PersistedTag`, `PersistedMediaType` |
| Container failure | `Librarium/LibrariumApp.swift:26-31` | `fatalError("Could not create ModelContainer: …")` — the app refuses to start without a local store |
| Scene | `Librarium/LibrariumApp.swift:47-53` | `WindowGroup { ContentView().environment(appState).modelContainer(modelContainer) }` |

`AppState` is created once at app level and injected through the environment, never
re-created by a screen. Android equivalent: a single app-scoped state holder (e.g. a
`LibrariumApplication` singleton or a top-level `ViewModel` retained by the activity),
not per-screen instances.

### 1.2 Root switch

`Librarium/ContentView.swift` is the only place the two top-level modes are chosen:

```swift
// Librarium/ContentView.swift:78-85
if appState.isAuthenticated { RedesignedAppShell() } else { AddAccountFlow() }
```

Around it:

* `BrandSplashView` overlay on **every** cold launch (`ContentView.swift:30-56`) — the splash is not
  skipped for returning users; it covers the first paint while `loadAccounts()` and the first
  network calls settle.
* `.task(id: appState.isAuthenticated)` → `runSyncAllAccounts()` (`ContentView.swift:87-129`).
* `maybeShowTelemetryOptIn(source:)` (`ContentView.swift:65-76`) — one-shot upgrade path for users
  who authenticated on a build that predates the opt-in stage of OOBE.
* `.onChange(of: scenePhase)`: on `.active`, `appState.reloadUnavailableTokens()` then the same sync
  drain. This is the "returning to foreground after Keychain unlock / network recovery" path.

`runSyncAllAccounts()` (`ContentView.swift:92-129`) — for each `.remote` account, in parallel
(`withTaskGroup`): `proactivelyRefreshIfNeeded` → `SyncService(...).syncOnce()`; then
`refreshOfflineCaches()` (`ContentView.swift:137-164`) walks every library the user opted into
"keep offline", skips `local://` URLs and non-remote accounts, and calls
`LibraryOfflineStore.shared.syncBooks(...)`. Whole function early-returns when `appState.isOffline`.

### 1.3 Shell / navigation structure

`Librarium/Redesigned/RedesignedAppShell.swift` (257 lines) is the authenticated shell. Its docblock
(`:6-28`) is the specification:

> 5-slot redesigned app shell: Home · Collection · [Scan] · Views · Search.

Structural decisions the Android port must make deliberately, because Compose's `NavigationBar` +
`NavHost` will **not** give you them for free:

1. **No `TabView`.** The bar is a hand-rolled floating `EditorialTabBar`
   (`RedesignedAppShell.swift:172-255`): a 320×64 `.ultraThinMaterial` pill with five slots —
   Home (`house.fill`), Collection (`books.vertical.fill`), a 56 pt centre Scan FAB
   (`barcode.viewfinder`, indigo gradient, accessibility label "Scan a book", `:242-255`),
   Views (`bookmark.fill`), Search (`magnifyingglass`). Icons are forced to a 26×22 frame so the
   optical weights match. Rationale at `:10-15`: iOS 26's `TabView` ships its own floating bar that
   `.toolbar(.hidden, for: .tabBar)` does not suppress reliably.
2. **Every tab's tree stays alive.** All four surfaces are stacked in a `ZStack` and shown with
   `.opacity(selectedTab == … ? 1 : 0)` + `.allowsHitTesting(…)` (`:63-86`) rather than swapped with
   `if/else`. That is what preserves each tab's internal state and its `NavigationStack` back stack
   across switches. In Compose: keep four `NavHost`s (or four saveable back stacks) alive and
   toggle visibility, instead of one `NavHost` with a shared back stack.
3. **`isActive` gates loading.** `RedesignedCollectionView(isActive:)` and
   `RedesignedViewsView(isActive:)` only run their `.task` when their tab is selected
   (`RedesignedViewsView.swift:67-70`). Without this, four tabs fire network at launch.
4. **`.id(appState.activeSource?.id)` on the whole tab stack** (`RedesignedAppShell.swift:93`,
   rationale `:87-92`). Changing the active collection rebuilds all four trees from scratch, because
   every tab holds "its own loaded books, counts, filters and navigation stack, and all of them
   belong to the collection they came from: a library filter is another server's UUID, a saved view
   is another server's row". Compose equivalent: key the whole tab subtree on the active source id
   so it recomposes from zero (`key(activeSourceId) { … }`).
5. **Bottom inset is reserved twice** (`:100-101`): `.safeAreaPadding(.bottom, 90)` **and**
   `.contentMargins(.bottom, 90, for: .scrollContent)`. 64 pt bar + 26 pt gap. Both are needed
   because `Form`/`ScrollView` use an internal scroll view that ignores the SwiftUI safe-area bump.
6. **Source picker replaces the shell, not overlays it**, when no collection is chosen
   (`:42-51`, `appState.needsSourceChoice`): "a tab bar over a screen that has no source to read is
   four tabs that cannot answer anything". A second, sheet-presented picker exists for mid-session
   switching (`:114-117`, reached through the `switchSource` environment action).
7. **Scan is a `fullScreenCover`, not a tab** (`:118-137`). `onOpenBook` closes the scanner, switches
   the active source if the found book lives on a different account, records `pendingOpenBookID` and
   selects Collection.

Profile is **not** a tab: it is a push off the Home avatar (`:22-26`). Shelves and Members have no
screen at all — see §9.

### 1.4 Screen composition / navigation graph

```
ContentView
├── AddAccountFlow (unauthenticated)
│   ├── ChooseAccountTypeView            .choice
│   ├── AddServerView(isFirstTime:)      .server
│   └── LiteNamePromptView               .liteName
└── RedesignedAppShell (authenticated)
    ├── SourcePickerView                 (when needsSourceChoice, replaces shell)
    ├── Home tab        RedesignedHomeView (own NavigationStack)
    │   ├── → RedesignedBookDetailView(library:book:)
    │   ├── → RedesignedProfileView → ProfileView / LiteAccountSettingsView / TelemetrySettingsView
    │   │                            / LiteMetadataSourcesView / AddAccountFlow (sheet)
    │   ├── sheet ReauthSheet(account:)
    │   └── sheet SourcePickerView (via switchSource)
    ├── Collection tab  RedesignedCollectionView  (runs 3 surfaces in a ZStack)
    │   ├── RedesignedBrowseView      books   → RedesignedBookDetailView
    │   ├── RedesignedSeriesListView  series  → RedesignedSeriesDetailView → books
    │   └── RedesignedAuthorsView     authors → books grid filtered by contributor
    ├── Scan            RedesignedScanFlow (fullScreenCover)
    ├── Views tab       RedesignedViewsView
    │   ├── saved views (smart lists)  → RedesignedBrowseView(initialSelection:)
    │   ├── → RedesignedLoansView
    │   ├── → RedesignedSuggestionsView
    │   ├── → RedesignedBrowseView(initialSelection: "own=wishlist", title: "Wishlist")
    │   └── → LibrariesBrowser → RedesignedLibrariesView → RedesignedBooksView(library:)
    └── Search tab      RedesignedSearchView (primary account only)
```

`LogicalTabPreferenceKey` (`RedesignedAppShell.swift:157-165`) lets a pushed detail view ask for a
*different* tab to be highlighted than the stack it lives on — e.g. a book opened from Home lifts the
Collection tab. Compose has no equivalent; carry an explicit "logical tab" in the shell state.

`libraryBack` (`Librarium/Views/LibraryEnvironment.swift:7-16`) is an environment closure, set by
`LibrariesBrowser` when a library is picked (`Librarium/Redesigned/LibrariesBrowser.swift:20`), that a
per-library root calls to pop the selection without owning a `NavigationStack` of its own.

### 1.5 Observable-state pattern

* `@Observable final class` (Swift Observation, not Combine) for shared state: `AppState`
  (`Librarium/AppState.swift:18`), `LibraryOfflineStore` (`Services/LibraryOfflineStore.swift:20-22`,
  `static let shared`), `NetworkMonitor`, `TelemetryService`, `LibraryOrderStore`.
* `@MainActor @Observable final class XViewModel` for per-screen state, held as
  `@State private var vm = XViewModel()` — e.g. `BrowseViewModel`
  (`Redesigned/BrowseViewModel.swift:13-15`), `RedesignedHomeViewModel` (`:41`),
  `RedesignedLibrariesViewModel` (`RedesignedLibrariesView.swift:25`).
* Views read shared state with `@Environment(AppState.self) private var appState` and local state with
  `@State`. Work happens in view models and services, never in `body` (`AGENTS.md:139-142`).
* The pattern that `@State vm` exists specifically to enable: **pull-to-refresh must not cancel the
  view model's own in-flight requests** — `RedesignedLibrariesView.swift:18-23` documents that the
  view model is owned by the view, not by the refresh closure.

Android translation: hoist view models above the composable that owns `.refreshable`, i.e. keep the
`ViewModel` in the nav graph / activity scope rather than recomposed per gesture.

---

## 2. Feature inventory

Grouped by area. "Screen" is the implementing file. "Services" are the ones whose methods that screen
actually calls (verified by reading call sites, not inferred from imports).

### 2.1 Auth & server setup

| Feature | One-line description | Screen | Services |
| --- | --- | --- | --- |
| Account-type chooser | First-run fork: connect to a server, or create an on-device library | `Views/AddAccountFlow.swift` (`.choice`) | — |
| Server sign-in / add server | Enter URL, auto-derive a name from the host, detect an uninitialised instance and offer to create the first admin, then log in | `Views/AddServerView.swift` | `SetupService`, `AuthService` |
| First-admin bootstrap | Create the instance's first admin account on a fresh server and receive tokens | `Views/AddServerView.swift:304` | `SetupService` |
| URL normalisation | Trim, prepend `https://` when the scheme is missing, strip trailing slashes while keeping a non-root path, reject non-http(s) | `Views/AddServerView.swift:349-367` + pinned by `LibrariumTests/NormalizeServerURLTests.swift` | — |
| Re-authentication | Sign back in on one server without losing its offline cache, libraries or position in the list | `Views/ReauthSheet.swift` | `AuthService` |
| OOBE telemetry stage | One-time opt-in decision before the app is used | `Redesigned/TelemetryOptInSheet.swift` | `TelemetryService` |
| Sign out of one server | Remove an account: delete Keychain tokens, purge its offline cache | `Redesigned/RedesignedProfileView.swift:75-89` | `KeychainService`, `LibraryOfflineStore` |
| Sign out of everything | Clear all accounts and all cached libraries | `Redesigned/RedesignedProfileView.swift:90-100` | `AppState.logout()` |
| Add account (in-session) | Same OOBE flow presented as a sheet | `Redesigned/RedesignedProfileView.swift:46-53` | as OOBE |

### 2.2 Libraries

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Libraries grid | 2-column editorial grid; first library spans both columns, the rest are 4:5 cards with a themed gradient and a fanned cover stack | `Redesigned/RedesignedLibrariesView.swift` | `LibraryService`, `BookService`, `LibraryOfflineStore` |
| Per-library book grid | Cover-forward grid/rows for one library, with paging, search, sort, tag/media-type filters | `Redesigned/RedesignedBooksView.swift` | `BookService`, `TagService`, `MediaTypeService`, `BookCache`, `LibraryOfflineStore` |
| Create library | Name / description / public flag | `Redesigned/CreateLibrarySheet.swift` | `LibraryService` |
| Edit library | Rename, change description or visibility | `Services/LibraryService.swift:18-22` (no shipping screen calls `update` — see §10) | `LibraryService` |
| Library entry point | Views → Libraries → library → books, with a `libraryBack` action instead of its own nav stack | `Redesigned/LibrariesBrowser.swift` | — |
| Keep library offline | Toggle full book caching for one library, with live progress and phase labels | `Redesigned/RedesignedLibrariesView.swift:181,219,701` | `LibraryOfflineStore`, `LibrarySync` |
| Library card ordering | Drag/drop order of library cards, persisted per device | `Services/LibraryOrderStore.swift` | — |
| Lite library creation | One on-device library per device, named "‹Name›'s Library" | `Views/AddAccountFlow.swift:65-78` | SwiftData |

### 2.3 Books — list, detail, create, edit, delete

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Browse books (cross-library) | The Collection › Books surface: every book in every library on every account, with 11 facet dimensions and grouping | `Redesigned/RedesignedBrowseView.swift`, `Redesigned/BrowseViewModel.swift` | `MeBrowseService`, `LocalBrowse` |
| Books grid inside a library | Legacy paging/search/sort list reused by the redesigned grid | `Redesigned/BooksViewModel.swift`, `Redesigned/BookRow.swift` | `BookService`, `TagService`, `MediaTypeService` |
| Book detail | The largest screen: hero, quick actions, edition picker + per-edition stats, series refs, contents/containers, copies, lists, loan history, own review | `Redesigned/RedesignedBookDetailView.swift` (1972 lines) | `BookService`, `BookContentsService`, `CopyService`, `ListService`, `LoanService`, `AuxiliaryCaches` |
| Add book (server) | Scan/ISBN tab + manual tab, tag + media-type pickers, cover fetch | `Views/Books/AddEditBookSheet.swift` | `BookService`, `LookupService`, `TagService`, `MediaTypeService` |
| Edit book (server) | Same sheet with `book:` set; PUT instead of POST | `Views/Books/AddEditBookSheet.swift:3-7` | `BookService` |
| Delete book (server) | Confirm, then delete and pop | `Redesigned/RedesignedBookDetailView.swift:1948` | `BookService` |
| Add book (Lite) | Title + author + media type, mints local rows | `Views/LiteManualAddSheet.swift` | `LiteBookWriter` |
| Edit book (Lite) | Lite-shaped edit sheet (no server tags/media types) | `Views/LiteEditBookSheet.swift` | `LiteBookWriter`, `BookCache` |
| Cover capture | Crop/scale a photo, downscale + re-encode, upload as multipart | `Redesigned/CoverCaptureSheet.swift` | `BookService.uploadCover` |
| Cover fetch from provider | Ask the server to pull a cover from a URL the lookup returned | `Redesigned/RedesignedBookDetailView.swift:1737` | `BookService.fetchCover` |
| Delete cover | Remove an uploaded cover | `Redesigned/RedesignedBookDetailView.swift:1737` | `BookService.deleteCover` |
| Enrich book | Fire-and-forget metadata enrichment from the server's providers | `Services/BookService.swift:87` (no screen calls it — §10) | `BookService.enrich` |
| Books fingerprints | `{total, maxUpdatedAt}` used to decide whether an offline cache is stale | `Services/BookService.swift:60` | `BookService.fingerprint` |
| Book by ISBN | Look up a book already in a library by barcode | `Services/BookService.swift:64` | `BookService.byISBN` |
| Book readers | Who else has read this book | `Redesigned/RedesignedBookDetailView.swift:1848` | `BookService.readers` |

### 2.4 Scanning

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Barcode scan | Live camera, ISBN/EAN metadata output, mask + brackets + laser overlay | `Redesigned/RedesignedScanFlow.swift`, `Views/Books/BarcodeScannerView.swift` | `LookupService`, `BookService` |
| Manual entry | Type an ISBN **or a title** when the barcode will not read | `Redesigned/RedesignedScanFlow.swift:261-310` | `LookupService`, `LiteMetadataAggregator` |
| Add found book | Create the book in the chosen library, pull its cover, optionally set read status, haptic, rescan | `Redesigned/RedesignedScanFlow.swift:1995-2023` | `BookService` |
| Duplicate detection | Ask whether the scanned ISBN is already on a shelf in any collection, and offer to open it | `Redesigned/RedesignedScanFlow.swift:2051-2067` | `BookCache`, `EditionCache` |
| Lite metadata lookup | Fan out to on-device providers and merge by priority | `Views/LiteMetadataSourcesView.swift`, `Services/LiteMetadataProvider.swift` | `OpenLibraryService`, `HardcoverService` |
| Refresh metadata (Lite) | Re-fetch and field-by-field review of incoming values, with candidate picking for books with no ISBN | `Views/LiteRefreshMetadataSheet.swift` | `LiteMetadataAggregator`, `OpenLibraryService`, `HardcoverService` |
| ISBN candidate expansion | ISBN-10 ↔ ISBN-13 and UPC-A heuristics before querying a source | `Services/ISBNCandidates.swift` | — |
| Scan cover photograph | OCR-free "scan the cover" path that captures an image and offers it as the cover | `Redesigned/CoverCaptureSheet.swift` + `RedesignedScanFlow` | `BookService.uploadCover` |

Full scan-flow spec already exists: `docs/ios-scan-flow.md` (1251 lines). Do not re-derive it.

### 2.5 Series

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Series list | The Collection › Series surface: search, sort, 9 facet dimensions, saved series views, mosaic tile | `Redesigned/RedesignedSeriesListView.swift` | `MeBrowseService`, `ListService`, `LibraryService` |
| Series detail | Hero 3-cover stack, progress card, continue CTA, volumes list | `Redesigned/RedesignedSeriesDetailView.swift` | `SeriesService`, `BookCache` |
| Series CRUD | Create/update/delete a series; add/remove volumes with positions; arcs | `Services/SeriesService.swift` | `SeriesService` |
| Series grouping tiles | Collapsed "runs" in the books grid, shaped like a book tile on purpose | `Redesigned/SeriesGroupTile.swift` | — |
| Series facets/filters | Library / reading / rating / my rating / media type / status / arcs / tag / genre + "has gaps" | `Redesigned/SeriesFilterSheet.swift`, `Models/SeriesBrowse.swift` | `MeBrowseService` |

### 2.6 Authors

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Authors index | Search + role filter over the author index; spine previews; deliberately **no** alphabet bar | `Redesigned/RedesignedAuthorsView.swift` | `MeBrowseService.authors` |
| Contributor search | Type-ahead used by the add/edit book flow | `Services/ContributorService.swift` | `ContributorService` |
| Author → books | Tap an author to see their books | `Redesigned/RedesignedAuthorsView.swift` → browse grid | `MeBrowseService` |

### 2.7 Browse & search

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Faceted browse | One `BrowseSelection` drives both the list and the facet counts | `Redesigned/BrowseFilterSheet.swift`, `Models/BookBrowse.swift` | `MeBrowseService.facets/books` |
| Saved views (smart lists) | Named filter+sort combos, saved per surface, listed vertically | `Redesigned/SavedViewsPanel.swift`, `Redesigned/SaveViewSheet.swift` | `ListService` |
| Global search | Cross-type search on the primary account: books, series, authors, "All" | `Redesigned/RedesignedSearchView.swift` | `MeBrowseService`, `ContributorService`, `BookCache` |
| Grid/rows layout | Per-device layout preference | `Redesigned/BookRow.swift:98` (`CollectionLayout`) | — |
| Grouping | Collapse series runs into group tiles | `Redesigned/BrowseViewModel.swift:44` | `MeBrowseService.grouped` |
| Filter drawers | Left = views, right = filters, opened by horizontal edge drag | `Redesigned/SideDrawer.swift` | — |

### 2.8 Loans

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Loans list (cross-library) | Every book out, on every account, in one request; overdue tinted | `Redesigned/RedesignedLoansView.swift` | `MeBrowseService.loans`, `LibraryService` |
| Per-library loans | Legacy list with include-returned toggle | `Services/LoanService.swift:7-13` | `LoanService` |
| Add / edit loan | Who it went to, when, due date, notes, with the book pre-selected | `Views/Loans/AddEditLoanSheet.swift` | `LoanService` |
| Mark returned | Resend the whole record (PATCH is replace, not merge) | `Services/LoanService.swift:19-23` | `LoanService` |
| Home loan banners | At most two out/late banners, with the rest behind Views › Loans | `Redesigned/RedesignedHomeView.swift:517-…` | `MeBrowseService` |

### 2.9 Shelves

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Book's shelf memberships | Which manual lists/shelves hold this book, shown on the detail screen | `Redesigned/RedesignedBookDetailView.swift:1748-1803` | `BookService.shelves`, `ShelfCache` |
| Shelves as saved lists | When a server has no `lists` capability, shelves are rendered through the same `SavedList` shape | `Models/SavedList.swift` (`Shelf.asSavedList`) | — |

**There is no Shelves screen** (`RedesignedAppShell.swift:103-106`). See §9.

### 2.10 Tags

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Library tags | List tags for a library, filter the books grid by one | `Redesigned/BooksViewModel.swift:88` | `TagService` |
| Create tag | Add a tag with a name and colour | `Services/TagService.swift:12` | `TagService` |
| My tags (cross-library) | All tags the user can see, for pickers | `Services/VocabularyService.swift:100-110` | `VocabularyService` |
| Tag pills | Wrapping chip layout | `Views/Components/TagPill.swift`, `Views/Components/FlowRow.swift` | — |

### 2.11 Lists & saved views

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| My lists | Smart lists (saved views) and manual lists | `Redesigned/RedesignedViewsView.swift:285-308` | `ListService` |
| Save a view | Name a filter+sort combination for the current surface | `Redesigned/SaveViewSheet.swift` | `ListService.saveView` |
| Delete a view | Remove a saved view | `Redesigned/RedesignedSeriesListView.swift:324` | `ListService.deleteView` |
| Lists holding a book | Which manual lists contain this book | `Librarium/Services/ListService.swift:44-46` | `ListService.listsHolding` |
| List icons | Named icon set shared with the web client, with emoji fallback | `Services/ListIcons.swift` | — |

### 2.12 Members

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| (none) | `MemberService` exists with `list` / `add` / `remove` / `searchUsers`, but **no screen or call site** anywhere | — | `MemberService` |

### 2.13 Profile & settings

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Profile (redesigned) | Head with generated avatar, per-server rows with status dots and library counts, app section | `Redesigned/RedesignedProfileView.swift` | `LibraryService`, `AppState` |
| Manage one server (legacy) | Rename, primary-server star, profile edit, change password, remove server, version | `Views/ProfileView.swift` | `UserService`, `AppState` |
| Lite account settings | Single "Your name" field that updates the account name, greeting and library name at once | `Views/LiteAccountSettingsView.swift` | `AppState`, SwiftData |
| Telemetry settings | Opt-in toggle + on-device log of the last 50 signals + per-signal detail | `Redesigned/TelemetrySettingsView.swift` | `TelemetryService` |
| Lite metadata sources | Enable/configure Open Library and Hardcover | `Views/LiteMetadataSourcesView.swift` | `LiteMetadataSettings`, `HardcoverService` |
| Choose collection | Switch which account/library the whole shell is scoped to | `Redesigned/SourcePickerView.swift` | `AppState` |

### 2.14 Admin

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Users | List, enable/disable (swipe), delete (swipe), add | `Views/Admin/AdminUsersView.swift` | `AdminService` |
| Providers | Enable/disable metadata providers, set API keys | `Views/Admin/AdminSettingsView.swift` | `AdminService` |

Reachability: the redesigned profile shows an "Instance admin" badge when
`primary?.user.isInstanceAdmin == true` (`Redesigned/RedesignedProfileView.swift:126-138`) but renders
no link, button or `NavigationLink` into either admin screen. The legacy `Views/ProfileView.swift:48`
only badges the row too. `AdminSettingsView` is reachable **only** as a `NavigationLink` inside
`AdminUsersView` (`Views/Admin/AdminUsersView.swift:52`). Net effect: **admin screens exist and are
fully implemented, but nothing in the shipping UI navigates to them.** See §10 for how to treat that.

### 2.15 Dashboard / stats

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Home dashboard | Greeting header, source eyebrow, saved-view chips, loan banners, re-auth banners, now-reading hero, 12-month stats card with sparkline, "jump back in", continue-series, recently-added and picks-of-the-day rails | `Redesigned/RedesignedHomeView.swift` (1234 lines) | `DashboardService`, `MeBrowseService`, `ListService`, `BookCache` |
| Stats aggregation | Client-side merge of per-account dashboard stats, with a separately-kept "remote only" slice | `Redesigned/RedesignedHomeView.swift:41,25,59,62,286-295` | `DashboardService` |
| Lite dashboard | Same layout computed from SwiftData | `Redesigned/RedesignedHomeView.swift:~207` (`loadLite`) | `BookCache`, `DashboardService` |

### 2.16 Offline

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Keep library offline | Opt-in per library, with progress + phase, fingerprint short-circuit | `Redesigned/RedesignedLibrariesView.swift` | `LibraryOfflineStore`, `LibrarySync`, `BookService.fingerprint` |
| Offline books grid | Cached libraries render SwiftData first then refresh in the background | `Redesigned/RedesignedBooksView.swift:663-709` | `BookCache`, `LibraryOfflineStore` |
| Offline search | Searches cached books per account, fabricating a library when metadata is missing | `Redesigned/RedesignedSearchView.swift:739-745` | `PersistedBook` |
| Outbox (pending writes) | Optimistic local writes queued for `/sync/apply` | `Redesigned/RedesignedBookDetailView.swift:472-544` | `PendingSyncOp`, `SyncService` |
| Sync drain | Push outbox, then pull `/sync/changes` per account with a persisted cursor | `Services/SyncService.swift` | `SyncService`, `SyncBackfill` |
| Network state | Online/offline plus per-server "unreachable for 60 s" suppression | `Services/NetworkMonitor.swift` | — |
| Server capabilities | Ask a server whether it supports work-keyed reading state (via `/health`) | `Services/ServerCapabilities.swift` | — |
| Re-auth banners | One row per unusable account, tap to sign back in | `Views/Components/ReauthBannerStack.swift` | `AppState` |

### 2.17 Adjacent areas that also need a home on Android

| Feature | Description | Screen | Services |
| --- | --- | --- | --- |
| Suggestions | "What to read next" with a reason line; interested / dismiss / block / added | `Redesigned/RedesignedSuggestionsView.swift` | `SuggestionService` |
| Wishlist | The browse grid with `own=wishlist` ticked; add/remove entries | `Redesigned/RedesignedViewsView.swift:118-130` | `WishlistService`, `MeBrowseService` |
| Edition management | List/create/update editions; page count editing from the detail screen | `Redesigned/RedesignedBookDetailView.swift:631-656` | `BookService.editions/createEdition/updateEdition` |
| Reading state | Read status, 10-point rating, progress JSONB, favourite, private notes, shared review | `Redesigned/RedesignedStatusSheet.swift`, `RedesignedRateSheet.swift`, `RedesignedProgressSheet.swift`, `ReviewSheet.swift` | outbox → `SyncService` |
| Contents / containers | "Contains" and "Bound into" (omnibus ↔ single volumes) | `Redesigned/RedesignedBookDetailView.swift:161-170` | `BookContentsService` |
| Copies | Physical copy condition, signed, notes, location | `Redesigned/RedesignedBookDetailView.swift:171-175` | `CopyService` |
| Vocabulary | Edition formats, genres, my tags | `Services/VocabularyService.swift` | `VocabularyService` |
| Covers | Authenticated cover loading, SHA-256 URL cache, generated jackets for coverless books | `Views/Components/BookCoverImage.swift`, `Views/Components/SplashPalette.swift` | `CoverCache` |
| Brand splash | Cold-launch animation: books shelve themselves, a rule draws, the wordmark sets | `Views/BrandSplashView.swift` | — |

---

## 3. Service layer

43 files under `Librarium/Services/`. Convention (`AGENTS.md:141-142`): one responsibility per
service, injected, never constructed inside a `body`. Every service is a `struct` holding an
`APIClient` except where noted.

All paths below are **quoted literally from source** and are relative to the client's base URL, which
is the server origin with no trailing slash (§7.1).

### 3.1 `APIClient` — `Services/APIClient.swift` (249 lines)

Responsibility: the only thing that talks HTTP. Owns JSON coding strategy, identity headers, the
`{"data": …}` envelope, the 10 s timeout, and the single 401 retry.

```swift
enum APIError { case invalidURL, unauthorized, notFound, serverError(Int, String?), decodingError, networkError }
```

Public API:

| Member | Line | Notes |
| --- | --- | --- |
| `init(baseURL:token:onUnauthorized:)` | `:46-76` | Trims trailing `/` from the base URL |
| `get / post / put / patch / postVoid` | `:99-105` | Typed `Decodable` in, `Encodable` out |
| `delete` | `:107` | Void |
| `uploadMultipart(_:method:fieldName:fileData:fileName:mimeType:retried:)` | `:113-160` | Defaults to `PUT`; `Content-Type: multipart/form-data; boundary=Boundary-<uuid>` |
| `onUnauthorized: (() async -> String?)?` | `:74` | Returns a replacement access token or nil; invoked at most once per request |

Not injectable-by-protocol: every service takes the concrete type. Android: a single Retrofit/OkHttp
(or Ktor) client plus an authenticator that mirrors `onUnauthorized`.

### 3.2 Auth — `Services/AuthService.swift` (20 lines)

| Method | Verb + path | Request | Response |
| --- | --- | --- | --- |
| `login(identifier:password:)` | `POST "/api/v1/auth/login"` (`:10-15`) | `LoginRequest { identifier, password }` | `AuthTokens` |
| `logout()` | `POST "/api/v1/auth/logout"` (`:17-19`) | — | void |

There is **no register endpoint in the app**. A new account is created either by an instance admin
(`AdminService.createUser`) or by bootstrapping the first admin on a fresh instance.

Token refresh is **not** in `AuthService`. It lives in `AppState`'s private
`actor RefreshCoordinator` (`AppState.swift:579+`, request at `:592-594`):
`POST "/api/v1/auth/refresh"`.

### 3.3 `SetupService` — `Services/SetupService.swift` (17 lines)

| Method | Verb + path | Response |
| --- | --- | --- |
| `status()` | `GET "/api/v1/setup/status"` (`:11`) | `SetupStatus { initialized }` |
| `bootstrapAdmin(_:)` | `POST "/api/v1/setup/admin"` (`:15`) | `AuthTokens` |

### 3.4 `LibraryService` — `Services/LibraryService.swift` (23 lines)

| Method | Verb + path |
| --- | --- |
| `list()` | `GET "/api/v1/libraries"` → **bare array** `[Library]` |
| `get(id:)` | `GET "/api/v1/libraries/{id}"` |
| `create(name:description:isPublic:)` | `POST "/api/v1/libraries"` |
| `update(libraryId:...)` | `PUT "/api/v1/libraries/{id}"` |

### 3.5 `BookService` — `Services/BookService.swift` (292 lines)

| Method | Verb + path | Notes |
| --- | --- | --- |
| `list(libraryId:page:perPage:q:tag:typeFilter:sort:sortDir:)` | `GET "/api/v1/me/books?lib=…&page=…&per_page=…"` `:25-36` | Optional `&q=`, `&tag=`, `&type_filter=`, `&sort=`, `&sort_dir=`. 404 → `MeBrowseError.serverTooOld` `:39-43` |
| `get(libraryId:bookId:)` | `GET "/api/v1/libraries/{id}/books/{id}"` `:47` | |
| `get(bookId:)` | `GET "/api/v1/books/{id}"` `:56` | For volumes nobody holds |
| `fingerprint(libraryId:)` | `GET "/api/v1/libraries/{id}/books/fingerprint"` `:60` | `{ total, maxUpdatedAt? }` |
| `byISBN(libraryId:isbn:)` | `GET "/api/v1/libraries/{id}/book-by-isbn/{isbn}"` `:64` | |
| `create(libraryId:_:)` | `POST "/api/v1/libraries/{id}/books"` `:68` | |
| `update(libraryId:bookId:_:)` | `PUT "/api/v1/libraries/{id}/books/{bookId}"` `:72` | |
| `delete(libraryId:bookId:)` | `DELETE "/api/v1/libraries/{id}/books/{bookId}"` `:76` | |
| `enrich(bookId:)` | `POST "/api/v1/books/{id}/enrich"` `:87` | Fire-and-forget |
| `uploadCover(libraryId:bookId:imageData:)` | multipart `PUT "/api/v1/libraries/{id}/books/{bookId}/cover"`, field `cover`, filename `cover.jpg`, `image/jpeg` `:92-100` | |
| `fetchCover(libraryId:bookId:url:)` | `POST "…/cover/fetch"` body `{url}` `:108-114` | |
| `deleteCover(libraryId:bookId:)` | `DELETE "…/cover"` `:117` | |
| `editions(libraryId:bookId:)` | `GET "…/editions"` `:123` | |
| `createEdition(...)` | `POST "…/editions"` `:127` | |
| `updateEdition(...)` | `PUT "…/editions/{editionId}"` `:131` | |
| `interaction(libraryId:bookId:editionId:)` | `GET "…/editions/{editionId}/my-interaction"` `:141-148` | Swallows `.decodingError` because "no interaction" arrives as `{"data": null}` |
| `updateInteraction(...)` | `PUT` same path `:151` | |
| `myBook(bookId:)` | `GET "/api/v1/books/{id}/me"` `:165` | |
| `updateMyBook(bookId:_:)` | `PUT "/api/v1/books/{id}/me"` `:173` | |
| `readers(bookId:)` | `GET "/api/v1/books/{id}/readers"` `:179-189` | `{items}` |
| `shelves(libraryId:bookId:)` | `GET "/api/v1/libraries/{id}/books/{bookId}/shelves"` `:195` | |
| `seriesRefs(libraryId:bookId:)` | `GET "…/series"` `:199` | |

Payload shapes (`:206-292`): `CreateBookRequest { title, subtitle="", mediaTypeId="",
description="", contributors:[ContributorInput], tagIds:[String], genreIds:[String], edition: CreateEditionRequest? }`;
`ContributorInput { contributorId, role, displayOrder }`;
`CreateEditionRequest` carries **explicit CodingKeys** `edition_name`, `publish_date`, `isbn_10`,
`isbn_13`, `page_count`, `copy_count`, `is_primary` with the source comment "convertToSnakeCase
won't add underscore before digits";
`UpdateMyBookRequest { readStatus?, rating?, clearRating?, isFavorite?, review?, notes? }` — `clearRating`
is separate because omission means "no change" (`:275-282`);
`UpdateInteractionRequest { readStatus, rating?, notes, review, dateStarted?, dateFinished?, isFavorite }`.

### 3.6 `MeBrowseService` — `Services/MeBrowseService.swift` (210 lines)

The cross-library browse/search API. Every method funnels 404 through `mapping()` (`:21-27`) into
`MeBrowseError.serverTooOld` so "this server predates the endpoint" is never rendered as "you have no
books".

| Method | Verb + path | Query |
| --- | --- | --- |
| `books(selection:sort:page:perPage:)` | `GET "/api/v1/me/books"` `:31-40` | `page`, `per_page`, `sort`=sort.field, `sort_dir`=sort.dir, plus the selection |
| `grouped(...)` | `GET "/api/v1/me/books/grouped"` `:47-56` | same |
| `facets(selection:)` | `GET "/api/v1/me/books/facets"` `:64-74` | **Double-wrapped**: decodes `FacetsEnvelope { data: BookFacets }` because the response is `{"data":{"data":{…}}}`, and "unwrapping once lands on an envelope rather than on the counts … a filter sheet that is silently empty" |
| `series(selection:sort:)` | `GET "/api/v1/me/series/index"` `:84-97` | Appends `sort` + `dir=desc` for every sort except `.name`; always `volumes=4` |
| `authors(libraries:roles:)` | `GET "/api/v1/me/authors/index"` `:104-113` | Comma-joined `lib` and `role`; **not** wrapped in `mapping()` |
| `loans(query:includeReturned:overdueOnly:)` | `GET "/api/v1/me/loans"` `:122-129` | `q`, `include_returned=true`, `overdue=true` |
| `counts()` | `GET "/api/v1/me/counts"` `:133-135` | `CollectionCounts { books, series, authors, loans, loansOverdue, suggestions }`, every field defaulting to 0 with an explicit `init()` |
| `static path(_:_:)` | `:141-146` | Builds queries through `URLComponents`/`percentEncodedQuery` so values are encoded the same way every time |

`MeBrowseError.serverTooOld` message (`:199-209`): `"<host> needs updating. This screen uses parts of
the API that arrived in 26.8.1."`

### 3.7 `SeriesService` — `Services/SeriesService.swift` (75 lines)

| Method | Verb + path |
| --- | --- |
| `list(libraryId:)` | `GET "/api/v1/me/series/index?lib={libraryId}&volumes=1"` (404 → `serverTooOld`) |
| `get` / `create` / `update` / `delete` | `/api/v1/libraries/{id}/series` and `…/series/{sid}` |
| `books(seriesId:)` | `GET "…/series/{sid}/books"` |
| `volumes(seriesId:)` | `GET "…/series/{sid}/volumes"` |
| `arcs(seriesId:)` | `GET "…/series/{sid}/arcs"` |
| `addBook(seriesId:bookId:position:)` | `POST "/api/v1/libraries/{id}/series/{sid}/books"` body `{bookId, position}` |
| `removeBook(seriesId:bookId:)` | `DELETE "…/series/{sid}/books/{bookId}"` |

`SeriesBody { name, description, totalCount, isComplete, status, originalLanguage, publicationYear,
demographic, genres, url, externalId, externalSource, tagIds }`.

### 3.8 `LoanService` — `Services/LoanService.swift` (76 lines)

| Method | Verb + path |
| --- | --- |
| `list(libraryId:includeReturned:)` | `GET "/api/v1/libraries/{id}/loans"` + optional `?include_returned=true` |
| `create(libraryId:_:)` | `POST "/api/v1/libraries/{id}/loans"` |
| `update(libraryId:loanId:_:)` | `PATCH "/api/v1/libraries/{id}/loans/{id}"` |
| `delete(libraryId:loanId:)` | `DELETE "/api/v1/libraries/{id}/loans/{id}"` |

Two porting traps, both in source comments: `markReturned` resends the **whole** record because
"PATCH is replace, not merge" (librarium-ios-122), and loans dropped `tags` on 2026-04-27
(librarium-ios-121) — a strict decoder broke on that. Date-only fields are formatted
`"yyyy-MM-dd"` with an `ISO8601` calendar, `en_US_POSIX` locale, `.current` timezone.
Bodies: `LoanBody { bookId, loanedTo, loanedAt, dueDate?, notes }`,
`LoanUpdateBody { loanedTo?, dueDate?, returnedAt?, notes? }`.

### 3.9 `ListService` — `Services/ListService.swift` (67 lines)

| Method | Verb + path |
| --- | --- |
| `myLists()` | `GET "/api/v1/me/lists"` → `{items:[SavedList]}` |
| `saveView(name:surface:query:)` | `POST "/api/v1/me/lists"` body `{name, kind:"smart", surface, filter:{query}, visibility:"private"}` |
| `deleteView(id:)` | `DELETE "/api/v1/me/lists/{id}"` |
| `listsHolding(bookId:)` | `GET "/api/v1/books/{id}/lists"` (manual lists only) |

`savedViews(surface:)` is a client-side filter of `myLists()` on `isSmart && surface`. Every call is
capability-gated; on 404 the app falls back to shelves (`Shelf.asSavedList`).

### 3.10 Tags, media types, vocabulary, contributors

| Service | Methods |
| --- | --- |
| `TagService` (15 lines) | `list(libraryId:)` `GET "/api/v1/libraries/{id}/tags"` `:7`; `create(libraryId:name:color:)` `POST` `:12` |
| `MediaTypeService` (9 lines) | `GET "/api/v1/media-types"` (bare array) |
| `VocabularyService` (131 lines) | `editionFormats()` `GET "/api/v1/edition-formats"` `{items}`, filtered `isActive`; `genres()` `GET "/api/v1/genres"` (bare array); `tags(query:)` `GET "/api/v1/me/tags[?q=]` |
| `ContributorService` (17 lines) | `search(query:)` `GET "/api/v1/contributors?q=<enc>"`; `create(name:)` `POST "/api/v1/contributors"` |

### 3.11 Members — `Services/MemberService.swift` (28 lines)

| Method | Verb + path |
| --- | --- |
| `list(libraryId:search:)` | `GET "/api/v1/libraries/{id}/members"` + optional `?search=` (bare array) |
| `add(libraryId:userId:roleId:)` | `POST "/api/v1/libraries/{id}/members"` |
| `remove(libraryId:userId:)` | `DELETE "/api/v1/libraries/{id}/members/{userId}"` |
| `searchUsers(query:)` | `GET "/api/v1/users?q="` |

Zero call sites. This is the most complete service with no UI (§9).

### 3.12 Users & admin

| Service | Methods |
| --- | --- |
| `UserService` (26 lines) | `updateProfile(displayName:email:)` `PUT "/api/v1/auth/me"`; `updatePassword(current:new:)` `PUT "/api/v1/auth/me/password"` → `{message}` |
| `AdminService` (39 lines) | `users(page:perPage:)` `GET "/api/v1/admin/users?page=&per_page=20"` `:9`; `createUser(...)` `POST "/api/v1/admin/users"` `:14`; `setActive(userId:isActive:)` `PATCH "/api/v1/admin/users/{id}"` `:21`; `deleteUser(userId:)` `DELETE` `:25`; `providers()` `GET "/api/v1/admin/providers"` `:31`; `updateProvider(name:enabled:apiKey:)` `PUT "/api/v1/admin/providers/{name}"` `:36` |

### 3.13 Dashboard, suggestions, wishlist, lookup

| Service | Methods |
| --- | --- |
| `DashboardService` (146 lines) | `GET "/api/v1/dashboard/currently-reading"` `:9`, `"/recently-finished"` `:13`, `"/stats"` `:17`, `"/continue-series"` `:22`, `"/recently-added"` `:26`, `"/picks-of-the-day"` `:32` |
| `SuggestionService` (80 lines) | `list()` `GET "/api/v1/me/suggestions"` (**bare array**, `:19`); `setStatus(id:status:)` `PUT "/api/v1/me/suggestions/{id}/status"` body `{status}` with `new`/`interested`/`dismissed`/`added_to_library`; `block(id:)` `POST "/api/v1/me/suggestions/{id}/block"` |
| `WishlistService` (79 lines) | `list()` `GET "/api/v1/me/wishlist"` `{items}`; `add(bookId:notes:)` `POST`; `add(title:authorName:notes:)` `POST`; `remove(entryId:)` `DELETE "/api/v1/me/wishlist/{id}"` |
| `LookupService` (22 lines) | `isbn(_:)` `GET "/api/v1/lookup/isbn/{isbn}"`; `books(query:)` `GET "/api/v1/lookup/books?q="`; `series(query:)` `GET "/api/v1/lookup/series?q="` |
| `CopyService` (42 lines) | `copies(bookId:)` `GET "/api/v1/books/{id}/copies"` `{items}` (cross-library on purpose); `update(copyId:body:)` `PATCH "/api/v1/copies/{id}"` body `{condition?, isSigned?, notes?, locationId?}` |
| `BookContentsService` (81 lines) | `contents(bookId:)` `GET "/api/v1/books/{id}/contents"`; `containers(bookId:)` `GET "/api/v1/books/{id}/containers"`; `add(containerId:containedId:position:)` `POST "/api/v1/books/{containerId}/contents"`; `remove` `DELETE "…/contents/{containedId}"` |

`DashboardService.ContinueSeriesEntry` is client-stamped with `serverURL`/`serverName` and given
`id = "\(serverURL)|\(bookId)"` — the same identity discipline as the caches.

### 3.14 Lite-only metadata providers

`LiteMetadataProvider` is a protocol (`Services/LiteMetadataProvider.swift:12-26`):
`id`, `displayName`, `isConfigured`, `isAuthenticated`, `lookup(isbn:)`, `search(query:)`.

| Provider | Host | Notes |
| --- | --- | --- |
| `OpenLibraryService` (377 lines) | `https://openlibrary.org/api/books?bibkeys=ISBN:<isbn>&format=json&jscmd=data`, then `https://openlibrary.org/isbn/<isbn>.json`, then `https://openlibrary.org/search.json?q=&limit=15&fields=…` | Enabled by default (`LiteMetadataSettings.swift:15-18`) |
| `HardcoverService` (266 lines) | GraphQL `https://api.hardcover.app/v1/graphql` | Opt-in; API key in Keychain `lite.hardcover.apiKey`; `normalize(key:)` strips repeated `Bearer ` prefixes (`:43`) |

`LiteMetadataAggregator` (`Services/LiteMetadataProvider.swift:54-155`) fans out concurrently and
returns results in **provider order, not completion order** — because `merge` takes the first
non-empty value per field, and a plain task-group drain made the winner a race ("the same book could
come back with different metadata run to run"). `merge` also credits every contributing source
(`providerDisplay` joined with `" + "`). `Outcome.allFailed` exists so "nobody has this book" is
distinguishable from "nobody could be reached".

### 3.15 Infrastructure services

| Service | Responsibility |
| --- | --- |
| `KeychainService` (96 lines) | `static let shared`; service `"ca.fireball1725.librarium-ios"`; `kSecAttrAccessibleAfterFirstUnlock`; `enum Lookup { found(String), absent, unavailable(OSStatus), locked(OSStatus) }` |
| `NetworkMonitor` (71 lines) | `@Observable`; `NWPathMonitor`; `isOnline` defaults **true** until first update; `markServerUnreachable(_:)` with `unreachableTTL = 60`; `shouldSkipAPI(for:)` true for `local://`, offline, or recently-failed servers |
| `ServerCapabilities` (74 lines) | `actor`, `static let shared`; unauth `GET <server>/health` with a 5 s timeout; `hasWorkKeyedReadingState(serverURL:)` true iff `minClients != nil`; **defaults false**; cached per URL for process life |
| `LibraryOfflineStore` (254 lines) | Per-library offline opt-in + book cache orchestration (§6) |
| `BookCache` (387 lines) | SwiftData book rows keyed `<serverURL>|<libraryId>|<bookId>`; `upsert`, `syncFull`, `books(for:)`, `allBooks(serverURL:)`, `searchAllBooks(serverURL:query:)`, `purgeLibrary` |
| `SeriesCache` (181 lines) | `[Series]` per composite id, single round-trip, `seriesByServer(serverURL:)` |
| `AuxiliaryCaches` (229 lines) | `EditionCache`, `ShelfCache` (`upsert(_:for:bookId:serverAccountID:)`, `shelves(for:bookId:)`), `ArcCache`, media-type cache |
| `CoverCache` (71 lines) | `static let shared`; `Caches/BookCovers`; filename is the SHA-256 hex of the cover URL; `prefetch(url:accessToken:)` adds `Authorization: Bearer` when non-empty |
| `LibrarySync` (215 lines) | The unified per-library sync: books 0→0.7, tags+media types at 0.72, series + entries + arcs, editions + shelves 0.80→1.0, phase labels exposed as progress |
| `SyncService` (247 lines) | `actor`; outbox drain + delta pull (§6.3) |
| `SyncBackfill` (100 lines) | `writeInteraction(_:serverAccountID:bookID:in:)` — server rows applied with per-field LWW |
| `LiteBookWriter` (353 lines) | Mirrors `POST /books` locally for Lite; notifies via `.liteLibraryDidChange` |
| `LiteMetadataSettings` (57 lines) | Provider enable flags in UserDefaults `"lite.metadata.<id>.enabled"`; defaults Open Library on, Hardcover off |
| `LocalBrowse` (224 lines) | Lite-only faceting; answers 6 of 11 dimensions and reports the other five as unavailable |
| `LibraryOrderStore` (57 lines) | `@Observable`; UserDefaults `"library.display.order.v1"` array of `Library.clientKey` |
| `ListIcons` (73 lines) | Maps the web client's named icon set to SF Symbols, with emoji fallback |
| `TelemetryService` (151 lines) | `@Observable`, `static let shared`; TelemetryDeck appID `"53CE081B-9A81-4CA3-BBE6-0932D36472E9"`; last 50 signals in UserDefaults; `send` no-ops when opted out |
| `ISBNCandidates` (116 lines) | ISBN-10↔13 conversion, UPC-A heuristic, EAN-13 and ISBN-10 check digits |

---

## 4. Data models

### 4.1 Coding strategy — the rule that generates most of the surprises

`Librarium/Services/APIClient.swift:62-72`:

```swift
let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
```

So for **almost every** model, Swift property names are camelCase and the wire keys are snake_case,
with no `CodingKeys` anywhere. `Paged<T>` declares `perPage` and therefore reads/writes `per_page`
(`Models/Library.swift`), `isPublic` ↔ `is_public`, `coverUrl` ↔ `cover_url`, etc.

The exceptions, all of which need explicit `CodingKeys` because the automatic strategy cannot produce
the right key:

| Model | Property | Wire key | Why |
| --- | --- | --- | --- |
| `CreateEditionRequest` (`BookService.swift:241-268`) | `isbn10`, `isbn13`, `pageCount`, `copyCount`, `isPrimary`, `editionName`, `publishDate` | `isbn_10`, `isbn_13`, `page_count`, `copy_count`, `is_primary`, `edition_name`, `publish_date` | "convertToSnakeCase won't add underscore before digits" |
| `BookEdition` | `isbn10`, `isbn13` | `isbn_10`, `isbn_13` | same |
| `MeTag` (`VocabularyService.swift`) | `libraryId`, `libraryName` | `library_id`, `library_name` | same |
| `SavedList` (`Models/SavedList.swift`) | `filterQuery` | `filter` | Renames the key entirely; the value is nested `{"query": "own=gap&type=manga"}` |
| `Library` (`Models/Library.swift:43`) | `serverURL`, `serverName` | **omitted from CodingKeys on purpose** | Client-only metadata, never sent to or read from the server |

One deliberate counter-example: `LiteBookWriter` round-trips its synthetic book dictionaries through
a **plain `JSONDecoder` with no key strategy** (`Services/LiteBookWriter.swift:345`) precisely because
`BookEdition` uses explicit `isbn_10`/`isbn_13`. Mixing strategies would double-convert.

Android: use `@SerialName` explicitly on every digit-adjacent field, or a `Moshi`/`kotlinx`
`JsonNamingStrategy.SnakeCase` plus overrides. Do **not** assume `isbn10` maps anywhere.

### 4.2 Envelope and list shapes

| Shape | Applies to |
| --- | --- |
| `{"data": T}` — stripped by `APIClient.Envelope` (`APIClient.swift:164-166`) on every 2xx | Single-object and most list endpoints |
| `{"data": {"data": T}}` | `/api/v1/me/books/facets` only (`MeBrowseService.swift:64-74`) |
| `{"items": [...]}` | `/me/lists`, `/books/{id}/copies`, `/books/{id}/contents`, `/books/{id}/readers`, `/api/v1/edition-formats`, `/me/wishlist` |
| **Bare array** | `/api/v1/libraries`, `/api/v1/media-types`, `/api/v1/genres`, `/api/v1/libraries/{id}/tags`, `/api/v1/libraries/{id}/members`, `/api/v1/me/suggestions`, `/api/v1/me/counts` |
| `Paged<T> { items, total, page, perPage }` | `/api/v1/me/books`, `/api/v1/admin/users` |
| `LoanPage { items, total }` | `/api/v1/me/loans` (tolerant decode, `MeBrowseService.swift:151-162`) |
| `AuthorIndexPage { items, total, roles }` | `/api/v1/me/authors/index` |
| `SeriesIndexPage { items, total, facets }` | `/api/v1/me/series/index` (`Models/SeriesBrowse.swift:248-261`) |

A `{"data": null}` for "no interaction" is *not* the same as an absent key: it arrives as
`Optional.none` inside the envelope and trips the typed decoder, which is why
`BookService.interaction` swallows `.decodingError` (`BookService.swift:141-148`).

### 4.3 Server-computed fields on `Book`

`Librarium/Models/Book.swift` (300 lines). Several fields exist **only because the server computed
them** and must not be recomputed on the client:

| Property | JSON | Note |
| --- | --- | --- |
| `userReadStatus` | `user_read_status` | Per the calling user |
| `userRating` | `user_rating` | **1–10**, not 1–5 |
| `userProgressPct` | `user_progress_pct` | 0–100 |
| `activeLoanCount` | `active_loan_count` | Drives the "on loan" flag |
| `ownership` | `ownership` | One of `"shelf"`, `"wishlist"`, `"suggested"`, `"gap"` |
| `hasCover` | `has_cover` | Distinguished from `coverUrl == nil` |
| `mediaType` | `media_type` | Denormalised object, not just an id |

`Book.contributors: [BookContributor { contributorId, name, role, displayOrder }]` and `tags`/`genres`
arrive fully expanded on list responses; the app never joins them client-side.

### 4.4 Optionals — where they actually are

Only the optionals that matter, because each one is a branch an Android port has to honour:

| Model | Optional | Meaning |
| --- | --- | --- |
| `Library` | `bookCount`, `readingCount`, `readCount` | Absent on create/update; present on list |
| `Book` | `coverUrl`, `hasCover`, `description`, `addedBy`, `publishYear`, `series…` | `coverUrl` nil ⇒ render a generated jacket |
| `Series` | `totalCount`, `rating`, `myRating`, `previewBooks` | Tolerant decoder for Go `nil` slices |
| `Loan` | `dueDate`, `returnedAt` | `returnedAt == nil` ⇒ active |
| `Copy` | `editionId`, `locationId` | |
| `AdminUser` | `lastLoginAt` | |
| `ServerAccount` | `accessTokenExpiresAt` | nil ⇒ refresh proactively before use |
| `ISBNLookupResult` | `pageCount`, `categories` | |

Two structural optionals that are load-bearing rather than cosmetic:

* `PersistedInteraction.bookID: UUID?` — optional **only** for in-place migration; rows written before
  reading state moved to the work have no book id (`Models/Persisted/PersistedInteraction.swift:29-38`,
  mirrors api migrations 000018 + 000019). `bookEditionID` is kept alongside so the backfill has
  something to map from.
* `PersistedInteraction.progressJSON: Data?` — the api stores progress as JSONB; the app keeps the raw
  bytes plus a per-field timestamp rather than modelling it (`:46-49`).

### 4.5 Wire shapes worth naming

| Type | File | Shape |
| --- | --- | --- |
| `SavedList` | `Models/SavedList.swift` (129 lines) | `{ id, name, description, icon, color, kind, visibility, sharedLibraryId?, bookCount, builtinKey, surface (default "books"), filterQuery ↔ "filter" }`; `kind` `"smart"`/`"manual"`; `visibility` `"private"`/`"library"` |
| `Shelf` | `Models/Shelf.swift` (15 lines) | `{ id, libraryId, name, description, color, icon, displayOrder, bookCount, tags: [Tag], createdAt, updatedAt }` — all non-optional, plain `Codable`, no computed members |
| `Loan` | `Models/Loan.swift` (69 lines) | `{ id, libraryId, bookId, bookTitle, loanedTo, loanedAt, dueDate?, returnedAt?, notes, createdAt, updatedAt }`; dates are **`String`**, not `Date`. Computed: `isActive` (`returnedAt == nil`), `isOverdue` (**a loan with no due date is never overdue** — "inventing a deadline for it would put a red badge on a book nobody is waiting for"), `dueLabel` ("3 days overdue" / "due today" / "due tomorrow" / weekday for 2–6 days / abbreviated date beyond that), `lentOn`, and `static day(_:)` — see §9 for the bug it documents |
| `AuthTokens` | `Models/AuthModels.swift:8-12` | `{ accessToken, refreshToken, expiresIn, user }`; `LoginRequest { identifier, password }` at `:3` |
| `ServerAccount` | `Models/ServerAccount.swift` (99 lines) | `{ id: UUID, name, url, accessToken, refreshToken, user, accessTokenExpiresAt?, kind: .remote/.local, tokensUnavailable, keychainLocked }`; `localURL(for:)` = `"local://\(uuid)"`; `defaultName(for:)` = host |
| `BrowseSelection` | `Models/BookBrowse.swift:248+` | `defaultOwnership = ["shelf"]`, `ownershipAny = "any"` sentinel, `values: [BrowseFacet: Set<String>]`, `query`, `contributors`, `grouped`, `series` |
| `SeriesSelection` | `Models/SeriesBrowse.swift:151-223` | 9 facets + `incompleteOnly`, with `init(query:)` as the exact reverse of `queryString()` |
| `SyncChangesResponse` | `Models/SyncTypes.swift:13-22` | `{ ops, serverTime, hasMore }`, tolerant because Go serialises a nil slice as `null` |
| `PersistedBook` | `Models/Persisted/PersistedBook.swift` (106 lines) | Composite `id` = `"<serverURL>|<libraryId>|<bookId>"`; `serverAccountID`; a duplicate `serverURL` column *because SwiftData `#Predicate` does not reliably support string-prefix matching* (`:27-31`); denormalised `title`, `subtitle`, `sortTitle` (defaults to `""` so lightweight migration can add the column, `:44-48`), `coverUrl?`, `primaryAuthor?`, `readStatus?`, `progressPct? : Double`, `rating? : Int`, `isFavorite`, `updatedAt`, then the whole original JSON in `payload: Data`. Docblock design rule: do **not** map every `Book` field to a column — store the payload verbatim so a new api property needs no schema churn. |
| `PersistedTag` | `Models/Persisted/PersistedTag.swift` (40 lines) | Composite `id` `"<serverURL>\|<libraryId>\|<tagId>"`; `serverURL` recovered in `init` by splitting `id` on the first pipe; `sortName = name.librariumSortKey()` but with **no** default, unlike `PersistedBook.sortTitle` |

`String.librariumSortKey()` (`Models/Extensions/String+LibrariumSortKey.swift`) reimplements the api's
`natural_sort_key()` — a multilingual article list, `l'` stripping, lowercasing, and zero-padding
digit runs to 10 chars. It exists only because Lite has no server to sort for it, and its docblock
says to keep it in lockstep with `api/internal/db/migrations/000001_init_schema.up.sql`.

---

## 5. Auth & session

### 5.1 Login

`AuthService.login(identifier:password:)` → `POST /api/v1/auth/login` with
`LoginRequest { identifier, password }` (identifier is a username **or** email) → `AuthTokens`.
The app stores, per account, `accessToken`, `refreshToken`, `accessTokenExpiresAt`
(derived from `expiresIn`) and the `User` object.

Registration: **none in the app.** First admin comes from `SetupService.bootstrapAdmin`
(`POST /api/v1/setup/admin`), offered by `AddServerView` when `GET /api/v1/setup/status` reports
`initialized == false` (`Views/AddServerView.swift:267`). Later users are created by an admin
(`AdminService.createUser`).

### 5.2 KeychainService

`Librarium/Services/KeychainService.swift` (96 lines):

* `static let shared`; `kSecClassGenericPassword`, service `"ca.fireball1725.librarium-ios"`, account
  = the key name.
* `set(_:for:)` deletes then adds, with `kSecAttrAccessibleAfterFirstUnlock` — readable after the
  first unlock following a reboot, so a background sync can use it.
* `enum Lookup { case found(String), absent, unavailable(OSStatus), locked(OSStatus) }` — the four-way
  result is deliberate: `unavailable` and `locked` are **not** "the token is gone".
* `isLockStatus` treats `errSecInteractionNotAllowed` and `errSecNotAvailable` as locked.
* `get(_:)` returns `String?` and returns nil unless `.found`.

Key names: `access_<account-uuid>` and `refresh_<account-uuid>` (`AppState.swift:166-181, 214-229`).

Android equivalent: `EncryptedSharedPreferences` (or a Keystore-backed blob) keyed the same way, plus
an explicit distinction between "no credential" and "keystore temporarily unavailable" — the state
machine below depends on it.

### 5.3 Re-auth state machine

The states a `ServerAccount` can be in, and the transitions, are spread across `AppState`:

| State | Representation | Entered by |
| --- | --- | --- |
| Healthy | both tokens present | login, refresh success |
| Expiring | `accessTokenExpiresAt < now + 5 min` | clock |
| Unusable | `accessToken.isEmpty || refreshToken.isEmpty`, `needsReauth == true` | refresh rejected, or keychain says absent |
| Temporarily unreadable | `tokensUnavailable == true` (and possibly `keychainLocked`) | keychain returned `.unavailable`/`.locked` |

`ServerAccount.needsReauth` (`Models/ServerAccount.swift`) is `false` for `.local` accounts and for
`keychainLocked`, otherwise `accessToken.isEmpty || refreshToken.isEmpty`.

`markNeedsReauth(id:)` is a **no-op when `tokensUnavailable`** (`AppState.swift:189-200`). That guard
exists because a build once wiped credentials it had merely failed to read, forcing a real sign-in for
a token that was still in the Keychain. `saveAccounts()` likewise never writes blank tokens and skips
`tokensUnavailable` accounts entirely (`AppState.swift:500-519`) — an earlier version deleted tokens
on every save.

`materialise(_:)` (`AppState.swift:441-484`) maps the Keychain `Lookup` result onto those flags:
`.found` → tokens, `.absent` → `needsReauth`, `.unavailable`/`.locked` → `tokensUnavailable` (+
`keychainLocked` for the latter). `reloadUnavailableTokens()` (`:489-498`) re-runs it for accounts in
the unavailable state; `ContentView` calls it on every `scenePhase == .active`.

Recovery: `ReauthSheet` (`Views/ReauthSheet.swift`, 143 lines). It is used, never destructive — its
docblock (`:3-7`) says it reuses the stored URL and name and never deletes the server record, so the
offline cache survives. It builds a **bare** `APIClient(baseURL: account.url)` with no token (`:122-142`)
so a failed sign-in cannot loop through the 401 retry path; on success it calls
`appState.updateTokens(for:tokens:)` and dismisses. The identifier is pre-filled from
`account.user.username` (`:84`).

The UI surface for this: `ReauthBannerStack` (`Views/Components/ReauthBannerStack.swift`) renders one
`InlineBanner` per unusable account, tap → `ReauthSheet`. Screens key their `.task` on
`accounts.map { "\($0.id):\($0.needsReauth)" }` so restoring a session actually re-runs the load
(`RedesignedHomeView.swift:481-487`).

### 5.4 Token refresh — and why single-flight is mandatory

`AppState.refreshCoordinator = RefreshCoordinator()` (`AppState.swift:345`). The docblock (`:339-344`):

> Cold-launch fans out many requests in parallel; if all of them get 401 and each fires its own
> refresh, the second call presents an already-rotated token and the server treats it as token-reuse
> and revokes every active refresh token for the user — boots them out of every device. One
> coordinator, one in-flight task per account, every caller awaits the same outcome.

`private actor RefreshCoordinator` (`:579+`) holds `inflight: [UUID: Task<RefreshOutcome, Never>]`,
POSTs `/api/v1/auth/refresh`, and returns
`enum RefreshOutcome { case success(AuthTokens), rejected, transient }` (`:573-577`).

`AppState.refreshToken(for:)` (`:366-412`) maps outcomes: `.success` → updateTokens; `.rejected` →
`markNeedsReauth`; `.transient` → leave the account untouched (a flaky network must not sign anyone
out). `proactivelyRefreshIfNeeded(accountID:)` (`:352-364`) early-returns when the token is valid for
more than five more minutes, and is called at the top of every sync.

Android: `OkHttp`'s `Authenticator` is *not* single-flight by default — it serialises per call but can
still rotate twice on parallel 401s. Use a `Mutex`-guarded refresh keyed per account, exactly as here.

### 5.5 401 handling

`APIClient.raw` (`:186-224`): on `401`, if `onUnauthorized` is set and the request has not already
been retried, it awaits the closure for a replacement token, rebuilds the request and retries **once**.
`onUnauthorized` is installed by `AppState.makeClient()` / `makeClient(serverURL:)`
(`AppState.swift:294-320`) and returns the account's current access token after a successful refresh,
or nil.

### 5.6 Multi-server: active, primary

Two distinct pointers, and every "which server?" question resolves against one of them:

| Pointer | Purpose | Persistence |
| --- | --- | --- |
| `activeSourceID` / `activeSource` (`AppState.swift:241-254`) | Which account/library the whole shell is scoped to. Drives `makeClient()` and `makeServerClient()`. | UserDefaults `"active_source_id"` (`:273`) |
| `primaryAccountID` / `primaryAccount` (`:26-30`) | The "home" server for global operations: scan metadata lookup, global search, the Home greeting identity | UserDefaults `"primary_account_id"`, saved by `savePrimary()` (`:521-527`) |

`makePrimaryClient()` (`:330-335`) is explicitly *not* guarded for Lite: "Primary does not mean 'a
server'. A Lite account can be primary, and this hands back a client pointed at its `local://` URL,
which every network call then fails on." Anything that must reach a server uses
`makeServerClient()` (`:287-290`), which returns nil for a Lite source.

`needsSourceChoice` (`:257`) is true when more than one source exists and none is selected; the shell
then shows `SourcePickerView` instead of tabs. `setPrimaryAccount(id:)` is reachable from
`RedesignedProfileView.swift:249,312` and from the legacy `ProfileView` star button (`:60`).

Removing one account (`removeAccount(id:)`, `:166-181`) deletes `access_<uuid>` and `refresh_<uuid>`,
re-saves the account list, and purges that server's libraries from the offline store
(`LibraryOfflineStore.purgeLibraries(forServerURL:)`). `logout()` (`:214-229`) does the same for every
account and also removes the UserDefaults keys `server_accounts` and `primary_account_id`.

`loadAccounts()` (`:416-432`) always materialises **every** meta row, so an account whose tokens could
not be read still appears in the list rather than vanishing — that is what makes ReauthBannerStack
possible.

---

## 6. Offline & caching

The product rule (`AGENTS.md:36-37`): "Offline is a real state, not an error. The app has a per-server
cache and a pending-operation queue. A screen that only works online is unfinished."

### 6.1 The identity rule

> **Anything cached needs the server identity in its key** (`AGENTS.md:130-131`).

Root cause, stated at `Services/LibraryOfflineStore.swift:16-19`:

> All state is keyed on `Library.clientKey` (`"<serverURL>|<libraryID>"`) rather than the bare
> server-side `id`, because two different Librarium servers can expose libraries with colliding UUIDs
> (common when both were seeded from the same database).

Concretely:

| Cache | Key |
| --- | --- |
| `Library.clientKey` | `"<serverURL>|<libraryId>"` (`Models/Library.swift:28-40`, with a DEBUG stack print when `serverURL` is empty) |
| `BookCache`, `EditionCache`, `ShelfCache`, `ArcCache`, `PersistedBook`, `PersistedTag`, `PersistedBookEditions`, `PersistedBookShelves`, `PersistedSeriesEntry`, `PersistedSeriesArc`, `PersistedMediaType` | `"<serverURL>|<libraryId>|<bookId>"` |
| `SeriesCache` | `"<serverURL>|<seriesId>"`; `seriesByServer(serverURL:)` filters in Swift on `row.serverURL == serverURL || row.id.hasPrefix("\(serverURL)|")` |
| `CoverCache` | SHA-256 hex of the cover URL — safe because URLs differ per host, and Lite stores absolute provider URLs |
| `LibraryOfflineStore` UserDefaults | `offline_enabled_keys`, `offline_known_keys`, `offline_books_fp_<percent-encoded key>`, `offline_lib_url_<…>`, `offline_lib_server_<…>` |
| Sync cursor | `"sync.lastSyncedThrough.<serverAccountID.uuidString>"` (`SyncService.swift:39`) |
| `DashboardService.ContinueSeriesEntry.id` | `"\(serverURL)|\(bookId)"` |

`LibraryOfflineStore.validKey(_:caller:)` (`:64-71`) refuses any operation whose key is not exactly two
non-empty pipe-separated halves, and prints
`⚠️ [OfflineStore.<caller>] refusing op on invalid key: …` rather than baking the bug into persisted
state. `syncBooks` refuses a library with an empty `serverURL` outright (`:174-177`).

Android: this is the single most likely place to lose weeks. Two Room tables keyed on
`libraryId`/`bookId` alone will silently cross-contaminate the moment a user has two servers seeded
from the same fixture database. Use composite string PKs mirroring `clientKey`, or a `serverKey`
column in every PK.

### 6.2 Per-library offline store

`Librarium/Services/LibraryOfflineStore.swift` (254 lines), `@Observable`, `static let shared`.

State:
* `enabledKeys: Set<String>` — libraries the user opted into full book caching.
* `knownKeys: Set<String>` — every library ever fetched; metadata is cached for all of them so the list
  still renders offline. `init` unions `enabledKeys` into `knownKeys` (`:47`).
* `bookCacheStates: [String: BookCacheState]` where
  `enum BookCacheState { notCached, syncing(progress:phase:), cached(bookCount:) }` (`:5-9`).

Storage split: small prefs in UserDefaults, larger JSON payloads in `Caches/LibraryOffline` (rebuildable
from the server). Filenames and UserDefaults keys run the composite key through
`addingPercentEncoding(withAllowedCharacters: .alphanumerics)` (`:247-253`) because the key contains
`://` and `|`.

`setEnabled(_:for:modelContainer:)` (`:73-103`):
* on **enable**, clears the fingerprint throttle so an off→on within 60 s still syncs;
* on **disable**, purges `BookCache` via `purgeLibrary(serverURL:libraryId:)`.

`cacheLibrary(_:)` (`:108-120`) refuses an empty `serverURL` and caches metadata **regardless** of
opt-in — offline users need to see the library list, not just the books they cached.

`syncBooks(...)` (`:167-206`) is the entry point used on every app activation:
1. bail on empty `serverURL`; bail unless `isEnabled(for: key)`; bail if already `.syncing`;
2. **throttle the fingerprint check to once per 60 s per library** (`lastFingerprintCheck`,
   `:182-184`) so rapid navigation does not spam the endpoint;
3. `GET /…/books/fingerprint`; on failure, fall through to a full sync **only if the cache is empty**
   (`:192-198`) — this is how an older server without the endpoint still works;
4. if the stored `fp.cacheToken == "\(total)|\(maxUpdatedAt ?? "")"` and the cache is non-empty, stop;
5. otherwise `fullSync` → `LibrarySync(modelContainer:).run(...)` with live progress, then record the
   new fingerprint.

`purgeLibraries(forServerURL:)` (`:144-159`) removes files, fingerprints, both UserDefaults
side-keys, the enable flag, the known flag and the cache state for that server. Called on account
removal and on `logout()`.

`LibrarySync.run` (`Services/LibrarySync.swift`) is the actual pull: books via `BookCache.syncFull`
(0→0.7), then tags + media types (`TagService.list`, `MediaTypeService.list`, phase `"tags"` at 0.72),
then series + entries + arcs (`SeriesService.list`, `.books`, `.arcs`), then editions + shelves for
every book in parallel (`async let` on `BookService.editions` and `.shelves`, phase `"editions"`,
0.80→1.0).

### 6.3 Pending-operation queue (the outbox) and sync reconciliation

Writes on a server-backed library that must survive going offline go through `PendingSyncOp`
(`Models/Persisted/PendingSyncOp.swift`, 68 lines):

```
opId (unique), serverAccountID, entityType, entityId, field?, valueJSON: Data? (nil = clear), deleted,
updatedAt, createdAt
```

`field` exists for **per-field last-write-wins**: `read_status`, `is_favorite`, `progress`. v1 scope
mirrors the server — `user_book_interactions` only (`SyncService.swift:14-17`).

The optimistic-write core is `RedesignedBookDetailView.writeLocal(_:)` (`:472-544`): it creates or
updates a `PersistedInteraction`, saves the context, mirrors to `PersistedBook` for Lite
(`mirrorInteractionToPersistedBook`, `:551-579`, deriving `progressPct` from
`{"pages_read": Int}` / `pageCount`), and returns the account id so the caller can `enqueueAndKick`.
Status, rating and progress **all** write through the outbox — `RedesignedProgressSheet`,
`RedesignedStatusSheet` and `RedesignedRateSheet` each carry "No direct PUT" in their docblocks.

`actor SyncService` (`Services/SyncService.swift`, 247 lines):

* `syncOnce()` = `drainOutbox()` **then** `pullChanges()` (`:46-54`). Push-then-pull is deliberate: send
  local mutations first so the server applies them, then pull the canonical state (including anything
  the server overrode via LWW).
* `pullChanges()` (`:57-88`): loop `GET "/api/v1/sync/changes?since=<iso8601>&limit=500"` (server caps
  at 1000), apply each op, save, advance the cursor to the max `updated_at` in the page, repeat while
  `has_more`. The cursor is persisted only after the loop.
* `applyOp` reads the field name and applies with a timestamp guard: `if row.<field>UpdatedAt == nil ||
  row.<field>UpdatedAt! < opTS` — so a late-arriving older write never clobbers a newer local one
  (`:220-245`).
* `drainOutbox()` (`:94+`) posts `PendingSyncOp`s to `POST "/api/v1/sync/apply"` in batches and clears
  rows the server acknowledged: `applied`, `discarded_stale`, `not_found`, `invalid`. Transient errors
  leave the rows in place for the next drain. Statuses are
  `SyncApplyStatus { applied, discardedStale = "discarded_stale", notFound = "not_found", invalid,
  error }` (`Models/SyncTypes.swift`).
* Timestamps use one shared formatter, ISO8601 with `[.withInternetDateTime, .withFractionalSeconds]`
  (`SyncTypes.swift:117-126`).

`SyncBackfill.writeInteraction` (`Services/SyncBackfill.swift:20-73`) is the reconciliation edge case
that will bite a port: when a pulled interaction arrives with an id the local store has never seen,
the service **adopts the stale row under the same book** (`stale.id = id`, `:43-53`) instead of
inserting a duplicate — because "the server minted new row ids when reading state moved to the work".
It also fills `row.bookID` when nil, and only overwrites a field when
`(row.<field>UpdatedAt ?? .distantPast) <= updatedAt` (`applyInto`, `:75-99`). Progress columns are
deliberately left alone there.

Reads and writes are asymmetric by design: `ContentView.runSyncAllAccounts` is best-effort and its
failures never surface, because "the existing read paths still hit the api directly until this rebuild
is wired through" (`ContentView.swift:87-91`). So the outbox is the *only* path for reading-state
writes, while book/library reads still go live.

### 6.4 UI behaviour while offline

| Behaviour | Where |
| --- | --- |
| Cached libraries still list | `LibraryOfflineStore.cachedLibraries()` (`:126-137`) re-injects `serverURL`/`serverName` from UserDefaults so `setActiveAccount(for:)` works offline |
| Cached libraries render SwiftData first, then refresh in the background | `RedesignedBooksView.loadBooks()` (`:663-709`) docstring is the strategy statement |
| Non-cached remote library → live API | same function |
| Successful live loads write through to SwiftData | same function |
| Offline search walks `PersistedBook` per account and fabricates a minimal `Library` when metadata is missing | `RedesignedSearchView.offlineBookSearch` (`:739-745`) |
| Per-server suppression after a failure | `NetworkMonitor.shouldSkipAPI(for:)`, 60 s TTL |
| No sync at all while offline | `ContentView.swift:94-98` early-return |
| "Needs updating" vs "empty" | 404 on `/me/*` maps to `MeBrowseError.serverTooOld` rather than an empty list (librarium-ios #77) |

### 6.5 `ServerCapabilities` and server adaptation

`Librarium/Services/ServerCapabilities.swift` (74 lines), `actor`, `static let shared`.

* `GET <server>/health` — **unauthenticated**, `timeoutInterval = 5`, includes the client identity
  headers. Decodes `Health { version?, minClients? }` with `.convertFromSnakeCase`.
* `hasWorkKeyedReadingState(serverURL:)` returns true iff `minClients != nil`. **Defaults to false**
  when the probe fails, so an unreachable or old server is assumed old.
* Cached per server URL for the lifetime of the process; `forget(serverURL:)` / `forgetAll()` reset it.

Capability gating is the app's general defence against 404-as-empty: `ListService`, `CopyService` and
the list/shelves fallback all consult it (or catch 404) before deciding that a feature simply does not
exist on this server. An Android port needs the same three-state answer —
*supported / unsupported / unknown* — or it will show "no saved views" to users whose server predates
lists.

---

## 7. Networking details

### 7.1 Base URL normalization

Two implementations exist and they are not identical; both are pinned by tests or heavily commented.

`normalizeServerURL` (`LibrariumTests/NormalizeServerURLTests.swift`, 73 lines — the test is the
contract):
* prepends `https://` **only** when no scheme is present;
* preserves `http://localhost:8080`;
* strips the trailing slash;
* trims whitespace;
* preserves port and path (`https://librarium.example.com:8443/api`);
* returns nil for empty/whitespace, for `file://`, `ftp://`, `javascript:alert(1)`, and for a hostless
  `https://`.

`AddServerView`'s inline helper (`Views/AddServerView.swift:349-367`) does the same and additionally
requires a non-empty `host`, and turns `path == "/"` into `""`.

`APIClient.init` trims trailing `/` again (`Services/APIClient.swift:46-76`), so paths are always built
as `base + "/api/v1/…"` with exactly one slash.

### 7.2 Headers

Every request (`Services/APIClient.swift:168-184`, mirrored in `ServerCapabilities`):

| Header | Value |
| --- | --- |
| `Content-Type` | `application/json` (except multipart) |
| `Authorization` | `Bearer <accessToken>` when a token exists |
| `X-Librarium-Client` | `ios` |
| `X-Librarium-Client-Version` | `CFBundleShortVersionString ?? "0.0.0-dev"` |

`ClientIdentity` at `APIClient.swift:34-43`. The server answers **426 Upgrade Required** for clients
below its minimum, and exempts the literal `0.0.0-dev`. Android must send its own name/version pair
(ask the backend which client identifiers it recognises — see §10).

Multipart uploads add `Content-Type: multipart/form-data; boundary=Boundary-<uuid>` (`:113-160`).

### 7.3 Timeout

`req.timeoutInterval = 10` (`APIClient.swift:168-184`). The rationale recorded elsewhere in the same
file: the URLSession default of 60 s made "server down but network up" hang for a minute, and 5 s
starved slow LANs. `ServerCapabilities` uses 5 s; `uploadMultipart` sets no explicit timeout (it
inherits the session default) — a port should give uploads their own, longer, budget.

### 7.4 Envelope and errors

Every 2xx body is `{"data": …}` and `APIClient.Envelope<T>` unwraps exactly one level
(`:164-166`, applied at `:186-224`).

| Status | Behaviour |
| --- | --- |
| 2xx | decode `Envelope<T>.data` |
| 401 | one retry after `onUnauthorized` returns a token; if it returns nil → `.unauthorized` |
| 404 | `.notFound` — call sites decide whether that means "missing" or "this server is too old" |
| other non-2xx | `.serverError(status, bodyText?)` |
| transport failure | `.networkError` |
| decode failure | `.decodingError` |

DEBUG-only logging prints `⏱️ [APIClient] <METHOD> <status> <ms>ms <bytes>B <path>` per request.

`voidRequest` mirrors all of the above for endpoints with no body.

---

## 8. UI / design system

### 8.1 Theme tokens

`Librarium/Redesigned/Theme.swift` (160 lines) is the single source of truth, mirroring CSS custom
properties from `plans/ios-redesign/mockup.html`. Android: copy these into a `Theme` object /
`ColorScheme` and never hard-code a hex in a composable.

Colors (`:23-63`):

| Token | Value | Use |
| --- | --- | --- |
| `appBackground` | `0x0c0d12` | Page background, always `ignoresSafeArea` |
| `appBackgroundEleva` | `0x16181f` | Raised surfaces |
| `appCard` | `0x1b1d25` | Cards |
| `appBackgroundTrans` | `0x16181f` @ 0.72 | What the floating bar sits on |
| `appText` | `0xf1ede2` | Primary text |
| `appText2` | `0xb9b3a4` | Body / supporting |
| `appText3` | `0x6e6a5f` | Meta / timestamps — **three tiers, no fourth shade** |
| `appLine` | white @ 0.07 | Hairlines |
| `appLineStrong` | white @ 0.13 | Emphasised dividers |
| `accent` | `0x8089ff` | Indigo accent |
| `accentStrong` | `0xaab1ff` | Accent text/icons |
| `accentSoft` | `(128,137,255)` @ 0.18 | Accent wash |
| `accentDeep` | `0x5a64e8` | Far end of every accent gradient (`linear-gradient(160deg, accent, accentDeep)`) |
| `good` | `0x7bd6a8` | read / success |
| `warn` | `0xffb866` | overdue / caution |
| `bad` | `0xff8a8a` | destructive |
| `gold` | `0xf3c971` | rating + admin badges |
| `lent` | `0xf59e0b` | books out on loan (a deeper amber than `warn`; the source flags that two close ambers for "caution" and "lent" is worth revisiting) |
| `lentInk` | `0x1a1306` | text on `lent` — amber is too light to carry white |

Spacing (`:130-137`): `xs 4`, `sm 8`, `md 12`, `lg 18`, `xl 24`, `xxl 32`.
Radius (`:141-146`): `chip 999`, `pill 14`, `card 18`, `sheet 24`.

### 8.2 Fonts — bundled files and where each is used

All four `.ttf` files ship in the app bundle and are declared in `Librarium/Info.plist:12-18`
(`UIAppFonts`). They are registered at launch with the rest printed in DEBUG
(`LibrariumApp.swift:34-45`).

| File | Bytes | Family / PostScript names | Used by |
| --- | --- | --- | --- |
| `Librarium/Resources/Fonts/CormorantGaramond-VariableFont_wght.ttf` | 1,195,560 | `CormorantGaramond-Regular` / `-Medium` / `-SemiBold` / `-Bold` | `Theme.Fonts.display(_:weight:)` (`Theme.swift:78-88`) → every display role: `pageTitle` 36/semibold, `heroTitle` 22, `cardTitle` 18. This is the editorial voice: page titles, hero titles, card titles. |
| `Librarium/Resources/Fonts/CrimsonPro-VariableFont_wght.ttf` | 246,324 | family `CrimsonPro` (variable weight axis) | `Theme.Fonts.bodySerif(_:weight:)` (`:91-94`) → `bodyPara` 15. Book descriptions, reviews, long-form body copy. |
| `Librarium/Resources/Fonts/CrimsonPro-Italic-VariableFont_wght.ttf` | 253,176 | `CrimsonPro` italic axis | Italic body text (declared alongside the roman; no separate helper) |
| `Librarium/Resources/Fonts/Cinzel-VariableFont_wght.ttf` | 125,468 | family `Cinzel` | **Bundled but not used for text.** The mockup's `--font-label` specified Cinzel for small-caps utility labels; `Theme.Fonts.label(_:)` (`:102-104`) deliberately uses SF Pro uppercased + tracked instead, because "Cinzel reads too display-face for utility labels (SERVERS / EDITION / CURRENTLY READING)". It remains in `UIAppFonts` and is registered. |

Non-editorial roles are all system fonts (`:102-114`): `label` = system semibold (used with
`.textCase(.uppercase)` + `.tracking(1.0)`), `ui` = system (the workhorse for chrome, dense rows,
numbers), `mono` = system monospaced (ISBNs, build versions, tabular figures).

Named type roles (`:118-125`): `pageTitle` `display(36,.semibold)`, `heroTitle` `display(22)`,
`cardTitle` `display(18)`, `rowPrimary` `ui(14,.semibold)`, `rowMeta` `ui(12,.medium)`,
`inlineAction` `ui(11,.semibold)`, `sectionLabel` `label(11)`, `bodyPara` `bodySerif(15)`.

Android: bundle Cormorant Garamond (or a close serif if licensing differs — check), Crimson Pro
roman + italic, and skip Cinzel unless you keep the same "bundled but unused" fidelity. Variable-font
weight selection via `.weight()` has a direct Compose equivalent in `Font(resId, weight = …)` only if
you ship static cuts; with variable TTFs you need `FontVariation.Settings`.

### 8.3 Colour helpers

`Librarium/Views/Components/ColorTokens.swift` (54 lines) — `Color.chipForeground(forTintHex:)`
returns white under luminance 0.55 and the system label colour above it; `chipBackground(forTintHex:opacity: 0.18)`;
`isDark(hex:)`; WCAG relative luminance with gamma correction. This exists so user-chosen tag colours
stay legible; port the luminance function, not a hard-coded white/black switch.

### 8.4 Reusable components

`Librarium/Views/Components/` — each docblock states the reason it exists; those reasons are the
porting requirements.

| File | Bytes | What it is / why |
| --- | --- | --- |
| `BookCoverImage.swift` | 11,240 | Cover with three fallbacks: loaded image → generated jacket placard (`GeneratedCover`, title + author, from `SplashPalette`) → icon placeholder. Optional corner triangle flags `read`/`reading`/`did_not_finish`, matching the web's `BookCover` indicator. Resolves the Bearer token by matching the cover URL against stored accounts' base URLs (`:41-45`). |
| `SplashPalette.swift` | 2,177 | Six gradient pairs plus a hash of the title, so a coverless book or series gets a stable, distinctive jacket. "librarium-ios-003". |
| `ChipView.swift` | 1,611 | The pill primitive shared by `TagPill`, the loan Active/Returned indicator, the member role badge and the scan status pill. Leaf view; selection state lives at the call site. |
| `TagPill.swift` | 1,566 | `TagPill`, `TagPillSmall` built on `ChipView` + `Color.chip*`. |
| `FlowRow.swift` | 1,877 | `struct FlowRow: Layout` — wrapping chips. "An HStack does not wrap; it squeezes." Compose: `FlowRow` from foundation layout. |
| `GeneratedAvatar.swift` | 4,972 | Per-account avatar derived from the account **id** as seed. "Names get edited; an avatar that changes colour because someone fixed a typo looks broken." Fallback because iOS exposes no Memoji/Apple-ID image. |
| `InlineBanner.swift` | 4,850 | Canonical icon + text + tinted box. Every banner in the app is this. |
| `LoadingRow.swift` | 1,543 | Spinner + caption row. |
| `ReauthBannerStack.swift` | 1,542 | One `InlineBanner` per unusable account; tap → `ReauthSheet`. |
| `SaveErrorBanner.swift` | 2,406 | Pinned under the nav bar for save failures (librarium-ios #56). |
| `StarRow.swift` | 2,343 | The single full/half/empty star rule — every star display in the app goes through it. |
| `EmptyState.swift` | 985 | Icon + title + message. |

`Librarium/Views/Scan/RatingStars.swift` (3,131 B) — the *input* counterpart: a 10-point rating as
five stars, each star two `Button`s (left half → 1/3/5/7/9, right half → 2/4/6/8/10). Buttons rather
than `onTapGesture` because "Form rows swallow tap gestures".

### 8.5 Dark mode and the "cozy" identity

* The app is **dark-only** in practice: every screen paints `Theme.Colors.appBackground` and
  `.ignoresSafeArea`, and there is no light palette in `Theme`. There is no `@Environment(\.colorScheme)`
  branch anywhere. Port as a fixed dark theme rather than a `MaterialTheme(darkColorScheme = …)` pair.
* The cozy identity is carried by three things, not by colour alone: (1) the serif display face on
  every title at 18–36 pt; (2) generated book-jacket gradients wherever a cover is missing, so an
  unfinished library still looks full; (3) generous spacing (`lg 18` / `xl 24`) with hairline dividers
  at 7 % white instead of borders.
* No asset catalog imagery is required for the look — gradients, glyphs and type do the work. Splash
  and empty states are drawn from `Theme`.

---

## 9. Things an Android port must not miss

Ordered by the cost of getting them wrong.

1. **Server identity in every cache key.** `Library.clientKey = "<serverURL>|<libraryId>"`, book rows
   `"<serverURL>|<libraryId>|<bookId>"`, sync cursor per `serverAccountID`. Two servers can expose
   libraries with identical UUIDs. See §6.1.
2. **Single-flight token refresh.** Parallel 401s each refreshing a rotating refresh token makes the
   server treat it as reuse and revoke every session the user has on every device
   (`AppState.swift:339-344`). One mutex-guarded refresh per account.
3. **Distinguish "no credential" from "credential unreadable".** `tokensUnavailable` /
   `keychainLocked` / `needsReauth` are three different states and only one of them should prompt a
   sign-in. `markNeedsReauth` must no-op when unreadable; `saveAccounts` must never write empty tokens.
4. **Keep all four tab trees alive** (or reproduce the effect with saveable back stacks), and rebuild
   them wholesale when the active source changes. Compose's default single `NavHost` gives neither.
5. **One selection object drives both list and facet counts.** `BrowseViewModel`'s docblock
   (`:9-12`) is the requirement: "a panel that says 'Reading 29' over a list that ignores the tick is
   the failure that shape exists to prevent." Android equivalent: one immutable filter state consumed
   by both requests.
6. **Do not reimplement server filtering/sorting.** Exceptions: Lite libraries, and
   `String.librariumSortKey()` which must stay in lockstep with the api's SQL function.
7. **404 ≠ empty.** Capability-gate list/shelf/copy endpoints, and map `/me/*` 404 to a "server too
   old" message (`<host> needs updating. … 26.8.1.`). A silently empty filter sheet is the classic bug
   here.
8. **`{"data": null}` is a value, not an absence.** Tolerate it on the endpoints that use it, most
   visibly `GET …/editions/{id}/my-interaction`.
9. **`/me/books/facets` is double-wrapped.** Unwrapping once yields an envelope, not counts.
10. **Bare arrays vs `{items}` vs `Paged`** — three different shapes; see §4.2. A generic Retrofit
    base type must not assume one.
11. **PATCH on a loan replaces the record.** Send the whole loan, not a diff. Likewise re-read the
    interaction before a field update, or use the outbox's per-field ops.
12. **Optimistic writes go through the outbox, not a PUT.** Status, rating, progress, favourite all
    write locally first and enqueue a `field`-scoped op. Reconciliation is per-field LWW on
    `<field>UpdatedAt`; a pulled row with an unknown id must **adopt** the existing row for that book
    rather than insert a duplicate (`SyncBackfill.swift:43-53`).
13. **Progress is JSONB, not a number.** v1 writes `{"pages_read": N}`; percent and position are part
    of the shape but unwritten.
14. **Ratings are 1–10.** Five stars is a display concern only; a half-star tap zone maps to an odd
    or even integer.
15. **Coming back to the foreground is an event**: reload unavailable tokens, then drain sync, then
    refresh offline caches. Skipping the first makes a locked-keystore session look signed out.
16. **Two pointers, not one:** active source (what the shell is scoped to) vs primary server (global
    lookup identity). Primary may be a Lite account, in which case there is no server to talk to.
17. **`local://` is not a URL you can request.** Fail closed with a nil client, not with an
    "unsupported URL" error rendered as an empty library.
18. **Zero-row states have three distinct causes** the UI must separate: nothing matched, no provider
    could be reached, and this server has never had this feature. `LiteMetadataAggregator.Outcome.allFailed`
    and `MeBrowseError.serverTooOld` are the two existing answers.
19. **The scan FAB is global and primary-account-scoped.** A scan can find a book in a collection the
    user does not currently have open; the shell must switch source, then open the book.
20. **Order of merged metadata is provider order, not completion order**, or the same ISBN yields
    different metadata run to run.
21. **`NaturalSortKey` parity** for anything sorted locally.
22. **Telemetry is opt-in, off by default, and never carries user content** (no titles, ISBNs, authors,
    ratings, reviews, notes, library or member names, or search queries). Progress is sent only as a
    percentage bucket (`"0-10"`, `"11-25"`, … `"100"`).
23. **Two missing screens are intentional, not oversights**: Shelves and Members have no UI
    (`RedesignedAppShell.swift:103-106`), and `MemberService` has no call sites. Do not invent
    endpoints for them; see §2.12 and §2.9 for what *does* exist.
24. **Loan dates are calendar days, parsed in the reader's own timezone.** The api sends either
    `2026-06-02` or a full timestamp depending on the column, and either way the app truncates to the
    first 10 characters and parses with `en_US_POSIX` + `TimeZone.current` + ISO8601 calendar
    (`Models/Loan.swift:48-68`). The docblock records the bug this prevents: parsing
    `2026-06-02T00:00:00Z` as an instant rendered it as "1 Jun" anywhere west of Greenwich. Android
    must use `LocalDate.parse(raw.take(10))`, never `Instant` → `ZonedDateTime` for loan dates.
25. **Rating input and rating display are two separate mechanisms.** Input is a pair of `Button`s
    per star — the left zone emits 1/3/5/7/9 and the right zone 2/4/6/8/10
    (`Views/Scan/RatingStars.swift`) — chosen over `onTapGesture` because "Form rows swallow tap
    gestures". Display goes through `Views/Components/StarRow.swift`, which owns the single
    full/half/empty rule. Both encode the same even/odd → half-star mapping; an Android port must
    implement one mapping used by both, and must not assume a stock `RatingBar` can express it.

---

## 10. Uncertainties

Recorded rather than guessed. Each item is a question the port needs an answer to before coding.

1. **Registering a new account.** `AuthService` exposes only `login` and `logout`; no
   `/api/v1/auth/register` appears anywhere in the app. Whether the api has one, and whether Android
   should expose sign-up, is **not determinable from this repo**.
2. **`PUT /api/v1/libraries/{id}`** exists in `LibraryService.update` but no shipping screen calls it.
   The redesigned libraries view does not offer an edit library flow. Whether editing a library is in
   scope for Android is a product decision, not a source fact.
3. **Admin reachability.** `AdminUsersView` / `AdminSettingsView` exist under `Views/` and are gated on
   `isInstanceAdmin`, but no redesigned screen navigates to them, and the only in-app
   `NavigationLink(destination: AdminUsersView())` does not exist. I could not find a call site that
   presents them from the current UI. Treat admin as **implemented but not wired** and confirm with the
   product owner whether Android should wire it.
4. **Services with no verified UI call site.** `BookService.enrich` (`BookService.swift:87`), every
   `MemberService` method, and `WishlistService.add(title:authorName:notes:)` (`WishlistService.swift:31`)
   are implemented but have no call site in any file I read; they may be reachable from a path I did
   not read, or dead. Not asserted either way.
5. **`RedesignedScanFlow` "quick rate" mode** (`ScanMode.rate`, `:94`) exists as a selector whose toggle
   the docblock calls a no-op for v1. Whether v1 rated books straight from the scanner is unclear from
   code alone.
6. **`hasWorkKeyedReadingState` semantics.** The app treats "`minClients` present in `/health`" as
   "server is new enough for work-keyed reading state". That is an inference from the field name plus
   usage, not a documented contract; the api's `/health` shape should be confirmed.
7. **Client identity values the server accepts.** The app sends `X-Librarium-Client: ios` and treats
   `0.0.0-dev` as exempt from the 426 floor. Whether `android` is a recognised value (and what its
   minimum version is) is a backend question.
8. **`RedesignedSeriesDetailView` enrichment.** The docblock says volume rows are enriched by fetching
   each entry's full `Book` in parallel, and that arc headers, ghost rows and AI-suggested volumes are
   deferred. The exact fields that enrichment fills were not traced line by line.
9. **Lite iCloud sync.** `AGENTS.md:26-29` says Lite "keeps everything on device with SwiftData and
   iCloud sync". No CloudKit code exists in this checkout: zero matches for `CloudKit`, `CKRecord` or
   `NSPersistentCloudKitContainer` across all Swift files, `LibrariumApp.swift:26` builds a plain
   `ModelConfiguration(schema: schema)` with no cloud container, and there is **no `.entitlements`
   file in the repository at all**. So the source does *not* document a working iCloud path today.
   Android's Lite equivalent should be built device-local until the backend/cloud story is confirmed
   with the maintainer; do not design a sync protocol against an unimplemented contract.
10. **`MARKETING_VERSION` / `CFBundleShortVersionString` in the identity header.** In source it is
    `0.0.0-dev`; releases inject `YY.M.R`. Android must send something equivalent, but which value the
    server's minimum-version gate compares against is a backend detail.
11. **Endpoint paths quoted above are exactly as they appear in the Swift string literals.** Where a
   path is built from an interpolated id the shape is `/api/v1/libraries/{id}/…`; the literal in source
   is e.g. `"/api/v1/libraries/\(libraryId)/books"`. No path in this document was reconstructed from
   memory.
12. **Sequence-numbered issue references** (librarium-ios-002, -003, -022, -034, -056, -064, -121,
   -122, #34, #56, #77) appear in source comments as rationale for fixes. The issues themselves are
   not in this checkout, so the comments are the only evidence for what the original bug was.
13. **`Librarium/Resources/Localizable.xcstrings` is 76 bytes** — effectively an empty catalogue. The
   app is English-only in code, with user-facing strings inline in views. There is no localisation
   infrastructure to port.
