---
name: librarium-ios-reference
description: Use when you need to know how the shipping iOS Librarium client behaves — the reference implementation this Android client mirrors. Covers app topology, scan flow, and the rules for porting Swift to Kotlin.
---

# Librarium iOS reference

The iOS client (`librarium-ios-main/`, read-only in this workspace) is the
**behavioural specification** for this Android client. When the question is
"what does Librarium do", the answer comes from here, not from invention.

## When to load this skill

- Porting a screen, service, or flow from Swift to Kotlin.
- Deciding exact user-visible strings, section order, or edge-case behaviour.
- Checking whether a feature exists at all (parity questions).

## The rules that make a port faithful

1. **The API owns the logic.** The iOS app renders what the server computes. Do
   not port iOS-side filtering/ranking that the server now does — verify against
   `../docs/api-contract.md` or the live `librarium-api` source.
2. **Match user-visible strings exactly** (modulo `NSLocalizedString` keys). If
   iOS says `"Pick a library"`, Kotlin says `"Pick a library"`.
3. **Match section order exactly.** The scan result sheet is cover hero →
   ownership card → library picker → media/status → more-options → CTA. If a
   section is missing, the port is incomplete.
4. **Preserve outcome splits.** `LookupOutcome{Success/NoSourceHasIt/Failed}`
   has three distinct user messages; collapsing them loses information the user
   needs to act.
5. **Comments explain why, not what.** Every Kotlin file carries the
   SPDX + copyright header, and comments cite the Swift file/line the behaviour
   ports from (e.g. `RedesignedScanFlow.swift:1755-1762`).

## iOS file → Kotlin file map

| iOS (under `Librarium/`) | Kotlin (under `app/.../`) |
| --- | --- |
| `Redesigned/RedesignedScanFlow.swift` (scan flow, add-to-library, lookup merge) | `ui/scan/ScanResultView.kt`, `ui/scan/ScanViewModel.kt`, `core/scan/ScanRepository.kt`, `core/lookup/IsbnLookupService.kt` |
| `Redesigned/Theme.swift` (colour/type/spacing tokens) | `ui/theme/Theme.kt`, `ui/theme/ColorTokens.kt` |
| Services (per-feature API clients) | `core/remote/Remote.kt` (one class per feature, one responsibility each) |
| Models | `core/model/Models.kt`, `core/model/Lookup.kt` |
| Session/account storage | `core/session/SessionState.kt`, `core/session/SessionStore.kt` |
| Cache (keys include server identity) | `core/cache/JsonCache.kt`, `core/cache/BookCache.kt` |

## Scan flow in one paragraph

Camera scans an EAN-13/EAN-8/Code-128 barcode → normalise the raw value →
`GET /api/v1/lookup/isbn/{isbn}` → filter results to ones with a non-empty
title → merge field-wise (first non-empty per field; `providerDisplay` = the
distinct credited providers joined with `" + "`) → ownership check fans out per
library (NotFound means "not owned", continue) → user picks a library (first
server-sorted, first library preselected) → `addToLibrary` creates a book with
a nested edition (`copyCount=1`, `isPrimary=true`), best-effort fetches a
cover, and only PATCHes the read status when it is not `"unread"` → 700 ms
success beat → auto-return to the scanner.

## Deliberate divergences (do not "fix" these)

- **No Lite/on-device mode.** `LiteMetadataAggregator`, `HardcoverService`,
  `OpenLibraryService` are Lite-only and are intentionally not ported.
- **Manual DI, no Hilt/KSP/Room.** `AppContainer` is constructed in
  `LibrariumApplication`; removing the highest-risk build coupling was a
  deliberate trade.
- **Inline pickers instead of half-sheets** on the scan result screen — an
  Android bottom sheet has the same dismissal cost as the enclosing screen.
- **Absent by design:** Shelves screens, Members screens, an Admin navigation
  entry, Lite iCloud sync, on-device providers.
