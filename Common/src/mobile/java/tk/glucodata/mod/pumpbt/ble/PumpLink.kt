// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// apps/android/app/src/main/java/de/sgitaize/openminipump/ble/PumpLink.kt, by Simon Gutjahr
// (itself a port of apps/ios/OpenMinipump/BLE/PumpLink.swift).
// Modified for JugglucoNGmod: cut down to a read-only trial - no demo mode, no saved session (every link is a new
// handshake), no Medtronic sensor (CGM), alarms, data store, Nightscout or preferences of the original; writes,
// reads and subscriptions go through PumpGate; the GATT client attaches only to a pump connected to our GATT server
// right now (checked just before connectGatt, closed at once when that link is gone); the pump is the device that
// reads our device information or writes to SAKE (a SAKE writer overrides a device that only read it); advertising
// stops when the pump is there, starts again when a connection ends before the pump is identified, and always restarts
// as "stop + start"; name variant B (phone Bluetooth name, given back right after the handshake); a GATT command
// timeout goes on with the next command, as the original, and a command Android does not start is retried (10 times,
// then dropped with a journal line); a watchdog frees the SAKE notification queue; reads the model (2A24) and IDD Features; history - the last
// 50 records after every start, then new ones (at most 200); English journal lines (MTU, every connection state, bond
// state, DIS reads, SAKE stages, services and how long their discovery took, decrypted answers).
//
// Roles: the pump is the BLE central and SAKE client, the phone is the BLE peripheral and SAKE server. After the SAKE
// handshake the phone additionally acts as GATT client on the same link to read the pump's services.
//
// Threading: all state lives on the main thread. Android BLE callbacks arrive on binder threads and are posted to the
// main looper with a copy of their data.
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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import tk.glucodata.mod.pumpbt.PumpBtHistory
import tk.glucodata.mod.pumpbt.PumpBtJournal
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtProgress
import tk.glucodata.mod.pumpbt.PumpBtReturn
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.PumpBtStore
import tk.glucodata.mod.pumpbt.PumpBtValues
import tk.glucodata.mod.pumpbt.core.ActiveBasalRate
import tk.glucodata.mod.pumpbt.core.HistoryRecord
import tk.glucodata.mod.pumpbt.core.InsulinOnBoard
import tk.glucodata.mod.pumpbt.core.KeyDatabase
import tk.glucodata.mod.pumpbt.core.PumpStatus
import tk.glucodata.mod.pumpbt.core.PumpUuid
import tk.glucodata.mod.pumpbt.core.SakeServer
import tk.glucodata.mod.pumpbt.core.StatusChangedFlags
import tk.glucodata.mod.pumpbt.core.StatusReaderOpcode
import tk.glucodata.mod.pumpbt.core.TherapyAlgorithmStates
import tk.glucodata.mod.pumpbt.core.TimeInRange
import tk.glucodata.mod.pumpbt.core.bytesOf
import tk.glucodata.mod.pumpbt.core.le32
import tk.glucodata.mod.pumpbt.core.statusReaderResponseOpcode
import tk.glucodata.mod.pumpbt.core.toHex
import java.time.Duration
import java.time.Instant

@SuppressLint("MissingPermission")
internal class PumpLink(
    context: Context,
    private val keyDatabase: KeyDatabase,
    private val store: PumpBtStore,
    /** A line for the trial journal (may hold values and the serial number; never key material). */
    private val journal: (String) -> Unit,
    /** A line for logcat: stages and errors only. */
    private val logcat: (String) -> Unit,
    private val publish: (PumpBtLinkState) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager = this.context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private var state = PumpBtLinkState()

    private var running = false
    private var pairMode = false
    private var receiversOn = false
    private val advertisedName: String = store.advertisedName
    private var variant = store.variant

    // Peripheral side (GATT server, SAKE)
    private var server: BluetoothGattServer? = null
    private var sakePort: BluetoothGattCharacteristic? = null
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var servicesReady = false
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseToken = 0
    private var sake: SakeServer? = null
    private var pumpDevice: BluetoothDevice? = null
    /** [pumpDevice] has written to SAKE (not only read our device information). */
    private var pumpBySake = false
    /** The last state the GATT server callback reported per address (for the journal next to the system's answer). */
    private val serverStates = mutableMapOf<String, Int>()
    /** Another device left and the pump did not look connected: asked again after [PumpLinkRules.RECHECK_MS]. */
    private var recheckFor: BluetoothDevice? = null
    private var recheckStatus = 0
    private val pumpRecheck = Runnable {
        val pump = recheckFor ?: return@Runnable
        recheckFor = null
        val same = pumpDevice?.address == pump.address
        val linked = same && isLinked(pump)
        journal(
            "pump ${pump.address} on our server ${PumpLinkRules.RECHECK_MS} ms later: " +
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
        if (running && pumpDevice == null) updateAdvertising()
    }

    // Central side (GATT client on the pump)
    private var gatt: BluetoothGatt? = null
    private val characteristics = mutableMapOf<String, BluetoothGattCharacteristic>()
    private var attachAttempts = 0
    /** elapsedRealtime of the last connectGatt, for "services discovered after N ms". */
    private var connectGattAtMs = 0L
    private var decryptFailures = 0
    private var featuresRead = false

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

    // Read cycle (logical operations, one at a time)
    private val operations = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var currentStartedAt: Instant? = null
    private var lastRefresh: Instant = Instant.EPOCH
    private var refreshPending = false
    private var cycleRunning = false
    private val operationTimeout = Runnable {
        current?.let { journal("no answer from the pump: $it") }
        nextOperation()
    }

    // Pump history (IDD RACP): records collected during one read.
    private val historyBuffer = mutableListOf<HistoryRecord>()
    private var historyNewestSeq: Long? = null
    private var lastHistorySync: Instant = Instant.EPOCH
    /** The newest history record read since the start; null - the next read takes the last 50. Not kept between runs. */
    private var lastHistorySeq: Long? = null

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
    private val gattRetry = Runnable { runGattQueue() }
    // As the original: a command without answer is logged and the next one goes; the client stays open (closing the
    // only client on a link the pump opened may drop the whole link). Every value is handled by its UUID, so a late
    // answer does no harm in a read-only trial.
    private val gattTimeout = Runnable {
        journal("GATT command without answer in ${GATT_TIMEOUT_MS / 1000} s, next one")
        gattDone()
    }
    private val attachTimeout = Runnable {
        journal("GATT client: no connection and services within ${ATTACH_TIMEOUT_MS / 1000} s, closing")
        // disconnect() only when the pump is no longer on our server: a live link of the pump is not touched, a
        // pending direct connection is cancelled
        closeClient(disconnect = pumpDevice?.let { !isLinked(it) } ?: true)
        resetClientState()
        attachAgainLater()
    }

    private sealed class Operation {
        data class Read(val service: String, val characteristic: String) : Operation() {
            override fun toString() = "read $characteristic"
        }

        data class StatusReader(val opcode: StatusReaderOpcode) : Operation() {
            override fun toString() = "status reader $opcode"
        }

        object HistoryLast : Operation() {
            override fun toString() = "history: last record"
        }

        data class HistoryRange(val from: Long, val to: Long) : Operation() {
            override fun toString() = "history $from…$to"
        }
    }

    // MARK: - Public controls (main thread)

    val isRunning: Boolean get() = running

    /** Starts the trial: [pair] - advertising for pairing (FE82), else for the paired pump's return (FE81). */
    fun start(pair: Boolean) {
        // every start reads the last 50 history records again
        lastHistorySeq = null
        if (running) {
            pairMode = pair
            journal("mode: " + if (pair) "pairing" else "waiting for the pump")
            updateAdvertising()
            return
        }
        variant = store.variant
        running = true
        pairMode = pair
        update { it.copy(running = true, pairMode = pair, error = null, lastProblem = null, lastProblemAtMs = null, furthestStage = null) }
        journal(
            "trial started ${PumpBtJournal.fullDate()}: " + (if (pair) "pairing" else "waiting for the pump") +
                ", name variant $variant, advertised name \"$advertisedName\", paired before: ${store.paired}",
        )
        registerReceivers()
        val a = adapter
        logBondedDevices(a)
        when {
            a == null -> fail("this phone has no Bluetooth")
            !a.isEnabled -> fail("Bluetooth is off")
            !a.isMultipleAdvertisementSupported || a.bluetoothLeAdvertiser == null ->
                fail("this phone cannot advertise as a Bluetooth accessory")
            else -> setUpServer()
        }
    }

    /**
     * The phone's bonded devices: how many, and name + address only of pump-like ones (a bond left from an earlier
     * attempt is a likely reason for a failed pairing). Other names are not written.
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
            journal("bonded devices of the phone: ${bonded.size}" + if (pumps.isEmpty()) ", none pump-like" else "; pump-like: " + pumps.joinToString(", "))
        } catch (_: SecurityException) {
            journal("bonded devices not read: Bluetooth permission missing")
        } catch (e: Exception) {
            journal("bonded devices not read (${e.javaClass.simpleName})")
        }
    }

    fun stop() {
        if (!running) return
        journal("trial stopped by the button")
        running = false
        pairMode = false
        unregisterReceivers()
        tearDown(cancelPump = true)
        PumpBtAdapterName.restore(context, store, journal)
        setStage(PumpBtStage.STOPPED)
        update { it.copy(running = false, pairMode = false, lostAtMs = null) }
    }

    /** Repeats the reading on a live link. */
    fun readAgain() {
        if (!running) return
        val device = pumpDevice
        when {
            gate.serialVerified && gatt != null -> {
                journal("read again (button)")
                scheduleRefresh(force = true, withHistory = true)
            }
            gate.crypt != null && device != null && gatt == null -> {
                journal("read again (button): attaching the GATT client")
                attachAttempts = 0
                connectAsGattClient()
            }
            else -> journal("read again (button): no live link with the pump")
        }
    }

    // MARK: - State

    private fun update(transform: (PumpBtLinkState) -> PumpBtLinkState) {
        state = transform(state)
        publish(state)
    }

    private fun updateValues(transform: (PumpBtValues) -> PumpBtValues) = update { it.copy(values = transform(it.values)) }

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

    private fun fail(reason: String) {
        journal("error: $reason")
        setStage(PumpBtStage.ERROR, reason)
    }

    // MARK: - Receivers (Bluetooth on/off, bond state)

    private fun registerReceivers() {
        if (receiversOn) return
        try {
            ContextCompat.registerReceiver(context, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(context, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
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
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    journal("Bluetooth on")
                    if (running) {
                        if (state.stage == PumpBtStage.ERROR) setStage(if (pairMode) PumpBtStage.ADVERTISING_PAIR else PumpBtStage.WAITING)
                        setUpServer()
                    }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (server == null && !servicesReady) return
                    journal("Bluetooth off")
                    tearDown(cancelPump = false)
                    if (running) fail("Bluetooth turned off")
                }
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
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
            journal("bond state of $address: ${bondName(before)} → ${bondName(now)}")
            if (!running) return
            val failed = PumpLinkRules.bondFailed(
                running, pumpKnown = pumpDevice != null,
                wasBonding = before == BluetoothDevice.BOND_BONDING, nowNone = now == BluetoothDevice.BOND_NONE,
            )
            if (failed == PumpLinkRules.Drop.ADVERTISE_AGAIN) {
                journal("pairing with $address failed or was refused, no pump identified: advertising again")
                advertiseAgain(pairingFailed = true, shownPairingDeviceLeft = false)
                return
            }
            val onServer = isLinked(device)
            if (PumpLinkRules.bondCountsAsPairing(address, pumpDevice?.address, onServer, pairMode)) {
                when (now) {
                    BluetoothDevice.BOND_BONDING -> {
                        if (pumpDevice == null) pairingShownAddress = address
                        setStage(PumpBtStage.PAIRING)
                    }
                    BluetoothDevice.BOND_BONDED -> if (state.stage == PumpBtStage.PAIRING) setStage(PumpBtStage.PUMP_CONNECTED)
                    BluetoothDevice.BOND_NONE -> if (before == BluetoothDevice.BOND_BONDING) journal("pairing with $address failed or was refused")
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
        closeClient(disconnect = false)
        resetClientState()
        if (cancelPump) {
            pumpDevice?.let { d ->
                if (isLinked(d)) {
                    journal("dropping our side of the link to the pump")
                    try {
                        server?.cancelConnection(d)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        sakePort = null
        servicesReady = false
        pendingServices.clear()
        pumpDevice = null
        pumpBySake = false
        pairingShownAddress = null
        serverStates.clear()
        main.removeCallbacks(advertiseAgainLater)
        main.removeCallbacks(pumpRecheck)
        recheckFor = null
        sake = null
        gate.crypt = null
        clearOutbox()
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
     * the official iOS app. To be verified on the real pump.
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

        // Values are served dynamically in onCharacteristicReadRequest, so every read gets logged.
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
        val mode = PumpLinkRules.advertising(running && servicesReady && a?.isEnabled == true, pumpDevice != null, pairMode) ?: return
        val advertiser = a?.bluetoothLeAdvertiser ?: run {
            fail("advertising not available")
            return
        }
        val token = advertiseToken
        if (mode == PumpLinkRules.Advertising.PAIR && variant == PumpBtNameVariant.B) {
            val name = try {
                a.name
            } catch (_: SecurityException) {
                null
            }
            if (name.isNullOrEmpty()) {
                // the name could not be given back: do not change it
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
        // LOW_LATENCY is about 100 ms on Android; the pump wants 150 ms or less for reconnects.
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
                main.post { if (token == advertiseToken) journal("advertising started: $what") }
            }

            override fun onStartFailure(errorCode: Int) {
                main.post {
                    if (token != advertiseToken) return@post
                    if (advertiseCallback === this) advertiseCallback = null
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
                return true
            }
            PumpLinkRules.Claim.IGNORE -> {
                journal("${device.address} $what - ignored, ${known?.address} is the pump")
                return false
            }
            PumpLinkRules.Claim.TAKE -> if (known != null && knownLinked) {
                journal("${device.address} $what - it is the pump, not ${known.address} (that one only read the device information)")
            }
        }
        pumpDevice = device
        pumpBySake = bySake
        pairingShownAddress = null
        main.removeCallbacks(advertiseAgainLater)
        store.pumpAddress = device.address
        journal("pump identified: ${device.address} ($what), bond state ${bondName(device.bondState)}")
        // the official app stops advertising once the pump is connected
        stopAdvertising()
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
            journal("SAKE error at stage $before: ${e.javaClass.simpleName}: ${e.message}")
            logcat("handshake failed at stage $before (${e.javaClass.simpleName})")
            fail("handshake failed at stage $before (${e.javaClass.simpleName})")
            sake = null
        }
    }

    private fun handshakeDone(server: SakeServer) {
        gate.crypt = server.crypt
        decryptFailures = 0
        store.paired = true
        pumpDevice?.let { store.pumpAddress = it.address }
        if (pairMode) {
            pairMode = false
            update { it.copy(pairMode = false) }
        }
        // variant B: the reconnect advertisement (FE81) carries no name, give the phone's name back right away
        PumpBtAdapterName.restore(context, store, journal)
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
                "GATT client: pump ${device.address} on our server - system: " + (if (linked) "connected" else "not connected") +
                    ", last server callback: " + (serverStates[device.address]?.let { connName(it) } ?: "none"),
            )
        }
        val decision = PumpLinkRules.clientAttach(device != null, linked)
        if (decision != PumpLinkRules.Attach.ALLOWED || device == null) {
            journal("GATT client: attach refused - ${decision.text}")
            if (device != null && attachAttempts < MAX_ATTACH) {
                main.postDelayed({ if (gatt == null && running) connectAsGattClient() }, 2000)
            } else if (device != null) {
                fail("the pump is not reachable as GATT server")
            }
            return
        }
        val existing = gatt
        if (existing != null && existing.device.address == device.address) {
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
        if (gatt != null) main.postDelayed(attachTimeout, ATTACH_TIMEOUT_MS)
    }

    /** After a closed client: attach again in 2 s if the pump is still on our server (the same check runs again). */
    private fun attachAgainLater() {
        val device = pumpDevice ?: return
        if (!running || gate.crypt == null) return
        if (!isLinked(device)) {
            journal("GATT client: not attaching again - the pump left our GATT server")
            return
        }
        if (attachAttempts >= MAX_ATTACH) {
            fail("GATT client closed $attachAttempts times on this link - press Read again to retry")
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
        main.removeCallbacks(gattRetry)
    }

    private fun resetClientState() {
        characteristics.clear()
        gate.serialVerified = false
        operations.clear()
        current = null
        currentStartedAt = null
        cycleRunning = false
        main.removeCallbacks(operationTimeout)
        historyBuffer.clear()
        historyNewestSeq = null
        featuresRead = false
        gattQueue.clear()
        gattBusy = false
        gattRetries = 0
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
            journal("pump service ${PumpUuid.short(s.uuid)}: " + s.characteristics.joinToString(" ") { PumpUuid.short(it.uuid) })
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
        updateValues { it.copy(serial = serial) }
        journal("pump serial number: $serial")
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
     * Hands the head of the queue to Android. Not started (Android still busy, e.g. after a command timed out): the
     * command stays at the head and is tried again in [PumpLinkRules.GATT_RETRY_MS], at most
     * [PumpLinkRules.GATT_RETRIES] times, then dropped with a journal line - never silently.
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
                    gattBusy = true
                    main.postDelayed(gattTimeout, GATT_TIMEOUT_MS)
                    return
                }
                Start.BUSY -> if (PumpLinkRules.gattRetry(gattRetries)) {
                    gattRetries++
                    journal("$cmd not started (GATT busy), retry in ${PumpLinkRules.GATT_RETRY_MS} ms")
                    main.postDelayed(gattRetry, PumpLinkRules.GATT_RETRY_MS)
                    return
                } else {
                    journal("$cmd dropped")
                    gattQueue.removeFirst()
                    gattRetries = 0
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
                operations.clear()
                current = null
                currentStartedAt = null
            }
        }
        if (current != null || operations.isNotEmpty()) {
            refreshPending = true
            return
        }
        if (!force && Duration.between(lastRefresh, Instant.now()).seconds < 50) return
        refreshPending = false
        lastRefresh = Instant.now()
        operations.addAll(
            listOf(
                Operation.Read(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS),
                Operation.StatusReader(StatusReaderOpcode.GET_INSULIN_ON_BOARD),
                Operation.StatusReader(StatusReaderOpcode.GET_THERAPY_ALGORITHM_STATES),
                Operation.StatusReader(StatusReaderOpcode.GET_ACTIVE_BASAL_RATE_DELIVERY),
                Operation.StatusReader(StatusReaderOpcode.GET_TIME_IN_RANGE),
                Operation.Read(PumpUuid.BATTERY_SERVICE, PumpUuid.BATTERY_LEVEL),
                // GST Battery Level (0x0400) is notify-only on the 780G ("Reading is not permitted").
            ),
        )
        if (!featuresRead) {
            featuresRead = true
            operations.add(Operation.Read(PumpUuid.IDD_SERVICE, PumpUuid.IDD_FEATURES))
        }
        if (withHistory || Duration.between(lastHistorySync, Instant.now()).toMinutes() >= 4) {
            lastHistorySync = Instant.now()
            historyBuffer.clear()
            operations.add(Operation.HistoryLast)
        }
        cycleRunning = true
        if (state.stage != PumpBtStage.DATA_READ) setStage(PumpBtStage.READING)
        nextOperation()
    }

    /** Queues a history read right away (e.g. a pump alert changed), unless one is running. */
    private fun requestHistoryNow() {
        if (!gate.serialVerified) return
        fun isHistory(op: Operation?) = op is Operation.HistoryLast || op is Operation.HistoryRange
        if (isHistory(current) || operations.any(::isHistory)) return
        lastHistorySync = Instant.now()
        historyBuffer.clear()
        operations.add(Operation.HistoryLast)
        if (current == null) nextOperation()
    }

    /** After "last record" told us the newest sequence number: queue the range to read. */
    private fun queueHistoryRange() {
        val newest = historyNewestSeq ?: run {
            journal("history: the pump reported no last record")
            return
        }
        val range = PumpLinkRules.historyRange(newest, lastHistorySeq) ?: run {
            journal("history: nothing new (last record $newest)")
            return
        }
        journal("history: reading ${range.last - range.first + 1} records (${range.first}…${range.last})")
        operations.addFirst(Operation.HistoryRange(range.first, range.last))
    }

    /** The records of one read: into the journal and the last 20 on the screen. */
    private fun processHistory() {
        if (historyBuffer.isEmpty()) return
        val records = historyBuffer.sortedBy { it.sequence }
        historyBuffer.clear()
        val (lines, reference) = PumpBtHistory.lines(records, store.historyReference)
        store.historyReference = reference
        records.maxOfOrNull { it.sequence }?.let { lastHistorySeq = maxOf(lastHistorySeq ?: 0, it) }
        for (r in records) {
            val (kind, value) = PumpBtHistory.describe(r.event)
            journal("history #${r.sequence} type 0x%04x +%d s: %s %s".format(r.eventType, r.relativeOffset, kind, value).trimEnd())
        }
        journal("history: ${records.size} records read" + if (reference == null) " (no reference time yet)" else "")
        updateValues { it.copy(history = PumpBtHistory.merge(it.history, lines)) }
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
                main.postDelayed(operationTimeout, if (op is Operation.HistoryRange) 45_000 else 8_000)
                return
            }
        }
        // Queue drained (also after a timed-out history range): process collected history.
        if (historyBuffer.isNotEmpty()) processHistory()
        if (cycleRunning) {
            cycleRunning = false
            updateValues { it.copy(readAtMs = System.currentTimeMillis()) }
            val serialRead = gate.serialVerified && state.values.serial != null
            journal("read cycle done" + if (serialRead) "" else " - no serial number read on this link")
            setStage(PumpLinkRules.cycleDoneStage(serialRead))
        }
        // Run a refresh that was requested while busy.
        if (refreshPending) scheduleRefresh()
    }

    private fun start(op: Operation): Boolean = when (op) {
        is Operation.Read -> gate.read(op.service, op.characteristic)
        is Operation.StatusReader -> gate.write(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, op.opcode.request)
        Operation.HistoryLast -> gate.write(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, bytesOf(0x33, 0x69, 0x0F))
        is Operation.HistoryRange ->
            gate.write(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, bytesOf(0x33, 0x5A, 0x0F) + le32(op.from) + le32(op.to))
    }

    private fun finish(matches: (Operation) -> Boolean) {
        val op = current ?: return
        if (matches(op)) nextOperation()
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
                // ciphertext length and first bytes help the analysis; they hold no key material
                journal(
                    "decrypting $uuid failed (${decryptFailures}x): ${e.javaClass.simpleName} ${e.message}; " +
                        "ciphertext ${data.size} bytes, first ${minOf(8, data.size)}: ${data.copyOfRange(0, minOf(8, data.size)).toHex()}",
                )
                if (decryptFailures >= 5) {
                    gate.crypt = null
                    fail("session lost - waiting for the pump to connect again")
                }
                finish { true }
                if (isTrigger) scheduleRefresh()
                return
            }
        }
        journal("pump ${if (read) "read" else "sent"} $uuid: ${plain.toHex()}")
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
            PumpUuid.SERIAL_NUMBER -> serialReceived(String(plain))
            PumpUuid.FIRMWARE_REVISION -> String(plain).let { fw ->
                journal("pump firmware: $fw")
                updateValues { it.copy(firmware = fw) }
            }
            PumpUuid.MODEL_NUMBER -> String(plain).let { model ->
                journal("pump model: $model")
                updateValues { it.copy(model = model) }
            }

            PumpUuid.RECORD_ACCESS_CP -> if (service == IDD_SERVICE) {
                // IDS RACP response: 0F 0F <request opcode> <code>; F0 = success, 06 = no records.
                val ok = plain.size >= 4 && plain[3].toInt() and 0xFF == 0xF0
                if (!ok) journal("history RACP answer is not success: ${plain.toHex()}")
                when (current) {
                    is Operation.HistoryLast -> {
                        queueHistoryRange()
                        finish { true }
                    }
                    is Operation.HistoryRange -> finish { true }
                    else -> {}
                }
            } else {
                journal("RACP answer of service $service ignored")
            }

            IDD_HISTORY_DATA -> try {
                val record = HistoryRecord.parse(plain)
                if (current is Operation.HistoryLast) historyNewestSeq = record.sequence else historyBuffer.add(record)
            } catch (e: Exception) {
                journal("history record not readable (${e.javaClass.simpleName})")
            }

            IDD_STATUS_READER_CP -> handleStatusReader(plain)

            IDD_STATUS_CHANGED -> {
                val flags = StatusChangedFlags.parse(plain)
                journal("status changed flags: " + (flags?.raw?.let { "0x%04x".format(it) } ?: "?"))
                if (flags != null && StatusChangedFlags.ANNUNCIATION in flags) requestHistoryNow()
            }

            IDD_STATUS -> {
                val s = runCatching { PumpStatus.parse(plain) }.getOrNull()
                if (s != null) {
                    journal(
                        "IDD status: therapy 0x%02x, operational 0x%02x, reservoir %.2f U, attached %b, sensor 0x%02x/0x%02x"
                            .format(s.therapyControlState, s.operationalState, s.reservoirUnits, s.reservoirAttached, s.sensorConnectivity, s.sensorMessageState),
                    )
                    updateValues { it.copy(reservoirUnits = s.reservoirUnits) }
                } else {
                    journal("IDD status not readable")
                }
                finish { it is Operation.Read }
            }

            IDD_FEATURES -> {
                journal("IDD features: ${plain.size} bytes")
                finish { it is Operation.Read }
            }

            PumpUuid.BATTERY_LEVEL -> {
                plain.firstOrNull()?.let { b ->
                    val pct = b.toInt() and 0xFF
                    journal("pump battery: $pct %")
                    updateValues { it.copy(batteryPercent = pct) }
                }
                finish { it is Operation.Read && it.characteristic == PumpUuid.BATTERY_LEVEL }
            }

            GST_BATTERY -> plain.firstOrNull()?.let { journal("GST battery level: ${it.toInt() and 0xFF} %") }

            else -> finish { it is Operation.Read && PumpUuid.normalize(it.characteristic) == uuid }
        }
    }

    private fun handleStatusReader(plain: ByteArray) {
        val opcode = statusReaderResponseOpcode(plain) ?: return
        try {
            when (opcode) {
                StatusReaderOpcode.GET_INSULIN_ON_BOARD.responseOpcode -> {
                    val v = InsulinOnBoard.parse(plain)
                    journal("active insulin: ${v.units} U, remaining ${v.remainingDurationMinutes ?: "-"} min")
                    updateValues { it.copy(iobUnits = v.units) }
                }
                StatusReaderOpcode.GET_THERAPY_ALGORITHM_STATES.responseOpcode -> {
                    val v = TherapyAlgorithmStates.parse(plain)
                    journal("therapy algorithm: ${v.modeDescription} (shield ${v.shieldState ?: "-"}, readiness ${v.readinessState ?: "-"})")
                }
                StatusReaderOpcode.GET_ACTIVE_BASAL_RATE_DELIVERY.responseOpcode -> {
                    val v = ActiveBasalRate.parse(plain)
                    journal(
                        "active basal: profile ${v.profileNumber}, ${v.unitsPerHour} U/h, temp basal " +
                            (v.tempBasalUnitsPerHour?.let { "$it U/h, ${v.tempBasalRemainingMinutes ?: "-"} min left" } ?: "none"),
                    )
                    updateValues {
                        it.copy(
                            basalUnitsPerHour = v.unitsPerHour, basalRead = true,
                            tempBasalUnitsPerHour = v.tempBasalUnitsPerHour, tempBasalRemainingMin = v.tempBasalRemainingMinutes,
                        )
                    }
                }
                StatusReaderOpcode.GET_TIME_IN_RANGE.responseOpcode -> {
                    val v = TimeInRange.parse(plain)
                    journal("time in range: ${v.inRangePercent} %, above ${v.abovePercent} %, below ${v.belowPercent} %, not enough data ${v.notEnoughData}")
                }
                else -> journal("status reader answer 0x%04x not expected".format(opcode))
            }
        } catch (e: Exception) {
            journal("status reader answer not readable (${e.javaClass.simpleName}: ${e.message})")
        }
        finish { it is Operation.StatusReader }
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
                    "GATT server: $address ${connName(newState)} (status $status, bond $bond)" +
                        if (isPump) " - the pump" else " - not counted as the pump",
                )
                if (newState != BluetoothProfile.STATE_DISCONNECTED) return@post
                // the pump may be reported under another address (random before the bond, identity after): when the
                // identified pump is gone from our server, it is lost whatever address this callback carries
                val pumpStillLinked = pump != null && !isPump && isLinked(pump)
                when (PumpLinkRules.serverDisconnect(running, pump != null, isPump, pumpStillLinked)) {
                    PumpLinkRules.Drop.PUMP_LOST -> pumpDisconnected(status)
                    PumpLinkRules.Drop.RECHECK -> if (pump != null) {
                        // a false "not connected" at this moment must not end a working session: ask again in 1 s
                        journal("another device left and the pump ${pump.address} does not look connected now: checking again in ${PumpLinkRules.RECHECK_MS} ms")
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
            val value = if (event == PumpLinkRules.ServerEvent.READ_DEVICE_INFO) deviceInfo[short] else null
            val s = server ?: return
            when {
                value == null -> s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                offset > value.size -> s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                else -> s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
            main.post {
                if (value == null) {
                    journal("${device.address} reads unknown characteristic $short: refused")
                    return@post
                }
                if (!pumpSignal(device, "read device information $short", bySake = false)) return@post
                journal("pump reads device information $short (offset $offset): \"${String(value)}\"")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val event = PumpLinkRules.classifyWrite(characteristic.uuid)
            val isPort = event == PumpLinkRules.ServerEvent.WRITE_SAKE_PORT
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (isPort) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
            val copy = value?.copyOf()
            main.post {
                if (!isPort || copy == null) {
                    journal("${device.address} writes ${PumpUuid.short(characteristic.uuid)}: refused")
                    return@post
                }
                if (!pumpSignal(device, "wrote the SAKE port", bySake = true)) return@post
                if (!running) return@post
                handleSake(copy)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0, 0))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            // answer first, the 20 zero bytes follow on the main thread (the pump loses them inside its own write)
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val event = PumpLinkRules.classifyDescriptorWrite(descriptor.uuid, descriptor.characteristic?.uuid)
            if (event != PumpLinkRules.ServerEvent.WRITE_SAKE_CCCD || value == null || value.size < 2) return
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
            main.post { journal("GATT server: MTU $mtu (${device.address})") }
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
        send(ByteArray(SakeServer.MESSAGE_SIZE))
    }

    private fun pumpDisconnected(status: Int) {
        val now = System.currentTimeMillis()
        journal("the pump disconnected (status $status)")
        logcat("pump disconnected (status $status)")
        // the client may still be connecting: without disconnect() Android would start a direct connection itself
        closeClient(disconnect = true)
        resetClientState()
        clearOutbox()
        main.removeCallbacks(pumpRecheck)
        recheckFor = null
        sake = null
        gate.crypt = null
        pumpDevice = null
        pumpBySake = false
        if (!running) return
        // No reconnect from our side: the pump reconnects to us, always with a new handshake.
        update { it.copy(lostAtMs = it.lostAtMs ?: now) }
        setStage(PumpBtStage.LOST)
        updateAdvertising()
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
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    journal("write to ${PumpUuid.short(c.uuid)} failed: status $status")
                    finish { true }
                }
                gattDone()
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
                finish { true }
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

        private val IDD_SERVICE = PumpUuid.normalize(PumpUuid.IDD_SERVICE)
        private val IDD_STATUS = PumpUuid.normalize(PumpUuid.IDD_STATUS)
        private val IDD_STATUS_CHANGED = PumpUuid.normalize(PumpUuid.IDD_STATUS_CHANGED)
        private val IDD_STATUS_READER_CP = PumpUuid.normalize(PumpUuid.IDD_STATUS_READER_CP)
        private val IDD_HISTORY_DATA = PumpUuid.normalize(PumpUuid.IDD_HISTORY_DATA)
        private val IDD_FEATURES = PumpUuid.normalize(PumpUuid.IDD_FEATURES)
        private val GST_BATTERY = PumpUuid.normalize(PumpUuid.GST_BATTERY_LEVEL)

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

        fun bondName(state: Int): String = when (state) {
            BluetoothDevice.BOND_NONE -> "none"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_BONDED -> "bonded"
            else -> "unknown"
        }
    }
}
