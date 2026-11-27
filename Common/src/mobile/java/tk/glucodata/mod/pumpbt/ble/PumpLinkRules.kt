package tk.glucodata.mod.pumpbt.ble

import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.core.PumpUuid
import java.util.UUID

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the link's decisions as plain functions (tested without Android).
 *
 * - Which device is the pump: Android's GATT server callback reports every device connected to the phone - also the
 *   Sibionics sensor JugglucoNG connects to itself. A connection alone is not the pump; the pump is the device that
 *   reads our device information (service 0x0900) or writes to SAKE (its CCCD or the port).
 * - The phone never starts a connection to the pump: the GATT client may only attach to a pump that is connected to
 *   our GATT server right now.
 */
internal object PumpLinkRules {

    /** What a device did on our GATT server. */
    enum class ServerEvent { CONNECTED, DISCONNECTED, READ_DEVICE_INFO, READ_OTHER, WRITE_SAKE_PORT, WRITE_OTHER, WRITE_SAKE_CCCD, WRITE_OTHER_DESCRIPTOR }

    private val appDeviceInfo = PumpUuid.parse(PumpUuid.APP_DEVICE_INFO_SERVICE)
    private val sakePort = PumpUuid.parse(PumpUuid.SAKE_PORT)
    private val cccd = PumpUuid.parse(PumpUuid.CCCD)

    fun countsAsPump(event: ServerEvent): Boolean =
        event == ServerEvent.READ_DEVICE_INFO || event == ServerEvent.WRITE_SAKE_PORT || event == ServerEvent.WRITE_SAKE_CCCD

    /** A read request on a characteristic of service [service]. */
    fun classifyRead(service: UUID?): ServerEvent = if (service == appDeviceInfo) ServerEvent.READ_DEVICE_INFO else ServerEvent.READ_OTHER

    fun classifyWrite(characteristic: UUID): ServerEvent = if (characteristic == sakePort) ServerEvent.WRITE_SAKE_PORT else ServerEvent.WRITE_OTHER

    fun classifyDescriptorWrite(descriptor: UUID, characteristic: UUID?): ServerEvent =
        if (descriptor == cccd && characteristic == sakePort) ServerEvent.WRITE_SAKE_CCCD else ServerEvent.WRITE_OTHER_DESCRIPTOR

    /** Whether the GATT client may attach now. */
    enum class Attach(val text: String) {
        ALLOWED("the pump is connected to our GATT server"),
        NO_PUMP("no pump identified on our GATT server"),
        NOT_CONNECTED("the pump is not connected to our GATT server (the phone never starts a connection)"),
    }

    /** [serverConnected] - `BluetoothManager.getConnectionState(pump, GATT_SERVER) == STATE_CONNECTED`, right now. */
    fun clientAttach(pumpKnown: Boolean, serverConnected: Boolean): Attach = when {
        !pumpKnown -> Attach.NO_PUMP
        !serverConnected -> Attach.NOT_CONNECTED
        else -> Attach.ALLOWED
    }

    /** The client reported "connected": keep it only while the pump is still on our GATT server. */
    fun keepClientConnection(serverConnected: Boolean): Boolean = serverConnected

    /**
     * A bond change shown as the "pairing" stage: of the identified pump, or - before any device counts as the pump -
     * of a device on our GATT server while we advertise for pairing (Android bonds before the pump reads anything).
     */
    fun bondCountsAsPairing(device: String, pump: String?, onOurServer: Boolean, pairMode: Boolean): Boolean =
        if (pump != null) device == pump else pairMode && onOurServer

    /** What a device on our GATT server did to count as the pump. */
    enum class Claim {
        /** It already is the pump. */
        SAME,

        /** It becomes the pump. */
        TAKE,

        /** Another device is the pump and stays. */
        IGNORE,
    }

    /**
     * [device] read our device information ([bySake] false) or wrote to SAKE ([bySake] true). [known] - the device
     * that counts as the pump now, [knownBySake] - it has written to SAKE, [knownLinked] - it is still on our GATT
     * server. A SAKE writer overrides a device known only from reading 0x0900: only the pump writes to SAKE.
     */
    fun claim(known: String?, knownBySake: Boolean, knownLinked: Boolean, device: String, bySake: Boolean): Claim = when {
        known == null -> Claim.TAKE
        known == device -> Claim.SAME
        !knownLinked -> Claim.TAKE
        bySake && !knownBySake -> Claim.TAKE
        else -> Claim.IGNORE
    }

    /** What to do when a device left our GATT server or a pairing failed. */
    enum class Drop {
        NOTHING,

        /** The pump is gone: [PumpLink] handles it as a lost pump. */
        PUMP_LOST,

        /** No pump identified yet: the connection may have ended the advertisement - "stop + start" it again. */
        ADVERTISE_AGAIN,

        /** Another device left and the pump does not look connected: check again in [RECHECK_MS]. */
        RECHECK,
    }

    /**
     * "Disconnected" from the GATT server callback. [pumpKnown] - a device counts as the pump, [isPumpAddress] - the
     * callback's address is the pump's, [pumpStillLinked] - the pump is still on our GATT server (the address in a
     * callback may differ from the one the pump was identified by: random address before the bond, identity after).
     * Another device left and the pump looks gone: [Drop.RECHECK] - ask again in [RECHECK_MS] before calling it lost
     * (a false "not connected" at that moment would otherwise end a working session).
     */
    fun serverDisconnect(running: Boolean, pumpKnown: Boolean, isPumpAddress: Boolean, pumpStillLinked: Boolean): Drop = when {
        pumpKnown && isPumpAddress -> Drop.PUMP_LOST
        pumpKnown && !pumpStillLinked -> Drop.RECHECK
        pumpKnown -> Drop.NOTHING
        running -> Drop.ADVERTISE_AGAIN
        else -> Drop.NOTHING
    }

    /** After [Drop.RECHECK]: lost only when the same pump is still the pump and still not on our GATT server. */
    fun recheckLost(samePump: Boolean, stillLinked: Boolean): Boolean = samePump && !stillLinked

    const val RECHECK_MS = 1_000L

    /** Several drops in a row give one advertising restart, [ADVERTISE_AGAIN_DELAY_MS] after the last. */
    const val ADVERTISE_AGAIN_DELAY_MS = 500L

    /** A bond went from "bonding" to "none" (pairing refused or failed). */
    fun bondFailed(running: Boolean, pumpKnown: Boolean, wasBonding: Boolean, nowNone: Boolean): Drop =
        if (running && !pumpKnown && wasBonding && nowNone) Drop.ADVERTISE_AGAIN else Drop.NOTHING

    /**
     * The stage after a drop before the pump is identified: back from "pairing" / "pump connected" to advertising
     * only when the pairing failed ([pairingFailed]) or the device whose pairing the screen shows left
     * ([shownPairingDeviceLeft]); another device leaving does not change the stage. Null - keep.
     */
    fun stageAfterDrop(current: PumpBtStage, pairMode: Boolean, pairingFailed: Boolean, shownPairingDeviceLeft: Boolean): PumpBtStage? {
        if (!pairingFailed && !shownPairingDeviceLeft) return null
        return when (current) {
            PumpBtStage.PAIRING, PumpBtStage.PUMP_CONNECTED -> if (pairMode) PumpBtStage.ADVERTISING_PAIR else PumpBtStage.WAITING
            else -> null
        }
    }

    /** A GATT command Android did not start (busy): try again while [attempt] (retries so far) is below [GATT_RETRIES]. */
    fun gattRetry(attempt: Int): Boolean = attempt < GATT_RETRIES

    const val GATT_RETRIES = 10
    const val GATT_RETRY_MS = 500L

    /** Notification failures written to the journal: the first and every 20th. */
    fun logNotifyFailure(failures: Int): Boolean = failures == 1 || (failures > 0 && failures % 20 == 0)

    /** Bonded devices named in the journal: only pump-like names (starts with "Pump", contains MiniMed or Medtronic). */
    fun pumpLikeName(name: String?): Boolean = name != null &&
        (name.startsWith("Pump", ignoreCase = true) || name.contains("MiniMed", ignoreCase = true) || name.contains("Medtronic", ignoreCase = true))

    /** The stage at the end of a read cycle: "connected, data read" only when the serial number was read on this link. */
    fun cycleDoneStage(serialRead: Boolean): PumpBtStage = if (serialRead) PumpBtStage.DATA_READ else PumpBtStage.READING

    enum class Advertising { PAIR, RECONNECT }

    /** No advertising while stopped or once the pump is there; pairing (FE82) or reconnect (FE81) otherwise. */
    fun advertising(active: Boolean, pumpPresent: Boolean, pairMode: Boolean): Advertising? = when {
        !active || pumpPresent -> null
        pairMode -> Advertising.PAIR
        else -> Advertising.RECONNECT
    }

    /** Variant A: Medtronic manufacturer data (company 0x01F9): `00 + "Mobile NNNNNN" + 00`. */
    fun manufacturerData(advertisedName: String): ByteArray = byteArrayOf(0) + advertisedName.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    /** Bytes of the pairing advertisement (flags 3 + FE82 4 + name or manufacturer data); the limit is 31. */
    fun pairAdvertisementBytes(variant: PumpBtNameVariant, advertisedName: String): Int = 3 + 4 + when (variant) {
        PumpBtNameVariant.A -> 2 + 2 + manufacturerData(advertisedName).size
        PumpBtNameVariant.B -> 2 + advertisedName.length
    }

    /**
     * History records to read: on the first read after each start ([last] null) the last [FIRST_COUNT] (up to
     * [newest]); later the new ones since [last], at most [MAX_COUNT] (the newest of them). Null - nothing new.
     */
    fun historyRange(newest: Long, last: Long?): LongRange? {
        if (newest < 0) return null
        if (last == null) return (newest - FIRST_COUNT + 1).coerceAtLeast(0)..newest
        if (last >= newest) return null
        return maxOf(last + 1, newest - MAX_COUNT + 1)..newest
    }

    const val FIRST_COUNT = 50L
    const val MAX_COUNT = 200L

    /** Android's AdvertiseCallback error codes, for the journal. */
    fun advertiseError(code: Int): String = when (code) {
        1 -> "data too large"
        2 -> "too many advertisers"
        3 -> "already started"
        4 -> "internal error"
        5 -> "feature unsupported"
        else -> "error"
    } + " (code $code)"
}
