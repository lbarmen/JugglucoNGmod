package tk.glucodata.mod.pumpbt.ble

import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.core.HistoryRead
import tk.glucodata.mod.pumpbt.core.HistoryReadStatus
import tk.glucodata.mod.pumpbt.core.PumpUuid
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import tk.glucodata.mod.pumpbt.core.StatusReaderOpcode
import tk.glucodata.mod.pumpbt.core.WriteRequest
import tk.glucodata.mod.pumpbt.core.bytesOf
import tk.glucodata.mod.pumpbt.core.le32
import java.util.UUID

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the link's decisions as plain functions (tested without Android).
 *
 * - Which device is the pump: Android's GATT server callback reports every device connected to the phone - also the
 *   Sibionics sensor JugglucoNG connects to itself. A connection alone is not the pump; the pump is the device that
 *   reads our device information (service 0x0900) or writes to SAKE (its CCCD or the port).
 * - The phone never starts a connection to the pump: the GATT client may only attach to a pump that is connected to
 *   our GATT server right now.
 * - No dead ends: every failure drops the link and advertises again, with a pause growing to a minute.
 * - History: read from the number the history memory gives, in pages, up to the newest; the result is reported as it
 *   is (complete, gap, timed out, failed) - the link keeps no number.
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

    /**
     * A GATT command Android did not start (busy): try again while [attempt] (retries so far) is below [GATT_RETRIES].
     * After a command timed out ([sinceTimeoutMs] - since then) Android may still be busy with it: wait (retry every
     * [GATT_RETRY_MS]) up to [GATT_STALE_MAX_MS] instead of dropping the next command.
     */
    fun gattRetry(attempt: Int, sinceTimeoutMs: Long? = null): Boolean =
        if (sinceTimeoutMs != null) sinceTimeoutMs < GATT_STALE_MAX_MS else attempt < GATT_RETRIES

    const val GATT_RETRIES = 10
    const val GATT_RETRY_MS = 500L
    const val GATT_STALE_MAX_MS = 30_000L

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

    // MARK: - Read cycle and history (the link keeps no history number: it reads from what it is given)

    /**
     * One logical operation of a read cycle. Every write it sends is [write] - the test checks each against
     * [ReadOnlyPolicy]; the link sends exactly this request (through [PumpGate]).
     */
    sealed class LinkOp {
        /** The write this operation sends to the pump; null - a characteristic read. */
        open fun write(): WriteRequest? = null

        data class Read(val service: String, val characteristic: String) : LinkOp() {
            override fun toString() = "read $characteristic"
        }

        data class StatusReader(val opcode: StatusReaderOpcode) : LinkOp() {
            override fun write() = WriteRequest(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, opcode.request)
            override fun toString() = "status reader $opcode"
        }

        /** RACP "first record" (33 66 0F): the oldest record on the pump. */
        object HistoryFirst : LinkOp() {
            override fun write() = WriteRequest(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, bytesOf(0x33, 0x66, 0x0F))
            override fun toString() = "history: first record"
        }

        /** RACP "last record" (33 69 0F): the newest record on the pump. */
        object HistoryLast : LinkOp() {
            override fun write() = WriteRequest(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, bytesOf(0x33, 0x69, 0x0F))
            override fun toString() = "history: last record"
        }

        /** RACP "records within range" by sequence number (33 5A 0F from to, both included). */
        data class HistoryRange(val from: Long, val to: Long) : LinkOp() {
            override fun write() =
                WriteRequest(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, bytesOf(0x33, 0x5A, 0x0F) + le32(from) + le32(to))
            override fun toString() = "history $from…$to"
        }

        val isHistory: Boolean get() = this is HistoryFirst || this is HistoryLast || this is HistoryRange
    }

    /**
     * The fixed part of a read cycle: status, active insulin, algorithm, basal, time in range (unless the pump said it
     * does not support it - [timeInRange] false), battery, IDD Features once per link ([features]), and - with
     * [history] - "first record" and "last record"; the pages follow from [historyPages] once both are known.
     */
    fun cycleOps(timeInRange: Boolean, features: Boolean, history: Boolean): List<LinkOp> = buildList {
        add(LinkOp.Read(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS))
        add(LinkOp.StatusReader(StatusReaderOpcode.GET_INSULIN_ON_BOARD))
        add(LinkOp.StatusReader(StatusReaderOpcode.GET_THERAPY_ALGORITHM_STATES))
        add(LinkOp.StatusReader(StatusReaderOpcode.GET_ACTIVE_BASAL_RATE_DELIVERY))
        if (timeInRange) add(LinkOp.StatusReader(StatusReaderOpcode.GET_TIME_IN_RANGE))
        // GST Battery Level (0x0400) is notify-only on the 780G ("Reading is not permitted").
        add(LinkOp.Read(PumpUuid.BATTERY_SERVICE, PumpUuid.BATTERY_LEVEL))
        if (features) add(LinkOp.Read(PumpUuid.IDD_SERVICE, PumpUuid.IDD_FEATURES))
        if (history) {
            add(LinkOp.HistoryFirst)
            add(LinkOp.HistoryLast)
        }
    }

    /**
     * Where the history read starts: [from] (asked of the history memory; null - from the oldest record), but never
     * before the oldest record on the pump ([oldest]); null - nothing to read: the pump has no records, [from] is past
     * the newest, or the start is not known (no answer to "first record" and no [from]).
     */
    fun historyStart(from: Long?, oldest: Long?, newest: Long?): Long? {
        if (newest == null || newest < 0) return null
        val start = when {
            from == null -> oldest ?: return null
            oldest != null && from < oldest -> oldest
            else -> from
        }
        return if (start > newest) null else start.coerceAtLeast(0)
    }

    /** Pages of at most [HISTORY_PAGE] records from [from] to [to], oldest first; no cap on the total. */
    fun historyPages(from: Long, to: Long): List<LinkOp.HistoryRange> {
        if (from > to) return emptyList()
        val pages = ArrayList<LinkOp.HistoryRange>(((to - from) / HISTORY_PAGE + 1).toInt())
        var start = from
        while (start <= to) {
            val end = minOf(to, start + HISTORY_PAGE - 1)
            pages += LinkOp.HistoryRange(start, end)
            start = end + 1
        }
        return pages
    }

    const val HISTORY_PAGE = 100L

    /** How a history read ended: all pages answered, an operation timed out, or the link / an answer failed. */
    enum class HistoryEnding { DONE, TIMED_OUT, FAILED }

    /**
     * The honest result of one history read. [received] - sequence numbers that came within the requested range.
     * [requestedFrom] null - nothing was asked (no records, nothing new): [HistoryReadStatus.COMPLETE] when the
     * answers came, otherwise the ending. `contiguousTo` - the last number up to which every number from
     * [requestedFrom] came; `missing` - every requested number that did not come (for a timed-out or failed read it
     * includes the part not read yet).
     */
    fun historyOutcome(
        ending: HistoryEnding, requestedFrom: Long?, requestedTo: Long?, oldest: Long?, newest: Long?, received: Collection<Long>,
    ): HistoryRead {
        val status = when (ending) {
            HistoryEnding.TIMED_OUT -> HistoryReadStatus.TIMED_OUT
            HistoryEnding.FAILED -> HistoryReadStatus.FAILED
            HistoryEnding.DONE -> HistoryReadStatus.COMPLETE
        }
        if (requestedFrom == null || requestedTo == null || requestedTo < requestedFrom) {
            return HistoryRead(status, oldestOnPump = oldest, newestOnPump = newest)
        }
        val got = received.filter { it in requestedFrom..requestedTo }.distinct().sorted()
        val missing = ArrayList<LongRange>()
        var next: Long = requestedFrom
        for (s in got) {
            if (s > next) missing += next until s
            next = s + 1
        }
        if (next <= requestedTo) missing += next..requestedTo
        val contiguous: Long? = when {
            missing.isEmpty() -> requestedTo
            missing.first().first == requestedFrom -> null
            else -> missing.first().first - 1
        }
        val finalStatus = if (status == HistoryReadStatus.COMPLETE && missing.isNotEmpty()) HistoryReadStatus.GAP else status
        return HistoryRead(finalStatus, requestedFrom, requestedTo, oldest, newest, contiguous, missing)
    }

    /** Records per type of one read, by their headers only (no values): "0x0069×2, 0xF00E×1". */
    fun typeCounts(records: Collection<RawHistoryRecord>): String =
        records.groupingBy { it.type }.eachCount().toSortedMap().entries.joinToString(", ") { "0x%04X×%d".format(it.key, it.value) }

    /** IDD RACP answer `0F 0F <request opcode> <code>`: the code; null - not such an answer. */
    fun racpCode(plain: ByteArray): Int? =
        if (plain.size >= 4 && plain[0].toInt() and 0xFF == 0x0F) plain[3].toInt() and 0xFF else null

    const val RACP_SUCCESS = 0xF0
    const val RACP_NO_RECORDS = 0x06

    /**
     * The request opcode a Status Reader answer belongs to: the answer opcode of a known request, or the request
     * opcode inside a "response code" answer (`03 03 <request> <code>`); null - neither. Only an answer to the running
     * request ends it (a late answer must not end the next one).
     */
    fun statusReaderAnswerFor(plain: ByteArray): Int? {
        if (plain.size < 2) return null
        val opcode = (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8)
        if (opcode == STATUS_READER_RESPONSE_CODE) {
            if (plain.size < 4) return null
            return (plain[2].toInt() and 0xFF) or ((plain[3].toInt() and 0xFF) shl 8)
        }
        return StatusReaderOpcode.entries.firstOrNull { it.responseOpcode == opcode }?.raw
    }

    /** A "response code" answer saying the request is not supported (`03 03 <request> 70`); the request or null. */
    fun statusReaderUnsupported(plain: ByteArray): StatusReaderOpcode? {
        if (plain.size < 5) return null
        val opcode = (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8)
        if (opcode != STATUS_READER_RESPONSE_CODE || plain[4].toInt() and 0xFF != OPCODE_NOT_SUPPORTED) return null
        return StatusReaderOpcode.of((plain[2].toInt() and 0xFF) or ((plain[3].toInt() and 0xFF) shl 8))
    }

    const val STATUS_READER_RESPONSE_CODE = 0x0303
    const val OPCODE_NOT_SUPPORTED = 0x70

    /** A string characteristic of the pump (serial, firmware, model): without NUL bytes and blanks at both ends. */
    fun pumpString(plain: ByteArray): String = String(plain, Charsets.UTF_8).trim { it == '\u0000' || it.isWhitespace() }

    // MARK: - No dead ends

    /**
     * Pause before advertising again after a failure (5 failed client attaches, advertising not started, session
     * lost, handshake over time, any error): 1 s, doubling, at most [RECOVER_MAX_MS]; [failures] - failures since the
     * last complete handshake (0 for the first). Advertising never stops for good.
     */
    fun recoverDelayMs(failures: Int): Long = minOf(RECOVER_MAX_MS, 1_000L shl failures.coerceIn(0, 16))

    const val RECOVER_MAX_MS = 60_000L

    /** The SAKE handshake from the pump's subscription to its end; past it the link is dropped. */
    const val HANDSHAKE_TIMEOUT_MS = 30_000L

    /**
     * Short wake hold of a handshake or read cycle: [WAKE_STEP_MS] from now, at most until [WAKE_MAX_MS] after the
     * start ([elapsedMs] since then); 0 - no more. Extended after every history page. No permanent hold.
     */
    fun wakeHoldMs(elapsedMs: Long): Long = if (elapsedMs >= WAKE_MAX_MS) 0 else minOf(WAKE_STEP_MS, WAKE_MAX_MS - elapsedMs)

    const val WAKE_STEP_MS = 30_000L
    const val WAKE_MAX_MS = 180_000L

    /**
     * A failed pairing (only recognised; advertising goes on): a bond of a device on our server or of the pump went
     * from "bonding" to "none", or - while advertising for pairing - a device left less than [PAIRING_DROP_MS] after
     * it connected without counting as the pump ([connectedForMs] null - its connection was not seen).
     */
    fun pairingFailedByBond(wasBonding: Boolean, nowNone: Boolean, ourDevice: Boolean): Boolean = wasBonding && nowNone && ourDevice

    fun pairingFailedByDrop(pairMode: Boolean, identified: Boolean, connectedForMs: Long?): Boolean =
        pairMode && !identified && connectedForMs != null && connectedForMs < PAIRING_DROP_MS

    const val PAIRING_DROP_MS = 5_000L

    /**
     * What a device gets for a request to our GATT server: only the device that is (or may become) the pump gets our
     * device information or a SAKE write accepted; another device gets a refusal and nothing else ([claim] - its
     * [Claim] at the request).
     */
    fun serves(event: ServerEvent, claim: Claim): Boolean = countsAsPump(event) && claim != Claim.IGNORE

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
