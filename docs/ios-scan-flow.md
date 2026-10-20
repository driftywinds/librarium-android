# iOS Barcode / ISBN Scan → Add-Book Flow — Implementation Spec

Source of truth: `librarium-ios-main` (Swift 6 / SwiftUI, iOS 26+).
All paths below are relative to `librarium-ios-main/` unless stated otherwise. Line numbers are from the
files as they exist in this checkout.

Two editions share one codebase and one data model:

* **Self-hosted** — a real Librarium server (`librarium-api`, Go). The app renders; the server owns lookup,
  dedup and enrichment logic (`AGENTS.md:14` "The API owns the logic").
* **Lite** — everything on device: SwiftData rows, `local://<account-uuid>` synthetic "server" URLs
  (`Librarium/Models/ServerAccount.swift:64-66`), iCloud sync.

The scanner is iOS-only: `AGENTS.md:11-12` calls this repo "the only client with a barcode scanner", and
`README.md:34` advertises "Barcode scanner for quick ISBN lookup and library matching". An Android port is
therefore a new feature, not a port of an existing Android implementation — this document is the contract it
must match.

---

## 1. Entry points & UX flow

### 1.1 Where the scanner is reachable from

| Entry point | Code | Notes |
| --- | --- | --- |
| Center Scan FAB in the floating tab bar | `Librarium/Redesigned/RedesignedAppShell.swift:107-137` — `EditorialTabBar(onScan: { showScan = true })`, `.fullScreenCover(isPresented: $showScan) { RedesignedScanFlow(...) }` | **The primary path.** Presented as a `fullScreenCover`, not a sheet. |
| "Add Book" legacy sheet → "Scan Barcode" button | `Librarium/Views/Books/AddEditBookSheet.swift:172-177` (button) and `:139-149` (`.sheet(isPresented: $showScanner) { BarcodeScannerView(...) }`) | Older path. Scans → fills the ISBN field → calls `lookupISBN` → user picks a result row → `applyLookup` copies fields into the form and switches to the Manual tab (`:208-222`). |
| Manual-entry sheet inside the redesigned flow | `Librarium/Redesigned/RedesignedScanFlow.swift:266-283` (camera) and `:1630-1642` (no-match view) both set `showManualEntry = true` | A sheet, presented from the flow root at `:60-69`, `.presentationDetents([.large])`. |

`onOpenBook` is threaded from the shell into the flow:

```swift
// Librarium/Redesigned/RedesignedAppShell.swift:118-137
.fullScreenCover(isPresented: $showScan) {
    RedesignedScanFlow(
        onClose: { showScan = false },
        onOpenBook: { library, bookID in
            showScan = false
            if let account = appState.accounts.first(where: { $0.url == library.serverURL }),
               appState.activeSource?.id != account.id {
                appState.setActiveSource(id: account.id)
            }
            pendingOpenBookID = bookID
            selectedTab = .collection
        }
    )
}
```

Note the FAB is always present; there is no per-library or per-mode gating of the scanner.

### 1.2 The full journey (redesigned flow)

`RedesignedScanFlow` (`:17-78`) is a two-phase state machine:

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:32-35, 37-59
private enum Phase: Equatable {
    case camera
    case result(isbn: String)
}
...
case .camera:
    RedesignedScanCameraView(
        onScan: { isbn in
            let normalized = normalize(isbn: isbn)
            phase = .result(isbn: normalized)
        },
        onManualEntry: { showManualEntry = true },
        onClose: onClose
    )
case .result(let isbn):
    RedesignedScanResultView(isbn: isbn, onScanAnother: { phase = .camera }, ...)
```

The journey:

1. **Camera phase** (`RedesignedScanCameraView`, `:85-302`). Full-screen `AVCaptureVideoPreviewLayer` with an
   editorial overlay: dimmed mask with a rounded cutout (`:288-301`, `280×180` cutout), corner brackets and an
   animated laser line (`:174-224`, target frame `260×160`), help copy at `y = -(maskH/2 + 40)`, a two-option
   mode toggle at `y = +maskH/2 + 40`, top-right close X (`:144-159`), and a bottom "Enter ISBN or title"
   button (`:266-283`).
   * Copy: title **"Scan a barcode"**, subtitle **"Hold steady — ISBN, UPC, or EAN."** (`:161-172`).
2. **First successful decode** → `hasFiredScan = true`, `onScan(value)` (`:106-112`); the value is normalized
   (`normalize(isbn:)`, `:75-77`) and `phase` becomes `.result(isbn:)`.
3. **Result phase** (`RedesignedScanResultView`, `:677-2159`). On appear, `.task(id: isbn) { await runLookup() }`
   (`:818`). The body (`:776-848`) renders, in order: `topBar`, `coverHero`, then either
   `ownershipCard → librarySection → mediaAndStatusRow → moreOptionsSection` (when `lookup != nil`) or
   `errorView(message:)`; a `safeAreaInset(edge: .bottom)` holds `addCTA`. The task id is keyed on the ISBN
   because "Enter manually" re-enters the same `case .result` branch and so keeps view identity (`:813-818`).
4. **`runLookup()` performs three things in parallel** (`:1766-1820`): metadata lookup, fan-out of
   `LibraryService.list()` across every account, then ownership checks per library. Library rows show a
   spinner until each one answers (`:1816-1818`).
5. **User adjusts** media type, read status, and optionally format / genres / tags under "MORE OPTIONS".
6. **CTA** (label from `ctaLabel(for:)`, `:1755-1762`):
   * `"Added"` — after a successful add,
   * `"Pick a library"` — no library selected,
   * `"Add another copy to \(library.name)"` — ownership match found,
   * `"Add to \(library.name)"` — normal case.
   When the book is already held in the selected library a **secondary** button `"View in \(library.name)"`
   appears (`:1713-1736`) and calls `onOpenBook(library, match.bookId)`.
7. **After a successful add**: success haptic, `addedSuccess = true`, a 700 ms sleep, then `onScanAnother()`
   returns to the camera so the user can scan the next book (`:2014-2019` server path, `:1953-1957` Lite path).

### 1.3 Duplicate detection / "already in library"

The result screen asks *every* library the user has an account for, not just the selected one
(`:1074-1080`: "Scanning is the one question that is not scoped to a collection"). Findings are surfaced twice:

* **`ownershipCard`** (`:1081-1121`) — green card, header `"You have this"` or
  `"You have this in N libraries"`, plus a spinner while any library is still pending. Each held library gets
  a `heldRow` (`:1123-1167`) showing a device/server glyph, library name, the account name when there is more
  than one source, `"\(copyCount) copies"` when `copyCount > 1`, and an `"Open"` affordance.
* **`libraryRow`** (`:1248-1298`) — per-library row inside "ADD TO", showing `ownedBadge(copies:)`
  (`:1300-1313`, `"N Copy"` / `"N Copies"`) when owned, a `ProgressView` when
  `ownership[clientKey] == nil` (in flight), or a checkmark when merely selected.

Tapping an owned row still selects the library; the CTA then reads "Add another copy to …". The inline comment
at `:1282-1284` states the server increments the existing edition's copy count when the ISBN matches — i.e.
adding a duplicate is deliberate and non-destructive.

### 1.4 Multiple matches

There is no "pick one of N" step for an ISBN scan. The result screen consumes a **single merged**
`ISBNLookupResult` (`LiteMetadataAggregator.merge`, `Librarium/Services/LiteMetadataProvider.swift:124-155`),
and `"Match found · \(lookup.providerDisplay)"` is rendered as a badge (`:1030-1040`). Multiple matches are only
surfaced on the **title search** path in `ManualEntrySheet` (`:415-498`), where one row per ISBN is listed and
the user's tap chooses which ISBN to carry into the result screen (`:466-468`).

### 1.5 No match / failure

`errorView(message:)` (`:1609-1649`) shows a warning triangle and a title that depends on
`scannedLooksLikeUPC(_:)` (`:1656-1663`: 12 digits → true; 13 digits not starting `978`/`979` → true):

* UPC-looking → title **"Not an ISBN"**, body
  *"This looks like a UPC barcode, not an ISBN. Find the ISBN inside the front cover, or search by title instead."*
  (the underlying `message` is discarded),
* otherwise → title **"No match"** with the `message` text.

Buttons: **"Scan another"** (→ `onScanAnother()`) and **"Enter manually"** (→ `onManualEntry()`) (`:1626-1643`).

The message strings come from `runLookup()` (`:1781-1795`):

| Outcome | Message |
| --- | --- |
| `.notFound` | `"No results for \(isbn)."` |
| `.noSourceHasIt` | `"None of the metadata sources have \(isbn). The book is probably fine; the sources just don't list it. Add it by hand, or ask an admin to enable another source."` (written as a multi-line Swift string literal at `:1788-1792`) |
| `.failed(message)` | the message verbatim |

`LookupOutcome` is declared at `:2069-2077`. Network-failure wording specifically comes from
`:2124` (`"No metadata sources are enabled. Turn one on in Settings → Metadata sources."`) and `:2135-2136`
(`"Couldn't reach \(names) to look up this ISBN."`, names joined with `" or "`).

Library-load failure is separate (`:1799-1805`): if no libraries came back and not every server answered,
`librariesError = "Couldn't reach your server. Check the connection and pull to refresh."`; that string is shown
as an `InlineBanner(tone: .warn, ...)` in the library section (`:1196-1198`). If libraries are empty *and* every
server answered, the app shows `noLibrariesYet` (`:1219-1246`): **"No libraries yet"**, *"A library is where a
book goes. Make one and this book lands in it."*, plus a **"Create a library"** button that opens
`CreateLibrarySheet` through `activeSheet = .createLibrary` (`:835-845`).

`addError` (a failed write) is rendered as red text under the sections (`:792-798`) — the CTA stays usable.

---

## 2. Barcode capture

### 2.1 Two scanners exist; they are byte-for-byte twins in behaviour

* Redesigned flow → `HeadlessBarcodeScanner` / `HeadlessScannerVC`
  (`Librarium/Redesigned/RedesignedScanFlow.swift:575-671`). "Headless" = no chrome; SwiftUI draws the overlay.
* Legacy `AddEditBookSheet` → `BarcodeScannerView` / `ScannerViewController`
  (`Librarium/Views/Books/BarcodeScannerView.swift:5-148`), which adds its own overlay (250 × 112.5 rounded-8
  frame, label `"Align barcode within the frame"`, `:101-127`) and a `Cancel` button (`:87-99`).

Both do exactly the same capture setup. `HeadlessScannerVC.setupSession()`
(`:616-640`):

```swift
let session = AVCaptureSession()
guard let device = AVCaptureDevice.default(for: .video),
      let input = try? AVCaptureDeviceInput(device: device),
      session.canAddInput(input) else {
    showPermissionError()
    return
}
session.addInput(input)

let output = AVCaptureMetadataOutput()
guard session.canAddOutput(output) else { return }
session.addOutput(output)
output.setMetadataObjectsDelegate(self, queue: .main)
output.metadataObjectTypes = [.ean13, .ean8, .code128]

let preview = AVCaptureVideoPreviewLayer(session: session)
preview.frame = view.layer.bounds
preview.videoGravity = .resizeAspectFill
view.layer.addSublayer(preview)
```

**Accepted symbologies — exactly three**: `EAN-13`, `EAN-8`, `Code 128`
(`RedesignedScanFlow.swift:630`, `BarcodeScannerView.swift:60`).

Notably **absent**: `UPC-A` (`EAN-13` is the superset encoding for EAN-prefixed UPCs, and a bare 12-digit UPC-A
is not requested), `UPC-E`, `EAN-5`/`EAN-2` add-ons, `ITF`/`ITF-14`, `Code 39`, `QR`, `DataMatrix`, `PDF417`.
There is no `supplementalCode` handling.

Other capture facts:

* Delegate queue is `.main` (`:629`).
* Session start/stop is off the main thread: `DispatchQueue.global(qos: .userInitiated).async` (`:607`, `:639`),
  and it is stopped in `viewWillDisappear` (`:611-614`) and in the delegate on a hit (`:647`).
* No `AVCaptureVideoDataOutput`, no torch/flash control, no zoom, no focus/exposure configuration, no
  orientation handling, no region-of-interest (`rectOfInterest`) — the scan target frame drawn by SwiftUI is
  **purely cosmetic** and does not restrict where a barcode may be found.
* Permission failure is a plain `UILabel`, not an alert: **"Camera access required.\nEnable it in Settings."**
  (`:657`, and `BarcodeScannerView.swift:132`). There is no button that deep-links to Settings.
* `NSCameraUsageDescription` (both Debug/Dev and Release) is
  `"Scan ISBN barcodes and photograph book covers"` (`Librarium.xcodeproj/project.pbxproj`,
  `INFOPLIST_KEY_NSCameraUsageDescription`).

### 2.2 Single-shot, not continuous

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:642-652
func metadataOutput(_ output: AVCaptureMetadataOutput,
                    didOutput objects: [AVMetadataObject],
                    from connection: AVCaptureConnection) {
    guard let obj = objects.first as? AVMetadataMachineReadableCodeObject,
          let value = obj.stringValue else { return }
    captureSession?.stopRunning()
    let generator = UINotificationFeedbackGenerator()
    generator.prepare()
    generator.notificationOccurred(.success)
    onScan?(value)
}
```

* Takes `objects.first` only — the first object in the frame, not the "best" one.
* Stops the session immediately; one barcode per capture.
* Success haptic via `UINotificationFeedbackGenerator.notificationOccurred(.success)`.
* A second layer of debounce lives in the view: `guard !hasFiredScan else { return }; hasFiredScan = true`
  (`:106-112`). Because `RedesignedScanCameraView` is recreated when `phase` returns to `.camera`, the "scan
  another" loop resets naturally.
* **Continuous scanning is achieved by looping**: after an add, `onScanAnother()` → `phase = .camera` after a
  700 ms pause (`:1955`, `:2018`). The camera view is fully rebuilt, so permission is not re-prompted but the
  session restarts.

### 2.3 "Quick rate" mode is a stub

`ScanMode { case add, rate }` (`:94`) with a segmented toggle labelled **"Add to library"** / **"Quick rate"**
(`:231-259`). The doc comment at `:226-230` is explicit: *"Quick rate doesn't branch the result flow yet … for
v1 it's wired as a no-op selector so the UI matches the mockup. Real divergence lands when the Rate sheet ships
(mockup card 29)."* The only rate-related asset present is `Librarium/Views/Scan/RatingStars.swift` (10-point
rating rendered as 5 half-stars), which the redesigned **book detail** screen uses, not the scan flow.
**Do not reimplement "quick rate" as functional** — it is inert.

### 2.4 Normalization — the exact code

There is exactly one normalizer in the redesigned flow, called both for camera hits and for manual entry:

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:72-77
/// Normalize a scanned barcode value before lookup. EAN-13 codes can
/// arrive with leading zero from some scanners; strip non-digits and
/// pass through.
private func normalize(isbn raw: String) -> String {
    raw.filter { $0.isNumber || $0 == "X" || $0 == "x" }.uppercased()
}
```

Call sites: `:43` (camera) and `:63` (manual sheet). `ManualEntrySheet.normalizedISBN` is a duplicate of the
same expression (`:555-557`).

So normalization is: **remove every character that is not a decimal digit or `X`/`x`, then uppercase.**
Consequences worth copying deliberately:

* Hyphens and spaces in a typed ISBN are stripped (`978-0-306-40615-7` → `9780306406157`).
* `x` → `X`, so a lowercase check digit works.
* Letters other than X are dropped rather than rejected — `"ABC9780306406157"` normalizes to a valid-looking
  ISBN. Nothing validates the input's shape beyond length on the manual path.
* **No checksum validation exists anywhere in the capture → normalize → lookup path.** Neither EAN-13
  (`(10 - sum % 10) % 10`) nor ISBN-10 (`(11 - sum % 11) % 11`) check digits are verified on the scanned value.
  Check-digit math exists only to *derive* new candidates (`Librarium/Services/ISBNCandidates.swift:92-115`) and
  to build the UPC-A heuristic (`:57-67`). A barcode with a consistent misread therefore flows into the lookup
  and comes back as "No match" rather than "invalid checksum".
* **Leading zeros are preserved.** `filter` does not trim; a scanner emitting `09780306406157` keeps the zero.
* The "manual vs ISBN" decision in the sheet is length-based, not checksum-based (`:559-568`):

```swift
/// True when what was typed reads as an ISBN rather than a title: only
/// digits, spaces and hyphens, and the right number of them.
private var isISBN: Bool {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return false }
    let allowed = trimmed.allSatisfy { $0.isNumber || $0 == "-" || $0 == " " || $0 == "X" || $0 == "x" }
    guard allowed else { return false }
    let n = normalizedISBN.count
    return n == 10 || n == 13
}
```

### 2.5 ISBN-10 ↔ ISBN-13 and the UPC-A heuristic

This is the only place the code reasons about barcode *form*. `ISBNCandidates.expand`
(`Librarium/Services/ISBNCandidates.swift:20-49`), quoted in full because the branching is the spec:

```swift
static func expand(_ raw: String) -> [String] {
    let digits = raw.filter { $0.isNumber || $0 == "X" || $0 == "x" }
    var out: [String] = [raw]

    switch digits.count {
    case 10:
        if let derived = isbn13(fromIsbn10: digits.uppercased()) {
            out.append(derived)
        }
    case 13 where digits.hasPrefix("978") || digits.hasPrefix("979"):
        if let derived = isbn10(fromIsbn13: digits) {
            out.append(derived)
        }
    case 13 where digits.hasPrefix("0"):
        // EAN-13 representation of a UPC-A (leading "0" + 12 UPC
        // digits). Strip the prefix and apply the UPC heuristic
        // below on the underlying 12-digit code.
        let upc = String(digits.dropFirst())
        if let derived = upcAToISBN13Heuristic(upc) {
            out.append(derived)
        }
    case 12:
        if let derived = upcAToISBN13Heuristic(digits) {
            out.append(derived)
        }
    default:
        break
    }
    return out
}
```

Conversion rules (`:73-87`):

* `isbn13(fromIsbn10:)` — take the first 9 characters, require all digits, prepend `"978"`, append
  `ean13CheckDigit`.
* `isbn10(fromIsbn13:)` — require exactly 13 characters **and** the `978` prefix (`979` has no ISBN-10 form),
  take characters 4…12, append `isbn10CheckDigit`.

Check digits (`:92-115`):

* `ean13CheckDigit(for body:)` — 12 digits, weights `1,3,1,3,…`, `(10 - sum % 10) % 10`.
* `isbn10CheckDigit(for body:)` — 9 digits, weights `10,9,…,2`, `(11 - sum % 11) % 11`, with `10` rendered `"X"`.

Both return `"0"` when the input length is wrong.

UPC-A heuristic (`:57-67`) — explicitly documented as best-effort:

```swift
static func upcAToISBN13Heuristic(_ upc: String) -> String? {
    let digits = upc.compactMap { $0.wholeNumberValue }
    guard digits.count == 12 else { return nil }
    // UPC-A on books usually has the ISBN-10 body in positions
    // 1..9, i.e. after the leading number-system digit. This used to
    // take `prefix(9)`, which includes that leading digit and so
    // shifted every derived ISBN by one place: the heuristic never
    // matched anything and only cost an extra round-trip.
    let body = "978" + String(upc.dropFirst().prefix(9))
    return body + String(ean13CheckDigit(for: body))
}
```

The returned array is `[raw] + derived`; **the original scanned string is always tried first**, and dedup is the
caller's job (`:16-18`). In `OpenLibraryService.isbn` the caller wraps it in a `Set` before iterating
(`Librarium/Services/OpenLibraryService.swift:35`), which loses ordering — see §9.

---

## 3. Lookup cascade

### 3.1 Which account is asked

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:2152-2158
private func lookupCapableAccount() -> ServerAccount? {
    if let active = appState.activeSource,
       active.kind == .remote, !active.needsReauth {
        return active
    }
    return appState.accounts.first { $0.kind == .remote && !$0.needsReauth }
}
```

Preferred account = the collection currently open, when it is a real server; otherwise the first remote account
that does not need re-auth. `needsReauth` is `accessToken.isEmpty || refreshToken.isEmpty` for remote accounts,
always `false` for local ones (`Librarium/Models/ServerAccount.swift:50-54`).

`ManualEntrySheet.lookupAccount()` (`:548-553`) is the same logic duplicated.

### 3.2 The cascade, in order

`RedesignedScanResultView.lookupMetadata(isbn:account:appState:)` (`:2084-2139`) — **Path 1 first, Path 2 as the
fallback**, with distinct terminal outcomes (`LookupOutcome`, `:2069-2077`).

**Path 1 — server (`/api/v1/lookup/isbn/{isbn}`)**, only when `lookupCapableAccount()` returned non-nil:

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:2089-2110
var serverAnswered = false
if let account {
    let client = await appState.makeClient(serverURL: account.url)
    do {
        let results = try await LookupService(client: client).isbn(isbn)
        serverAnswered = true
        let named = results.filter { !$0.title.isEmpty }
        if let match = LiteMetadataAggregator.merge(named) {
            return .success(match)
        }
    } catch {
        // fall through and try on-device
    }
}
```

* Empty titles are filtered out before merging (`:2102`).
* The request itself: `GET /api/v1/lookup/isbn/\(isbn)` (`Librarium/Services/LookupService.swift:6-8`).
  **The ISBN is interpolated into the path with no percent-encoding** — fine for digits and `X`, but the
  normalizer can leave nothing else in, so in practice the URL is safe; there is no explicit
  `addingPercentEncoding` here, unlike the query-based endpoints (`:14`, `:19`).
* Any throw (network, 401 after one refresh retry, decode) is swallowed and falls through to Path 2. **There is
  no explicit handling of 429 / rate limiting on this path.**

**Path 2 — on-device providers** (`:2111-2138`):

```swift
let providers = LiteMetadataSettings.activeProviders
guard !providers.isEmpty else {
    return serverAnswered
        ? .noSourceHasIt
        : .failed("No metadata sources are enabled. Turn one on in Settings → Metadata sources.")
}
let outcome = await LiteMetadataAggregator(providers: providers).lookupDetailed(isbn: isbn)
let named = outcome.results.filter { !$0.title.isEmpty }
if let merged = LiteMetadataAggregator.merge(named) {
    return .success(merged)
}
if outcome.allFailed {
    let names = providers.map(\.displayName).joined(separator: " or ")
    return .failed("Couldn't reach \(names) to look up this ISBN.")
}
return serverAnswered ? .noSourceHasIt : .notFound
```

`activeProviders` (`Librarium/Services/LiteMetadataSettings.swift:46-54`) is
`allProviders.filter(\.isConfigured)` where `allProviders = [HardcoverService(), OpenLibraryService()]` —
**Hardcover first, Open Library second**. `isConfigured` means "switched on in settings **and** (for Hardcover)
a key is stored". Defaults: `openlibrary: true`, `hardcover: false` (`:15-18`).

So the effective cascade for a **self-hosted** install is:

1. `GET {serverURL}/api/v1/lookup/isbn/{isbn}` — the server fans out across its own providers. Per
   `Librarium/Services/LiteMetadataProvider.swift:8-10`, *"Self-hosted installs get their metadata through the
   api, which fans out across six providers server-side."* `AGENTS.md:34` echoes that the primary server drives
   global lookups such as an ISBN scan. **The six providers, their order and their URLs are not in this repo —
   see §10.**
2. If that throws or yields nothing with a title: `HardcoverService.lookup(isbn:)` (only if enabled + key).
3. Then `OpenLibraryService.lookup(isbn:)`.

For a **Lite-only** install, step 1 is skipped entirely (no remote account) and it starts at step 2.

### 3.3 Provider: Hardcover

`Librarium/Services/HardcoverService.swift`.

* `id = "hardcover"`, `displayName = "Hardcover"` (`:18-19`).
* Endpoint: `https://api.hardcover.app/v1/graphql` (`:25`), POST, `Content-Type: application/json`,
  `Authorization: Bearer <key>`, `timeoutInterval = 15` (`:121-132`). The key is read from the Keychain under
  `lite.hardcover.apiKey` (`:24-30`) and `normalize(key:)` strips repeated leading `bearer ` prefixes so a
  pasted `Bearer eyJ…` does not become `Bearer Bearer eyJ…` (`:43-50`).
* The GraphQL document is a **port of the server's Go provider** — the header comment at `:8-11` says the
  documents "match `internal/providers/books/hardcover.go` so both clients ask for the same fields":

```graphql
# Librarium/Services/HardcoverService.swift:56-77
query BookByISBN($isbn: String!) {
  editions(
    where: { _or: [{ isbn_13: { _eq: $isbn } }, { isbn_10: { _eq: $isbn } }] }
    limit: 1
  ) {
    isbn_13
    isbn_10
    pages
    release_date
    image { url }
    publisher { name }
    language { language }
    book {
      title
      description
      cached_tags
      contributions { author { name } }
    }
  }
}
```

  `limit: 1` — a single edition, matched on either ISBN field.
* Field mapping (`decodeEdition`, `:196-223`): `title` ← `book.title` (required; absent → result dropped),
  `authors` ← `book.contributions[].author.name`, `publisher` ← `publisher.name`,
  `publishDate` ← `release_date`, `coverUrl` ← `image.url`, `language` ← `language.language`,
  `pageCount` ← `pages`, `categories` ← `decodeGenres(book.cached_tags)` (reads `cached_tags["Genre"][].tag`
  only, `:259-265`), `description` ← `book.description`. `subtitle` is always `""`.
  ISBN fallback: if the edition's own ISBN is empty and the queried string is the right length, the queried
  value is used (`:215-216`).
* Status handling (`:136-151`): `200` → body; `401`/`403` → `LiteMetadataError.invalidKey`;
  `429` → `LiteMetadataError.rateLimited`; anything else → `URLError(.badServerResponse)`, **deliberately thrown**
  rather than returning empty data (comment at `:144-149`: an empty body would be swallowed by the caller's
  `try?` and read as "no results").
* Free tier is documented as **60 requests/minute** (`:15-16`).

### 3.4 Provider: Open Library

`Librarium/Services/OpenLibraryService.swift`. Keyless and anonymous; `id = "openlibrary"`,
`displayName = "Open Library"` (`:336-337`). `isConfigured` is purely the user's toggle
(`:342`), `isAuthenticated = false` (`:343`).

`isbn(_:)` (`:26-56`) iterates candidates and tries **two endpoints per candidate, in order**:

1. `GET https://openlibrary.org/api/books?bibkeys=ISBN:<isbn>&format=json&jscmd=data` (`fetchBooksAPI`,
   `:153-171`). The response is a dict keyed by the literal `"ISBN:<isbn>"`; a missing key or non-200 yields `[]`.
   Request timeout 10 s (`:162`).
   Decode (`decodeBooksAPI`, `:231-287`): `title` required; `subtitle`; `authors` ← `authors[].name`;
   `publisher` ← first of `publishers[].name`; `publishDate` ← `publish_date`; `pageCount` ← `number_of_pages`;
   `isbn10` ← `identifiers.isbn_10[0]` else the queried value when it is 10 chars; `isbn13` ←
   `identifiers.isbn_13[0]` else the queried value when 13 chars; `coverUrl` ← `cover.large` → `cover.medium` →
   `cover.small`; `categories` ← `subjects[].name` (nil when empty); `description` is **always `""`** (`:267-270`);
   `language` is **always `""`** (`:283`).
2. `GET https://openlibrary.org/isbn/<isbn>.json` (`fetchISBNEndpoint`, `:175-184`), only if endpoint 1 gave
   nothing. Because this shape carries author *refs*, names are resolved by a **concurrent fan-out** to
   `https://openlibrary.org{key}.json` per `authors[].key` (`resolveAuthorNames`, `:195-222`, 10 s timeouts,
   order restored by index, unresolvable refs dropped).
   Decode (`decodeISBNEndpoint`, `:295-329`): `publishers` is a `[String]` (first wins); `isbn_10`/`isbn_13` are
   string arrays; cover = `https://covers.openlibrary.org/b/id/<coverId>-L.jpg` from `covers[0]`; `pageCount`
   ← `number_of_pages`; `categories` is **nil**; `description` and `language` are `""`.

Candidate loop semantics (`:36-55`): a `throw` is recorded and the loop continues; a candidate that returns `[]`
from both endpoints falls through to the next candidate with no error; if **every** candidate threw, the last
error is rethrown so the UI can say "couldn't reach Open Library"; otherwise `[]` means "genuinely not found".
The candidate set is `Array(Set(ISBNCandidates.expand(trimmed)))` (`:35`).

`OpenLibraryService.lookup(isbn:)` (the `LiteMetadataProvider` conformance, `:348-372`) additionally folds in
`worksDescription(isbn:)` — a **third and fourth round-trip** (`/isbn/<isbn>.json` → `works[0].key` →
`/works/<key>.json`, `:130-149`, polymorphic `description` as either a string or `{value: …}`) — but *only* when
the first result has an empty description. The comment at `:345-347` notes the scan flow deliberately skips this
to keep the scan fast; it is the refresh flow that uses it.

### 3.5 Free-text search (manual entry)

`ManualEntrySheet.search(_:)` (`:510-544`):

1. Server first, if a remote account exists: `GET /api/v1/lookup/books?q=<percent-encoded>`
   (`Librarium/Services/LookupService.swift:13-16`, encoding via
   `addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`).
   A throw is recorded into `failure`.
2. If the server returned nothing (or there is no server):
   `LiteMetadataAggregator(providers: LiteMetadataSettings.activeProviders).search(query:)`. A non-empty local
   result clears `failure`.
3. Rows **without an ISBN are dropped**, and rows are deduplicated on `isbn13.isEmpty ? isbn10 : isbn13` —
   first claimant wins (`:537-541`):
   ```swift
   var seen = Set<String>()
   results = found.filter { item in
       let key = item.isbn13.isEmpty ? item.isbn10 : item.isbn13
       return !key.isEmpty && seen.insert(key).inserted
   }
   searchError = results.isEmpty ? failure : nil
   ```
4. `searchError` is shown only when the filtered list is empty and something threw.

Server-side search for Hardcover is the Typesense query
`search(query: $query, query_type: "Book", per_page: 15) { results }` (`HardcoverService.swift:79-85`), decoded
from `data.search.results.hits[].document` (`:106-116`, `decodeSearchDoc` at `:225-253`: `title`,
cover from `image_url` **or** nested `image.url`, `isbns[]` split by length into 13/10 — note `publisher`,
`publishDate`, `language`, `pageCount` are all empty/nil on search hits).

Open Library search: `GET https://openlibrary.org/search.json?q=<query>&limit=15&fields=key,title,subtitle,author_name,first_publish_year,isbn,cover_i,number_of_pages_median,publisher,language`
(`OpenLibraryService.swift:63-87`). Decode at `:89-117`: `title` required; `isbn10`/`isbn13` from the `isbn[]`
array by length; cover from `cover_i` → `https://covers.openlibrary.org/b/id/<id>-L.jpg`;
`publishDate` from `first_publish_year`; `pageCount` from `number_of_pages_median`; `description` `""`;
`categories` nil.

Typing behaviour: a 400 ms debounce, a minimum query length of 3, and cancellation of the in-flight task
(`:349-370`). Rationale comment: *"Typing a title fires a request per keystroke otherwise, and every one of them
fans out across the providers server-side."* The trigger only fires when the input is **not** ISBN-shaped.

### 3.6 Merge / ranking rules

`LiteMetadataAggregator` (`Librarium/Services/LiteMetadataProvider.swift:54-156`) is used for **both** the server
results and the on-device results, which is how a single merged result reaches the UI either way.

Fan-out (`run`, `:96-117`) runs every configured provider concurrently, tags each with its list index, and
**sorts back into provider order** before flattening — explicitly so the winner is not a race (comment `:88-95`:
"the same book could come back with different metadata run to run").

`merge` (`:124-155`):

* Returns `nil` when the input is empty.
* Every string field via `pick(_:)` (`:126-128`): **first non-empty value in list order wins.**
* `authors` — first non-empty array. `pageCount` — first `> 0`. `categories` — first non-empty array.
* `provider` ← the first result's `provider` (so `.provider` names the top-priority source even if it supplied
  nothing).
* `providerDisplay` ← **every** contributing provider's `providerDisplay`, deduped in order, joined with
  `" + "` (`:133-141`). This is what the `"Match found · …"` badge shows.

Because the server path merges `results.filter { !$0.title.isEmpty }` with no reordering, the **effective
priority on the server path is whatever order `/api/v1/lookup/isbn/{isbn}` returns**, which the client does not
know. The comment at `:2096-2101` records the reasoning (Hardcover wins on title and carries no categories, so
one whole answer must not be taken).

### 3.7 Partial failure

* On-device: a single provider that throws contributes nothing; the others still count
  (`LiteMetadataProvider.swift:71-72`). `Outcome.allFailed` (`:65-69`) is `collected.allSatisfy { $0.1 == nil }`
  and is what distinguishes an outage from a genuine miss.
* Server: a throw or an all-empty result silently degrades to the on-device path.
* Rate limits: `HardcoverService.post` maps `429` to a user-readable string
  (`"Hardcover is rate limiting requests. Try again in a minute."`, `LiteMetadataError.rateLimited`,
  `:42-44`) — but `LiteMetadataAggregator.run` swallows thrown errors with `try?` (`:106`), so a rate-limited
  Hardcover is invisible unless it was the **only** configured provider, in which case the scan shows
  `"Couldn't reach Hardcover to look up this ISBN."` The precise `LiteMetadataError` text surfaces on the
  Hardcover settings screen instead (see §8).
* No retry logic beyond `APIClient`'s single 401 refresh-and-retry (`Librarium/Services/APIClient.swift:214-219`).
* Timeouts: 10 s for every `APIClient` request (`:182`), 10 s for Open Library's direct `URLSession` calls, 15 s
  for Hardcover.

---

## 4. Metadata model

### 4.1 `ISBNLookupResult` — the wire shape for both paths

```swift
// Librarium/Models/Lookup.swift:3-22
struct ISBNLookupResult: Codable {
    let provider: String
    let providerDisplay: String
    let title: String
    let subtitle: String
    let authors: [String]
    let publisher: String
    let publishDate: String
    let isbn10: String
    let isbn13: String
    let description: String
    let coverUrl: String
    let language: String
    let pageCount: Int?
    let categories: [String]?
}

extension ISBNLookupResult: Identifiable {
    var id: String { "\(provider)-\(isbn13.isEmpty ? isbn10 : isbn13)" }
}
```

**No explicit `CodingKeys`.** The JSON keys are produced by `APIClient`'s
`keyDecodingStrategy = .convertFromSnakeCase` (`APIClient.swift:68-72`), so the server must send:

`provider`, `provider_display`, `title`, `subtitle`, `authors`, `publisher`, `publish_date`, `isbn_10`,
`isbn_13`, `description`, `cover_url`, `language`, `page_count`, `categories`.

**Every scalar is non-optional** (only `pageCount` and `categories` are optional). A server response missing
`subtitle`, `publisher`, `description`, `cover_url` or `language` fails decoding for the whole response —
`APIClient.raw` then throws `.decodingError` (`:206-213`), `LookupService.isbn` throws, and `lookupMetadata`
falls through to the on-device providers. **This is the single most important contract detail for an Android
port.**

The `id` is derived, and collides when two rows share a provider and an ISBN — hence the manual-search dedup
step in §3.5.

`SeriesLookupResult` (`:24-40`) is the sibling shape for series lookups (`/api/v1/lookup/series?q=`), not used by
the scan flow.

### 4.2 What the result screen consumes

`lookup.coverUrl` → `BookCoverImage` (`:1020-1028`); `lookup.providerDisplay` → the match badge (`:1034`);
`lookup.title` (`:1042`); `"ISBN \(lookup.isbn13.isEmpty ? lookup.isbn10 : lookup.isbn13)"`
(`:1052` — the **displayed** ISBN prefers 13); `lookup.categories` → `applyGenreDefaults()` (`:1489-1492`) matched
against the instance's genre list by exact (case-insensitive, whitespace-trimmed) name
(`genresMatching`, `:1499-1503`).

The byline is authors only, plus a year:

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:1063-1070
private func authorMeta(for lookup: ISBNLookupResult) -> String {
    var parts: [String] = lookup.authors
    let year = String(lookup.publishDate.prefix(4))
    if !year.isEmpty && Int(year) != nil {
        parts.append(year)
    }
    return parts.joined(separator: " · ")
}
```

i.e. `authors` joined by `" · "` with a trailing 4-character year **only when those first four characters parse
as an `Int`**. `publisher`, `pageCount`, `language` and `subtitle` are **not** shown on the scan result screen at
all — they are carried into the create request (§6.2) and nothing else. (Contrast the manual-search rows, which
show authors · year · `providerDisplay`, `:500-506`.)

While the lookup is in flight the hero renders a 140 × 210 placeholder with a `ProgressView` and the copy
`"Looking up \(isbn)…"` (`:1011-1018`).

### 4.3 Read status enum

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:761-774
private enum ReadStatus: Hashable {
    case unread, wantToRead, reading, read
    var apiValue: String {
        switch self {
        case .unread:     return "unread"
        case .wantToRead: return "want_to_read"
        case .reading:    return "reading"
        case .read:       return "read"
        }
    }
}
```

Segments labelled Unread / Want / Reading / Read (`:1378-1398`). Note `Book.userReadStatus` also documents
`"did_not_finish"` (`Librarium/Models/Book.swift:44`) — the scanner cannot select it.

---

## 5. Matching against the library

### 5.1 Candidate set

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:1891-1893
let lookupISBN13 = isbn.count == 13 ? isbn : (lookup?.isbn13 ?? "")
let lookupISBN10 = isbn.count == 10 ? isbn : (lookup?.isbn10 ?? "")
let candidates = Set([isbn, lookupISBN13, lookupISBN10].filter { !$0.isEmpty })
```

So up to **three** identifiers are tried: the scanned/normalized string, plus whichever of the lookup result's
`isbn13`/`isbn10` differs from it. A `Set`, so iteration order is non-deterministic.

### 5.2 Server path

Per library, in a task group (`:1895-1930`):

```swift
let client = await appState.makeClient(serverURL: library.serverURL)
let svc = BookService(client: client)
for candidate in candidates {
    guard let book = try? await svc.byISBN(libraryId: library.id, isbn: candidate) else { continue }
    let editions = (try? await svc.editions(libraryId: library.id, bookId: book.id)) ?? []
    let copies = editions.first(where: { $0.isPrimary })?.copyCount
        ?? editions.first?.copyCount
        ?? 1
    return (library.clientKey, OwnedMatch(bookId: book.id, copyCount: copies))
}
return (library.clientKey, nil)
```

Endpoints:

* `GET /api/v1/libraries/{libraryId}/book-by-isbn/{isbn}` → `Book`
  (`Librarium/Services/BookService.swift:63-65`). A miss is a `404` → `APIError.notFound` → `try?` → next
  candidate. Any other error also just moves to the next candidate — **a server outage is indistinguishable from
  "not owned" here.**
* `GET /api/v1/libraries/{libraryId}/books/{bookId}/editions` → `[BookEdition]` (`:122-124`), fetched **only on
  a hit** ("the copy count lives on the edition, not the book, so it needs a second call", `:1915-1917`).

`OwnedMatch` (`:712-715`) is `{ bookId: String, copyCount: Int }`. Results land in
`ownership: [String: OwnedMatch?]` keyed by `Library.clientKey` (`:717-720`): a **missing key means "still in
flight"**, `.some(nil)` means "confirmed absent". `ownedMatch(for:)` (`:1751-1753`) collapses both to `nil` for
callers that do not care.

Matching is entirely **server-side**. The client does not compare ISBNs itself on this path, so whatever
normalization the server applies to `book-by-isbn` (separators, 10-vs-13 equivalence) governs behaviour.

### 5.3 Lite path

`localOwnsISBN(library:candidates:modelContainer:)` (`:2051-2067`):

```swift
for book in bookCache.books(for: library) {
    let editions = editionCache.editions(for: library, bookId: book.id)
    for edition in editions {
        if candidates.contains(edition.isbn13) || candidates.contains(edition.isbn10) {
            return OwnedMatch(bookId: book.id, copyCount: edition.copyCount)
        }
    }
}
return nil
```

An exact string `Set` membership test on both ISBN fields of every edition, no ISBN canonicalization — so
`9780306406157` and `978-0-306-40615-7` stored in SwiftData would not match, while a match on `isbn10` or
`isbn13` either way does. Also note this can *disagree* with the server path (which delegates equivalence to
the server). It returns the **first** match found and its edition's `copyCount` (not a sum across editions).

### 5.4 Library fan-out

`loadAllLibraries()` (`:1834-1888`): Lite accounts first (`PersistedLibrary` rows matching
`serverAccountID == accountID && deletedAt == nil`, converted with
`toLibrary(serverAccountID:accountName:)`), then one `LibraryService.list()` (`GET /api/v1/libraries`,
`Librarium/Services/LibraryService.swift:7`) per remote account inside a task group, with the account's `url` and
`name` stamped onto each row so the flow can route later calls. A `nil` group result means "did not answer", which
clears `everyServerAnswered` (`:1876-1883`).

Sorting (`:1807-1812`): by `serverName`, then by `name`, both `localizedStandardCompare`. The default selection
is `libraries.first?.clientKey` (`:1813-1815`).

`Library.clientKey` is `"\(serverURL)|\(id)"` (`Librarium/Models/Library.swift:28-41`) and is the identity used
throughout the scan flow — deliberately, so the same library UUID on two servers does not co-select
(`:721-724`). `serverURL`/`serverName` are client-only and excluded from `CodingKeys` (`:43-46`).

`LibraryService.list()` uses `appState.makeClient(serverURL: library.serverURL)`; for a `local://` URL that would
fail, which is why the Lite branch is taken first (`ServerAccount.swift:68-76` documents the "unsupported URL"
footgun).

---

## 6. Saving

Two mutually exclusive write paths, chosen by the library's URL scheme.

### 6.1 Which path is "the server path"

The dispatch is:

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:1940-1942
// Lite library? Write to SwiftData and we're done.
if library.serverURL.hasPrefix("local://"),
   let account = appState.accounts.first(where: { $0.kind == .local && $0.url == library.serverURL }) {
```

So `local://<uuid>` + a matching `kind == .local` account → Lite; **everything else → the HTTP path**. The
HTTP path is the one an Android self-hosted client must implement; the Lite path is a local-database write.

### 6.2 Server path — exact requests

Three requests, in this order. Only the first is required.

**(1) Create the book — required.**

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:1965-1995
let mediaTypeID: String = selectedMediaTypeID
    ?? mediaTypesByServer[library.serverURL]?.first?.id
    ?? ""

let edition = CreateEditionRequest(
    format: selectedFormat,
    language: lookup.language,
    editionName: "",
    narrator: "",
    publisher: lookup.publisher,
    publishDate: lookup.publishDate.isEmpty ? nil : lookup.publishDate,
    isbn10: lookup.isbn10,
    isbn13: lookup.isbn13,
    description: lookup.description,
    pageCount: lookup.pageCount,
    copyCount: 1,
    isPrimary: true
)
let body = CreateBookRequest(
    title: lookup.title,
    subtitle: lookup.subtitle,
    mediaTypeId: mediaTypeID,
    description: lookup.description,
    contributors: [],
    tagIds: Array(selectedTagIDs),
    genreIds: Array(selectedGenreIDs),
    edition: edition
)

let book = try await BookService(client: client).create(libraryId: library.id, body: body)
```

* `POST /api/v1/libraries/{libraryId}/books` (`BookService.swift:67-69`).
* Request models (`BookService.swift:223-268`):

```swift
struct CreateBookRequest: Encodable {
    var title: String
    var subtitle: String = ""
    var mediaTypeId: String = ""
    var description: String = ""
    var contributors: [ContributorInput] = []
    var tagIds: [String] = []
    var genreIds: [String] = []
    var edition: CreateEditionRequest? = nil
}

struct ContributorInput: Encodable {
    var contributorId: String
    var role: String
    var displayOrder: Int
}

struct CreateEditionRequest: Encodable {
    var format: String = "physical"
    var language: String = ""
    var editionName: String = ""
    var narrator: String = ""
    var publisher: String = ""
    var publishDate: String? = nil
    var isbn10: String = ""
    var isbn13: String = ""
    var description: String = ""
    var pageCount: Int? = nil
    var copyCount: Int? = nil
    var isPrimary: Bool = true

    // isbn_10 / isbn_13 need explicit keys — convertToSnakeCase won't add underscore before digits.
    enum CodingKeys: String, CodingKey {
        case format, language
        case editionName  = "edition_name"
        case narrator, publisher
        case publishDate  = "publish_date"
        case isbn10       = "isbn_10"
        case isbn13       = "isbn_13"
        case description
        case pageCount    = "page_count"
        case copyCount    = "copy_count"
        case isPrimary    = "is_primary"
    }
}
```

* Serialization: `.convertToSnakeCase` (`APIClient.swift:62-66`) turns `mediaTypeId` → `media_type_id`,
  `tagIds` → `tag_ids`, `genreIds` → `genre_ids`, `contributorId` → `contributor_id`, `displayOrder` →
  `display_order`. The nested edition object is keyed `edition`. **All JSON keys in one `POST` body:**

  ```json
  {
    "title": "…",
    "subtitle": "…",
    "media_type_id": "…",
    "description": "…",
    "contributors": [],
    "tag_ids": [],
    "genre_ids": [],
    "edition": {
      "format": "paperback",
      "language": "…",
      "edition_name": "",
      "narrator": "",
      "publisher": "…",
      "publish_date": "…",
      "isbn_10": "…",
      "isbn_13": "…",
      "description": "…",
      "page_count": 320,
      "copy_count": 1,
      "is_primary": true
    }
  }
  ```

  `publish_date` and `page_count` are **omitted entirely** when nil (`publishDate == nil` when the provider's
  value was empty; `pageCount` comes straight from the optional lookup field).
* Response: `Envelope<Book>` → `{"data": { …Book… }}` (`APIClient.swift:164-166`, `:206`), decoded with
  `.convertFromSnakeCase`. `Book` is permissive: only `id`, `title`, `createdAt`, `updatedAt` are required; the
  rest `decodeIfPresent` with defaults (`Librarium/Models/Book.swift:59-82`). Note `Book.libraryId` is documented
  as possibly null in modern payloads and defaults to `""`.
* **The scanned ISBN's fate**: it lands on the **edition**, verbatim, in both `isbn_10` and `isbn_13` — whichever
  fields the lookup result filled. If the user scanned an ISBN-10 and the provider echoed it back only in
  `isbn10`, `isbn_13` goes to the server as `""`. The client **never converts** the scanned value for the write
  (it only expands candidates for the *lookup*, §2.5). The scanned string itself is not otherwise persisted —
  it is not stored on the book row, and `mediaTypeName`/`format` are not derived from it.
* `contributors: []` — the scanner **does not create contributors**, even though the lookup has `authors`.
  Author data exists only for Lite writes (§6.3). This is a real, deliberate-looking asymmetry: an Android port
  must decide whether to match it.
* `format` defaults to `selectedFormat`, initialized to `"paperback"` (`:726`), overridden by the Format chips
  under MORE OPTIONS (values from `/api/v1/edition-formats`; `EditionFormatLabels.fallback` =
  `paperback, hardcover, ebook, audiobook`, `Librarium/Services/VocabularyService.swift:125-130`).
* `copyCount: 1` and `isPrimary: true` always.

**(2) Fetch the provider's cover — best effort.**

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:1996-2003
if !lookup.coverUrl.isEmpty {
    try? await BookService(client: client)
        .fetchCover(libraryId: library.id, bookId: book.id, url: lookup.coverUrl)
}
```

`POST /api/v1/libraries/{libraryId}/books/{bookId}/cover/fetch` with body `{"url": "<provider cover URL>"}`
(`BookService.swift:108-114`). The comment at `:102-107` explains why it exists: *"A lookup only hands back a URL
at someone else's host. Until the server has fetched it, the book has no cover of its own: the image shown right
after a scan is the provider's, and it is gone the next time the book is read back."* `try?` — a failure never
blocks the add.

**(3) Apply the chosen read status — conditional and best effort.**

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:2004-2013
if selectedStatus != .unread {
    _ = try? await BookService(client: client)
        .updateMyBook(bookId: book.id,
                      body: UpdateMyBookRequest(readStatus: selectedStatus.apiValue))
}
```

`PUT /api/v1/books/{bookId}/me` (`BookService.swift:172-174`) with `{"read_status": "…"}`.
`UpdateMyBookRequest` is all-optional — omitting a field means "leave it alone"
(`BookService.swift:270-282`). `"unread"` is **not** sent: the comment at `:2004-2008` notes reading state
belongs to the work and the previous per-edition route "wrote where nothing reads from".

Then: success haptic, `addedSuccess = true`, 700 ms sleep, `onScanAnother()`; on throw,
`addError = error.localizedDescription` (`:2014-2022`).

### 6.3 Lite path

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:1943-1957
let picked = mediaTypesByServer[library.serverURL]?
    .first(where: { $0.id == selectedMediaTypeID })
let writer = LiteBookWriter(modelContainer: modelContext.container)
_ = writer.add(
    lookup: lookup,
    to: library,
    serverAccountID: account.id,
    mediaType: picked?.displayName ?? "Book",
    readStatus: selectedStatus.apiValue
)
```

`LiteBookWriter.add` (`Librarium/Services/LiteBookWriter.swift:20-107`) writes a `PersistedBook` and a primary
`PersistedEdition` from hand-built dictionaries, **with literal, non-converted key names** because it uses a
baret `JSONDecoder` (`:340-345`). Book dict keys (`:40-57`): `id, libraryId, title, subtitle, mediaTypeId: "",
mediaType, description, createdAt, updatedAt, contributors[{contributorId,name,role:"Author",displayOrder}],
tags: [], genres: [], coverUrl, hasCover: !coverUrl.isEmpty, userReadStatus, activeLoanCount: 0`.
Edition dict keys (`:82-99`): `id, bookId, format: "physical", language, editionName, narrator, publisher,
publishDate, isbn_10, isbn_13, copyCount: 1, description, pageCount, isPrimary: true, createdAt, updatedAt`.

Differences from the server path worth noting: `format` is hardcoded `"physical"` (the Format chips are ignored),
`contributors` **are** created from `lookup.authors` with role `"Author"` and sequential `displayOrder`, genres and
tags are always empty, and the cover stays a provider URL on the row (`coverUrl`) with `hasCover` set accordingly
— there is no download. A `.liteLibraryDidChange` notification with `userInfo["libraryID"]` is posted after the
book write (`:72-76`) so open books views re-read SwiftData, because the scan flow lives in a
`fullScreenCover` above them.

`LiteManualAddSheet` + `LiteBookWriter.addManual` (`Librarium/Views/LiteManualAddSheet.swift:56-67`,
`LiteBookWriter.swift:113+`) is the no-ISBN variant: media types offered are
`["Book", "Manga", "Comic", "Graphic novel", "Audiobook"]` (`:23`), provider is synthesized as `"manual"` /
`"Manual entry"`, ISBNs empty.

### 6.4 Legacy `AddEditBookSheet` save (for completeness)

`save()` (`:241-270`) reuses the same `CreateBookRequest`, drops the nested edition entirely when
`isbn13`, `isbn10`, `publisher`, `language`, `publishDate` and `pageCount` are all empty (`:245-250`), and makes
the same best-effort `fetchCover` call after `create` (`:259-263`). It posts to the **app's active client**
(`appState.makeClient()`), not a specific library's server.

---

## 7. Cover handling

* **The cover shown right after a scan** is the provider's URL (`lookup.coverUrl`) rendered by `BookCoverImage`
  (`RedesignedScanFlow.swift:1020-1028`). Nothing is uploaded from the scan flow.
* **After the add**, the server is asked to download and store it, once, via
  `POST /api/v1/libraries/{lib}/books/{bookId}/cover/fetch` with `{"url": …}` (§6.2 step 2). This is the only
  cover work the scan flow does.
* **Manual camera/photo upload** exists but is *not* part of the scan flow. `CoverCaptureSheet`
  (`Librarium/Redesigned/CoverCaptureSheet.swift`), presented from the book detail screen
  (`RedesignedBookDetailView.swift:319-329`), offers "Take a photo" (or "Retake") via `UIImagePickerController`
  (only when `UIImagePickerController.isSourceTypeAvailable(.camera)`, `:126`) and "Choose a photo" via
  `PhotosPicker` (`:136-140`), downscales to a **1600 px** long edge and re-encodes as **JPEG 0.85**
  (`:163-174`, `maxEdge: CGFloat = 1600`, `jpegData(compressionQuality: 0.85)`), then calls
  `BookService.uploadCover`.
* **Upload endpoint**: `PUT /api/v1/libraries/{libraryId}/books/{bookId}/cover`
  (`BookService.swift:92-100`), multipart with **field name `"cover"`**, **filename `"cover.jpg"`**,
  **mime type `"image/jpeg"`**. `uploadMultipart` defaults to `PUT` (`APIClient.swift:~130-155`).
* Cover **deletion**: `DELETE /api/v1/libraries/{lib}/books/{bookId}/cover` (`BookService.swift:116-118`).
* Provider cover URL shapes: Hardcover — Grafbase image `url`; Open Library — either `cover.large/medium/small`
  from `api/books`, or `https://covers.openlibrary.org/b/id/<id>-L.jpg` built from the `covers[]` array /
  `cover_i`. Note `https://openlibrary.org/api/books?…&jscmd=data` returns `https` URLs, and the `covers`-based
  URLs are built with `https` explicitly. **No plain-`http` cover URLs are constructed anywhere in this code.**

---

## 8. Settings that affect scanning

There is **no scan-specific settings screen**. Four things change scanner behaviour:

1. **Metadata source toggles** — `Librarium/Views/LiteMetadataSourcesView.swift`. Two `Toggle`s: "Open Library"
   and "Hardcover" (`:45`, `:54`). Persisted as booleans in `UserDefaults` only:
   `LiteMetadataSettings.setEnabled(_:providerID:)` writes key `lite.metadata.<providerID>.enabled`
   (`Librarium/Services/LiteMetadataSettings.swift:20-36`). Absent values fall through to
   `defaults = ["openlibrary": true, "hardcover": false]` (`:15-18`) — so **Open Library on, Hardcover off** is
   the out-of-box state, and a fresh Lite install can still scan.
2. **Hardcover API key** — a `SecureField` plus "Save and check" / "Remove key"; the key is validated by
   `HardcoverService.validate(key:)` *before* being stored (`LiteMetadataSourcesView.swift:136-139`, validator at
   `HardcoverService.swift:157-192`), then written to the **Keychain** under `lite.hardcover.apiKey` (never
   `UserDefaults` — `:22-24` "The token is a credential, so it never goes near UserDefaults"). Copy: "Stored in
   the device Keychain, never synced." A "Get a Hardcover API key" link points at
   `https://hardcover.app/account/api` (`:81`).
3. **Which server answers** — the primary/active source (`AppState.activeSource`, `active_source_id` in
   `AppState.swift:273`, `makeClient(serverURL:)` at `:308-320`). `lookupCapableAccount()` prefers it. The notice
   at the top of the metadata screen is explicit that these sources only apply to on-device libraries:
   *"These sources are only used for libraries stored on this device. Books on a Librarium server get their
   metadata from that server's own providers."* (`LiteMetadataSourcesView.swift:38`). The server's provider set
   is an **admin-console** concern, not an app setting.
4. **Telemetry** — opt-in and off by default (`AGENTS.md:36`). `TelemetryService.shared.send(...)` is used by the
   book detail screen (ratings, reviews, progress — `RedesignedBookDetailView.swift:704-855`, `:1815`) but **no
   telemetry event is emitted anywhere in the scan flow**. An Android port should not invent one.

Defaults the flow itself establishes, not user-configurable: `selectedStatus = .unread` (`:725`),
`selectedFormat = "paperback"` (`:726`), empty tag/genre selections, and media type guessed by
`guessMediaTypeID(for:types:)` (`:950-977`): match lookup `categories` against media-type `displayName`
(case-insensitive, substring in either direction), then exact `"book"`, then exact `"novel"`, then any display
name containing `"novel"`, then the first type.

`loadLibraryDependentMetadata()` (`:858-936`) keeps per-server caches keyed by `serverURL`:
`mediaTypesByServer` ← `GET /api/v1/media-types` (`MediaTypeService.swift:7`), `formatsByServer` ←
`GET /api/v1/edition-formats` (filtered `isActive`, sorted by `sortOrder`, `VocabularyService.swift:19`),
`genres` ← `GET /api/v1/genres` (fetched once per view instance, `:31`), `libraryTags` ←
`GET /api/v1/libraries/{id}/tags` (`TagService.swift:7`), with `selectedTagIDs` pruned to still-valid ids when the
library changes (`:929-935`). Lite libraries get a hardcoded media-type list instead and no tags (`:869-889`):

```swift
// Librarium/Redesigned/RedesignedScanFlow.swift:2039-2045
private static let liteMediaTypes: [MediaType] = [
    MediaType(id: "lite-book", name: "book", displayName: "Book"),
    MediaType(id: "lite-manga", name: "manga", displayName: "Manga"),
    MediaType(id: "lite-comic", name: "comic", displayName: "Comic"),
    MediaType(id: "lite-graphic-novel", name: "graphic_novel", displayName: "Graphic novel"),
    MediaType(id: "lite-audiobook", name: "audiobook", displayName: "Audiobook"),
]
```

Note `lite-graphic-novel` maps to server code `graphic_novel`, and `lite-book` to `book`, deliberately matching
the api's own names (`:2035-2038`).

---

## 9. Gotchas & edge cases

1. **No checksum validation.** A misread barcode with a bad check digit is looked up like any other string and
   reported as "No match" (or, if 12 digits or 13-with-a-non-978/979 prefix, as "Not an ISBN"). If the Android
   port validates checksums, it will diverge — decide explicitly.
2. **`X` survives normalization in a numeric position.** `normalize` keeps `X`/`x` anywhere, so a 13-character
   string containing `X` is not rejected; it is sent to the server inside the URL path and to `ISBNCandidates`
   as-is.
3. **Leading zeros are preserved**, and the code comment at `:72-74` claims some scanners *add* a leading zero to
   EAN-13 — but nothing in the code strips one. A 14-digit result falls through `ISBNCandidates.expand`'s
   `default: break` and is only ever tried verbatim.
4. **10 → 13 conversion only happens inside `ISBNCandidates`**, which is only reached by the Open Library
   provider (`OpenLibraryService.swift:35`) and by the Hardcover `fallbackISBN` logic (which does *not* expand).
   The server path and the Hardcover path receive the **verbatim normalized string**. So scanning an ISBN-10
   against a server depends entirely on the server's own normalization.
5. **`Set` iteration order in the candidate loop.** `Array(Set(ISBNCandidates.expand(trimmed)))` and
   `Set([isbn, lookupISBN13, lookupISBN10])` are unordered; which candidate wins is not deterministic. Any port
   should use an ordered, deduplicated list.
6. **`lookupISBN13`/`lookupISBN10` are derived by string length**, not by validating the ISBN: a 13-char scanned
   value that is not an ISBN still sets `lookupISBN13`. Since candidates already include `isbn`, the effect is
   benign, but the naming is misleading.
7. **`convertFromSnakeCase` + all-non-optional strings** makes `ISBNLookupResult` brittle (see §4.1). A single
   missing field makes the whole server lookup fall through to the on-device providers, which may then return
   *different* metadata for the same ISBN — a silent, hard-to-diagnose divergence.
8. **`isbn_10` / `isbn_13` need explicit `CodingKeys`** on both `BookEdition` and `CreateEditionRequest`, because
   `convertToSnakeCase`/`convertFromSnakeCase` do not insert an underscore before a digit
   (comments at `BookService.swift:255` and `Book.swift:136-137`). Model key strategies alone will silently
   produce `isbn10`.
9. **A miss on `book-by-isbn` is only a 404.** Any other failure (500, timeout, 401) is also swallowed by `try?`
   and treated as "not owned", so a flaky server produces a wrong "not in your library" answer with no error
   surfaced.
10. **Ownership needs two round-trips per library per candidate**, and enumerates libraries × candidates; the
    copy count is only available after a hit.
11. **Cover URLs are ephemeral.** Until `cover/fetch` succeeds the cover is a third-party URL; the comment at
    `BookService.swift:102-107` names three past issues (#33, #55, #78) where a scanned book lost its image on
    the next read. The scan also has no client-side cover cache-busting, unlike the detail view
    (`RedesignedBookDetailView.swift:325-326` bumps `coverCacheBuster`).
12. **`cover/fetch` and `updateMyBook` failures are swallowed** (`try?`) — the UI reports success. If the Android
    port surfaces them, it will be noisier than iOS.
13. **`"unread"` is never written.** Since the default status is `.unread`, a scan-and-add sends **no** status
    update at all. Status is only sent for want-to-read / reading / read.
14. **Duplicate adds are intentional.** "Add another copy to X" posts another book; the comment at
    `:1282-1284` says the API increments the existing edition's `copy_count` when the ISBN matches. The client
    does not pre-empt this.
15. **No caching of lookup results.** Every `.task(id: isbn)` run re-hits the network. `RedesignedScanResultView`
    is re-created on each "scan another", so `genres`, `libraryTags` etc. re-fetch too (media types and formats
    are per-server cached in `@State`, which is also reset).
16. **A 400 ms debounce plus a 3-character minimum** guards the title-search path only; the ISBN path fires as
    soon as the field is recognized (`onSubmit`, `:388-395`). The server-side search fans out across providers
    per keystroke otherwise — a real cost the comment at `:363-364` calls out.
17. **The scan target frame is cosmetic.** `rectOfInterest` is never set, so a barcode anywhere in the frame
    scans. `objects.first` also means two barcodes in view resolve to whichever AVFoundation lists first.
18. **No retry/backoff and no offline queue for the scan path.** `APIClient` retries once on 401 after a token
    refresh (`APIClient.swift:214-219`) and nothing else. `AGENTS.md:35` claims a per-server cache and a
    pending-operation queue exist for the app generally; the scan add path does not use them.
19. **Timeout is 10 s** for every API request (`APIClient.swift:176-182`) — deliberately short so a down server
    fails before the user gives up.
20. **Client identity headers** are on every request: `X-Librarium-Client: ios` and
    `X-Librarium-Client-Version: <CFBundleShortVersionString or 0.0.0-dev>` (`APIClient.swift:34-51`). The server
    answers `426` with a message for clients it deems too old. An Android port should send its own values.
21. **`LiteRefreshMetadataSheet` uses `allProviders`, not `activeProviders`** (`LiteRefreshMetadataSheet.swift:292`,
    `:322`) — i.e. it does not honour the on/off toggles, unlike the scan flow
    (`RedesignedScanFlow.swift:2116`). Probably a bug, but an Android port should follow the scan flow's
    behaviour and treat the difference as intentional-for-now.
22. **The "Quick rate" toggle is inert** (§2.3). Do not build a rating path from it.
23. **`hasFiredScan` is per-camera-view-instance**, and the delegate also stops the session — belt and braces.
    If a port keeps the camera alive across scans, it needs its own debounce.
24. **`ManualEntrySheet` filters out results without an ISBN** (`:538-541`), including Hardcover search hits whose
    `isbns` array was empty — so a legitimate search match can be invisible with no explanation.

---

## 10. Uncertainties (not verifiable from this repo)

Stated explicitly so nothing is invented:

1. **The server's provider fan-out is not in this repo.** `LiteMetadataProvider.swift:8-10` says the api "fans
   out across six providers server-side", and `AGENTS.md:34` says the primary server "drives global lookups such
   as an ISBN scan", but **the six providers, their names, order, URLs, credentials, merge/ranking rules and rate
   limits exist only in `librarium-api` (Go)**. This document therefore describes the *client's* contract
   (`GET /api/v1/lookup/isbn/{isbn}` → `Envelope<[ISBNLookupResult]>`) and not the server's internals. Android
   should call the same endpoint and merge in returned order, not reimplement providers.
2. **The response field names on `/api/v1/lookup/isbn/{isbn}`** are inferred from
   `keyDecodingStrategy = .convertFromSnakeCase` applied to `ISBNLookupResult`. The iOS client never inspects raw
   JSON here, so the mapping `isbn13 → isbn_13` etc. is a decoding *requirement*, not an observed sample.
   Whether the server also sends extra keys is unknown.
3. **Whether `/api/v1/lookup/isbn/{isbn}` URL-decodes the path segment** is unknown — the client interpolates the
   normalized ISBN without percent-encoding. For digits and `X` this is moot; for an exotic input it is untested.
4. **`book-by-isbn` matching semantics** (does the server canonicalize `978-…`, hyphenated forms, or ISBN-10 ↔ 13
   equivalence?) are server-side and unverifiable here. The Lite path's exact-string comparison
   (`:2061`) is the only equivalence logic visible in the client, and it is *not* what the server path does.
5. **Whether `POST /books` with an ISBN that already exists increments `copy_count`** is asserted only in a code
   comment (`RedesignedScanFlow.swift:1282-1284`) and is not implemented client-side.
6. **The exact rank/merge order for the server's multi-provider result array** is unknown; the client trusts the
   array order (`:2102-2105`).
7. **Error-body shapes** for the api (`APIError.serverError(status, String(data:encoding:))`) are passed through
   as raw text; no structured error contract is visible.
8. **`/api/v1/edition-formats`, `/api/v1/genres`, `/api/v1/media-types`, `/api/v1/libraries/{id}/tags`,
   `/api/v1/me/tags`** are called by the scan screen, but their response envelopes are only partially modelled
   here (`VocabularyTerm`, `MediaType`, `Tag`, `MeTag`), and how the server *ranks* or *filters* their contents is
   server-side.
9. **`isbn10`/`isbn13` on an edition**: nothing in the client validates or canonicalizes them on write. Whether
   the server normalizes, rejects, or stores them verbatim is unknown.
10. **UPC-A is not a requested symbology.** A 12-digit UPC-A is only ever encountered as a *digit string* on the
    manual path or if some scanner surfaces it under another type; `scannedLooksLikeUPC` only produces a friendlier
    error message. Whether Android's ML Kit/CameraX would deliver UPC-A as a distinct type is a platform
    difference the port must handle deliberately — iOS's choice, whatever its reason, is `.ean13, .ean8, .code128`
    only.
