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
    /**
     * A temporary basal runs, but the window cannot show its value: the IDD type when it is neither 0x33 (U/h) nor
     * 0x3C (percent), or the value is special (no number); null - no temporary basal, or one shown in its unit.
     */
    val tempBasalType: Int? = null,
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

/**
 * Why the trial cannot start. [NO_KEY] no longer stops "Start pairing" (the press downloads the key first, series
 * brief, decision 11; [PumpBtWindow.ready]); it still stops turning "Permanent operation" on ([PumpBtWindow.turnOnHint]).
 */
internal enum class PumpBtBlocker { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, NO_ADVERTISING, NO_KEY }

/** The pairing wizard's step ([PumpBtWindow.wizardStep]); the window shows the wizard above everything else. */
internal enum class PumpBtWizardStep {
    /** The warnings before the first pairing are not accepted yet (decision 14). */
    WARNINGS,

    /** The phone is not ready: permissions, no Bluetooth, Bluetooth off, or no advertising. */
    PHONE,

    /** Everything there: "Start pairing". */
    START,

    /** "Start pairing" was pressed without a key: the key database is downloading, or it did not come ("Load from file"). */
    KEY,

    /** A pairing goes on (a failed one - [PumpBtWindow.pairingAdvice]; variant B as a fallback - [PumpBtTrial.tryOtherName]). */
    PAIRING,

    /** Paired, the source is off: offer "Permanent operation". */
    PAIRED,

    /** The source is on: no wizard ("Pair again" lives in the lost-bond card and in the diagnostics). */
    NONE,
}

/** What a press of "Start pairing" (or the trial's "Wait for the pump") does ([PumpBtWindow.startAction]). */
internal enum class PumpBtStartAction {
    /** The phone is not ready (permissions are asked before the press): nothing. */
    REFUSE,

    /** No key database: download it first ([PumpBtKey.downloadChain]), then start. */
    LOAD_KEY,
    START,
}

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
    /**
     * When this window last started a pairing (process memory; only the window starts one): the wait time counts from
     * it, and a failed pairing older than it is not this pairing's (a running link keeps its failure mark). null - none.
     */
    val pairingStartedAtMs: Long? = null,
    /**
     * The person accepted the warnings before the first pairing ([PumpBtStore.warningsAccepted]), or the pump paired
     * with this phone once ([PumpBtStore.paired]: an owner never sees them).
     */
    val warningsAccepted: Boolean = false,
    /**
     * A start pressed without a key database, waiting for it: true - a pairing, false - waiting for the pump; null -
     * none. The start goes on once a key is in place (downloaded, or from a file).
     */
    val pendingStart: Boolean? = null,
) {
    val blockers: List<PumpBtBlocker>
        get() = PumpBtReadiness.blockers(hasBluetooth, bluetoothOn, permissionsGranted, advertisingSupported, keyCrc != null)

    /**
     * The trial's old rule (a key needed to start). The window's buttons follow [PumpBtWindow.controls], where the key
     * no longer stops a start (decision 11).
     */
    val canStart: Boolean get() = !link.running && blockers.all { it == PumpBtBlocker.NO_PERMISSION }

    /** The wizard's KEY step: a start waits for the key database ([pendingStart]). */
    val keyStep: Boolean get() = pendingStart != null
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

/** Why "Permanent operation" cannot be turned on now: the first thing to do ([PumpBtWindow.turnOnHint]). */
internal enum class PumpBtTurnOnHint { NO_BLUETOOTH, BLUETOOTH_OFF, NO_ADVERTISING, NO_KEY, PAIRING }

/** What became of the card's last answer ([PumpBtWindow.answer]). */
internal enum class PumpBtAnswer {
    /** Nothing pressed (or the answer was taken: the card is gone). */
    NONE,
    /** Pressed, the source has not answered yet: both buttons wait. */
    WAITING,
    /** "Write" was not taken: the source's list changed before it got there (it shows the card anew). */
    REJECTED,
    /** No answer within [PumpBtWindow.ANSWER_WAIT_MS] (a failed save, or the source busy): the buttons work again. */
    NOT_TAKEN,
}

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
    /** The pairing button is pressable: everything there and no pairing going on. */
    val canPair: Boolean,
    /**
     * With the source on and a pairing going on: "Cancel pairing" - back to waiting for the paired pump (critic of the
     * joints 2, N-4: a pairing the pump never starts would otherwise advertise for pairing until permanent operation is
     * turned off).
     */
    val canCancelPairing: Boolean,
)

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2b): what the trial window shows of [PumpBtSourceState] and
 * which buttons it allows. Pure functions (tested without Android).
 *
 * With "Permanent operation" on, the trial's Start and Stop are not offered: Stop would end the link and the watchdog
 * would start it again - the link stops only by turning permanent operation off; a pairing is left by "Cancel pairing".
 */
internal object PumpBtWindow {

    /** How long the card's buttons wait for the source's answer before they work again (critic B2b, N2). */
    const val ANSWER_WAIT_MS = 10_000L

    /** Stages at which the pump is connected and the handshake went through: the bond works. */
    private val LINK_UP = setOf(PumpBtStage.HANDSHAKE, PumpBtStage.READING, PumpBtStage.DATA_READ)

    /**
     * Stages that only a pairing has: a running link switched to pairing ("Pair again" with the source on) does not
     * publish its pair mode, but its stage shows it (critic B2b, B1).
     */
    private val PAIRING_STAGES = setOf(PumpBtStage.ADVERTISING_PAIR, PumpBtStage.PAIRING)

    /** Stages at which the phone waits for the pump to come for the pairing (the wait time is shown). */
    private val PAIR_WAIT_STAGES = setOf(PumpBtStage.ADVERTISING_PAIR, PumpBtStage.WAITING)

    private val WARNING_ORDER = listOf(
        PumpWarning.CLOCK_DRIFT, PumpWarning.FUTURE_EVENT, PumpWarning.NO_RESULT_BOLUS, PumpWarning.HELD_BOLUS,
        PumpWarning.UNPAIRED_MEAL,
    )

    fun linkUp(link: PumpBtLinkState): Boolean = link.running && link.stage in LINK_UP

    /**
     * A pairing is going on: the published pair mode (a link started for pairing) or a stage only a pairing has (a
     * running link switched to pairing keeps `pairMode` false in its state). Everything in the window that depends on
     * a pairing asks this.
     */
    fun pairing(link: PumpBtLinkState): Boolean = link.running && (link.pairMode || link.stage in PAIRING_STAGES)

    /** What the phone lacks for a start: the blockers without the key (the press downloads it first, decision 11). */
    fun phoneBlockers(ui: PumpBtUiState): List<PumpBtBlocker> = ui.blockers.filter { it != PumpBtBlocker.NO_KEY }

    /**
     * Everything there for a start, or only the permissions missing (the press asks for them first). The key is not
     * needed: the press downloads it first (decision 11).
     */
    fun ready(ui: PumpBtUiState): Boolean = phoneBlockers(ui).all { it == PumpBtBlocker.NO_PERMISSION }

    /** What a start does when pressed (the permissions were asked before): refused, the key first, or the start. */
    fun startAction(ui: PumpBtUiState, hasKey: Boolean): PumpBtStartAction = when {
        phoneBlockers(ui).isNotEmpty() -> PumpBtStartAction.REFUSE
        !hasKey -> PumpBtStartAction.LOAD_KEY
        else -> PumpBtStartAction.START
    }

    /**
     * The pairing wizard's step. With the source on - none. The warnings first, unless accepted or the pump paired
     * once (read from the store at once: an owner sees no wizard flashing while the source starts). Then a pairing
     * going on, a start waiting for the key, paired (offer "Permanent operation"), the phone not ready, the start.
     */
    fun wizardStep(ui: PumpBtUiState, source: PumpBtSourceState): PumpBtWizardStep = when {
        source.enabled -> PumpBtWizardStep.NONE
        !ui.warningsAccepted && !ui.paired -> PumpBtWizardStep.WARNINGS
        pairing(source.link) -> PumpBtWizardStep.PAIRING
        ui.keyStep -> PumpBtWizardStep.KEY
        ui.paired -> PumpBtWizardStep.PAIRED
        phoneBlockers(ui).isNotEmpty() -> PumpBtWizardStep.PHONE
        else -> PumpBtWizardStep.START
    }

    /**
     * Name variant after a link state: B is only a fallback for one pairing (series brief, finding 11) - once the
     * handshake went through, back to A.
     */
    fun variantAfter(variant: PumpBtNameVariant, link: PumpBtLinkState): PumpBtNameVariant =
        if (variant == PumpBtNameVariant.B && linkUp(link)) PumpBtNameVariant.A else variant

    /**
     * Why "Permanent operation" cannot be turned on now - the first thing to do; null - it can (or it is on). A pairing
     * of the trial must end or be stopped first (critic B2b, C1): turning on would leave the phone waiting for a pump
     * that is being paired.
     */
    fun turnOnHint(ui: PumpBtUiState, source: PumpBtSourceState): PumpBtTurnOnHint? {
        if (source.enabled) return null
        when (ui.blockers.firstOrNull { it != PumpBtBlocker.NO_PERMISSION }) {
            PumpBtBlocker.NO_BLUETOOTH -> return PumpBtTurnOnHint.NO_BLUETOOTH
            PumpBtBlocker.BLUETOOTH_OFF -> return PumpBtTurnOnHint.BLUETOOTH_OFF
            PumpBtBlocker.NO_ADVERTISING -> return PumpBtTurnOnHint.NO_ADVERTISING
            PumpBtBlocker.NO_KEY -> return PumpBtTurnOnHint.NO_KEY
            PumpBtBlocker.NO_PERMISSION, null -> {}
        }
        return if (pairing(source.link)) PumpBtTurnOnHint.PAIRING else null
    }

    fun controls(ui: PumpBtUiState, source: PumpBtSourceState): PumpBtControls {
        val on = source.enabled
        val link = source.link
        val ready = ready(ui)
        // shown during a pairing too, but not pressable then
        val pairOffered = on && (source.bondLost || (!ui.paired && !linkUp(link)))
        return PumpBtControls(
            canTurnOn = !on && turnOnHint(ui, source) == null,
            trialButtons = !on,
            canStart = !on && !link.running && ready,
            canStop = !on && link.running,
            canReadAgain = link.running,
            variantEditable = !on && !link.running,
            pairOffered = pairOffered,
            canPair = pairOffered && ready && !pairing(link),
            canCancelPairing = on && pairing(link),
        )
    }

    /**
     * "The phone forgot the pump": with the source on, by the source's mark only (it is cleared by every complete
     * handshake and checked again when the window comes back or a bond changes) - no guess from the link's stage.
     */
    fun bondAdvice(source: PumpBtSourceState): Boolean = source.enabled && source.bondLost

    /**
     * Decision 12: a pairing failed and the phone still advertises for pairing. A running link switched to pairing
     * keeps the failure mark of an earlier pairing: only a failure since [pairingSinceMs] (when the window started
     * this pairing) counts; without it, only with the published pair mode (a fresh start cleared the mark).
     */
    fun pairingAdvice(link: PumpBtLinkState, pairingSinceMs: Long?): Boolean {
        if (!pairing(link)) return false
        val failedAt = link.pairingFailedAtMs ?: return false
        return if (pairingSinceMs != null) failedAt >= pairingSinceMs else link.pairMode
    }

    /** Decision 12: the phone waits for the pump to pair - the wait time is shown. */
    fun pairWaitShown(link: PumpBtLinkState): Boolean = pairing(link) && link.stage in PAIR_WAIT_STAGES

    /**
     * The warning before "Share": while the journal file holds detailed lines (the source's mark, until the journal is
     * cleared), not only while the detailed journal is on (critic B2b, C2).
     */
    fun shareWarning(source: PumpBtSourceState): Boolean = source.journalHasDetail || source.verbose

    /** The first catch-up card: only with the source on (turning it on again starts a new catch-up; critic B2b, N1). */
    fun card(source: PumpBtSourceState): BackfillCard? = source.card?.takeIf { source.enabled }

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

    /**
     * The source's card no longer lists what the window shows: the window keeps the shown list until the person asks
     * for the new one (critic B2b, C3).
     */
    fun listChanged(shown: List<BackfillItem>, current: List<BackfillItem>): Boolean = shown != current

    /** "Write" is pressable: for the list shown only, while it is still the source's list, and no answer pending. */
    fun canWrite(shown: List<BackfillItem>, current: List<BackfillItem>, answer: PumpBtAnswer): Boolean =
        !listChanged(shown, current) && answer != PumpBtAnswer.WAITING

    /**
     * What became of the card's answer pressed at [pendingAtMs] (null - none pending): [writeKeys] - the keys "Write"
     * passed (null - "Do not write old ones"). The source takes "Write" only for its own list; another list - it shows
     * the card anew, so the card's keys differ from those passed: [PumpBtAnswer.REJECTED]. A taken answer ends the card
     * (the window's card section is gone). No answer within [ANSWER_WAIT_MS] - [PumpBtAnswer.NOT_TAKEN].
     */
    fun answer(pendingAtMs: Long?, writeKeys: List<String>?, current: List<BackfillItem>, nowMs: Long): PumpBtAnswer {
        if (pendingAtMs == null) return PumpBtAnswer.NONE
        if (writeKeys != null && writeKeys.toSet() != shownKeys(current).toSet()) return PumpBtAnswer.REJECTED
        return if (nowMs - pendingAtMs >= ANSWER_WAIT_MS) PumpBtAnswer.NOT_TAKEN else PumpBtAnswer.WAITING
    }

    /** The shown list in a form the window's saved state keeps (a turned screen): keys, times, doses, grams (NaN - none). */
    fun packItems(items: List<BackfillItem>): List<Any> = listOf(
        ArrayList(items.map { it.key }),
        LongArray(items.size) { items[it].atMs },
        DoubleArray(items.size) { items[it].units ?: Double.NaN },
        DoubleArray(items.size) { items[it].grams ?: Double.NaN },
    )

    /** [packItems] back; anything else - null. */
    fun unpackItems(packed: List<Any?>): List<BackfillItem>? {
        if (packed.size != 4) return null
        val keys = (packed[0] as? List<*>)?.map { it as? String ?: return null } ?: return null
        val at = packed[1] as? LongArray ?: return null
        val units = packed[2] as? DoubleArray ?: return null
        val grams = packed[3] as? DoubleArray ?: return null
        if (at.size != keys.size || units.size != keys.size || grams.size != keys.size) return null
        return keys.indices.map { i ->
            BackfillItem(keys[i], at[i], units[i].takeUnless { it.isNaN() }, grams[i].takeUnless { it.isNaN() })
        }
    }

    /** The data section (last reading, memory, counters): with the source on, or while it holds anything. */
    fun dataShown(source: PumpBtSourceState): Boolean =
        source.enabled || source.records > 0 || source.card != null || source.lastCycleAtMs != null
}
