package tk.glucodata.mod.pumpbt

import tk.glucodata.mod.carelink.PumpNow

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
    /** Another app's GATT server lies in front of ours - no advertisement for the paired pump (build 17); null - nothing in front. */
    val front: PumpBtFront? = null,
    /** When Android last removed the pump's bond right after it hung on another app's server; phone clock; null - not since the start. */
    val heldBondLossAtMs: Long? = null,
)

/**
 * Another app's GATT server lies in front of ours (build 17): since [sinceMs] (phone clock), our SAKE at [ourHandle],
 * the first app service handle [firstAppHandle] - services from there up to ours belong to other apps.
 */
internal data class PumpBtFront(val sinceMs: Long, val ourHandle: Int, val firstAppHandle: Int)

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
 * What the phone lacks for a start. The key database is not among them: "Start pairing" downloads it first (series
 * brief, decision 11); a missing key stops only turning "Permanent operation" on ([PumpBtWindow.turnOnHint]).
 */
internal enum class PumpBtBlocker { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, NO_ADVERTISING }

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
    /**
     * When this window saw the phone's Bluetooth come back on (it was off at an earlier check); null - not seen. The
     * wait for the pump is counted from it at the latest ([PumpBtWindow.waitShownSince]; critic G2, N-8).
     */
    val bluetoothBackAtMs: Long? = null,
) {
    val blockers: List<PumpBtBlocker>
        get() = PumpBtReadiness.blockers(hasBluetooth, bluetoothOn, permissionsGranted, advertisingSupported)

    /** The wizard's KEY step: a start waits for the key database ([pendingStart]). */
    val keyStep: Boolean get() = pendingStart != null
}

internal object PumpBtReadiness {
    fun blockers(hasBluetooth: Boolean, on: Boolean, permissions: Boolean, advertising: Boolean?): List<PumpBtBlocker> =
        buildList {
            if (!hasBluetooth) add(PumpBtBlocker.NO_BLUETOOTH)
            else if (!on) add(PumpBtBlocker.BLUETOOTH_OFF)
            if (!permissions) add(PumpBtBlocker.NO_PERMISSION)
            if (hasBluetooth && on && advertising == false) add(PumpBtBlocker.NO_ADVERTISING)
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
    /**
     * "Pair again" of the diagnostics and of the wizard's PAIRED step (critic G1, N5: with the source off too):
     * everything there and no pairing going on.
     */
    val canPairAgain: Boolean,
)

/**
 * The pump's active insulin as the window shows it ([PumpBtWindow.iobShown]): [units] null - older than
 * [PumpBtWindow.IOB_FRESH_MS], not shown as a number; [ownAge] - its own time ([atMs]) is shown next to it.
 */
internal data class PumpBtIob(val units: Double?, val atMs: Long, val ownAge: Boolean)

/** The link line of the window's state section ([PumpBtWindow.linkStatus]). */
internal enum class PumpBtLinkStatus {
    /** The phone's Bluetooth is off (or there is none): nothing can come. */
    BLUETOOTH_OFF,

    /** The pump is connected (from the connection to the read data). */
    CONNECTED,

    /** The link runs and waits for the pump to come (the time is shown, [PumpBtWindow.waitingSince]). */
    WAITING,
    PAIRING,
    ERROR,

    /** The link does not run. */
    OFF,
}

/** A button of the pairing wizard ([PumpBtWindow.wizardButtons]). */
internal enum class PumpBtWizardButton {
    /** WARNINGS: "Got it". */
    ACCEPT,

    /** PHONE: the Bluetooth permissions. */
    ALLOW,

    /** START: "Start pairing" (the key database is downloaded first when there is none). */
    START,

    /** KEY: the start again (the download again). */
    RETRY,

    /** KEY: the start waiting for the key no longer goes on ([PumpBtTrial.cancelPendingStart]). */
    CANCEL,

    /** KEY, after a failed download: the key database from a file. */
    LOAD_FILE,

    /** PAIRING, after a failed pairing with variant A: the other name ([PumpBtTrial.tryOtherName]). */
    OTHER_NAME,

    /** PAIRING: the pairing ends ([PumpBtTrial.stop]). */
    STOP,

    /** PAIRED: "Permanent operation" (with its confirmation). */
    TURN_ON,

    /** PAIRED: "Pair again" (critic G1, N5). */
    PAIR_AGAIN,
}

/** A text line of the pairing wizard ([PumpBtWindow.wizardLines]), in the order shown. */
internal enum class PumpBtWizardLine {
    // WARNINGS (decision 14)
    FAMILY,
    NOT_MEDICAL,
    UNPAIR_MINIMED_LOSES,
    NOT_CHECKED,
    GO_BACK,

    // PHONE
    NO_BLUETOOTH,
    BLUETOOTH_OFF,
    NO_PERMISSION,
    NO_ADVERTISING,

    // START, and the pump steps again during PAIRING
    STOP_MINIMED,
    UNPAIR_ON_PUMP,
    PAIR_ON_PUMP,
    CONFIRM_ON_PHONE,
    KEY_NEEDS_INTERNET,

    // KEY
    KEY_LOADING,
    KEY_FAILED,

    // PAIRING
    /** Another app's GATT server lies in front of ours: turn Bluetooth off and on before pairing (build 17). */
    FRONT_BLOCKS_PAIRING,
    PAIR_WAIT,
    PAIRING_FAILED,
    OTHER_NAME_HINT,
    OTHER_NAME_ON,

    // PAIRED
    PAIRED,
    TURN_ON_HINT,
}

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

    /** How often the window's clock (data ages, the wait for the pump) moves while the window is on screen (critic G2, C-1). */
    const val CLOCK_TICK_MS = 15_000L

    /**
     * The pump's active insulin older than this is not shown as a number: the IoB plate takes a pump number as fresh for
     * as long (`ApiIobSnapshot.FRESHNESS_WINDOW_MS`, [tk.glucodata.mod.carelink.CareLinkIob]; critic G2, C-2).
     */
    const val IOB_FRESH_MS = 30L * 60_000L

    /** The active insulin's own time is shown when it is this much older than the data time of the snapshot. */
    const val IOB_OWN_AGE_MS = 5L * 60_000L

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

    /** What the phone lacks for a start (the key is not needed: the press downloads it first, decision 11). */
    fun phoneBlockers(ui: PumpBtUiState): List<PumpBtBlocker> = ui.blockers

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
            PumpBtBlocker.NO_PERMISSION, null -> {}
        }
        // without the key the source would not get through the handshake (critic of the briefs, N-4)
        if (ui.keyCrc == null) return PumpBtTurnOnHint.NO_KEY
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
            canPairAgain = ready && !pairing(link),
        )
    }

    /** Stages at which a running link waits for the pump (not connected, not pairing). */
    private val WAITING_STAGES = setOf(PumpBtStage.NOT_STARTED, PumpBtStage.WAITING, PumpBtStage.LOST)

    private val CONNECTED_STAGES = setOf(PumpBtStage.PUMP_CONNECTED, PumpBtStage.HANDSHAKE, PumpBtStage.READING, PumpBtStage.DATA_READ)

    /**
     * Since when the link waits for the pump to come (series brief, decision 12; journal of build 15: after turning
     * permanent operation on the pump came only after 4 min, and the person removed the pairing): [prev] - the mark so
     * far; a running link not pairing and not connected keeps the earliest of [prev] and the time the pump was lost,
     * else [nowMs]; anything else - null. The source keeps it with every link state ([PumpBtSourceState.waitingSinceMs]).
     */
    fun waitingSince(prev: Long?, link: PumpBtLinkState, nowMs: Long): Long? {
        if (!link.running || pairing(link) || link.stage !in WAITING_STAGES) return null
        return listOfNotNull(prev, link.lostAtMs).minOrNull() ?: nowMs
    }

    /** Whole minutes waited since [sinceMs] (never below 0). */
    fun waitingMinutes(sinceMs: Long, nowMs: Long): Int = ((nowMs - sinceMs).coerceAtLeast(0L) / 60_000L).toInt()

    /** The link line of the state section: Bluetooth off first (nothing can come), then the link's own state. */
    fun linkStatus(ui: PumpBtUiState, source: PumpBtSourceState): PumpBtLinkStatus {
        val link = source.link
        return when {
            !ui.hasBluetooth || !ui.bluetoothOn -> PumpBtLinkStatus.BLUETOOTH_OFF
            !link.running -> PumpBtLinkStatus.OFF
            pairing(link) -> PumpBtLinkStatus.PAIRING
            link.stage == PumpBtStage.ERROR -> PumpBtLinkStatus.ERROR
            link.stage in CONNECTED_STAGES -> PumpBtLinkStatus.CONNECTED
            else -> PumpBtLinkStatus.WAITING
        }
    }

    /** The screen stays on only while a pairing goes on (plan G2b, point 1): not while the window is merely open. */
    fun keepScreenOn(source: PumpBtSourceState): Boolean = pairing(source.link)

    /** Variant B is offered only after a failed pairing with A, with the source off (finding 11; critic G1, N1). */
    fun otherNameOffered(ui: PumpBtUiState, source: PumpBtSourceState): Boolean =
        !source.enabled && ui.variant == PumpBtNameVariant.A && pairingAdvice(source.link, ui.pairingStartedAtMs)

    /** The buttons of a wizard step, in the order shown. */
    fun wizardButtons(step: PumpBtWizardStep, ui: PumpBtUiState, source: PumpBtSourceState): List<PumpBtWizardButton> =
        when (step) {
            PumpBtWizardStep.WARNINGS -> listOf(PumpBtWizardButton.ACCEPT)
            PumpBtWizardStep.PHONE -> if (!ui.permissionsGranted) listOf(PumpBtWizardButton.ALLOW) else emptyList()
            PumpBtWizardStep.START -> listOf(PumpBtWizardButton.START)
            PumpBtWizardStep.KEY -> if (ui.keyLoading) {
                listOf(PumpBtWizardButton.CANCEL)
            } else {
                listOfNotNull(
                    PumpBtWizardButton.RETRY,
                    PumpBtWizardButton.LOAD_FILE.takeIf { ui.keyFailure != null },
                    PumpBtWizardButton.CANCEL,
                )
            }
            PumpBtWizardStep.PAIRING -> listOfNotNull(
                PumpBtWizardButton.OTHER_NAME.takeIf { otherNameOffered(ui, source) },
                PumpBtWizardButton.STOP,
            )
            PumpBtWizardStep.PAIRED -> listOf(PumpBtWizardButton.TURN_ON, PumpBtWizardButton.PAIR_AGAIN)
            PumpBtWizardStep.NONE -> emptyList()
        }

    /** The text lines of a wizard step, in the order shown. */
    fun wizardLines(step: PumpBtWizardStep, ui: PumpBtUiState, source: PumpBtSourceState): List<PumpBtWizardLine> =
        when (step) {
            PumpBtWizardStep.WARNINGS -> listOf(
                PumpBtWizardLine.FAMILY, PumpBtWizardLine.NOT_MEDICAL, PumpBtWizardLine.UNPAIR_MINIMED_LOSES,
                PumpBtWizardLine.NOT_CHECKED, PumpBtWizardLine.GO_BACK,
            )
            PumpBtWizardStep.PHONE -> phoneBlockers(ui).map {
                when (it) {
                    PumpBtBlocker.NO_BLUETOOTH -> PumpBtWizardLine.NO_BLUETOOTH
                    PumpBtBlocker.BLUETOOTH_OFF -> PumpBtWizardLine.BLUETOOTH_OFF
                    PumpBtBlocker.NO_PERMISSION -> PumpBtWizardLine.NO_PERMISSION
                    PumpBtBlocker.NO_ADVERTISING -> PumpBtWizardLine.NO_ADVERTISING
                }
            }
            PumpBtWizardStep.START -> listOfNotNull(
                PumpBtWizardLine.STOP_MINIMED, PumpBtWizardLine.UNPAIR_ON_PUMP, PumpBtWizardLine.PAIR_ON_PUMP,
                PumpBtWizardLine.CONFIRM_ON_PHONE, PumpBtWizardLine.KEY_NEEDS_INTERNET.takeIf { ui.keyCrc == null },
            )
            PumpBtWizardStep.KEY ->
                listOf(if (ui.keyLoading || ui.keyFailure == null) PumpBtWizardLine.KEY_LOADING else PumpBtWizardLine.KEY_FAILED)
            PumpBtWizardStep.PAIRING -> {
                val failed = pairingAdvice(source.link, ui.pairingStartedAtMs)
                listOfNotNull(
                    PumpBtWizardLine.FRONT_BLOCKS_PAIRING.takeIf { source.link.running && source.link.front != null },
                    PumpBtWizardLine.PAIR_WAIT.takeIf { pairWaitShown(source.link) },
                    // the failure advice repeats the pump and phone steps itself
                    PumpBtWizardLine.PAIR_ON_PUMP.takeIf { !failed },
                    PumpBtWizardLine.CONFIRM_ON_PHONE.takeIf { !failed },
                    PumpBtWizardLine.PAIRING_FAILED.takeIf { failed },
                    PumpBtWizardLine.OTHER_NAME_HINT.takeIf { otherNameOffered(ui, source) },
                    PumpBtWizardLine.OTHER_NAME_ON.takeIf { ui.variant == PumpBtNameVariant.B },
                )
            }
            PumpBtWizardStep.PAIRED -> listOfNotNull(
                PumpBtWizardLine.PAIRED,
                PumpBtWizardLine.TURN_ON_HINT.takeIf { turnOnHint(ui, source) != null },
            )
            PumpBtWizardStep.NONE -> emptyList()
        }

    /**
     * The card "the pump connection is paused" (build 17): another app's GATT server lies in front of ours and the
     * paired pump is not called. Not during a pairing - the wizard's PAIRING line says it there.
     */
    fun frontCard(source: PumpBtSourceState): Boolean =
        source.link.running && source.link.front != null && !source.link.pairMode && !pairing(source.link)

    /** The lost-bond card adds that the bond went because of another app's server (Android removed it right after the pump hung). */
    fun heldBondLoss(source: PumpBtSourceState): Boolean = source.link.heldBondLossAtMs != null

    /** An app whose GATT server held the pump is installed (OMRON connect): the window gives a hint. */
    fun foreignAppHint(source: PumpBtSourceState): Boolean = source.foreignApps.isNotEmpty()

    /** The state section (link, last reading, permanent operation, cards): once paired, or with the source on. */
    fun stateShown(step: PumpBtWizardStep): Boolean = step == PumpBtWizardStep.NONE || step == PumpBtWizardStep.PAIRED

    /**
     * "Pair again" with the source on (the lost-bond card, the diagnostics) goes on: the window shows the wizard's
     * PAIRING lines there - the wait, the pump steps, the advice after a failed one - and "Cancel pairing" (critic G2, C-4).
     */
    fun pairAgainShown(source: PumpBtSourceState): Boolean = source.enabled && pairing(source.link)

    /**
     * Since when the window counts the wait for the pump: the source's mark, but not before Bluetooth came back on
     * (the time with Bluetooth off is not waiting; critic G2, N-8); null - not waiting.
     */
    fun waitShownSince(waitingSinceMs: Long?, bluetoothBackAtMs: Long?): Long? {
        val since = waitingSinceMs ?: return null
        return maxOf(since, bluetoothBackAtMs ?: since)
    }

    /**
     * The pump's active insulin as the window shows it (critic G2, C-2: a snapshot keeps an older number with its own
     * time when a cycle brought none): null - none in the snapshot. Its time is `iobAtMs` (none - the data time).
     */
    fun iobShown(pump: PumpNow, nowMs: Long): PumpBtIob? {
        val units = pump.iobUnits?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val at = pump.iobAtMs ?: pump.dataAtMs
        if (nowMs - at > IOB_FRESH_MS) return PumpBtIob(units = null, atMs = at, ownAge = true)
        return PumpBtIob(units, at, ownAge = pump.dataAtMs - at > IOB_OWN_AGE_MS)
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
