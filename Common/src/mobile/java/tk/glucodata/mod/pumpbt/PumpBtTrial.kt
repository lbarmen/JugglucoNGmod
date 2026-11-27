package tk.glucodata.mod.pumpbt

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tk.glucodata.mod.carelink.PumpSourceSwitch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JugglucoNGmod, pump over Bluetooth (pump window): a thin layer over [PumpBtSource], which owns the link, the history
 * memory and its number (series part 2, part B2a). Here only what the window shows besides the source - Bluetooth and
 * permission checks, the pairing wizard's warnings, the key database (downloaded by "Start pairing" when there is none,
 * series brief, decision 11), the name variant (B only as a fallback of one pairing, finding 11), the journal's copy
 * and clearing; the buttons go to the source. Closing the window or turning the screen off does not end the link. All
 * calls on the main thread; the network off it.
 */
internal object PumpBtTrial {

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val _state = MutableStateFlow(PumpBtUiState())
    val state: StateFlow<PumpBtUiState> = _state.asStateFlow()

    private var app: Context? = null

    /**
     * What the window's actions need from Android, the network and the source; tests give their own ([resetForTest]).
     * [offMain] runs blocking work (the network, a file) off the main thread, [onMain] brings its result back.
     */
    internal class Env(
        val checkPhone: () -> Unit = { app?.let { refreshChecks(it) } },
        val hasKey: () -> Boolean = { app?.let { PumpBtSource.key(it) } != null },
        val download: (String) -> PumpBtKey.Result = { PumpBtKey.download(it) },
        val offMain: (String, () -> Unit) -> Unit = { name, block -> Thread(block, name).start() },
        val onMain: (() -> Unit) -> Unit = { block -> main.post(block) },
        /** Saves a key database (throws when it is not saved) and hands it to the source for the next link start. */
        val saveKey: (PumpBtKey.Result.Ok) -> Unit = { ok ->
            app?.let { PumpBtKey.save(it, ok) }
            PumpBtSource.keyLoaded(ok)
        },
        val startLink: (Boolean) -> Unit = { pair -> app?.let { PumpBtSource.startLink(it, pair) } },
        val stopLink: () -> Unit = { PumpBtSource.stopLink() },
        val linkRunning: () -> Boolean = { PumpBtSource.linkRunning },
        val sourceOn: () -> Boolean = { PumpBtSource.state.value.enabled },
        val store: () -> PumpBtStore? = { app?.let { PumpBtSource.store(it) } },
        val journal: (String) -> Unit = { PumpBtJournal.add(it) },
        val now: () -> Long = { System.currentTimeMillis() },
    )

    private var env = Env()

    private fun journal(line: String) = env.journal(line)

    /**
     * The source is set up for this window ([attach]); false - the window was opened by someone who never used the pump
     * and nothing is made until the warnings are accepted (critic G1, N4: no link state file, no source).
     */
    @Volatile
    var attached = false
        private set

    /**
     * The window opened: the source is set up (idempotent; it gives back a changed phone name), nothing started - only
     * for someone who used the pump (the link state file is there, a pump address is kept in the old preferences) or
     * has the source on; anyone else sees the warnings and the source is set up when they are accepted.
     */
    fun onScreenOpened(context: Context) {
        val c = context.applicationContext
        app = c
        val used = usedBefore(PumpSourceSwitch.bluetoothOn(c), File(File(c.noBackupFilesDir, PumpBtStore.DIR), PumpBtStore.FILE)) {
            PumpBtStore.addressKnown(c)
        }
        if (used) attach(c) else _state.update { it.copy(link = PumpBtSource.state.value.link, warningsAccepted = false, paired = false) }
        refreshChecks(c)
    }

    /** The pump was used here: the source on, the link state [file] there, or a pump address kept (read only, nothing made). */
    internal fun usedBefore(sourceOn: Boolean, file: File, addressKnown: () -> Boolean): Boolean =
        sourceOn || file.isFile || addressKnown()

    private fun attach(c: Context) {
        attached = true
        // init leaves a running link alone (a pairing from this window, a turned screen)
        PumpBtSource.init(c)
        val s = PumpBtSource.store(c)
        journal("trial screen opened ${PumpBtJournal.fullDate()}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        PumpBtSource.onLinkState = ::onLink
        _state.update {
            it.copy(
                link = PumpBtSource.state.value.link, variant = s.variant, advertisedName = s.advertisedName,
                keyCrc = PumpBtSource.key(c)?.crc, paired = s.paired,
                // read at once: an owner sees no wizard while the source starts
                warningsAccepted = s.warningsAccepted || s.paired,
            )
        }
    }

    /**
     * A link state: "paired" refreshed; once the handshake went through, name variant B goes back to A (finding 11;
     * the phone's name came back already - [PumpBtAdapterName.restore] in the link).
     */
    internal fun onLink(ls: PumpBtLinkState) {
        val s = env.store()
        if (s != null) {
            val after = PumpBtWindow.variantAfter(s.variant, ls)
            if (after != s.variant) {
                s.variant = after
                journal("name variant back to $after after the pairing")
            }
        }
        if (!ls.running) linkMayHoldB = false
        val paired = s?.paired ?: false
        _state.update {
            it.copy(link = ls, paired = paired, variant = s?.variant ?: it.variant, warningsAccepted = it.warningsAccepted || paired)
        }
    }

    /** Bluetooth there and on, permissions, advertising support. */
    fun refreshChecks(context: Context) {
        val c = context.applicationContext
        val adapter = try {
            (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: Throwable) {
            null
        }
        val has = adapter != null && c.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val on = has && try {
            adapter!!.isEnabled
        } catch (_: Throwable) {
            false
        }
        val advertising = if (!on) null else try {
            adapter!!.isMultipleAdvertisementSupported && adapter.bluetoothLeAdvertiser != null
        } catch (_: Throwable) {
            false
        }
        // Bluetooth seen off, then on: the wait for the pump counts from now at the latest (critic G2, N-8)
        val back = if (on && lastBluetoothOn == false) env.now() else null
        lastBluetoothOn = on
        _state.update {
            it.copy(
                hasBluetooth = has, bluetoothOn = on, permissionsGranted = hasPermissions(c), advertisingSupported = advertising,
                bluetoothBackAtMs = if (!on) null else back ?: it.bluetoothBackAtMs,
            )
        }
    }

    /** Bluetooth at the last check; null - not checked yet in this process. */
    private var lastBluetoothOn: Boolean? = null

    /** Android 12+: BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (in upstream's manifest); before - install-time ones. */
    fun permissions(): Array<String> = PumpBtSource.permissions()

    fun hasPermissions(context: Context): Boolean = PumpBtSource.hasPermissions(context)

    /** The wizard's warnings (decision 14) accepted: kept in the link state file, they are not shown again. */
    fun acceptWarnings(context: Context) {
        val c = context.applicationContext
        acceptWarnings(PumpBtSource.store(c))
        // the window of someone new: the source is set up now (critic G1, N4)
        if (!attached) attach(c)
    }

    internal fun acceptWarnings(store: PumpBtStore) {
        store.warningsAccepted = true
        journal("pairing warnings accepted")
        _state.update { it.copy(warningsAccepted = true) }
    }

    /** "Start pairing" (also "Pair again" with the source on): without a key database it is downloaded first. */
    fun startPairing() = start(pair = true)

    fun waitForPump() = start(pair = false)

    /**
     * A start: the phone not ready - refused; no key database - the chain first ([loadKey]; the start goes on once a
     * key is in place, by the download or from a file, while it still waits - [cancelPendingStart]); else the link.
     * [afterKey] - the start a key went on with: no key even then is refused, never downloaded again. [keepVariant] -
     * only [tryOtherName]: every other pairing starts with variant A (critic G1, C1: a failed or dropped pairing with B
     * must not leave B for the next one).
     */
    private fun start(pair: Boolean, afterKey: Boolean = false, keepVariant: Boolean = false) {
        env.checkPhone()
        val action = PumpBtWindow.startAction(_state.value, env.hasKey())
        if (action == PumpBtStartAction.REFUSE || (action == PumpBtStartAction.LOAD_KEY && afterKey)) {
            val why = PumpBtWindow.phoneBlockers(_state.value).map { it.name.lowercase() }.ifEmpty { listOf("no_key") }
            journal("start refused: ${why.joinToString()}")
            _state.update { it.copy(pendingStart = null) }
            return
        }
        if (action == PumpBtStartAction.LOAD_KEY) {
            _state.update { it.copy(pendingStart = pair) }
            loadKey()
            return
        }
        if (pair && !keepVariant) pairWithVariantA()
        // a pairing's wait time and its failures count from here (a running link keeps an older failure mark)
        _state.update { it.copy(pendingStart = null, pairingStartedAtMs = if (pair) env.now() else null) }
        val fresh = !env.linkRunning()
        env.startLink(pair)
        // a running link keeps the variant it started with (PumpLink.start)
        if (fresh) linkMayHoldB = env.store()?.variant == PumpBtNameVariant.B
    }

    /** The running link was started by this window while the stored variant was B (cleared when the link stops). */
    private var linkMayHoldB = false

    /**
     * A pairing uses variant A (finding 11; with the source on always, with it off unless [tryOtherName] asks for B).
     * A running link keeps the variant it started with: when that may have been B, it is stopped first, so the pairing
     * starts anew with A.
     */
    private fun pairWithVariantA() {
        val s = env.store() ?: return
        val want = PumpBtNameVariant.A
        val stored = s.variant
        if (stored == want && !linkMayHoldB) return
        if (stored != want) {
            s.variant = want
            _state.update { it.copy(variant = want) }
            journal("name variant set to $want for the pairing")
        }
        if (env.linkRunning()) env.stopLink()
        linkMayHoldB = false
    }

    /**
     * Variant B from the wizard's PAIRING step, after A failed: the pump does not see the phone - pair with the other
     * name (the phone's Bluetooth name is changed for the pairing and comes back right after it). The pairing is
     * stopped, B set, the pairing started again. Only with the source off ("Pair again" with it on stays on A) and
     * while a pairing goes on (a waiting link of a paired pump is not stopped - critic G1, N1).
     */
    fun tryOtherName() {
        val s = env.store() ?: return
        if (env.sourceOn()) {
            journal("other name not tried: permanent operation is on (pairing again uses variant A)")
            return
        }
        if (!PumpBtWindow.pairing(_state.value.link)) {
            journal("other name not tried: no pairing going on")
            return
        }
        if (s.variant == PumpBtNameVariant.B) return
        journal("pairing again with name variant B")
        // setVariant leaves a running link alone: the pairing ends first
        env.stopLink()
        s.variant = PumpBtNameVariant.B
        _state.update { it.copy(variant = PumpBtNameVariant.B) }
        start(pair = true, keepVariant = true)
    }

    /**
     * The KEY step's "Cancel" (critic G1, C2): a start waiting for the key database no longer goes on by itself when a
     * key comes later (a download still running is saved, nothing starts).
     */
    fun cancelPendingStart() {
        if (_state.value.pendingStart == null) return
        journal("start waiting for the key cancelled")
        _state.update { it.copy(pendingStart = null) }
    }

    /** Stops the link (and a start waiting for the key); with the source on its watchdog starts it again, waiting. */
    fun stop() {
        cancelPendingStart()
        env.stopLink()
    }

    fun readAgain() = PumpBtSource.readNow()

    fun setVariant(v: PumpBtNameVariant) {
        val s = env.store() ?: return
        if (env.linkRunning()) return
        s.variant = v
        journal("name variant set to $v")
        _state.update { it.copy(variant = v) }
    }

    /** The key database from OpenMinimed's page (the old window's "Load key"; the wizard downloads it by itself). */
    fun loadKeyFromInternet() = loadKey()

    /** The chain - the pinned page, then main - off the main thread; the outcome back on it ([applyKey]). */
    private fun loadKey() {
        if (_state.value.keyLoading) return
        _state.update { it.copy(keyLoading = true, keyFailure = null, keyFailureCode = 0) }
        journal("loading the key database from the OpenMinimed page (pinned commit, then main)")
        val download = env.download
        val onMain = env.onMain
        env.offMain("ModPumpBtKey") {
            val attempts = try {
                PumpBtKey.downloadChain(download)
            } catch (_: Throwable) {
                listOf(PumpBtKey.Attempt(PumpBtKey.MAIN_URL, PumpBtKey.Result.Failed(PumpBtKey.Failure.NO_NETWORK)))
            }
            onMain {
                for (a in attempts.dropLast(1)) journal("key database not loaded from ${pageName(a.url)}: ${failureText(a.result)}")
                val last = attempts.last()
                applyKey(last.result, pageName(last.url))
            }
        }
    }

    private fun pageName(url: String): String = when (url) {
        PumpBtKey.PINNED_URL -> "the OpenMinimed page (pinned commit)"
        PumpBtKey.MAIN_URL -> "the OpenMinimed page (main)"
        else -> "the OpenMinimed page"
    }

    private fun failureText(result: PumpBtKey.Result): String = when (result) {
        is PumpBtKey.Result.Ok -> "ok"
        is PumpBtKey.Result.Failed -> result.failure.name.lowercase() + if (result.code != 0) " ${result.code}" else ""
    }

    /** "Load from file" (also after a failed download: a start waiting for the key goes on with it). */
    fun loadKeyFromFile(context: Context, uri: Uri) {
        if (_state.value.keyLoading) return
        val c = context.applicationContext
        _state.update { it.copy(keyLoading = true, keyFailure = null, keyFailureCode = 0) }
        journal("loading the key database from a chosen file")
        val onMain = env.onMain
        env.offMain("ModPumpBtKeyFile") {
            val result = try {
                val text = c.contentResolver.openInputStream(uri)?.use { input ->
                    // a key page is a few kB: read at most 1 MB
                    String(input.readNBytesCompat(1024 * 1024), Charsets.UTF_8)
                }
                if (text == null) PumpBtKey.Result.Failed(PumpBtKey.Failure.FILE_NOT_READ) else PumpBtKey.parse(text)
            } catch (t: Throwable) {
                PumpBtKey.Result.Failed(PumpBtKey.Failure.FILE_NOT_READ)
            }
            onMain { applyKey(result, "a file") }
        }
    }

    /**
     * A key database came (or did not): saved and handed to the source; a CRC other than the trial's one leaves a
     * journal line, not a refusal. A start waiting for the key goes on; a failure leaves it waiting ("Load from file").
     */
    internal fun applyKey(result: PumpBtKey.Result, source: String) {
        when (result) {
            is PumpBtKey.Result.Ok -> {
                val saved = try {
                    env.saveKey(result)
                    true
                } catch (t: Throwable) {
                    journal("key database not saved (${t.javaClass.simpleName})")
                    false
                }
                if (!saved) {
                    _state.update { it.copy(keyLoading = false, keyFailure = PumpBtKey.Failure.FILE_NOT_READ) }
                    return
                }
                // a new key reaches the link at its next start
                journal("key database loaded from $source: CRC ${result.crc}, Mobile Application ↔ Insulin Pump")
                PumpBtKey.crcLine(result.crc)?.let { journal(it) }
                val pending = _state.value.pendingStart
                _state.update { it.copy(keyLoading = false, keyCrc = result.crc, keyFailure = null, keyFailureCode = 0) }
                if (pending != null) start(pending, afterKey = true)
            }
            is PumpBtKey.Result.Failed -> {
                journal("key database not loaded from $source: ${failureText(result)}")
                PumpBtLog.w("key not loaded (${result.failure.name.lowercase()})")
                _state.update { it.copy(keyLoading = false, keyFailure = result.failure, keyFailureCode = result.code) }
            }
        }
    }

    /** Empties the journal and deletes its shared copies (the screen asks first). */
    fun clearJournal(context: Context) {
        PumpBtJournal.clear(exportsDir(context))
        PumpBtSource.journalCleared(context)
    }

    /**
     * A copy of the journal in `cacheDir/exports/` for sharing (older copies there are deleted first); null - none.
     * Blocking: call off the main thread.
     */
    fun exportJournal(context: Context): File? {
        journal("journal shared")
        val name = PumpBtJournal.COPY_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"
        return PumpBtJournal.copyTo(exportsDir(context), name)
    }

    private fun exportsDir(context: Context) = File(context.applicationContext.cacheDir, "exports")

    /** Tests: another environment and a fresh window state (null - back to Android's). */
    internal fun resetForTest(testEnv: Env?, ui: PumpBtUiState = PumpBtUiState()) {
        env = testEnv ?: Env()
        _state.value = ui
        linkMayHoldB = false
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
