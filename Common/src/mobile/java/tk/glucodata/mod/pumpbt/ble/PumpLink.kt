// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// apps/android/app/src/main/java/de/sgitaize/openminipump/ble/PumpLink.kt, by Simon Gutjahr
// (itself a port of apps/ios/OpenMinipump/BLE/PumpLink.swift).
// Modified for JugglucoNGmod: cut down to a read-only link - no demo mode, no saved session (every link is a new
// handshake), no Medtronic sensor (CGM), alarms, data store, Nightscout or preferences of the original; writes,
// reads and subscriptions go through PumpGate; the GATT client attaches only to a pump connected to our GATT server
// right now (checked just before connectGatt, closed at once when that link is gone); the pump is the device that
// reads our device information or writes to SAKE (a SAKE writer overrides a device that only read it), any other
// device gets refusals only; advertising stops when the pump is there, starts again when a connection ends before the
// pump is identified, and always restarts as "stop + start"; name variant B (phone Bluetooth name, given back right
// after the pairing); no dead ends - every failure drops the link and advertises again with a pause growing to a
// minute, the handshake has an overall time limit; a GATT command timeout waits until Android takes the next command;
// Status Reader answers end only their own request; short wake holds for a handshake and a read cycle only; Bluetooth
// and bond receivers registered like upstream's (no export flag, system actions only); history read from the number
// the caller gives, in pages, up to the newest, with an honest result; every finished cycle handed out as a
// PumpCycle; the journal holds no serial number, doses or decrypted answers unless verbose; time in range is not asked
// again once the pump said it does not support it.
//
// Roles: the pump is the BLE central and SAKE client, the phone is the BLE peripheral and SAKE server. After the SAKE
// handshake the phone additionally acts as GATT client on the same link to read the pump's services.
//
// Threading: all state lives on the main thread. Android BLE callbacks arrive on binder threads and are posted to the
// main looper with a copy of their data; the few fields the binder threads read to answer at once are volatile.
package tk.glucodata.mod.pumpbt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import tk.glucodata.mod.pumpbt.PumpBtHistory
import tk.glucodata.mod.pumpbt.PumpBtJournal
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtProgress
import tk.glucodata.mod.pumpbt.PumpBtReturn
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.PumpBtStore
import tk.glucodata.mod.pumpbt.PumpBtValues
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules.HistoryEnding
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules.LinkOp
import tk.glucodata.mod.pumpbt.core.ActiveBasalRate
import tk.glucodata.mod.pumpbt.core.HistoryRead
import tk.glucodata.mod.pumpbt.core.HistoryReadStatus
import tk.glucodata.mod.pumpbt.core.InsulinOnBoard
import tk.glucodata.mod.pumpbt.core.KeyDatabase
import tk.glucodata.mod.pumpbt.core.PumpCycle
import tk.glucodata.mod.pumpbt.core.PumpIdentity
import tk.glucodata.mod.pumpbt.core.PumpStatus
import tk.glucodata.mod.pumpbt.core.PumpUuid
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import tk.glucodata.mod.pumpbt.core.SakeServer
import tk.glucodata.mod.pumpbt.core.StatusChangedFlags
import tk.glucodata.mod.pumpbt.core.StatusReaderOpcode
import tk.glucodata.mod.pumpbt.core.TherapyAlgorithmStates
import tk.glucodata.mod.pumpbt.core.TimeInRange
import tk.glucodata.mod.pumpbt.core.statusReaderResponseOpcode
import tk.glucodata.mod.pumpbt.core.toHex
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

@SuppressLint("MissingPermission")
internal class PumpLink(
    context: Context,
    private val keyDatabase: KeyDatabase,
    private val store: PumpBtStore,
    /** A line for the link journal: no serial number, doses or decrypted answers unless [verbose]; never key material. */
    private val journal: (String) -> Unit,
    /** A line for logcat: stages and errors only. */
    private val logcat: (String) -> Unit,
    private val publish: (PumpBtLinkState) -> Unit,
    /** Asked at the start of every history read; null - from the oldest record on the pump. */
    private val historyFrom: () -> Long?,
    /** Every finished read cycle, on the main thread. */
    private val onCycle: (PumpCycle) -> Unit,
    /** Detailed journal (serial number, values, decrypted answers, history records); off by default. */
    private val verbose: () -> Boolean = { false },
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private var state = PumpBtLinkState()

    @Volatile private var running = false
    private var pairMode = false
    private var receiversOn = false
    private val advertisedName: String = store.advertisedName
    private var variant = store.variant

    // Peripheral side (GATT server, SAKE). Read by binder threads too: volatile.
    @Volatile private var server: BluetoothGattServer? = null
    private var sakePort: BluetoothGattCharacteristic? = null
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var servicesReady = false
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseToken = 0
    /** Android confirmed the current advertisement. */
    @Volatile private var advertisingOn = false
    @Volatile private var sake: SakeServer? = null
    @Volatile private var pumpDevice: BluetoothDevice? = null
    /** [pumpDevice] has written to SAKE (not only read our device information). */
    @Volatile private var pumpBySake = false
    /** The last state the GATT server callback reported per address (for the journal next to the system's answer). */
    private val serverStates = mutableMapOf<String, Int>()
    /** elapsedRealtime when a device connected to our server (for a failed pairing: left within 5 s). */
    private val serverConnectedAt = mutableMapOf<String, Long>()
    /** Addresses that counted as the pump during their current connection. */
    private val identifiedAddresses = mutableSetOf<String>()
    /** Another device left and the pump did not look connected: asked again after [PumpLinkRules.RECHECK_MS]. */
    private var recheckFor: BluetoothDevice? = null
    private var recheckStatus = 0
    private val pumpRecheck = Runnable {
        val pump = recheckFor ?: return@Runnable
        recheckFor = null
        val same = pumpDevice?.address == pump.address
        val linked = same && isLinked(pump)
        journal(
            "pump ${mac(pump.address)} on our server ${PumpLinkRules.RECHECK_MS} ms later: " +
                if (!same) "no longer the pump" else if (linked) "connected - not lost" else "not connected - lost",
        )
        if (PumpLinkRules.recheckLost(same, linked)) pumpDisconnected(recheckStatus)
    }

    /** The device whose pairing the screen shows (stage "pairing"), before the pump is identified. */
    private var pairingShownAddress: String? = null
    private val outbox = ArrayDeque<ByteArray>()
    private var notifyInFlight = false
    private var notifyFailures = 0
    /** onNotificationSent did not come: free the queue so the handshake does not stall silently. */
    private val notifyWatchdog = Runnable {
        if (!notifyInFlight) return@Runnable
        journal("SAKE notification: no 'sent' callback within ${NOTIFY_WATCHDOG_MS / 1000} s, sending the next one")
        notifyInFlight = false
        flushOutbox()
    }
    private val flushRetry = Runnable { flushOutbox() }
    /** Several drops in a row before the pump is identified: one advertising restart. */
    private val advertiseAgainLater = Runnable {
        if (running && pumpDevice == null && !recoverScheduled) updateAdvertising()
    }

    // No dead ends: after a failure the link is dropped and advertising starts again after a growing pause.
    /** Failures since the last complete handshake (sets the pause, see [PumpLinkRules.recoverDelayMs]). */
    private var recoverFailures = 0
    private var recoverScheduled = false
    private val recoverRun = Runnable { recoverNow() }
    private val handshakeDeadline: Runnable = Runnable { handshakeOverTime() }
    private var handshakeExtensions = 0

    // Central side (GATT client on the pump)
    private var gatt: BluetoothGatt? = null
    private val characteristics = mutableMapOf<String, BluetoothGattCharacteristic>()
    private var attachAttempts = 0
    /** elapsedRealtime of the last connectGatt, for "services discovered after N ms". */
    private var connectGattAtMs = 0L
    private var decryptFailures = 0
    private var featuresRead = false
    /** [PumpIdentity.hash] of the serial number read on this client link; null - not read. */
    private var linkSerialHash: String? = null

    /** The only way to the pump's characteristics: queues GATT commands, held by [gate] only. */
    private val outlet = object : PumpOutlet {
        override fun read(service: String, characteristic: String): Boolean =
            enqueueFor(service, characteristic) { GattCmd.Read(it) }

        override fun subscribe(service: String, characteristic: String): Boolean =
            enqueueFor(service, characteristic) { GattCmd.Subscribe(it) }

        override fun write(service: String, characteristic: String, payload: ByteArray): Boolean =
            enqueueFor(service, characteristic) { GattCmd.Write(it, payload) }
    }
    private val gate = PumpGate(outlet) { journal(it) }

    // Read cycle (logical operations, one at a time; the list comes from PumpLinkRules)
    private val operations = ArrayDeque<LinkOp>()
    private var current: LinkOp? = null
    private var currentStartedAt: Instant? = null
    private var lastRefresh: Instant = Instant.EPOCH
    private var refreshPending = false
    private var historyPending = false
    private var lastHistorySync: Instant = Instant.EPOCH
    private val operationTimeout = Runnable {
        current?.let { op ->
            journal("no answer from the pump: $op")
            if (op.isHistory) historyOpFailed(op, HistoryEnding.TIMED_OUT)
        }
        nextOperation()
    }

    /** Values of the running cycle; null - no cycle running. */
    private var cycle: CycleData? = null

    private class CycleData {
        var therapyState: Int? = null
        var reservoirUnits: Double? = null
        var iobUnits: Double? = null
        var basalRate: Double? = null
        var basalRead = false
        var tempBasalRate: Double? = null
        var tempBasalType: Int? = null
        var tempBasalMinutesLeft: Int? = null
        var batteryPercent: Int? = null
        /** A status value came in this cycle (IDD Status, a Status Reader answer, battery). */
        var gotData = false
        /** null - the cycle reads no history. */
        var history: HistoryReading? = null
    }

    /** One history read: from the number given, "first" and "last" record, the pages, what came. */
    private class HistoryReading(val from: Long?) {
        /** Smallest number seen during "first record", largest during "last record"; taken only with a success answer. */
        var firstSeen: Long? = null
        var lastSeen: Long? = null
        var firstAnswer = PumpLinkRules.EndAnswer.FAILED
        var lastAnswer = PumpLinkRules.EndAnswer.FAILED
        var oldest: Long? = null
        var newest: Long? = null
        var requestedFrom: Long? = null
        var requestedTo: Long? = null
        /** Read from newest − 3000: neither the memory's number nor the oldest record was known. */
        var fallback = false
        val received = TreeMap<Long, RawHistoryRecord>()
        var outside = 0
        /** Late answers dropped in the pause after "first record" timed out. */
        var dropped = 0
        var ending: HistoryEnding? = null
    }

    @Volatile private var lastCycleAtMs: Long? = null
    @Volatile private var lastHistoryAtMs: Long? = null

    // Android allows one outstanding GATT operation; commands are queued here.
    private sealed class GattCmd {
        class Read(val c: BluetoothGattCharacteristic) : GattCmd() {
            override fun toString() = "read ${PumpUuid.short(c.uuid)}"
        }

        class Write(val c: BluetoothGattCharacteristic, val value: ByteArray) : GattCmd() {
            override fun toString() = "write ${PumpUuid.short(c.uuid)}"
        }

        class Subscribe(val c: BluetoothGattCharacteristic) : GattCmd() {
            override fun toString() = "subscribe ${PumpUuid.short(c.uuid)}"
        }
    }

    /** Result of handing one command to Android. */
    private enum class Start { STARTED, BUSY, IMPOSSIBLE }

    private val gattQueue = ArrayDeque<GattCmd>()
    private var gattBusy = false
    /** Times Android did not start the command at the head of [gattQueue]. */
    private var gattRetries = 0
    /** elapsedRealtime of the last command timeout while Android may still be busy with it; null - none. */
    private var gattStaleSinceMs: Long? = null
    private val gattRetry = Runnable { runGattQueue() }
    // A command without answer is logged; the next one waits until Android takes it (Android may still be busy with the
    // old one). The client stays open (closing the only client on a link the pump opened may drop the whole link).
    private val gattTimeout = Runnable {
        journal("GATT command without answer in ${GATT_TIMEOUT_MS / 1000} s; the next one waits until Android takes it")
        gattBusy = false
        gattStaleSinceMs = SystemClock.elapsedRealtime()
        main.removeCallbacks(gattRetry)
        main.postDelayed(gattRetry, PumpLinkRules.GATT_RETRY_MS)
    }
    private val attachTimeout = Runnable {
        journal("GATT client: no connection and services within ${ATTACH_TIMEOUT_MS / 1000} s, closing")
        // disconnect() only when the pump is no longer on our server: a live link of the pump is not touched, a
        // pending direct connection is cancelled
        closeClient(disconnect = pumpDevice?.let { !isLinked(it) } ?: true)
        resetClientState()
        attachAgainLater()
    }

    /** Short wake holds only: a handshake or read cycle, extended per history page, capped (see [PumpLinkRules.wakeHoldMs]). */
    private val wake = WakeHold()

    private inner class WakeHold {
        private val lock: PowerManager.WakeLock? = try {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JugglucoNGmod:pumpbt")
                ?.apply { setReferenceCounted(false) }
        } catch (_: Exception) {
            null
        }
        private var startedAt = 0L

        /** Counts [begin]s: a release for an earlier hold does not end a newer one. */
        var generation = 0
            private set

        fun begin() {
            generation++
            startedAt = SystemClock.elapsedRealtime()
            hold()
        }

        /** Releases the hold only if no new one began since [ofGeneration]. */
        fun release(ofGeneration: Int) {
            if (ofGeneration == generation) release()
        }

        fun extend() = hold()

        private fun hold() {
            val ms = PumpLinkRules.wakeHoldMs(SystemClock.elapsedRealtime() - startedAt)
            if (ms <= 0) return release()
            try {
                lock?.acquire(ms)
            } catch (_: Exception) {
            }
        }

        fun release() {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Exception) {
            }
        }
    }

    // MARK: - Public controls (main thread)

    val isRunning: Boolean get() = running

    /** For the watchdog: running, advertising, the pump connected, last cycle and last history read. */
    val health: PumpLinkHealth
        get() = PumpLinkHealth(running, advertisingOn, pumpDevice != null, lastCycleAtMs, lastHistoryAtMs)

    /**
     * Starts the link: [pair] - advertising for pairing (FE82; from the trial window only, the only case name variant B
     * is used), else for the paired pump's return (FE81) - the permanent mode.
     */
    fun start(pair: Boolean) {
        // the permanent mode never keeps the phone's name of variant B
        if (!pair) PumpBtAdapterName.restore(context, store, nameJournal)
        if (running) {
            pairMode = pair
            update { restarted(it, pair) }
            journal("mode: " + if (pair) "pairing" else "waiting for the pump")
            if (!recoverScheduled) updateAdvertising()
            return
        }
        variant = store.variant
        running = true
        pairMode = pair
        recoverFailures = 0
        update {
            it.copy(
                running = true, pairMode = pair, error = null, lastProblem = null, lastProblemAtMs = null,
                furthestStage = null, pairingFailedAtMs = null,
            )
        }
        journal(
            "link started ${PumpBtJournal.fullDate()}: " + (if (pair) "pairing, name variant $variant" else "waiting for the pump") +
                ", paired before: ${store.paired}",
        )
        registerReceivers()
        val a = adapter
        logBondedDevices(a)
        when {
            a == null -> showError("this phone has no Bluetooth")
            !isEnabled(a) -> showError("Bluetooth is off - the link starts when it is on")
            else -> setUpServer()
        }
    }

    /**
     * The phone's bonded devices: how many and how many pump-like (a bond left from an earlier attempt is a likely
     * reason for a failed pairing); name + address of pump-like ones only in the detailed journal.
     */
    private fun logBondedDevices(a: BluetoothAdapter?) {
        if (a == null) return
        try {
            if (!a.isEnabled) return
            val bonded = a.bondedDevices ?: emptySet()
            val pumps = bonded.mapNotNull { d ->
                val name = try {
                    d.name
                } catch (_: SecurityException) {
                    null
                }
                if (PumpLinkRules.pumpLikeName(name)) "\"$name\" ${d.address}" else null
            }
            // a pump's Bluetooth name may hold its serial number: names only in the detailed journal
            journal("bonded devices of the phone: ${bonded.size}, pump-like: ${pumps.size}")
            if (pumps.isNotEmpty()) detail { "pump-like bonded devices: " + pumps.joinToString(", ") }
        } catch (_: SecurityException) {
            journal("bonded devices not read: Bluetooth permission missing")
        } catch (e: Exception) {
            journal("bonded devices not read (${e.javaClass.simpleName})")
        }
    }

    fun stop() {
        if (!running) return
        journal("link stopped")
        running = false
        pairMode = false
        main.removeCallbacks(recoverRun)
        recoverScheduled = false
        unregisterReceivers()
        tearDown(cancelPump = true)
        PumpBtAdapterName.restore(context, store, nameJournal)
        setStage(PumpBtStage.STOPPED)
        update { it.copy(running = false, pairMode = false, lostAtMs = null) }
    }

    /** Watchdog and the window's "read again": read now if there is a link with the pump (otherwise nothing). */
    fun readNow() {
        if (!running) return
        val device = pumpDevice
        when {
            gate.serialVerified && gatt != null -> {
                journal("read now")
                scheduleRefresh(force = true, withHistory = true)
            }
            gate.crypt != null && device != null && gatt == null -> {
                journal("read now: attaching the GATT client")
                attachAttempts = 0
                connectAsGattClient()
            }
            else -> journal("read now: no live link with the pump")
        }
    }

    /** Watchdog: drop the link and advertise again at once (the pump comes back with a new handshake). */
    fun reconnect() {
        if (!running) return
        journal("reconnect: dropping the link and advertising again")
        main.removeCallbacks(recoverRun)
        recoverScheduled = false
        dropPumpLink()
        if (server == null || !servicesReady) {
            closeServer()
            if (adapter?.let { isEnabled(it) } == true) setUpServer()
        } else {
            setStage(if (pairMode) PumpBtStage.ADVERTISING_PAIR else PumpBtStage.WAITING)
            updateAdvertising()
        }
    }

    // MARK: - State

    private fun update(transform: (PumpBtLinkState) -> PumpBtLinkState) {
        state = transform(state)
        publish(state)
    }

    private fun updateValues(transform: (PumpBtValues) -> PumpBtValues) = update { it.copy(values = transform(it.values)) }

    /** A journal line only in the detailed journal (values, serial number, decrypted answers). */
    private inline fun detail(line: () -> String) {
        if (verbose()) journal(line())
    }

    /** A device in the journal: the address only in the detailed journal. */
    private fun mac(address: String?): String = PumpLinkRules.deviceTag(address, verbose())

    /** Lines of [PumpBtAdapterName]: the phone's own Bluetooth name only in the detailed journal. */
    private val nameJournal: (String) -> Unit = { line -> journal(if (verbose()) line else PumpLinkRules.hideQuoted(line)) }

    private fun setStage(stage: PumpBtStage, error: String? = null) {
        if (state.stage == stage && state.error == error) return
        journal("stage: ${stage.name.lowercase()}" + (error?.let { " - $it" } ?: ""))
        logcat("stage ${stage.name.lowercase()}" + (error?.let { " - $it" } ?: ""))
        val now = System.currentTimeMillis()
        update {
            it.copy(
                stage = stage,
                error = error,
                furthestStage = if (running) PumpBtProgress.furthest(it.furthestStage, stage) else it.furthestStage,
                // the last problem stays on the screen until the next start ("connection lost" does not replace it)
                lastProblem = error ?: it.lastProblem,
                lastProblemAtMs = if (error != null) now else it.lastProblemAtMs,
            )
        }
    }

    /** An error shown without recovery of its own (Bluetooth off or missing: the state receiver brings it back). */
    private fun showError(reason: String) {
        journal("error: $reason")
        setStage(PumpBtStage.ERROR, reason)
    }

    /**
     * Any failure: drop the link and advertise again (FE81, or FE82 while pairing) after a pause that grows to a
     * minute ([PumpLinkRules.recoverDelayMs]). Never a dead end; the phone still never connects to the pump itself.
     */
    private fun fail(reason: String) {
        if (!running) return
        val delay = PumpLinkRules.recoverDelayMs(recoverFailures)
        recoverFailures++
        journal("error: $reason - link dropped, advertising again in ${delay / 1000} s")
        logcat("error: $reason")
        setStage(PumpBtStage.ERROR, reason)
        dropPumpLink()
        stopAdvertising()
        recoverScheduled = true
        main.removeCallbacks(recoverRun)
        main.postDelayed(recoverRun, delay)
    }

    private fun recoverNow() {
        recoverScheduled = false
        if (!running) return
        val a = adapter
        if (a == null || !isEnabled(a)) {
            journal("advertising again: waiting for Bluetooth to be on")
            return
        }
        if (server == null || !servicesReady) {
            closeServer()
            setUpServer()
        } else {
            updateAdvertising()
        }
    }

    private fun isEnabled(a: BluetoothAdapter): Boolean = try {
        a.isEnabled
    } catch (_: Exception) {
        false
    }

    private fun markPairingFailed(why: String) {
        journal("pairing failed: $why - advertising goes on")
        update { it.copy(pairingFailedAtMs = System.currentTimeMillis()) }
    }

    // MARK: - Receivers (Bluetooth on/off, bond state)

    /**
     * Like upstream (SensorBluetooth.java, ACTION_PAIRING_REQUEST): no export flag - these are protected system
     * broadcasts sent by the Bluetooth process, which RECEIVER_NOT_EXPORTED would reject. Only system actions in the
     * filters.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        if (receiversOn) return
        try {
            context.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            receiversOn = true
        } catch (e: Exception) {
            journal("receivers not registered (${e.javaClass.simpleName})")
        }
    }

    private fun unregisterReceivers() {
        if (!receiversOn) return
        receiversOn = false
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: Exception) {
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    journal("Bluetooth on")
                    if (running) {
                        main.removeCallbacks(recoverRun)
                        recoverScheduled = false
                        if (state.stage == PumpBtStage.ERROR) setStage(if (pairMode) PumpBtStage.ADVERTISING_PAIR else PumpBtStage.WAITING)
                        closeServer()
                        setUpServer()
                    }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (server == null && !servicesReady && pumpDevice == null) return
                    journal("Bluetooth off")
                    main.removeCallbacks(recoverRun)
                    recoverScheduled = false
                    tearDown(cancelPump = false)
                    if (running) showError("Bluetooth turned off - the link starts again when it is on")
                }
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val extra: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            }
            val device = extra ?: return
            val now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            val before = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
            val address = device.address ?: return
            journal(bondLine(mac(address), before, now, intent.getIntExtra(EXTRA_BOND_REASON, -1)))
            if (!running) return
            val onServer = isLinked(device)
            val ours = address == pumpDevice?.address || address == pairingShownAddress ||
                address in serverConnectedAt || onServer
            if (PumpLinkRules.pairingFailedByBond(before == BluetoothDevice.BOND_BONDING, now == BluetoothDevice.BOND_NONE, ours)) {
                markPairingFailed("bond of ${mac(address)} went from bonding to none")
            }
            if (ours && now == BluetoothDevice.BOND_BONDED) {
                // variant B: the phone's own name is given back right after the pairing
                PumpBtAdapterName.restore(context, store, nameJournal)
            }
            val failed = PumpLinkRules.bondFailed(
                running, pumpKnown = pumpDevice != null,
                wasBonding = before == BluetoothDevice.BOND_BONDING, nowNone = now == BluetoothDevice.BOND_NONE,
            )
            if (failed == PumpLinkRules.Drop.ADVERTISE_AGAIN) {
                journal("pairing with ${mac(address)} failed or was refused, no pump identified: advertising again")
                advertiseAgain(pairingFailed = true, shownPairingDeviceLeft = false)
                return
            }
            if (PumpLinkRules.bondCountsAsPairing(address, pumpDevice?.address, onServer, pairMode)) {
                when (now) {
                    BluetoothDevice.BOND_BONDING -> {
                        if (pumpDevice == null) pairingShownAddress = address
                        setStage(PumpBtStage.PAIRING)
                    }
                    BluetoothDevice.BOND_BONDED -> if (state.stage == PumpBtStage.PAIRING) setStage(PumpBtStage.PUMP_CONNECTED)
                }
            }
        }
    }

    /**
     * A connection ended or a pairing failed before any device counted as the pump: the connection may have ended the
     * connectable advertisement, so "stop + start" it - once, [PumpLinkRules.ADVERTISE_AGAIN_DELAY_MS] after the last
     * of several drops. The stage goes back to advertising only when the pairing failed or the device whose pairing is
     * shown left (another device leaving does not change it).
     */
    private fun advertiseAgain(pairingFailed: Boolean, shownPairingDeviceLeft: Boolean) {
        if (!running || pumpDevice != null) return
        PumpLinkRules.stageAfterDrop(state.stage, pairMode, pairingFailed, shownPairingDeviceLeft)?.let {
            pairingShownAddress = null
            setStage(it)
        }
        main.removeCallbacks(advertiseAgainLater)
        main.postDelayed(advertiseAgainLater, PumpLinkRules.ADVERTISE_AGAIN_DELAY_MS)
    }

    private fun tearDown(cancelPump: Boolean) {
        stopAdvertising()
        if (cancelPump) {
            dropPumpLink()
        } else {
            forgetPump()
        }
        closeServer()
        serverStates.clear()
        serverConnectedAt.clear()
        identifiedAddresses.clear()
        main.removeCallbacks(advertiseAgainLater)
        wake.release()
    }

    private fun closeServer() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        sakePort = null
        servicesReady = false
        pendingServices.clear()
    }

    private fun clearOutbox() {
        outbox.clear()
        notifyInFlight = false
        notifyFailures = 0
        main.removeCallbacks(notifyWatchdog)
        main.removeCallbacks(flushRetry)
    }

    // MARK: - Peripheral side (GATT server, SAKE)

    /**
     * Values of the app's Device Information Service (custom UUID 0x0900). The pump reads all of them before it starts
     * the SAKE handshake. On iOS they mirror the official app; with other values the pump reported "device not
     * compatible". The same set of characteristics is served here, with Android device data and the version string of
     * the official iOS app.
     */
    private val deviceInfo: Map<String, ByteArray> by lazy {
        mapOf(
            "2A29" to Build.MANUFACTURER.toByteArray(),
            "2A24" to Build.MODEL.toByteArray(),
            "2A25" to advertisedName.toByteArray(),
            "2A27" to "Android".toByteArray(),
            "2A26" to "Android ${Build.VERSION.RELEASE}".toByteArray(),
            "2A28" to COMPAT_SOFTWARE_REVISION.toByteArray(),
            "2A23" to ByteArray(8),
            "2A50" to ByteArray(7),
            "2A2A" to ByteArray(0),
        )
    }

    private fun setUpServer() {
        if (!running || server != null) return
        val s = try {
            manager.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            fail("GATT server: Bluetooth permission missing")
            return
        } ?: run {
            fail("GATT server could not be opened")
            return
        }
        server = s
        servicesReady = false
        val port = BluetoothGattCharacteristic(
            PumpUuid.parse(PumpUuid.SAKE_PORT),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        port.addDescriptor(
            BluetoothGattDescriptor(
                PumpUuid.parse(PumpUuid.CCCD),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val sakeService = BluetoothGattService(PumpUuid.parse(PumpUuid.SAKE_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        sakeService.addCharacteristic(port)
        sakePort = port

        // Values are served dynamically in onCharacteristicReadRequest.
        val dis = BluetoothGattService(PumpUuid.parse(PumpUuid.APP_DEVICE_INFO_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for (uuid in listOf("2A29", "2A24", "2A25", "2A27", "2A26", "2A28", "2A23", "2A50", "2A2A")) {
            dis.addCharacteristic(
                BluetoothGattCharacteristic(
                    PumpUuid.parse(uuid), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
        }
        // Same order as the official app: SAKE service first, then device information.
        // Android accepts the next service only after onServiceAdded of the previous one.
        pendingServices.clear()
        pendingServices.add(sakeService)
        pendingServices.add(dis)
        addNextService()
    }

    private fun addNextService() {
        val s = server ?: return
        val next = pendingServices.removeFirstOrNull()
        if (next == null) {
            servicesReady = true
            journal("GATT server ready (SAKE 0xFE82, device information 0x0900)")
            updateAdvertising()
            return
        }
        val ok = try {
            s.addService(next)
        } catch (e: Exception) {
            false
        }
        if (!ok) fail("service ${PumpUuid.short(next.uuid)} not added to the GATT server")
    }

    /** Always "stop + start": the controller may have ended the advertisement by itself on a connection. */
    private fun updateAdvertising() {
        stopAdvertising()
        val a = adapter
        val mode = PumpLinkRules.advertising(running && servicesReady && a != null && isEnabled(a), pumpDevice != null, pairMode) ?: return
        if (a == null) return
        val advertiser = try {
            if (a.isMultipleAdvertisementSupported) a.bluetoothLeAdvertiser else null
        } catch (_: Exception) {
            null
        } ?: run {
            fail("this phone cannot advertise as a Bluetooth accessory now")
            return
        }
        val token = advertiseToken
        // name variant B only while pairing from the trial window; the permanent mode (FE81) carries no name
        if (mode == PumpLinkRules.Advertising.PAIR && variant == PumpBtNameVariant.B) {
            val name = try {
                a.name
            } catch (_: SecurityException) {
                null
            }
            if (name.isNullOrEmpty()) {
                // the name could not be given back: do not change it; not a dead end - advertising is tried again
                fail("phone Bluetooth name not readable — use variant A")
                return
            }
            if (name != advertisedName) {
                if (store.originalAdapterName == null) store.originalAdapterName = name
                val ok = try {
                    a.setName(advertisedName)
                } catch (_: SecurityException) {
                    false
                }
                journal("name variant B: phone Bluetooth name set to \"$advertisedName\" (" + (if (ok) "accepted" else "refused") + ")")
                // the new name takes a moment to reach the controller
                main.postDelayed({ if (token == advertiseToken) startAdvertising(advertiser, mode, token) }, 1500)
                return
            }
        }
        startAdvertising(advertiser, mode, token)
    }

    private fun startAdvertising(advertiser: BluetoothLeAdvertiser, mode: PumpLinkRules.Advertising, token: Int) {
        if (!running) return
        // LOW_LATENCY is about 100 ms on Android; the pump wants 150 ms or less for reconnects. No time limit.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder().setIncludeTxPowerLevel(false)
        val what: String
        if (mode == PumpLinkRules.Advertising.PAIR) {
            data.addServiceUuid(ParcelUuid(PumpUuid.parse(PumpUuid.SAKE_SERVICE)))
            if (variant == PumpBtNameVariant.A) {
                // Android cannot set a local name per advertisement (only the adapter name), so the "Mobile …" name
                // goes into Medtronic manufacturer data: company 0x01F9, 00 + name + 00.
                data.setIncludeDeviceName(false)
                data.addManufacturerData(MEDTRONIC_COMPANY_ID, PumpLinkRules.manufacturerData(advertisedName))
                what = "pairing (FE82, manufacturer data \"$advertisedName\")"
            } else {
                data.setIncludeDeviceName(true)
                what = "pairing (FE82, phone name \"$advertisedName\")"
            }
            if (state.stage in ADVERTISE_STAGES) setStage(PumpBtStage.ADVERTISING_PAIR)
        } else {
            // Reconnects work via the bond (IRK).
            data.setIncludeDeviceName(false)
            data.addServiceUuid(ParcelUuid(PumpUuid.parse(PumpUuid.RECONNECT_SERVICE)))
            what = "waiting for the pump (FE81)"
            if (state.stage in ADVERTISE_STAGES) setStage(PumpBtStage.WAITING)
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                main.post {
                    if (token != advertiseToken) return@post
                    advertisingOn = true
                    journal("advertising started: $what")
                }
            }

            override fun onStartFailure(errorCode: Int) {
                main.post {
                    if (token != advertiseToken) return@post
                    if (advertiseCallback === this) advertiseCallback = null
                    advertisingOn = false
                    fail("advertising failed: ${PumpLinkRules.advertiseError(errorCode)}")
                }
            }
        }
        advertiseCallback = callback
        try {
            advertiser.startAdvertising(settings, data.build(), callback)
        } catch (e: Exception) {
            advertiseCallback = null
            fail("advertising not started (${e.javaClass.simpleName})")
        }
    }

    private fun stopAdvertising() {
        advertiseToken++
        advertisingOn = false
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
            journal("advertising stopped")
        } catch (_: Exception) {
        }
    }

    private fun send(data: ByteArray) {
        outbox.addLast(data)
        flushOutbox()
    }

    private fun flushOutbox() {
        val s = server ?: return
        val port = sakePort ?: return
        val device = pumpDevice ?: return
        if (notifyInFlight) return
        val next = outbox.firstOrNull() ?: return
        // the return code for the journal: Android 13+ gives a status code, older ones true/false
        val code: String = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, port, false, next).let { if (it == BluetoothStatusCodes.SUCCESS) OK else "code $it" }
            } else {
                @Suppress("DEPRECATION")
                port.value = next
                @Suppress("DEPRECATION")
                if (s.notifyCharacteristicChanged(device, port, false)) OK else "false"
            }
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
        main.removeCallbacks(flushRetry)
        if (code == OK) {
            outbox.removeFirst()
            notifyInFlight = true
            notifyFailures = 0
            main.removeCallbacks(notifyWatchdog)
            main.postDelayed(notifyWatchdog, NOTIFY_WATCHDOG_MS)
        } else {
            notifyFailures++
            if (PumpLinkRules.logNotifyFailure(notifyFailures)) {
                journal("SAKE notification (${next.size} bytes) not sent: $code (failure $notifyFailures), retrying every 50 ms")
            }
            main.postDelayed(flushRetry, 50)
        }
    }

    /** [device]'s claim to be the pump right now (binder or main thread: reads only volatile fields). */
    private fun claimNow(device: BluetoothDevice, bySake: Boolean): PumpLinkRules.Claim {
        val known = pumpDevice
        val knownLinked = known != null && known.address != device.address && isLinked(known)
        return PumpLinkRules.claim(known?.address, pumpBySake, knownLinked, device.address, bySake)
    }

    /**
     * A device did something only the pump does ([bySake] - wrote to SAKE, else read our device information): from now
     * on it is the pump. Returns whether [device] is the pump (false - another device already is and stays).
     */
    private fun pumpSignal(device: BluetoothDevice, what: String, bySake: Boolean): Boolean {
        val known = pumpDevice
        val knownLinked = known != null && known.address != device.address && isLinked(known)
        when (PumpLinkRules.claim(known?.address, pumpBySake, knownLinked, device.address, bySake)) {
            PumpLinkRules.Claim.SAME -> {
                if (bySake) pumpBySake = true
                identifiedAddresses += device.address
                return true
            }
            PumpLinkRules.Claim.IGNORE -> {
                journal("${mac(device.address)} $what - ignored, ${mac(known?.address)} is the pump")
                return false
            }
            PumpLinkRules.Claim.TAKE -> if (known != null && knownLinked) {
                journal("${mac(device.address)} $what - it is the pump, not ${mac(known.address)} (that one only read the device information)")
            }
        }
        pumpDevice = device
        pumpBySake = bySake
        identifiedAddresses += device.address
        pairingShownAddress = null
        main.removeCallbacks(advertiseAgainLater)
        store.pumpAddress = device.address
        journal("pump identified: ${mac(device.address)} ($what), bond state ${bondName(device.bondState)}")
        // the official app stops advertising once the pump is connected
        stopAdvertising()
        // a device that only read our device information and never starts SAKE must not keep advertising off;
        // while pairing a person confirms on both devices first: 2 min
        armHandshakeDeadline(PumpLinkRules.identifyToSakeMs(pairMode))
        if (state.stage in ADVERTISE_STAGES || state.stage == PumpBtStage.LOST) {
            setStage(if (device.bondState == BluetoothDevice.BOND_BONDING) PumpBtStage.PAIRING else PumpBtStage.PUMP_CONNECTED)
        }
        return true
    }

    private fun handleSake(value: ByteArray) {
        if (sake == null || sake?.isComplete == true) sake = SakeServer(keyDatabase)
        val server = sake ?: return
        val before = server.stage
        try {
            val reply = server.handle(value)
            if (reply != null) {
                journal("SAKE: stage $before → ${server.stage}, received ${value.size} bytes, sending ${reply.size} bytes")
                send(reply)
            } else if (server.isComplete) {
                journal("SAKE: stage $before → ${server.stage}, received ${value.size} bytes - handshake complete")
                handshakeDone(server)
            }
        } catch (e: Exception) {
            journal("SAKE error at stage $before: ${e.javaClass.simpleName}")
            sake = null
            fail("handshake failed at stage $before (${e.javaClass.simpleName})")
        }
    }

    private fun handshakeDone(server: SakeServer) {
        main.removeCallbacks(handshakeDeadline)
        gate.crypt = server.crypt
        decryptFailures = 0
        recoverFailures = 0
        store.paired = true
        pumpDevice?.let { store.pumpAddress = it.address }
        if (pairMode) {
            pairMode = false
            update { it.copy(pairMode = false) }
        }
        // variant B: the reconnect advertisement (FE81) carries no name, give the phone's name back right away
        PumpBtAdapterName.restore(context, store, nameJournal)
        val lostAt = state.lostAtMs
        if (lostAt != null) {
            val after = System.currentTimeMillis() - lostAt
            journal("pump back after ${after / 60_000} min ${(after / 1000) % 60} s (handshake complete)")
            update { it.copy(lostAtMs = null, returns = PumpBtProgress.addReturn(it.returns, PumpBtReturn(lostAt, after))) }
        }
        setStage(PumpBtStage.READING)
        attachAttempts = 0
        connectAsGattClient()
    }

    /** [PumpLinkRules.HANDSHAKE_TIMEOUT_MS] from now: at pump identification and again at its SAKE subscription. */
    private fun armHandshakeDeadline(ms: Long = PumpLinkRules.HANDSHAKE_TIMEOUT_MS) {
        handshakeExtensions = 0
        main.removeCallbacks(handshakeDeadline)
        main.postDelayed(handshakeDeadline, ms)
    }

    /**
     * No SAKE subscription after the identification, or no complete handshake after the subscription, within the time
     * limit: drop and advertise. While a pairing is running it waits longer, at most [PumpLinkRules.HANDSHAKE_EXTENSIONS]
     * times.
     */
    private fun handshakeOverTime() {
        if (!running || gate.crypt != null) return
        val d = pumpDevice ?: return
        val bonding = try {
            d.bondState == BluetoothDevice.BOND_BONDING
        } catch (_: Exception) {
            false
        }
        if (PumpLinkRules.handshakeExtend(bonding, handshakeExtensions)) {
            // the pairing confirmation on the phone may take longer than the handshake itself
            handshakeExtensions++
            journal("handshake: pairing still running, waiting ${PumpLinkRules.HANDSHAKE_TIMEOUT_MS / 1000} s more")
            main.postDelayed(handshakeDeadline, PumpLinkRules.HANDSHAKE_TIMEOUT_MS)
            return
        }
        fail(if (sake == null) "no SAKE handshake started within the time limit" else "handshake not complete within the time limit")
    }

    // MARK: - Central side (GATT client on the pump)

    /** The pump is connected to our GATT server right now. */
    private fun isLinked(device: BluetoothDevice): Boolean = try {
        manager.getConnectionState(device, BluetoothProfile.GATT_SERVER) == BluetoothProfile.STATE_CONNECTED
    } catch (_: Exception) {
        false
    }

    private fun connectAsGattClient() {
        if (!running) return
        val device = pumpDevice
        attachAttempts++
        // checked right before connectGatt: with autoConnect=false Android would otherwise open a direct connection
        val linked = device != null && isLinked(device)
        if (device != null) {
            // the system's answer decides; the server callback's last state is logged next to it in case they differ
            journal(
                "GATT client: pump ${mac(device.address)} on our server - system: " + (if (linked) "connected" else "not connected") +
                    ", last server callback: " + (serverStates[device.address]?.let { connName(it) } ?: "none"),
            )
        }
        val decision = PumpLinkRules.clientAttach(device != null, linked)
        if (decision != PumpLinkRules.Attach.ALLOWED || device == null) {
            journal("GATT client: attach refused - ${decision.text}")
            if (device != null && attachAttempts < MAX_ATTACH) {
                main.postDelayed({ if (gatt == null && running) connectAsGattClient() }, 2000)
            } else if (device != null) {
                fail("the pump is not reachable as GATT server ($attachAttempts attempts)")
            }
            return
        }
        val existing = gatt
        if (existing != null && existing.device.address == device.address) {
            // a time limit on this path too: a discovery that never answers must not hang the link
            main.removeCallbacks(attachTimeout)
            main.postDelayed(attachTimeout, ATTACH_TIMEOUT_MS)
            startDiscovery(existing)
            return
        }
        closeClient(disconnect = false)
        journal("GATT client: attaching to the pump's existing link (autoConnect=false, attempt $attachAttempts)")
        connectGattAtMs = SystemClock.elapsedRealtime()
        gatt = try {
            device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            journal("GATT client: connectGatt failed (${e.javaClass.simpleName})")
            null
        }
        if (gatt != null) main.postDelayed(attachTimeout, ATTACH_TIMEOUT_MS) else attachAgainLater()
    }

    /** After a closed client: attach again in 2 s if the pump is still on our server; after [MAX_ATTACH] - drop the link. */
    private fun attachAgainLater() {
        val device = pumpDevice ?: return
        if (!running || gate.crypt == null) return
        if (!isLinked(device)) {
            journal("GATT client: not attaching again - the pump left our GATT server")
            pumpDisconnected(-1)
            return
        }
        if (attachAttempts >= MAX_ATTACH) {
            fail("GATT client closed $attachAttempts times on this link")
            return
        }
        main.postDelayed({ if (gatt == null && running) connectAsGattClient() }, 2000)
    }

    private fun startDiscovery(g: BluetoothGatt) {
        resetClientState()
        val started = try {
            g.discoverServices()
        } catch (_: Exception) {
            false
        }
        journal(if (started) "GATT client: discovering the pump's services" else "GATT client: service discovery not started")
    }

    private fun closeClient(disconnect: Boolean) {
        main.removeCallbacks(attachTimeout)
        main.removeCallbacks(gattTimeout)
        gatt?.let {
            if (disconnect) {
                try {
                    it.disconnect()
                } catch (_: Exception) {
                }
            }
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
        gattQueue.clear()
        gattBusy = false
        gattRetries = 0
        gattStaleSinceMs = null
        main.removeCallbacks(gattRetry)
    }

    /** The client link's state ends; a cycle still running is handed out as it is (history: failed). */
    private fun resetClientState() {
        if (cycle != null) finishCycle(lost = true)
        characteristics.clear()
        gate.serialVerified = false
        linkSerialHash = null
        operations.clear()
        current = null
        currentStartedAt = null
        refreshPending = false
        historyPending = false
        main.removeCallbacks(operationTimeout)
        main.removeCallbacks(quietDone)
        featuresRead = false
        gattQueue.clear()
        gattBusy = false
        gattRetries = 0
        gattStaleSinceMs = null
        main.removeCallbacks(gattTimeout)
        main.removeCallbacks(gattRetry)
    }

    private fun key(service: String, characteristic: String) =
        "${PumpUuid.normalize(service)}|${PumpUuid.normalize(characteristic)}"

    private fun characteristic(service: String, uuid: String): BluetoothGattCharacteristic? = characteristics[key(service, uuid)]

    private fun allDiscovered(g: BluetoothGatt) {
        main.removeCallbacks(attachTimeout)
        journal("GATT client: services discovered after ${SystemClock.elapsedRealtime() - connectGattAtMs} ms (from connectGatt)")
        for (s in g.services) {
            detail { "pump service ${PumpUuid.short(s.uuid)}: " + s.characteristics.joinToString(" ") { PumpUuid.short(it.uuid) } }
            for (c in s.characteristics) characteristics[key(s.uuid.toString(), c.uuid.toString())] = c
        }
        journal("${characteristics.size} characteristics of the pump found")
        setStage(PumpBtStage.READING)
        // Before anything else, read the serial number (unencrypted DIS); writes wait for it.
        if (characteristic(PumpUuid.DEVICE_INFO_SERVICE, PumpUuid.SERIAL_NUMBER) == null) {
            fail("the pump's serial number characteristic was not found")
            return
        }
        gate.read(PumpUuid.DEVICE_INFO_SERVICE, PumpUuid.SERIAL_NUMBER)
        gate.read(PumpUuid.DEVICE_INFO_SERVICE, PumpUuid.FIRMWARE_REVISION)
        gate.read(PumpUuid.DEVICE_INFO_SERVICE, PumpUuid.MODEL_NUMBER)
    }

    private fun serialReceived(serial: String) {
        if (serial.isEmpty()) {
            fail("the pump's serial number is empty")
            return
        }
        updateValues { it.copy(serial = serial) }
        linkSerialHash = PumpIdentity.hash(serial)
        journal("pump serial number read")
        detail { "pump serial number: $serial" }
        gate.serialVerified = true
        // the link works: closes before this point do not count against later ones
        attachAttempts = 0
        for ((service, uuid) in listOf(
            PumpUuid.IDD_SERVICE to PumpUuid.IDD_STATUS_READER_CP,
            PumpUuid.IDD_SERVICE to PumpUuid.IDD_STATUS_CHANGED,
            PumpUuid.IDD_SERVICE to PumpUuid.IDD_HISTORY_DATA,
            PumpUuid.IDD_SERVICE to PumpUuid.RECORD_ACCESS_CP,
            PumpUuid.IDD_SERVICE to PumpUuid.GST_BATTERY_LEVEL,
            PumpUuid.BATTERY_SERVICE to PumpUuid.BATTERY_LEVEL,
        )) {
            if (characteristic(service, uuid) != null) gate.subscribe(service, uuid)
            else journal("no ${PumpUuid.normalize(uuid)} in service ${PumpUuid.normalize(service)}: not subscribed")
        }
        scheduleRefresh(force = true, withHistory = true)
    }

    // MARK: - GATT command queue (filled only through the outlet, i.e. through the gate)

    private fun enqueueFor(service: String, uuid: String, make: (BluetoothGattCharacteristic) -> GattCmd): Boolean {
        val c = characteristic(service, uuid) ?: run {
            journal("characteristic ${PumpUuid.normalize(uuid)} of service ${PumpUuid.normalize(service)} not found on the pump")
            return false
        }
        if (gatt == null) return false
        gattQueue.addLast(make(c))
        runGattQueue()
        return true
    }

    /**
     * Hands the head of the queue to Android. Not started (Android still busy): the command stays at the head and is
     * tried again in [PumpLinkRules.GATT_RETRY_MS] - at most [PumpLinkRules.GATT_RETRIES] times, or up to
     * [PumpLinkRules.GATT_STALE_MAX_MS] after a command timed out - then dropped with a journal line, never silently.
     */
    private fun runGattQueue() {
        main.removeCallbacks(gattRetry)
        if (gattBusy) return
        val g = gatt ?: return gattQueue.clear()
        while (gattQueue.isNotEmpty()) {
            val cmd = gattQueue.first()
            val result = try {
                if (execute(g, cmd)) Start.STARTED else Start.BUSY
            } catch (e: Exception) {
                journal("$cmd failed (${e.javaClass.simpleName}" + (e.message?.let { ": $it" } ?: "") + ")")
                Start.IMPOSSIBLE
            }
            when (result) {
                Start.STARTED -> {
                    gattQueue.removeFirst()
                    gattRetries = 0
                    gattStaleSinceMs = null
                    gattBusy = true
                    main.postDelayed(gattTimeout, GATT_TIMEOUT_MS)
                    return
                }
                Start.BUSY -> {
                    val sinceTimeout = gattStaleSinceMs?.let { SystemClock.elapsedRealtime() - it }
                    if (PumpLinkRules.gattRetry(gattRetries, sinceTimeout)) {
                        gattRetries++
                        if (sinceTimeout == null || gattRetries == 1) {
                            journal("$cmd not started (GATT busy), retry in ${PumpLinkRules.GATT_RETRY_MS} ms")
                        }
                        main.postDelayed(gattRetry, PumpLinkRules.GATT_RETRY_MS)
                        return
                    }
                    journal("$cmd dropped")
                    gattQueue.removeFirst()
                    gattRetries = 0
                    gattStaleSinceMs = null
                }
                Start.IMPOSSIBLE -> {
                    gattQueue.removeFirst()
                    gattRetries = 0
                }
            }
        }
    }

    /** True - started; false - Android did not take it (busy). Throws when it cannot run at all. */
    private fun execute(g: BluetoothGatt, cmd: GattCmd): Boolean = when (cmd) {
        is GattCmd.Read -> g.readCharacteristic(cmd.c)
        is GattCmd.Write -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(cmd.c, cmd.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cmd.c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            cmd.c.value = cmd.value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(cmd.c)
        }
        is GattCmd.Subscribe -> {
            val cccd = cmd.c.getDescriptor(PumpUuid.parse(PumpUuid.CCCD))
                ?: throw IllegalStateException("no CCCD on ${PumpUuid.short(cmd.c.uuid)}")
            g.setCharacteristicNotification(cmd.c, true)
            run {
                val value = if (cmd.c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }
    }

    private fun gattDone() {
        main.removeCallbacks(gattTimeout)
        gattBusy = false
        runGattQueue()
    }

    // MARK: - Read cycle

    private fun scheduleRefresh(force: Boolean = false, withHistory: Boolean = false) {
        if (!gate.serialVerified) return
        // Detect a stuck operation by time as well (timers may be delayed while the phone dozes).
        currentStartedAt?.let {
            if (Duration.between(it, Instant.now()).seconds > 60) {
                journal("reading stuck (${current ?: "?"}), resetting")
                if (current?.isHistory == true) finishHistory(HistoryEnding.TIMED_OUT)
                // the cycle is cut here: nothing more of it is read
                main.removeCallbacks(operationTimeout)
                operations.clear()
                current = null
                currentStartedAt = null
                if (cycle != null) finishCycle(lost = false)
            }
        }
        if (current != null || operations.isNotEmpty()) {
            refreshPending = true
            if (withHistory) historyPending = true
            return
        }
        if (!force && Duration.between(lastRefresh, Instant.now()).seconds < 50) return
        val history = withHistory || historyPending || Duration.between(lastHistorySync, Instant.now()).toMinutes() >= 4
        refreshPending = false
        historyPending = false
        lastRefresh = Instant.now()
        val c = CycleData()
        if (history) c.history = newHistoryReading()
        cycle = c
        operations.addAll(PumpLinkRules.cycleOps(timeInRange = !timeInRangeUnsupported, features = !featuresRead, history = history))
        featuresRead = true
        wake.begin()
        if (state.stage != PumpBtStage.DATA_READ) setStage(PumpBtStage.READING)
        nextOperation()
    }

    private fun newHistoryReading(): HistoryReading {
        lastHistorySync = Instant.now()
        val from = try {
            historyFrom()
        } catch (e: Exception) {
            journal("history: start number not known (${e.javaClass.simpleName}), reading from the oldest record")
            null
        }
        journal("history: reading from " + (from?.let { "#$it" } ?: "the oldest record"))
        return HistoryReading(from)
    }

    /** A history read right away (e.g. a pump alert changed): added to the running cycle, or a cycle of its own. */
    private fun requestHistoryNow() {
        if (!gate.serialVerified) return
        val c = cycle
        if (c == null) {
            scheduleRefresh(force = true, withHistory = true)
            return
        }
        if (c.history != null) return
        c.history = newHistoryReading()
        operations.addLast(LinkOp.HistoryFirst)
        operations.addLast(LinkOp.HistoryLast)
        if (current == null) nextOperation()
    }

    /** "Last record" answered (or failed): queue the pages from the start to the newest record, or end the read. */
    private fun planHistoryPages(h: HistoryReading) {
        if (h.firstAnswer != PumpLinkRules.EndAnswer.SUCCESS) journal("history: no usable answer to \"first record\", the oldest is not known")
        val plan = PumpLinkRules.historyPlan(h.from, h.firstAnswer, h.firstSeen, h.lastAnswer, h.lastSeen)
        h.oldest = plan.oldest
        h.newest = plan.newest
        if (h.firstAnswer == PumpLinkRules.EndAnswer.SUCCESS && h.firstSeen != null && plan.oldest == null && plan.newest != null) {
            journal("history: \"first record\" #${h.firstSeen} is above the newest #${plan.newest}: not used")
        }
        when (plan) {
            is PumpLinkRules.HistoryPlan.Failed -> {
                journal("history: no usable answer to \"last record\"")
                finishHistory(HistoryEnding.FAILED)
            }
            is PumpLinkRules.HistoryPlan.NothingToRead -> {
                journal(if (plan.newest == null) "history: the pump has no records" else "history: nothing new (newest #${plan.newest})")
                finishHistory(HistoryEnding.DONE)
            }
            is PumpLinkRules.HistoryPlan.Read -> {
                if (plan.fallback) {
                    journal("history: neither a start number nor the oldest record known: reading the last ${PumpLinkRules.HISTORY_FALLBACK_SPAN} numbers")
                } else if (h.from != null && plan.oldest != null && h.from < plan.oldest) {
                    journal("history: #${h.from} is older than the oldest record on the pump (#${plan.oldest}), reading from there")
                }
                h.requestedFrom = plan.from
                h.requestedTo = plan.to
                h.fallback = plan.fallback
                val pages = PumpLinkRules.historyPages(plan.from, plan.to)
                journal("history: reading ${plan.to - plan.from + 1} records (#${plan.from}…#${plan.to}) in ${pages.size} page(s)")
                operations.addAll(0, pages)
            }
        }
    }

    /**
     * The running history operation ended without a usable answer ([ending] for a page or "last record"). "First
     * record" only leaves the oldest record unknown: "last record" still follows (its support on the 740G is not
     * verified, the history must not depend on it).
     */
    private fun historyOpFailed(op: LinkOp, ending: HistoryEnding) {
        val h = cycle?.history
        if (op === LinkOp.HistoryQuiet) return
        if (op === LinkOp.HistoryFirst) {
            h?.firstAnswer = PumpLinkRules.EndAnswer.FAILED
            journal("history: \"first record\" not answered, going on without it")
            if (ending == HistoryEnding.TIMED_OUT) {
                // a late answer to it must not be taken for "last record": a pause first, dropping what comes
                operations.addFirst(LinkOp.HistoryQuiet)
            }
            return
        }
        if (op is LinkOp.HistoryRange && ending == HistoryEnding.FAILED && h != null &&
            PumpLinkRules.fallbackPageErrorIsEmpty(h.fallback, h.received.isNotEmpty())
        ) {
            journal("history: page $op failed before any record came (fallback read): taken as \"no records\"")
            if (operations.none { it is LinkOp.HistoryRange }) finishHistory(HistoryEnding.DONE)
            return
        }
        finishHistory(ending)
    }

    private val quietDone = Runnable { finish { it === LinkOp.HistoryQuiet } }

    /** Ends the running history read once ([ending]) and drops its operations still queued. */
    private fun finishHistory(ending: HistoryEnding) {
        val h = cycle?.history ?: return
        if (h.ending != null) return
        h.ending = ending
        operations.removeAll { it.isHistory }
        val result = historyResult(h)
        if (result.status == HistoryReadStatus.COMPLETE || result.status == HistoryReadStatus.GAP) {
            lastHistoryAtMs = System.currentTimeMillis()
        }
        // counts by type from the headers only; the records themselves only in the detailed journal
        journal(
            "history: ${result.status.name.lowercase()}, ${h.received.size} records" +
                (h.requestedFrom?.let { " of #$it…#${h.requestedTo}" } ?: "") +
                (result.contiguousTo?.let { ", all up to #$it" } ?: "") +
                (if (result.missing.isNotEmpty()) ", missing ${result.missing.sumOf { it.last - it.first + 1 }}" else "") +
                (if (h.outside > 0) ", ${h.outside} outside the range" else "") +
                (if (h.dropped > 0) ", ${h.dropped} late answers dropped" else "") +
                (if (h.fallback) ", fallback read from #${result.requestedFrom ?: h.requestedFrom}" else "") +
                (if (h.received.isNotEmpty()) "; types: " + PumpLinkRules.typeCounts(h.received.values) else ""),
        )
        if (verbose()) {
            for (r in h.received.values) {
                val parsed = r.parse()
                val (kind, value) = PumpBtHistory.describe(parsed.event)
                journal("history #${r.sequence} type 0x%04x +%d s: %s %s".format(r.type, r.offsetSec, kind, value).trimEnd())
            }
        }
    }

    private fun historyResult(h: HistoryReading): HistoryRead {
        val ending = h.ending ?: HistoryEnding.FAILED
        val from = h.requestedFrom
        val to = h.requestedTo
        return if (h.fallback && from != null && to != null) {
            PumpLinkRules.fallbackOutcome(ending, from, to, h.newest, h.received.keys)
        } else {
            PumpLinkRules.historyOutcome(ending, from, to, h.oldest, h.newest, h.received.keys)
        }
    }

    /** The cycle ends (queue drained, or the link [lost]): hand it out as a [PumpCycle]. */
    private fun finishCycle(lost: Boolean) {
        val c = cycle ?: return
        // a history read still open at the end of a cycle did not finish: failed, never "complete"
        c.history?.let { if (it.ending == null) finishHistory(HistoryEnding.FAILED) }
        cycle = null
        val wakeOfThisCycle = wake.generation
        val now = System.currentTimeMillis()
        val h = c.history
        val records = h?.received?.values?.toList() ?: emptyList()
        val result = PumpCycle(
            readAtMs = now,
            serialHash = linkSerialHash,
            therapyState = c.therapyState,
            reservoirUnits = c.reservoirUnits,
            iobUnits = c.iobUnits,
            basalRate = c.basalRate,
            basalRead = c.basalRead,
            tempBasalRate = c.tempBasalRate,
            tempBasalType = c.tempBasalType,
            tempBasalMinutesLeft = c.tempBasalMinutesLeft,
            batteryPercent = c.batteryPercent,
            records = records,
            history = h?.let(::historyResult) ?: HistoryRead(HistoryReadStatus.NONE),
        )
        // the watchdog's "no data" goes by this: only a cycle that really read something moves it
        val readSomething = c.gotData || result.history.status == HistoryReadStatus.COMPLETE || result.history.status == HistoryReadStatus.GAP
        if (readSomething) lastCycleAtMs = now
        journal(
            "read cycle " + (if (lost) "ended by the lost link" else "done") + (if (readSomething) "" else ", nothing read") +
                if (linkSerialHash == null) " - no serial number read on this link" else "",
        )
        try {
            // the cycle goes to the history memory first; the window's lines must not stop it
            try {
                onCycle(result)
            } catch (e: Exception) {
                journal("cycle not taken (${e.javaClass.simpleName})")
            }
            try {
                if (records.isNotEmpty()) {
                    val (lines, reference) = PumpBtHistory.lines(records.map { it.parse() }, store.historyReference)
                    store.historyReference = reference
                    updateValues { it.copy(history = PumpBtHistory.merge(it.history, lines)) }
                }
                updateValues { it.copy(readAtMs = now) }
            } catch (e: Exception) {
                journal("window lines of the cycle not made (${e.javaClass.simpleName})")
            }
        } finally {
            // not the hold of a cycle that onCycle may have started meanwhile
            wake.release(wakeOfThisCycle)
        }
        if (!lost) setStage(PumpLinkRules.cycleDoneStage(gate.serialVerified && linkSerialHash != null))
    }

    private fun nextOperation() {
        main.removeCallbacks(operationTimeout)
        current = null
        currentStartedAt = null
        while (operations.isNotEmpty()) {
            val op = operations.removeFirst()
            if (start(op)) {
                current = op
                currentStartedAt = Instant.now()
                main.postDelayed(operationTimeout, if (op is LinkOp.HistoryRange) 45_000 else 8_000)
                return
            }
            if (op.isHistory) historyOpFailed(op, HistoryEnding.FAILED)
        }
        if (cycle != null) finishCycle(lost = false)
        // Run a refresh that was requested while busy.
        if (refreshPending) scheduleRefresh()
    }

    /** Every write of a cycle is [LinkOp.write] - checked by the gate against the read-only policy. */
    private fun start(op: LinkOp): Boolean {
        if (op is LinkOp.Read) return gate.read(op.service, op.characteristic)
        if (op === LinkOp.HistoryQuiet) {
            main.removeCallbacks(quietDone)
            main.postDelayed(quietDone, PumpLinkRules.FIRST_RECORD_QUIET_MS)
            return true
        }
        val w = op.write() ?: return false
        return gate.write(w.service, w.characteristic, w.plaintext)
    }

    private fun finish(matches: (LinkOp) -> Boolean) {
        val op = current ?: return
        if (matches(op)) nextOperation()
    }

    private fun isReadOf(op: LinkOp, uuid: String) = op is LinkOp.Read && PumpUuid.normalize(op.characteristic) == uuid

    /** The running operation failed (write or read error): a history read ends as failed. */
    private fun currentFailed() {
        val op = current ?: return
        if (op.isHistory) historyOpFailed(op, HistoryEnding.FAILED)
        nextOperation()
    }

    // MARK: - Incoming values

    private fun handleValue(service: String, uuid: String, data: ByteArray, read: Boolean) {
        var plain = data
        // The pump signals new data every ~5 min with a notification on GST Battery Level followed by an indication on
        // IDD Status Changed. Any of these starts a refresh, even if the payload cannot be decrypted.
        val isTrigger = uuid == IDD_STATUS_CHANGED || uuid == GST_BATTERY
        if (uuid in PumpGate.ENCRYPTED) {
            val cr = gate.crypt ?: run {
                journal("no session to decrypt $uuid")
                return
            }
            try {
                plain = cr.decrypt(data)
                decryptFailures = 0
            } catch (e: Exception) {
                decryptFailures++
                journal("decrypting $uuid failed (${decryptFailures}x): ${e.javaClass.simpleName}; ciphertext ${data.size} bytes")
                if (decryptFailures >= 5) {
                    gate.crypt = null
                    fail("session lost after $decryptFailures decryption failures")
                    return
                }
                // only the request whose answer this was ends; a history record that cannot be decrypted is a hole of
                // this read, not the end of the request
                finish { op -> (uuid == IDD_STATUS_READER_CP && op is LinkOp.StatusReader) || isReadOf(op, uuid) }
                if (isTrigger) scheduleRefresh()
                return
            }
        }
        detail { "pump ${if (read) "read" else "sent"} $uuid: ${plain.toHex()}" }
        try {
            dispatch(service, uuid, plain)
        } catch (e: Exception) {
            journal("value of $uuid not handled (${e.javaClass.simpleName})")
        } finally {
            if (isTrigger) scheduleRefresh()
        }
    }

    private fun dispatch(service: String, uuid: String, plain: ByteArray) {
        when (uuid) {
            PumpUuid.SERIAL_NUMBER -> serialReceived(PumpLinkRules.pumpString(plain))
            PumpUuid.FIRMWARE_REVISION -> PumpLinkRules.pumpString(plain).let { fw ->
                journal("pump firmware: $fw")
                updateValues { it.copy(firmware = fw) }
            }
            PumpUuid.MODEL_NUMBER -> PumpLinkRules.pumpString(plain).let { model ->
                journal("pump model: $model")
                updateValues { it.copy(model = model) }
            }

            PumpUuid.RECORD_ACCESS_CP -> if (service == IDD_SERVICE) racpAnswer(plain) else journal("RACP answer of service $service ignored")

            IDD_HISTORY_DATA -> historyRecord(plain)

            IDD_STATUS_READER_CP -> handleStatusReader(plain)

            IDD_STATUS_CHANGED -> {
                val flags = StatusChangedFlags.parse(plain)
                journal("status changed flags: " + (flags?.raw?.let { "0x%04x".format(it) } ?: "?"))
                if (flags != null && StatusChangedFlags.ANNUNCIATION in flags) requestHistoryNow()
            }

            IDD_STATUS -> {
                val s = runCatching { PumpStatus.parse(plain) }.getOrNull()
                if (s != null) {
                    journal("IDD status: therapy 0x%02x, operational 0x%02x".format(s.therapyControlState, s.operationalState))
                    detail { "IDD status: reservoir ${s.reservoirUnits} U, attached ${s.reservoirAttached}, sensor 0x%02x/0x%02x".format(s.sensorConnectivity, s.sensorMessageState) }
                    cycle?.let {
                        it.therapyState = s.therapyControlState
                        it.reservoirUnits = s.reservoirUnits
                        it.gotData = true
                    }
                    updateValues { it.copy(reservoirUnits = s.reservoirUnits) }
                } else {
                    journal("IDD status not readable")
                }
                finish { isReadOf(it, IDD_STATUS) }
            }

            IDD_FEATURES -> {
                journal("IDD features: ${plain.size} bytes")
                finish { isReadOf(it, IDD_FEATURES) }
            }

            BATTERY_LEVEL -> {
                plain.firstOrNull()?.let { b ->
                    val pct = b.toInt() and 0xFF
                    journal("pump battery: $pct %")
                    cycle?.let {
                        it.batteryPercent = pct
                        it.gotData = true
                    }
                    updateValues { it.copy(batteryPercent = pct) }
                }
                finish { isReadOf(it, BATTERY_LEVEL) }
            }

            GST_BATTERY -> plain.firstOrNull()?.let { journal("GST battery level: ${it.toInt() and 0xFF} %") }

            else -> finish { isReadOf(it, uuid) }
        }
    }

    /** A history record notification: header read, body kept raw; for "first"/"last" only its number counts. */
    private fun historyRecord(plain: ByteArray) {
        val record = RawHistoryRecord.fromPlain(plain) ?: run {
            journal("history record shorter than its header (${plain.size} bytes)")
            return
        }
        val h = cycle?.history
        val op = current
        if (h == null || h.ending != null || op == null || !op.isHistory) {
            journal("history record #${record.sequence} outside a history read: ignored")
            return
        }
        when (op) {
            // a late record of an earlier request may arrive here: the smallest / largest number wins, and only a
            // success answer to this request makes it count
            LinkOp.HistoryQuiet -> h.dropped++
            LinkOp.HistoryFirst -> h.firstSeen = minOf(h.firstSeen ?: record.sequence, record.sequence)
            LinkOp.HistoryLast -> h.lastSeen = maxOf(h.lastSeen ?: record.sequence, record.sequence)
            is LinkOp.HistoryRange -> {
                val from = h.requestedFrom
                val to = h.requestedTo
                if (from != null && to != null && record.sequence in from..to) h.received[record.sequence] = record else h.outside++
            }
            else -> {}
        }
    }

    /** IDD RACP answer `0F 0F <request opcode> <code>`: ends the running history operation. */
    private fun racpAnswer(plain: ByteArray) {
        val op = current
        val h = cycle?.history
        if (op == null || !op.isHistory || h == null || h.ending != null) {
            journal("history RACP answer without a running history request: ignored")
            return
        }
        if (op === LinkOp.HistoryQuiet) {
            h.dropped++
            journal("history: late RACP answer in the pause after \"first record\": dropped")
            return
        }
        // the request opcode inside the answer must be the running request's
        val request = op.write()?.plaintext?.firstOrNull()?.toInt()?.and(0xFF)
        val code = PumpLinkRules.racpCode(plain, request) ?: run {
            journal("history RACP answer for another request: ignored (${plain.toHex()})")
            return
        }
        val ok = code == PumpLinkRules.RACP_SUCCESS
        val none = code == PumpLinkRules.RACP_NO_RECORDS
        if (!ok && !none) journal("history RACP answer is not success: ${plain.toHex()}")
        val answer = when {
            ok -> PumpLinkRules.EndAnswer.SUCCESS
            none -> PumpLinkRules.EndAnswer.NO_RECORDS
            else -> PumpLinkRules.EndAnswer.FAILED
        }
        when (op) {
            LinkOp.HistoryFirst -> h.firstAnswer = answer
            LinkOp.HistoryLast -> {
                h.lastAnswer = answer
                planHistoryPages(h)
            }
            is LinkOp.HistoryRange -> {
                wake.extend()
                if (!ok && !none) {
                    // fallback read below the oldest record: an error before any record came is "no records"
                    historyOpFailed(op, HistoryEnding.FAILED)
                } else if (operations.none { it is LinkOp.HistoryRange }) {
                    finishHistory(HistoryEnding.DONE)
                }
            }
            else -> {}
        }
        finish { it === op }
    }

    private fun handleStatusReader(plain: ByteArray) {
        val forRequest = PumpLinkRules.statusReaderAnswerFor(plain)
        PumpLinkRules.statusReaderUnsupported(plain)?.let { op ->
            journal("status reader: $op not supported by the pump")
            if (op == StatusReaderOpcode.GET_TIME_IN_RANGE && !timeInRangeUnsupported) {
                timeInRangeUnsupported = true
                journal("time in range: not asked again while the app runs")
            }
        }
        val opcode = statusReaderResponseOpcode(plain)
        try {
            when (opcode) {
                StatusReaderOpcode.GET_INSULIN_ON_BOARD.responseOpcode -> {
                    val v = InsulinOnBoard.parse(plain)
                    journal("active insulin read")
                    detail { "active insulin: ${v.units} U, remaining ${v.remainingDurationMinutes ?: "-"} min" }
                    cycle?.iobUnits = v.units
                    updateValues { it.copy(iobUnits = v.units) }
                }
                StatusReaderOpcode.GET_THERAPY_ALGORITHM_STATES.responseOpcode -> {
                    val v = TherapyAlgorithmStates.parse(plain)
                    journal("therapy algorithm: ${v.modeDescription} (shield ${v.shieldState ?: "-"}, readiness ${v.readinessState ?: "-"})")
                }
                StatusReaderOpcode.GET_ACTIVE_BASAL_RATE_DELIVERY.responseOpcode -> {
                    val v = ActiveBasalRate.parse(plain)
                    journal("active basal read, temp basal " + if (v.tempBasalValue != null) "running" else "none")
                    detail {
                        val unit = when (v.tempBasalType) {
                            ActiveBasalRate.TBR_ABSOLUTE -> "U/h"
                            ActiveBasalRate.TBR_RELATIVE -> "%"
                            else -> "(type ${v.tempBasalType ?: "-"})"
                        }
                        "active basal: profile ${v.profileNumber}, ${v.unitsPerHour} U/h, temp basal " +
                            (v.tempBasalValue?.let { "$it $unit, ${v.tempBasalRemainingMinutes ?: "-"} min left" } ?: "none")
                    }
                    cycle?.let {
                        it.basalRate = v.unitsPerHour
                        it.basalRead = true
                        it.tempBasalRate = v.tempBasalValue
                        it.tempBasalType = v.tempBasalType
                        it.tempBasalMinutesLeft = v.tempBasalRemainingMinutes
                    }
                    updateValues { withActiveBasal(it, v) }
                }
                StatusReaderOpcode.GET_TIME_IN_RANGE.responseOpcode -> {
                    val v = TimeInRange.parse(plain)
                    journal("time in range read")
                    detail { "time in range: ${v.inRangePercent} %, above ${v.abovePercent} %, below ${v.belowPercent} %, not enough data ${v.notEnoughData}" }
                }
                PumpLinkRules.STATUS_READER_RESPONSE_CODE -> {}
                else -> journal("status reader answer 0x%04x not expected".format(opcode ?: -1))
            }
            if (opcode != PumpLinkRules.STATUS_READER_RESPONSE_CODE && forRequest != null) cycle?.gotData = true
        } catch (e: Exception) {
            journal("status reader answer not readable (${e.javaClass.simpleName})")
        }
        // only the answer to the running request ends it: a late answer must not end the next one
        val op = current
        if (op is LinkOp.StatusReader && op.opcode.raw == forRequest) {
            nextOperation()
        } else if (forRequest != null) {
            journal("status reader answer for 0x%04x while waiting for ${op ?: "nothing"}: values kept, request goes on".format(forRequest))
        }
    }

    // MARK: - GATT server callback (binder threads → main)

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("service ${PumpUuid.short(service.uuid)} not added to the GATT server (status $status)")
                    return@post
                }
                journal("service ${PumpUuid.short(service.uuid)} added")
                addNextService()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val bond = try {
                bondName(device.bondState)
            } catch (_: Exception) {
                "unknown"
            }
            main.post {
                val address = device.address
                serverStates[address] = newState
                val pump = pumpDevice
                val isPump = address == pump?.address
                journal(
                    "GATT server: ${mac(address)} ${connName(newState)} (status $status, bond $bond)" +
                        if (isPump) " - the pump" else " - not counted as the pump",
                )
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    serverConnectedAt.getOrPut(address) { SystemClock.elapsedRealtime() }
                    return@post
                }
                if (newState != BluetoothProfile.STATE_DISCONNECTED) return@post
                val connectedFor = serverConnectedAt.remove(address)?.let { SystemClock.elapsedRealtime() - it }
                val identified = identifiedAddresses.remove(address) || isPump
                if (running && PumpLinkRules.pairingFailedByDrop(pairMode, identified, connectedFor)) {
                    markPairingFailed("${mac(address)} left $connectedFor ms after connecting, without counting as the pump")
                }
                // the pump may be reported under another address (random before the bond, identity after): when the
                // identified pump is gone from our server, it is lost whatever address this callback carries
                val pumpStillLinked = pump != null && !isPump && isLinked(pump)
                when (PumpLinkRules.serverDisconnect(running, pump != null, isPump, pumpStillLinked)) {
                    PumpLinkRules.Drop.PUMP_LOST -> pumpDisconnected(status)
                    PumpLinkRules.Drop.RECHECK -> if (pump != null) {
                        // a false "not connected" at this moment must not end a working session: ask again in 1 s
                        journal("another device left and the pump ${mac(pump.address)} does not look connected now: checking again in ${PumpLinkRules.RECHECK_MS} ms")
                        recheckFor = pump
                        recheckStatus = status
                        main.removeCallbacks(pumpRecheck)
                        main.postDelayed(pumpRecheck, PumpLinkRules.RECHECK_MS)
                    }
                    PumpLinkRules.Drop.ADVERTISE_AGAIN -> {
                        val shownLeft = address == pairingShownAddress
                        journal("connection ended before the pump was identified: advertising again in ${PumpLinkRules.ADVERTISE_AGAIN_DELAY_MS} ms" + if (shownLeft) " (the pairing device left)" else "")
                        advertiseAgain(pairingFailed = false, shownPairingDeviceLeft = shownLeft)
                    }
                    PumpLinkRules.Drop.NOTHING -> {}
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val short = PumpUuid.short(characteristic.uuid)
            val event = PumpLinkRules.classifyRead(characteristic.service?.uuid)
            // another device than the pump gets nothing: only the (possible) pump reads our device information
            val value = if (PumpLinkRules.serves(event, claimNow(device, bySake = false))) deviceInfo[short] else null
            val s = server ?: return
            when {
                value == null -> s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                offset > value.size -> s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                else -> s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
            main.post {
                if (value == null) {
                    journal("${mac(device.address)} reads $short: refused")
                    return@post
                }
                if (!pumpSignal(device, "read device information $short", bySake = false)) return@post
                journal("pump reads device information $short (offset $offset)")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val event = PumpLinkRules.classifyWrite(characteristic.uuid)
            val served = PumpLinkRules.serves(event, claimNow(device, bySake = true))
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (served) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
            val copy = value?.copyOf()
            main.post {
                if (!served || copy == null) {
                    journal("${mac(device.address)} writes ${PumpUuid.short(characteristic.uuid)}: refused")
                    return@post
                }
                if (!pumpSignal(device, "wrote the SAKE port", bySake = true)) return@post
                if (!running) return@post
                handleSake(copy)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val event = PumpLinkRules.classifyDescriptorWrite(descriptor.uuid, descriptor.characteristic?.uuid)
            if (PumpLinkRules.serves(event, claimNow(device, bySake = false))) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0, 0))
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val event = PumpLinkRules.classifyDescriptorWrite(descriptor.uuid, descriptor.characteristic?.uuid)
            val served = PumpLinkRules.serves(event, claimNow(device, bySake = true))
            // answer first, the 20 zero bytes follow on the main thread (the pump loses them inside its own write)
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (served) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
            if (!served || value == null || value.size < 2) return
            val subscribed = value[0].toInt() and 0x03 != 0
            main.post {
                if (!pumpSignal(device, if (subscribed) "subscribed to SAKE" else "unsubscribed from SAKE", bySake = true)) return@post
                if (subscribed) sakeSubscribed(device) else journal("the pump ended its SAKE subscription")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                main.removeCallbacks(notifyWatchdog)
                if (status != BluetoothGatt.GATT_SUCCESS) journal("SAKE notification sent with status $status")
                notifyInFlight = false
                flushOutbox()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { journal("GATT server: MTU $mtu (${mac(device.address)})") }
        }
    }

    private fun sakeSubscribed(device: BluetoothDevice) {
        if (!running) return
        journal("the pump subscribed to SAKE: starting the handshake, sending 20 zero bytes")
        pumpDevice = device
        closeClient(disconnect = false)
        resetClientState()
        sake = null
        gate.crypt = null
        clearOutbox()
        setStage(PumpBtStage.HANDSHAKE)
        wake.begin()
        armHandshakeDeadline()
        send(ByteArray(SakeServer.MESSAGE_SIZE))
    }

    /** Everything of the pump's session ends on our side (the GATT server stays). */
    private fun forgetPump() {
        // the client may still be connecting: without disconnect() Android would start a direct connection itself
        closeClient(disconnect = true)
        resetClientState()
        clearOutbox()
        main.removeCallbacks(pumpRecheck)
        main.removeCallbacks(handshakeDeadline)
        recheckFor = null
        sake = null
        gate.crypt = null
        decryptFailures = 0
        pumpDevice = null
        pumpBySake = false
        pairingShownAddress = null
        wake.release()
    }

    /** We end the pump's link ourselves (failure, watchdog, stop): cancel it on our server and forget the session. */
    private fun dropPumpLink() {
        val d = pumpDevice
        if (d != null && isLinked(d)) {
            journal("dropping our side of the link to the pump")
            try {
                server?.cancelConnection(d)
            } catch (_: Exception) {
            }
        }
        forgetPump()
        if (running && d != null) update { it.copy(lostAtMs = it.lostAtMs ?: System.currentTimeMillis()) }
    }

    private fun pumpDisconnected(status: Int) {
        val now = System.currentTimeMillis()
        journal("the pump disconnected (status $status)")
        logcat("pump disconnected (status $status)")
        forgetPump()
        if (!running) return
        // No reconnect from our side: the pump reconnects to us, always with a new handshake.
        update { it.copy(lostAtMs = it.lostAtMs ?: now) }
        setStage(PumpBtStage.LOST)
        if (!recoverScheduled) updateAdvertising()
    }

    // MARK: - GATT client callback (binder threads → main)

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                journal("GATT client: ${connName(newState)} (status $status)")
                if (g !== gatt) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        val device = pumpDevice
                        if (!PumpLinkRules.keepClientConnection(device != null && isLinked(device))) {
                            journal("GATT client connected, but the pump is no longer on our GATT server: closing at once")
                            closeClient(disconnect = true)
                            resetClientState()
                            return@post
                        }
                        // A handshake is running: discovery starts once it completed.
                        if (gate.crypt == null) {
                            journal("waiting for the SAKE handshake")
                            return@post
                        }
                        startDiscovery(g)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        closeClient(disconnect = false)
                        resetClientState()
                        attachAgainLater()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || g.services.isEmpty()) {
                    journal("no services of the pump found (status $status)")
                    return@post
                }
                allDiscovered(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { journal("GATT client: MTU $mtu (status $status)") }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            delivered(g, c, value.copyOf(), status, read = true)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            delivered(g, c, c.value?.copyOf() ?: ByteArray(0), status, read = true)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            delivered(g, c, value.copyOf(), BluetoothGatt.GATT_SUCCESS, read = false)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            delivered(g, c, c.value?.copyOf() ?: ByteArray(0), BluetoothGatt.GATT_SUCCESS, read = false)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (g !== gatt) return@post
                gattDone()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    journal("write to ${PumpUuid.short(c.uuid)} failed: status $status")
                    currentFailed()
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) journal("subscription to ${PumpUuid.short(d.characteristic.uuid)} failed: status $status")
                gattDone()
            }
        }
    }

    private fun delivered(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int, read: Boolean) {
        val service = PumpUuid.short(c.service.uuid)
        val uuid = PumpUuid.short(c.uuid)
        main.post {
            if (g !== gatt) return@post
            if (read) gattDone()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                journal("read of $uuid failed: status $status")
                if (current?.let { isReadOf(it, uuid) } == true) currentFailed()
                return@post
            }
            handleValue(service, uuid, value, read)
        }
    }

    companion object {
        /**
         * Software revision reported to the pump. The pump may check the companion app version; this is the version
         * of the official iOS app seen by iPump-mini's author.
         */
        const val COMPAT_SOFTWARE_REVISION = "3.3.0(80)"
        const val MEDTRONIC_COMPANY_ID = 0x01F9

        const val GATT_TIMEOUT_MS = 6_000L
        const val NOTIFY_WATCHDOG_MS = 3_000L
        private const val OK = "ok"
        /** Connection and discovery of all the pump's services: Android cannot filter them (about 12 services). */
        const val ATTACH_TIMEOUT_MS = 45_000L
        const val MAX_ATTACH = 5

        /** The pump answered "not supported" to time in range: not asked again in this process. */
        @Volatile
        internal var timeInRangeUnsupported = false

        private val IDD_SERVICE = PumpUuid.normalize(PumpUuid.IDD_SERVICE)
        private val IDD_STATUS = PumpUuid.normalize(PumpUuid.IDD_STATUS)
        private val IDD_STATUS_CHANGED = PumpUuid.normalize(PumpUuid.IDD_STATUS_CHANGED)
        private val IDD_STATUS_READER_CP = PumpUuid.normalize(PumpUuid.IDD_STATUS_READER_CP)
        private val IDD_HISTORY_DATA = PumpUuid.normalize(PumpUuid.IDD_HISTORY_DATA)
        private val IDD_FEATURES = PumpUuid.normalize(PumpUuid.IDD_FEATURES)
        private val GST_BATTERY = PumpUuid.normalize(PumpUuid.GST_BATTERY_LEVEL)
        private val BATTERY_LEVEL = PumpUuid.normalize(PumpUuid.BATTERY_LEVEL)

        /** Stages an advertisement start may replace (not the pump's own stages, not LOST). */
        private val ADVERTISE_STAGES = setOf(
            PumpBtStage.NOT_STARTED, PumpBtStage.STOPPED, PumpBtStage.ERROR, PumpBtStage.ADVERTISING_PAIR, PumpBtStage.WAITING,
        )

        fun connName(state: Int): String = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            else -> "state $state"
        }

        fun bondName(state: Int): String = PumpLinkRules.bondName(state)

        /**
         * The trial window's values after an active basal answer: the temporary basal in its own unit - U/h only for
         * type 0x33, percent only for 0x3C (critic of the joints 1, P-N5: a percentage was shown as U/h); another type
         * or a special value - neither, and its type goes to [PumpBtValues.tempBasalType] (the window tells "running"
         * from "none").
         */
        fun withActiveBasal(values: PumpBtValues, v: ActiveBasalRate): PumpBtValues {
            val temp = v.tempBasalValue?.takeIf { it.isFinite() && it >= 0.0 }
            val type = v.tempBasalType
            val shown = temp != null && (type == ActiveBasalRate.TBR_ABSOLUTE || type == ActiveBasalRate.TBR_RELATIVE)
            return values.copy(
                basalUnitsPerHour = v.unitsPerHour, basalRead = true,
                tempBasalUnitsPerHour = temp.takeIf { type == ActiveBasalRate.TBR_ABSOLUTE },
                tempBasalPercent = temp.takeIf { type == ActiveBasalRate.TBR_RELATIVE }?.let { Math.round(it).toInt() },
                tempBasalRemainingMin = v.tempBasalRemainingMinutes,
                tempBasalType = type.takeIf { !shown },
            )
        }

        /**
         * The hidden extra of ACTION_BOND_STATE_CHANGED with the system's reason (with BOND_NONE - why the bond went:
         * failed authentication, removed, ...). Not in the public API: read as a plain int, -1 when absent.
         */
        const val EXTRA_BOND_REASON = "android.bluetooth.device.extra.REASON"

        /** The journal line of a bond change: one format with the source's ([PumpLinkRules.bondLine]). */
        fun bondLine(device: String, before: Int, now: Int, reason: Int): String =
            PumpLinkRules.bondLine(device, before, now, reason)

        /**
         * [start] of a running link (the window's pairing over a link already waiting, at any stage - also
         * PUMP_CONNECTED and LOST): the window sees the mode at once, and an earlier failed pairing is gone, as after a
         * fresh start (critic B2b, second round, N-1).
         */
        fun restarted(state: PumpBtLinkState, pair: Boolean): PumpBtLinkState =
            state.copy(pairMode = pair, pairingFailedAtMs = null)
    }
}

/** What the watchdog needs of the link (contract 3.4). */
internal data class PumpLinkHealth(
    val running: Boolean,
    val advertising: Boolean,
    val connected: Boolean,
    val lastCycleAtMs: Long?,
    val lastHistoryAtMs: Long?,
)
