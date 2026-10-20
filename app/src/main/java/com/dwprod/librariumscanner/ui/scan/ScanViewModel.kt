// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dwprod.librariumscanner.core.di.AppContainer
import com.dwprod.librariumscanner.core.lookup.IsbnLookupService
import com.dwprod.librariumscanner.core.model.IsbnLookupResult
import com.dwprod.librariumscanner.core.model.Library
import com.dwprod.librariumscanner.core.model.MediaType
import com.dwprod.librariumscanner.core.remote.LibrariumApi2
import com.dwprod.librariumscanner.core.scan.OwnedMatch
import com.dwprod.librariumscanner.core.scan.ScanRepository
import com.dwprod.librariumscanner.core.scan.guessMediaTypeId
import com.dwprod.librariumscanner.core.session.AccountManager
import com.dwprod.librariumscanner.core.session.StoredAccount
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

    /**
     * The add is one tap away from a network round-trip, and every failure path
     * here used to return silently: the CTA stayed looking idle and the sheet
     * said nothing. [adding] drives the button's busy state and [addError] is
     * the sentence the sheet shows when the server refused the create.
     */
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

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
            _addError.value = null
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

            // Media types are needed for the picker the moment the sheet opens,
            // so fetch them here rather than only on the ownership path. That
            // path needs a metadata hit, which left a scan against an unknown
            // ISBN (or a coarser one) showing an empty "Loading…" media row.
            usableAccounts().forEach { prefillMediaTypes(it) }

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
                        // Both client-only fields are stamped outside the
                        // constructor (the wire has no such field): `serverName`
                        // labels the row, and `serverUrl` is load-bearing —
                        // `clientKey` is "$serverUrl|$id", so leaving it blank
                        // made every library key collapse to "|<id>" and every
                        // per-server lookup (media types, routing) miss.
                        val libs = clientFor(account).libraries()
                        libs.forEach {
                            it.serverName = account.name
                            it.serverUrl = account.url
                        }
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
        // iOS source achieves by stamping `serverURL` onto the library. The URL
        // is checked as well because a renamed account, or two accounts whose
        // names were both left empty, would otherwise fall through to null and
        // make the add a silent no-op.
        val usable = usableAccountsSnapshot()
        return usable.firstOrNull { it.name == lib.serverName && it.name.isNotEmpty() }
            ?: usable.firstOrNull { lib.serverUrl.isNotEmpty() && it.url == lib.serverUrl }
    }

    private fun usableAccountsSnapshot(): List<StoredAccount> =
        accounts.snapshot.accounts.filter { !it.needsReauth }

    /**
     * Fetches the server's media-type catalog once per server so the add never
     * sends an empty UUID. Failure is fine — the add path falls back.
     */
    private suspend fun prefillMediaTypes(account: StoredAccount) {
        // The catalog is fetched once per server, but publishing happens on
        // every call: `publish` tracks the *current* selection, so skipping it
        // on a cache hit would leave the picker on whatever server was
        // selected when the cache was first filled.
        if (!mediaTypesByServer.containsKey(account.url)) {
            try {
                mediaTypesByServer[account.url] = clientFor(account).mediaTypes()
            } catch (_: Exception) {
                // A server that will not answer this will not answer the create
                // either; leaving it empty lets the add path report the real error.
                mediaTypesByServer[account.url] = emptyList()
            }
        }
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
     * Add the scanned book to the selected library.
     *
     * Success haptics and the 700 ms pause before returning to the camera are
     * the UI layer's job; this only advances state so the sheet can react.
     *
     * Every refusal publishes an [addError] rather than returning quietly. The
     * create itself is strict about `media_type_id`: the handler calls
     * `uuid.Parse` on it (`internal/api/handlers/books.go:727-730`) and answers
     * 400 "invalid media_type_id" for anything that is not a UUID, so an empty
     * catalog must stop the tap here, with a sentence the user can act on,
     * instead of becoming a 400 that nothing displays.
     */
    fun addBook(isbn: String, mediaTypeId: String?, readStatus: String?) {
        viewModelScope.launch {
            val phase = _phase.value as? ScanPhase.Result ?: return@launch
            val found = phase.found ?: return@launch
            val library = selected ?: run {
                _addError.value = "No library is selected. Pick one above and try again."
                return@launch
            }
            addMutex.withLock {
                _addError.value = null
                _adding.value = true
                try {
                    val account = accountFor(library) ?: run {
                        _addError.value =
                            "No signed-in server matches ${library.name}. Sign in to its server again."
                        return@withLock
                    }
                    val client = clientFor(account)

                    // The user's pick wins; otherwise the shared guess. A null
                    // here means the catalog has not arrived (a prefetch that
                    // failed during lookup), so one retry is worth making before
                    // declaring the add impossible.
                    val resolvedMediaTypeId = mediaTypeId
                        ?: guessMediaTypeId(found.categories, mediaTypesByServer[account.url].orEmpty())
                        ?: guessMediaTypeId(found.categories, retryMediaTypes(account))
                    if (resolvedMediaTypeId.isNullOrBlank()) {
                        _addError.value =
                            "${account.name} answered with no media types, so there is nothing to " +
                                "file the book under. Try again in a moment."
                        return@withLock
                    }

                    val outcome = ScanRepository(lookupService).addToLibrary(
                        api = client,
                        library = library,
                        lookupResult = found,
                        mediaTypeId = resolvedMediaTypeId,
                        readStatus = readStatus,
                    )
                    when (outcome) {
                        is ScanRepository.AddOutcome.Added -> {
                            _addError.value = null
                            // Auto-return to camera after a short pause so the
                            // next book goes in back-to-back; the user can tap
                            // close first.
                            delay(700)
                            resetForNextScan()
                        }
                        is ScanRepository.AddOutcome.Failure -> {
                            _addError.value = outcome.message
                        }
                    }
                } finally {
                    _adding.value = false
                }
            }
        }
    }

    /**
     * One forced catalog fetch, for the add path only.
     *
     * [prefillMediaTypes] caches the first answer, including a failed one
     * (empty list) so a dead server is not re-asked on every tap. That cache is
     * still allowed to poison this add, so a blank catalog gets exactly one
     * re-read before the failure is reported.
     */
    private suspend fun retryMediaTypes(account: StoredAccount): List<MediaType> {
        val fresh = try {
            clientFor(account).mediaTypes()
        } catch (_: Exception) {
            emptyList()
        }
        if (fresh.isNotEmpty()) {
            mediaTypesByServer[account.url] = fresh
            publishMediaTypesForSelection()
        }
        return fresh
    }

    /** Back to the camera, with all per-scan state discarded. */
    fun resetForNextScan() {
        _phase.value = ScanPhase.Scanning
        _ownership.value = emptyMap()
        _libraries.value = emptyList()
        _selectedLibraryKey.value = null
        _mediaTypes.value = emptyList()
        _addError.value = null
        _adding.value = false
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
