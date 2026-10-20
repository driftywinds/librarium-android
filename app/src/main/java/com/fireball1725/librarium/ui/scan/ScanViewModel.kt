// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fireball1725.librarium.core.di.AppContainer
import com.fireball1725.librarium.core.lookup.IsbnLookupService
import com.fireball1725.librarium.core.model.IsbnLookupResult
import com.fireball1725.librarium.core.model.Library
import com.fireball1725.librarium.core.model.MediaType
import com.fireball1725.librarium.core.remote.LibrariumApi2
import com.fireball1725.librarium.core.scan.OwnedMatch
import com.fireball1725.librarium.core.scan.ScanRepository
import com.fireball1725.librarium.core.session.AccountManager
import com.fireball1725.librarium.core.session.StoredAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How far along this scanned barcode is. Mirrors the iOS state cluster. */
sealed interface ScanPhase {
    /** Camera live, waiting for a barcode. */
    data object Scanning : ScanPhase

    /** Barcode in hand, provider fan-out running. */
    data object LookingUp : ScanPhase

    /**
     * Metadata resolved (or definitively absent). The result sheet shows either
     * the book or the reason it is not there.
     */
    data class Result(
        val isbn: String,
        /** Null when the lookup did not resolve to metadata. */
        val found: IsbnLookupResult?,
        val outcome: IsbnLookupService.LookupOutcome,
    ) : ScanPhase
}

/**
 * Drives the scan flow: camera → lookup → ownership checks → add.
 *
 * One instance outlives the screen so a configuration change (rotation) does not
 * throw away a lookup that is in flight. The camera composable hands it
 * barcodes; everything else is a function of state.
 */
class ScanViewModel(
    private val accounts: AccountManager,
    private val lookupService: IsbnLookupService,
) : ViewModel() {

    private val _phase = MutableStateFlow<ScanPhase>(ScanPhase.Scanning)
    val phase: StateFlow<ScanPhase> = _phase.asStateFlow()

    /** Every account's libraries, stamped so requests route back to their server. */
    private val _libraries = MutableStateFlow<List<Library>>(emptyList())
    val libraries: StateFlow<List<Library>> = _libraries.asStateFlow()

    private val _selectedLibraryKey = MutableStateFlow<String?>(null)
    val selectedLibraryKey: StateFlow<String?> = _selectedLibraryKey.asStateFlow()

    /** Per-`clientKey` ownership answers; a missing key means "still asking". */
    private val _ownership = MutableStateFlow<Map<String, OwnedMatch?>>(emptyMap())
    val ownership: StateFlow<Map<String, OwnedMatch?>> = _ownership.asStateFlow()

    /** Media types for the selected library's server, prefetched. */
    private val _mediaTypes = MutableStateFlow<List<MediaType>>(emptyList())
    val mediaTypes: StateFlow<List<MediaType>> = _mediaTypes.asStateFlow()

    /** Which account's server each library came from; keys are `clientKey`s. */
    private val libraryAccount = mutableMapOf<String, StoredAccount>()

    /** Media-type catalog per server URL, prefetched so the add never sends "". */
    private val mediaTypesByServer = mutableMapOf<String, List<MediaType>>()

    private val addMutex = Mutex()

    @Volatile
    private var lookupInFlight = false

    val selected: Library?
        get() = _libraries.value.firstOrNull { it.clientKey == _selectedLibraryKey.value }

    /**
     * Port of `ctaLabel` (`RedesignedScanFlow.swift:1755-1762`). "Added" is
     * carried by the phase machine rather than a separate flag: the CTA is only
     * ever read while the result sheet is up.
     */
    val ctaLabel: String
        get() {
            val lib = selected
            return when {
                lib == null -> "Pick a library"
                (_ownership.value[lib.clientKey]) != null -> "Add another copy to ${lib.name}"
                else -> "Add to ${lib.name}"
            }
        }

    fun selectLibrary(key: String) {
        _selectedLibraryKey.value = key
        publishMediaTypesForSelection()
    }

    /** Entry point from the camera. */
    fun onBarcode(raw: String) {
        if (lookupInFlight) return
        viewModelScope.launch { runLookup(raw) }
    }

    /** Manual ISBN entry path (the "type it instead" fallback). */
    fun onManualIsbn(isbn: String) {
        if (lookupInFlight) return
        viewModelScope.launch { runLookup(isbn) }
    }

    private suspend fun runLookup(raw: String) {
        if (lookupInFlight) return
        lookupInFlight = true
        try {
            val isbn = raw.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()
            if (isbn.isEmpty()) return
            _phase.value = ScanPhase.LookingUp

            // Lookup and library fan-out run concurrently: the ownership card
            // needs libraries whether or not metadata shows up, and the iOS
            // source runs them as `async let` for the same reason.
            val (outcome, libs) = coroutineScope {
                val libsTask = async { loadAllLibraries() }
                val lookupTask = async {
                    lookupAccount()?.let { lookupService.lookup(clientFor(it), isbn) }
                        ?: IsbnLookupService.LookupOutcome.Failed(NO_SERVER_MESSAGE)
                }
                // Order matters: the pair reads (outcome, libs).
                lookupTask.await() to libsTask.await()
            }

            _libraries.value = libs.sortedWith(compareBy({ it.serverName }, { it.name }))
            if (_selectedLibraryKey.value == null) {
                _selectedLibraryKey.value = libs.firstOrNull()?.clientKey
            }
            _phase.value = ScanPhase.Result(isbn, (outcome as? IsbnLookupService.LookupOutcome.Success)?.result, outcome)

            // Ownership checks fire after libraries are visible — rows spin
            // until each library reports back.
            val found = (outcome as? IsbnLookupService.LookupOutcome.Success)?.result
            if (libs.isNotEmpty() && found != null) {
                checkOwnership(isbn, found, libs)
            }
        } finally {
            lookupInFlight = false
        }
    }

    /**
     * Fan out `LibraryService.list` across every signed-in account — port of
     * `loadAllLibraries` (`RedesignedScanFlow.swift:1834-1885`) minus the Lite
     * branch.
     */
    private suspend fun loadAllLibraries(): List<Library> {
        val usable = usableAccounts()
        if (usable.isEmpty()) return emptyList()
        return coroutineScope {
            usable.map { account ->
                async {
                    try {
                        // `serverName` is stamped outside the constructor
                        // (the wire has no such field), so plain mutation
                        // rather than `copy()` here.
                        val libs = clientFor(account).libraries()
                        libs.forEach { it.serverName = account.name }
                        libs
                    } catch (_: Exception) {
                        // Empty is "did not answer" here, which the UI treats as
                        // distinct from an account that answered with none.
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Accounts this scan can use: the active one when it is healthy, otherwise
     * every signed-in account that is not waiting for re-auth.
     */
    private suspend fun usableAccounts(): List<StoredAccount> {
        val snap = accounts.snapshot
        val active = snap.accounts.firstOrNull { it.id == snap.activeAccountId && !it.needsReauth }
        return when {
            active != null -> listOf(active)
            else -> snap.accounts.filter { !it.needsReauth }
        }
    }

    /**
     * Port of `lookupCapableAccount` (`RedesignedScanFlow.swift:2152-2158`): the
     * active server when healthy, else the first that is. Two servers can answer
     * the same barcode differently, so the answer should come from the shelf the
     * reader is looking at rather than whichever account sorts first.
     */
    private suspend fun lookupAccount(): StoredAccount? = usableAccounts().firstOrNull()

    private fun clientFor(account: StoredAccount): LibrariumApi2 = accounts.clientFor(account)

    /** Port of `checkOwnership`, fanned out per library through its own server. */
    private suspend fun checkOwnership(
        isbn: String,
        result: IsbnLookupResult,
        libs: List<Library>,
    ) {
        val repo = ScanRepository(lookupService)
        coroutineScope {
            libs.map { lib ->
                async {
                    val account = accountFor(lib) ?: return@async
                    val client = clientFor(account)
                    libraryAccount[lib.clientKey] = account
                    val matches = repo.checkOwnership(client, isbn, result, listOf(lib))
                    for ((k, v) in matches) {
                        _ownership.value = _ownership.value + (k to v)
                    }
                    prefillMediaTypes(account)
                }
            }.awaitAll()
        }
    }

    /** The account whose server a library lives on. */
    private fun accountFor(lib: Library): StoredAccount? {
        libraryAccount[lib.clientKey]?.let { return it }
        // `Library.serverName` carries the account's display name, and its URL
        // is not persisted on the model; matching by name is the routing the
        // iOS source achieves by stamping `serverURL` onto the library.
        return usableAccountsSnapshot().firstOrNull { it.name == lib.serverName }
    }

    private fun usableAccountsSnapshot(): List<StoredAccount> =
        accounts.snapshot.accounts.filter { !it.needsReauth }

    /**
     * Fetches the server's media-type catalog once per server so the add never
     * sends an empty UUID. Failure is fine — the add path falls back.
     */
    private suspend fun prefillMediaTypes(account: StoredAccount) {
        if (mediaTypesByServer.containsKey(account.url)) return
        try {
            mediaTypesByServer[account.url] = clientFor(account).mediaTypes()
        } catch (_: Exception) {
            // A server that will not answer this will not answer the create
            // either; leaving it empty lets the add path report the real error.
            mediaTypesByServer[account.url] = emptyList()
        }
        // Publish the selected library's server catalog; the picker reads it.
        publishMediaTypesForSelection()
    }

    /**
     * Pushes the current selection's server's media types into the UI flow.
     */
    private fun publishMediaTypesForSelection() {
        val lib = selected
        val url = lib?.serverUrl ?: return
        _mediaTypes.value = mediaTypesByServer[url].orEmpty()
    }

    /**
     * Pre-select a media type the way the scan sheet does
     * (`RedesignedScanFlow.swift:950-977`).
     *
     * Both halves matter. The category pass lets provider genres drive the pick —
     * a scan whose providers say "Manga" lands on Manga rather than Novel. The
     * name fallback then prefers a plain "Book" or "Novel" **by exact match**,
     * with any partial "…novel" match last so "Graphic Novel" and "Light Novel"
     * do not become the default for an unclassified scan.
     *
     * Falling back to the server's first row is only correct because it is a
     * last resort: the catalog is ordered alphabetically, so a bare
     * `firstOrNull()` would file every scanned book as "Anthology".
     */
    fun guessMediaTypeId(
        categories: List<String>?,
        types: List<MediaType>,
    ): String? {
        if (types.isEmpty()) return null

        // Blank categories must be dropped, not carried through: every string
        // contains "", so a provider answering `[""]` would otherwise match the
        // very first media type and file the book as "Anthology".
        val cats = categories.orEmpty()
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
        for (t in types) {
            val name = t.displayName.lowercase()
            if (name.isEmpty()) continue
            if (cats.any { it.contains(name) || name.contains(it) }) return t.id
        }

        types.firstOrNull { it.displayName.lowercase() == "book" }?.let { return it.id }
        types.firstOrNull { it.displayName.lowercase() == "novel" }?.let { return it.id }
        types.firstOrNull { it.displayName.lowercase().contains("novel") }?.let { return it.id }
        return types.first().id
    }

    /**
     * Add the scanned book to the selected library.
     *
     * Success haptics and the 700 ms pause before returning to the camera are
     * the UI layer's job; this only advances state so the sheet can react.
     */
    fun addBook(isbn: String, mediaTypeId: String?, readStatus: String?) {
        viewModelScope.launch {
            val phase = _phase.value as? ScanPhase.Result ?: return@launch
            val found = phase.found ?: return@launch
            val library = selected ?: return@launch
            addMutex.withLock {
                val account = accountFor(library) ?: return@withLock
                val client = clientFor(account)
                val outcome = ScanRepository(lookupService).addToLibrary(
                    api = client,
                    library = library,
                    lookupResult = found,
                    // User's pick when they made one; otherwise the guess, not the
                    // server's first row — the catalog is alphabetical, so
                    // `firstOrNull()` files every book as "Anthology".
                    mediaTypeId = mediaTypeId
                        ?: guessMediaTypeId(found.categories, mediaTypesByServer[account.url].orEmpty()),
                    readStatus = readStatus,
                )
                when (outcome) {
                    is ScanRepository.AddOutcome.Added -> {
                        // Auto-return to camera after a short pause so the next
                        // book goes in back-to-back; the user can tap close first.
                        delay(700)
                        resetForNextScan()
                    }
                    is ScanRepository.AddOutcome.Failure -> Unit
                }
            }
        }
    }

    /** Back to the camera, with all per-scan state discarded. */
    fun resetForNextScan() {
        _phase.value = ScanPhase.Scanning
        _ownership.value = emptyMap()
        _libraries.value = emptyList()
        _selectedLibraryKey.value = null
        _mediaTypes.value = emptyList()
        libraryAccount.clear()
        lookupInFlight = false
    }

    /**
     * Manual DI has no injector to reflect through, so the factory takes the
     * container and pulls the two collaborators out of it.
     */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ScanViewModel(container.accountManager, container.isbnLookupService) as T
    }

    private companion object {
        const val NO_SERVER_MESSAGE =
            "No server is signed in. Add a server in Profile → Servers to scan against your collection."
    }
}
