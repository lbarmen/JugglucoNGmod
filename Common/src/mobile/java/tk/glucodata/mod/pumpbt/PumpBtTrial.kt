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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JugglucoNGmod, pump over Bluetooth (trial window): a thin layer over [PumpBtSource], which owns the link, the history
 * memory and its number (series part 2, part B2a). Here only what the window shows besides the source - Bluetooth and
 * permission checks, the key database loading, the name variant, the journal's copy and clearing; the buttons go to the
 * source. Closing the window or turning the screen off does not end the link. All calls on the main thread.
 */
internal object PumpBtTrial {

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PumpBtUiState())
    val state: StateFlow<PumpBtUiState> = _state.asStateFlow()

    private var app: Context? = null

    private val journal: (String) -> Unit = { PumpBtJournal.add(it) }

    /** The trial window opened: the source is set up (idempotent; it gives back a changed phone name), nothing started. */
    fun onScreenOpened(context: Context) {
        val c = context.applicationContext
        app = c
        PumpBtSource.init(c)
        val s = PumpBtSource.store(c)
        journal("trial screen opened ${PumpBtJournal.fullDate()}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        PumpBtSource.onLinkState = { ls -> _state.update { it.copy(link = ls) } }
        _state.update {
            it.copy(
                link = PumpBtSource.state.value.link, variant = s.variant, advertisedName = s.advertisedName,
                keyCrc = PumpBtSource.key(c)?.crc,
            )
        }
        refreshChecks(c)
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
        _state.update { it.copy(hasBluetooth = has, bluetoothOn = on, permissionsGranted = hasPermissions(c), advertisingSupported = advertising) }
    }

    /** Android 12+: BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (in upstream's manifest); before - install-time ones. */
    fun permissions(): Array<String> = PumpBtSource.permissions()

    fun hasPermissions(context: Context): Boolean = PumpBtSource.hasPermissions(context)

    fun startPairing() = start(pair = true)

    fun waitForPump() = start(pair = false)

    private fun start(pair: Boolean) {
        val c = app ?: return
        refreshChecks(c)
        val blockers = _state.value.blockers
        if (PumpBtSource.key(c) == null || blockers.isNotEmpty()) {
            journal("start refused: ${blockers.joinToString { it.name.lowercase() }}")
            return
        }
        PumpBtSource.startLink(c, pair)
    }

    /** Stops the link; with the source on its watchdog starts it again, waiting for the pump. */
    fun stop() = PumpBtSource.stopLink()

    fun readAgain() = PumpBtSource.readNow()

    fun setVariant(v: PumpBtNameVariant) {
        val c = app ?: return
        val s = PumpBtSource.store(c)
        if (PumpBtSource.linkRunning) return
        s.variant = v
        journal("name variant set to $v")
        _state.update { it.copy(variant = v) }
    }

    fun loadKeyFromInternet() {
        if (_state.value.keyLoading) return
        _state.update { it.copy(keyLoading = true, keyFailure = null) }
        journal("loading the key database from the OpenMinimed page")
        Thread({
            val result = try {
                PumpBtKey.download()
            } catch (t: Throwable) {
                PumpBtKey.Result.Failed(PumpBtKey.Failure.NO_NETWORK)
            }
            main.post { applyKey(result, "the OpenMinimed page") }
        }, "ModPumpBtKey").start()
    }

    fun loadKeyFromFile(context: Context, uri: Uri) {
        if (_state.value.keyLoading) return
        val c = context.applicationContext
        _state.update { it.copy(keyLoading = true, keyFailure = null) }
        journal("loading the key database from a chosen file")
        Thread({
            val result = try {
                val text = c.contentResolver.openInputStream(uri)?.use { input ->
                    // a key page is a few kB: read at most 1 MB
                    String(input.readNBytesCompat(1024 * 1024), Charsets.UTF_8)
                }
                if (text == null) PumpBtKey.Result.Failed(PumpBtKey.Failure.FILE_NOT_READ) else PumpBtKey.parse(text)
            } catch (t: Throwable) {
                PumpBtKey.Result.Failed(PumpBtKey.Failure.FILE_NOT_READ)
            }
            main.post { applyKey(result, "a file") }
        }, "ModPumpBtKeyFile").start()
    }

    private fun applyKey(result: PumpBtKey.Result, source: String) {
        val c = app
        when (result) {
            is PumpBtKey.Result.Ok -> {
                val saved = try {
                    if (c != null) PumpBtKey.save(c, result)
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
                PumpBtSource.keyLoaded(result)
                journal("key database loaded from $source: CRC ${result.crc}, Mobile Application ↔ Insulin Pump")
                _state.update { it.copy(keyLoading = false, keyCrc = result.crc, keyFailure = null) }
            }
            is PumpBtKey.Result.Failed -> {
                journal("key database not loaded from $source: ${result.failure.name.lowercase()}" + if (result.code != 0) " ${result.code}" else "")
                PumpBtLog.w("key not loaded (${result.failure.name.lowercase()})")
                _state.update { it.copy(keyLoading = false, keyFailure = result.failure, keyFailureCode = result.code) }
            }
        }
    }

    /** Empties the journal and deletes its shared copies (the screen asks first). */
    fun clearJournal(context: Context) {
        PumpBtJournal.clear(exportsDir(context))
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
