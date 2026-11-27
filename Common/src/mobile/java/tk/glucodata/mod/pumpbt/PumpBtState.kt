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
    /** A temp basal set in percent (filled by the link later; null - none or given in units per hour). */
    val tempBasalPercent: Int? = null,
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
    /**
     * When a pairing failed last (bond "bonding → none", or a device left less than 5 s after connecting without
     * counting as the pump, while advertising for pairing); null - none since the start. Advertising goes on.
     */
    val pairingFailedAtMs: Long? = null,
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
    /** [PumpBtStore.paired]: the pump completed a pairing with this phone once (refreshed with every link state). */
    val paired: Boolean = false,
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

/** A counter of the history memory as the window lists it (plan B2b), in the window's order. */
internal enum class PumpBtCount { UNPARSED, GAPS, UNPAIRED_MEALS, NO_RESULT, FUTURE, HELD, UNDATED }

/** What the window lets the person press ([PumpBtWindow.controls]). */
internal data class PumpBtControls(
    /** The "Permanent operation" switch may be turned on (turning off is always possible). */
    val canTurnOn: Boolean,
    /** The trial's own buttons (start pairing, wait, stop) and the name variant are shown: only with the source off. */
    val trialButtons: Boolean,
    val canStart: Boolean,
    val canStop: Boolean,
    val canReadAgain: Boolean,
    val variantEditable: Boolean,
    /** With the source on: a pairing button (the pump never paired, or the phone forgot it). */
    val pairOffered: Boolean,
    val canPair: Boolean,
)

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2b): what the trial window shows of [PumpBtSourceState] and
 * which buttons it allows. Pure functions (tested without Android).
 *
 * With "Permanent operation" on, the trial's Start and Stop are not offered: Stop would end the link and the watchdog
 * would start it again - the link stops only by turning permanent operation off.
 */
internal object PumpBtWindow {

    /** Stages at which the pump is connected and the handshake went through: the bond works. */
    private val LINK_UP = setOf(PumpBtStage.HANDSHAKE, PumpBtStage.READING, PumpBtStage.DATA_READ)

    private val WARNING_ORDER = listOf(
        PumpWarning.CLOCK_DRIFT, PumpWarning.FUTURE_EVENT, PumpWarning.NO_RESULT_BOLUS, PumpWarning.HELD_BOLUS,
        PumpWarning.UNPAIRED_MEAL,
    )

    fun linkUp(link: PumpBtLinkState): Boolean = link.running && link.stage in LINK_UP

    /** Everything there for a start, or only the permissions missing (the press asks for them first). */
    fun ready(ui: PumpBtUiState): Boolean = ui.blockers.all { it == PumpBtBlocker.NO_PERMISSION }

    fun controls(ui: PumpBtUiState, source: PumpBtSourceState): PumpBtControls {
        val on = source.enabled
        val link = source.link
        val ready = ready(ui)
        val pairOffered = on && !link.pairMode && !linkUp(link) && (source.bondLost || !ui.paired)
        return PumpBtControls(
            canTurnOn = !on && ready,
            trialButtons = !on,
            canStart = !on && !link.running && ready,
            canStop = !on && link.running,
            canReadAgain = link.running,
            variantEditable = !on && !link.running,
            pairOffered = pairOffered,
            canPair = pairOffered && ready,
        )
    }

    /** "The phone forgot the pump": with the source on, until the pump is linked again. */
    fun bondAdvice(source: PumpBtSourceState): Boolean = source.enabled && source.bondLost && !linkUp(source.link)

    /** Decision 12: a pairing failed and the phone still advertises for pairing. */
    fun pairingAdvice(link: PumpBtLinkState): Boolean = link.running && link.pairMode && link.pairingFailedAtMs != null

    /** The non-zero counters, in the window's order. */
    fun counts(c: PumpHistoryCounters): List<Pair<PumpBtCount, Long>> = listOf(
        PumpBtCount.UNPARSED to c.unparsed.toLong(),
        PumpBtCount.GAPS to c.gaps,
        PumpBtCount.UNPAIRED_MEALS to c.unpairedMeals.toLong(),
        PumpBtCount.NO_RESULT to c.noResultBoluses.toLong(),
        PumpBtCount.FUTURE to c.futureEvents.toLong(),
        PumpBtCount.HELD to c.heldBoluses.toLong(),
        PumpBtCount.UNDATED to c.undated.toLong(),
    ).filter { it.second > 0 }

    /** The warnings in a fixed order. */
    fun warnings(w: Set<PumpWarning>): List<PumpWarning> = WARNING_ORDER.filter { it in w }

    /** The card's list as the window shows it: by time. */
    fun cardItems(card: BackfillCard): List<BackfillItem> = card.toWrite.sortedWith(compareBy({ it.atMs }, { it.key }))

    /** "Write" passes the keys of exactly the list shown (decision 5). */
    fun shownKeys(items: List<BackfillItem>): List<String> = items.map { it.key }

    /** The data section (last reading, memory, counters): with the source on, or while it holds anything. */
    fun dataShown(source: PumpBtSourceState): Boolean =
        source.enabled || source.records > 0 || source.card != null || source.lastCycleAtMs != null
}
