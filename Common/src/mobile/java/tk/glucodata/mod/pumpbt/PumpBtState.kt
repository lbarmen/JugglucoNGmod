package tk.glucodata.mod.pumpbt

/** The trial's stage, as the screen names it. */
internal enum class PumpBtStage {
    NOT_STARTED,
    ADVERTISING_PAIR,
    WAITING,
    PUMP_CONNECTED,
    PAIRING,
    HANDSHAKE,
    READING,
    DATA_READ,
    LOST,
    ERROR,
    STOPPED,
}

/** One pump history record for the screen: when (null - no reference time yet), what, value. */
internal data class PumpBtHistoryLine(val sequence: Long, val timeMs: Long?, val kind: String, val value: String)

/** What the trial read from the pump; null - not read. */
internal data class PumpBtValues(
    val readAtMs: Long? = null,
    val model: String? = null,
    val firmware: String? = null,
    val serial: String? = null,
    val iobUnits: Double? = null,
    val reservoirUnits: Double? = null,
    val batteryPercent: Int? = null,
    val basalUnitsPerHour: Double? = null,
    /** true once the active basal answer came: then "Temp basal: none" is known, not just unread. */
    val basalRead: Boolean = false,
    val tempBasalUnitsPerHour: Double? = null,
    val tempBasalRemainingMin: Int? = null,
    val history: List<PumpBtHistoryLine> = emptyList(),
)

/** One return of the pump: lost at (epoch ms), handshake complete again after (ms). */
internal data class PumpBtReturn(val lostAtMs: Long, val afterMs: Long)

/** State of the Bluetooth link to the pump, published by the link on the main thread. */
internal data class PumpBtLinkState(
    val running: Boolean = false,
    val pairMode: Boolean = false,
    val stage: PumpBtStage = PumpBtStage.NOT_STARTED,
    /** English detail of [PumpBtStage.ERROR] (journal words). */
    val error: String? = null,
    /** When the pump was lost (stage LOST and until it is back). */
    val lostAtMs: Long? = null,
    /** The last [PumpBtProgress.MAX_RETURNS] returns, newest first. */
    val returns: List<PumpBtReturn> = emptyList(),
    /** The last error (English, journal words) and when; kept until the next start, not replaced by "connection lost". */
    val lastProblem: String? = null,
    val lastProblemAtMs: Long? = null,
    /** The furthest stage reached since the start (see [PumpBtProgress.furthest]). */
    val furthestStage: PumpBtStage? = null,
    val values: PumpBtValues = PumpBtValues(),
)

/** How far a trial got and what came back: plain functions (tested without Android). */
internal object PumpBtProgress {
    const val MAX_RETURNS = 3

    /** Rank of a stage on the way to the data; 0 - not a step forward (not started, lost, error, stopped). */
    fun rank(stage: PumpBtStage): Int = when (stage) {
        PumpBtStage.ADVERTISING_PAIR, PumpBtStage.WAITING -> 1
        PumpBtStage.PAIRING -> 2
        PumpBtStage.PUMP_CONNECTED -> 3
        PumpBtStage.HANDSHAKE -> 4
        PumpBtStage.READING -> 5
        PumpBtStage.DATA_READ -> 6
        PumpBtStage.NOT_STARTED, PumpBtStage.LOST, PumpBtStage.ERROR, PumpBtStage.STOPPED -> 0
    }

    /** The furthest of [furthest] and [stage]; a later stage of the same rank replaces the earlier one. */
    fun furthest(furthest: PumpBtStage?, stage: PumpBtStage): PumpBtStage? {
        val r = rank(stage)
        if (r == 0) return furthest
        return if (furthest == null || r >= rank(furthest)) stage else furthest
    }

    /** [returns] with [new] in front, at most [MAX_RETURNS]. */
    fun addReturn(returns: List<PumpBtReturn>, new: PumpBtReturn): List<PumpBtReturn> = (listOf(new) + returns).take(MAX_RETURNS)
}

/** Why the trial cannot start. */
internal enum class PumpBtBlocker { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, NO_ADVERTISING, NO_KEY }

/** Everything the trial screen shows. */
internal data class PumpBtUiState(
    val link: PumpBtLinkState = PumpBtLinkState(),
    val hasBluetooth: Boolean = true,
    val bluetoothOn: Boolean = false,
    val permissionsGranted: Boolean = false,
    /** null - not known (Bluetooth off). */
    val advertisingSupported: Boolean? = null,
    val keyCrc: String? = null,
    val keyLoading: Boolean = false,
    val keyFailure: PumpBtKey.Failure? = null,
    val keyFailureCode: Int = 0,
    val variant: PumpBtNameVariant = PumpBtNameVariant.A,
    val advertisedName: String = "",
) {
    val blockers: List<PumpBtBlocker>
        get() = PumpBtReadiness.blockers(hasBluetooth, bluetoothOn, permissionsGranted, advertisingSupported, keyCrc != null)

    /** Start buttons: everything there, or only the permissions missing (the start asks for them first). */
    val canStart: Boolean get() = !link.running && blockers.all { it == PumpBtBlocker.NO_PERMISSION }
}

internal object PumpBtReadiness {
    fun blockers(hasBluetooth: Boolean, on: Boolean, permissions: Boolean, advertising: Boolean?, key: Boolean): List<PumpBtBlocker> =
        buildList {
            if (!hasBluetooth) add(PumpBtBlocker.NO_BLUETOOTH)
            else if (!on) add(PumpBtBlocker.BLUETOOTH_OFF)
            if (!permissions) add(PumpBtBlocker.NO_PERMISSION)
            if (hasBluetooth && on && advertising == false) add(PumpBtBlocker.NO_ADVERTISING)
            if (!key) add(PumpBtBlocker.NO_KEY)
        }
}
