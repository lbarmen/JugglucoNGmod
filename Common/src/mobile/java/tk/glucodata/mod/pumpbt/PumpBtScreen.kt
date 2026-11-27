package tk.glucodata.mod.pumpbt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tk.glucodata.R
import tk.glucodata.mod.carelink.IntakeOutcome
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the pump window asks of its activity. */
internal class PumpBtScreenActions(
    val onBack: () -> Unit,
    val onAllow: () -> Unit,
    /** The wizard's warnings: "Got it" ([PumpBtTrial.acceptWarnings]). */
    val onAcceptWarnings: () -> Unit,
    /** "Start pairing", "Retry" of the KEY step and "Pair again" (with the source on a running link switches to pairing). */
    val onStartPairing: () -> Unit,
    val onWaitForPump: () -> Unit,
    /** With the source on, during a pairing: back to waiting for the paired pump ([PumpBtSource.cancelPairing]). */
    val onCancelPairing: () -> Unit,
    /** The KEY step's "Cancel" ([PumpBtTrial.cancelPendingStart]). */
    val onCancelKey: () -> Unit,
    /** The PAIRING step's other name ([PumpBtTrial.tryOtherName]). */
    val onTryOtherName: () -> Unit,
    val onStop: () -> Unit,
    val onReadAgain: () -> Unit,
    val onLoadKeyFile: () -> Unit,
    /** The diagnostics' "Download key database" ([PumpBtTrial.loadKeyFromInternet]; critic G2, N-9). */
    val onDownloadKey: () -> Unit,
    val onShareJournal: () -> Unit,
    val onClearJournal: () -> Unit,
    /** "Permanent operation": true - [PumpBtSource.enable] (after the screen's confirmation), false - [PumpBtSource.disable]. */
    val onPermanent: (Boolean) -> Unit,
    /** The first catch-up card's "Write" with the keys of exactly the list shown. */
    val onWriteBackfill: (List<String>) -> Unit,
    val onDeclineBackfill: () -> Unit,
    val onVerbose: (Boolean) -> Unit,
    /** The screen stays on ([PumpBtWindow.keepScreenOn]). */
    val onKeepScreenOn: (Boolean) -> Unit,
    /** The source read the pump: the kept snapshot is read again. */
    val onPumpRead: () -> Unit,
    // settings ([PumpBtSettingsModel]) and the phone ([PumpBtActivity])
    val onWarnAfter: (Int) -> Unit,
    val onInsulin: (Long) -> Unit,
    val onWriteCarbs: (Boolean) -> Unit,
    val onNightscout: (Boolean) -> Unit,
    val onBattery: () -> Unit,
    val onNotifications: () -> Unit,
    val onSound: () -> Unit,
)

/**
 * JugglucoNGmod, pump over Bluetooth: the drawing of the one pump window in upstream's settings style (top bar,
 * section labels, setting rows). Top to bottom: the pairing wizard ([PumpBtWizardSection], while the pump is not paired
 * or after "Pair again"), the state, the pump's numbers, the settings ([PumpBtSettingsSection]), the diagnostics
 * (folded). State - [PumpBtTrial] (phone checks, key, wizard), [PumpBtSource.state] (the source and its link), the
 * settings model and the kept pump snapshot; what is pressable - [PumpBtWindow]. Nothing read or written here.
 */
@Composable
internal fun PumpBtScreen(
    stateFlow: StateFlow<PumpBtUiState>,
    sourceFlow: StateFlow<PumpBtSourceState>,
    journalFlow: StateFlow<List<String>>,
    settingsFlow: StateFlow<PumpBtSettingsState>,
    pumpNowFlow: StateFlow<PumpNow?>,
    actions: PumpBtScreenActions,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val source by sourceFlow.collectAsStateWithLifecycle()
    val journal by journalFlow.collectAsStateWithLifecycle()
    val settings by settingsFlow.collectAsStateWithLifecycle()
    val pumpNow by pumpNowFlow.collectAsStateWithLifecycle()
    val controls = PumpBtWindow.controls(state, source)
    val step = PumpBtWindow.wizardStep(state, source)
    val keepOn = PumpBtWindow.keepScreenOn(source)
    LaunchedEffect(keepOn) { actions.onKeepScreenOn(keepOn) }
    LaunchedEffect(source.lastCycleAtMs) { actions.onPumpRead() }
    var askOn by rememberSaveable { mutableStateOf(false) }
    // one clock for the data ages and the wait, moving while the window is on screen (critic G2, C-1)
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(PumpBtWindow.CLOCK_TICK_MS)
            }
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { AppTopBar(stringResource(R.string.mod_pumpbt_title), actions.onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        ) {
            if (step != PumpBtWizardStep.NONE) WizardSection(step, state, source, actions, onTurnOn = { askOn = true })
            if (PumpBtWindow.stateShown(step)) StateSection(state, source, controls, actions, now)
            PumpBtWindow.card(source)?.let { BackfillSection(it, actions) }
            if (PumpBtWindow.dataShown(source)) DataSection(source)
            if (pumpNow != null || source.enabled) PumpNowSection(pumpNow, source.link.values, now)
            SettingsSection(settings, actions)
            DiagnosticsSection(step, state, source, controls, journal, actions)
        }
    }
    if (askOn) {
        AlertDialog(
            onDismissRequest = { askOn = false },
            title = { Text(stringResource(R.string.mod_pumpbt_permanent_ask_title)) },
            text = { Text(stringResource(R.string.mod_pumpbt_permanent_ask_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askOn = false
                    actions.onPermanent(true)
                }) { Text(stringResource(R.string.mod_pumpbt_permanent_ask_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askOn = false }) { Text(stringResource(R.string.mod_pumpbt_cancel)) }
            },
        )
    }
}

/**
 * The state: the link (connected / waiting for the pump, how long / Bluetooth off), "Permanent operation" (the switch
 * while it is on - the wizard's PAIRED step turns it on), its warnings, the lost bond.
 */
@Composable
private fun StateSection(state: PumpBtUiState, source: PumpBtSourceState, controls: PumpBtControls, actions: PumpBtScreenActions, now: Long) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_state))
    when (PumpBtWindow.linkStatus(state, source)) {
        PumpBtLinkStatus.BLUETOOTH_OFF -> BtWarning(stringResource(R.string.mod_pumpbt_link_bluetooth_off))
        PumpBtLinkStatus.CONNECTED -> SettingsItem(title = stringResource(R.string.mod_pumpbt_link_connected), position = CardPosition.SINGLE)
        PumpBtLinkStatus.WAITING -> WaitingForPump(PumpBtWindow.waitShownSince(source.waitingSinceMs, state.bluetoothBackAtMs), now, keepBond = source.enabled)
        PumpBtLinkStatus.PAIRING -> SettingsItem(title = stringResource(R.string.mod_pumpbt_link_pairing), position = CardPosition.SINGLE)
        PumpBtLinkStatus.ERROR -> SettingsItem(
            title = stringResource(R.string.mod_pumpbt_link_error, source.link.error ?: "—"),
            position = CardPosition.SINGLE,
        )
        PumpBtLinkStatus.OFF -> SettingsItem(title = stringResource(R.string.mod_pumpbt_link_off), position = CardPosition.SINGLE)
    }
    if (source.enabled) {
        Spacer(Modifier.padding(top = 8.dp))
        SettingsSwitchItem(
            title = stringResource(R.string.mod_pumpbt_permanent),
            subtitle = stringResource(R.string.mod_pumpbt_permanent_on),
            checked = true,
            onCheckedChange = { want -> if (!want) actions.onPermanent(false) },
        )
        if (!source.sensorBluetooth) BtWarning(stringResource(R.string.mod_pumpbt_sensor_bluetooth_off))
    }
    if (source.memoryLoadFailed) BtWarning(stringResource(R.string.mod_pumpbt_memory_load_failed))
    if (PumpBtWindow.pairAgainShown(source)) {
        PairingAgainCard(state, source, actions)
    } else if (PumpBtWindow.bondAdvice(source)) {
        BondLostCard(state, controls, actions)
    }
}

/**
 * "Waiting for the pump N min" (journal of build 15: the pump came only after 4 min and the person removed the
 * pairing): how long, how long it usually takes, and - with the source on - not to remove the pairing.
 */
@Composable
private fun WaitingForPump(sinceMs: Long?, now: Long, keepBond: Boolean) {
    val min = sinceMs?.let { PumpBtWindow.waitingMinutes(it, now) } ?: 0
    SettingsItem(
        title = stringResource(R.string.mod_pumpbt_link_waiting, min),
        subtitle = stringResource(R.string.mod_pumpbt_link_waiting_hint),
        position = CardPosition.SINGLE,
    )
    if (keepBond) BtHint(stringResource(R.string.mod_pumpbt_link_keep_bond), Modifier.padding(top = 4.dp))
}

@Composable
internal fun turnOnHintText(hint: PumpBtTurnOnHint): String = when (hint) {
    PumpBtTurnOnHint.NO_BLUETOOTH -> stringResource(R.string.mod_pumpbt_permanent_needs_bluetooth)
    PumpBtTurnOnHint.BLUETOOTH_OFF -> stringResource(R.string.mod_pumpbt_permanent_needs_bluetooth_on)
    PumpBtTurnOnHint.NO_ADVERTISING -> stringResource(R.string.mod_pumpbt_permanent_needs_advertising)
    PumpBtTurnOnHint.NO_KEY -> stringResource(R.string.mod_pumpbt_permanent_needs_key)
    PumpBtTurnOnHint.PAIRING -> stringResource(R.string.mod_pumpbt_permanent_wait_pairing)
}

/** "The phone forgot the pump" (BOND_LOST, journal 14): what to do and the pairing button (not pressable during a pairing). */
@Composable
private fun BondLostCard(state: PumpBtUiState, controls: PumpBtControls, actions: PumpBtScreenActions) {
    Spacer(Modifier.padding(top = 8.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mod_pumpbt_bond_lost_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.mod_pumpbt_bond_lost_text, state.advertisedName), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.onStartPairing, enabled = controls.canPair) {
                Text(stringResource(R.string.mod_pumpbt_pair_again))
            }
        }
    }
}

/** The shown list of the card in the window's saved state (a turned screen keeps what the person was reading). */
private val shownSaver = listSaver<List<BackfillItem>, Any>(
    save = { PumpBtWindow.packItems(it) },
    restore = { PumpBtWindow.unpackItems(it) },
)

/**
 * The first catch-up card (decision 5): "Write" passes the keys of exactly the list shown. The shown list stays while
 * the person reads it; a new list from the source is shown only after "Show the new list" (critic B2b, C3). An answer
 * the source did not take is said, not left silent.
 */
@Composable
private fun BackfillSection(card: BackfillCard, actions: PumpBtScreenActions) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_backfill))
    val current = remember(card) { PumpBtWindow.cardItems(card) }
    var shown by rememberSaveable(stateSaver = shownSaver) { mutableStateOf(current) }
    // the press waiting for the source: when (wall clock), and the keys "Write" passed (null - "Do not write old ones")
    var pendingAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingWrite by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var note by rememberSaveable { mutableStateOf<PumpBtAnswer?>(null) }
    var askDecline by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pendingAt, current) {
        while (true) {
            val at = pendingAt ?: break
            val answer = PumpBtWindow.answer(at, pendingWrite, current, System.currentTimeMillis())
            if (answer == PumpBtAnswer.WAITING) {
                delay((at + PumpBtWindow.ANSWER_WAIT_MS - System.currentTimeMillis()).coerceAtLeast(100L))
                continue
            }
            note = answer
            pendingAt = null
            pendingWrite = null
        }
    }
    val answer = if (pendingAt != null) PumpBtAnswer.WAITING else PumpBtAnswer.NONE
    val changed = PumpBtWindow.listChanged(shown, current)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BtBody(stringResource(R.string.mod_pumpbt_backfill_found, card.bolusesBefore, card.mealsBefore))
        BtBody(stringResource(R.string.mod_pumpbt_backfill_in_journal, card.alreadyInJournal))
        if (shown.isEmpty()) {
            BtBody(stringResource(R.string.mod_pumpbt_backfill_nothing))
        } else {
            BtBody(stringResource(R.string.mod_pumpbt_backfill_to_write, shown.size))
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (item in shown) {
                    val amounts = listOfNotNull(
                        item.units?.let { stringResource(R.string.mod_pumpbt_units, btUnits(it)) },
                        item.grams?.let { stringResource(R.string.mod_pumpbt_grams, btUnits(it)) },
                    )
                    Text(
                        (listOf(btDateTime(item.atMs)) + amounts).joinToString("  "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (card.oldNotFound > 0) BtBody(stringResource(R.string.mod_pumpbt_backfill_old_not_found, card.oldNotFound))
        if (card.mealsNotWritten > 0) BtBody(stringResource(R.string.mod_pumpbt_backfill_meals_not_written, card.mealsNotWritten))
        BtHint(stringResource(R.string.mod_pumpbt_backfill_hint))
    }
    if (note == PumpBtAnswer.REJECTED) BtWarning(stringResource(R.string.mod_pumpbt_backfill_rejected))
    if (note == PumpBtAnswer.NOT_TAKEN) BtWarning(stringResource(R.string.mod_pumpbt_backfill_not_taken))
    if (changed) {
        BtWarning(stringResource(R.string.mod_pumpbt_backfill_changed))
        Spacer(Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = {
            shown = current
            note = null
        }) { Text(stringResource(R.string.mod_pumpbt_backfill_show_new)) }
    }
    Spacer(Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val keys = PumpBtWindow.shownKeys(shown)
                note = null
                pendingWrite = ArrayList(keys)
                pendingAt = System.currentTimeMillis()
                actions.onWriteBackfill(keys)
            },
            enabled = PumpBtWindow.canWrite(shown, current, answer),
        ) { Text(stringResource(R.string.mod_pumpbt_backfill_write)) }
        OutlinedButton(onClick = { askDecline = true }, enabled = answer != PumpBtAnswer.WAITING) {
            Text(stringResource(R.string.mod_pumpbt_backfill_decline))
        }
    }
    if (askDecline) {
        AlertDialog(
            onDismissRequest = { askDecline = false },
            title = { Text(stringResource(R.string.mod_pumpbt_backfill_decline_title)) },
            text = { Text(stringResource(R.string.mod_pumpbt_backfill_decline_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askDecline = false
                    note = null
                    pendingWrite = null
                    pendingAt = System.currentTimeMillis()
                    actions.onDeclineBackfill()
                }) { Text(stringResource(R.string.mod_pumpbt_backfill_decline)) }
            },
            dismissButton = {
                TextButton(onClick = { askDecline = false }) { Text(stringResource(R.string.mod_pumpbt_cancel)) }
            },
        )
    }
}

/** The source's status: last reading and what the journal did with it, the history memory, counters, warnings. */
@Composable
private fun DataSection(source: PumpBtSourceState) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_data))
    val rows = ArrayList<String>(10)
    val at = source.lastCycleAtMs
    rows += if (at == null) {
        stringResource(R.string.mod_pumpbt_last_reading_none)
    } else {
        val outcome = when {
            !source.enabled -> null
            source.lastCycleNoSerial -> stringResource(R.string.mod_pumpbt_outcome_no_serial)
            source.lastIntake == IntakeOutcome.WRITTEN -> stringResource(R.string.mod_pumpbt_outcome_written)
            source.lastIntake == IntakeOutcome.SKIPPED -> stringResource(R.string.mod_pumpbt_outcome_skipped)
            source.lastIntake == IntakeOutcome.FAILED -> stringResource(R.string.mod_pumpbt_outcome_failed)
            else -> null
        }
        if (outcome == null) stringResource(R.string.mod_pumpbt_last_reading, btDateTimeSeconds(at))
        else stringResource(R.string.mod_pumpbt_last_reading_outcome, btDateTimeSeconds(at), outcome)
    }
    if (source.records > 0) rows += stringResource(R.string.mod_pumpbt_records_saved, source.records)
    if (source.enabled && source.backfill == BackfillState.NOT_READY) rows += stringResource(R.string.mod_pumpbt_first_read_pending)
    for ((count, n) in PumpBtWindow.counts(source.counters)) rows += countText(count, n)
    BtRows(rows)
    for (w in PumpBtWindow.warnings(source.warnings)) BtWarning(warningText(w))
}

@Composable
private fun countText(count: PumpBtCount, n: Long): String = when (count) {
    PumpBtCount.UNPARSED -> stringResource(R.string.mod_pumpbt_count_unparsed, n)
    PumpBtCount.GAPS -> stringResource(R.string.mod_pumpbt_count_gaps, n)
    PumpBtCount.UNPAIRED_MEALS -> stringResource(R.string.mod_pumpbt_count_unpaired_meals, n)
    PumpBtCount.NO_RESULT -> stringResource(R.string.mod_pumpbt_count_no_result, n)
    PumpBtCount.FUTURE -> stringResource(R.string.mod_pumpbt_count_future, n)
    PumpBtCount.HELD -> stringResource(R.string.mod_pumpbt_count_held, n)
    PumpBtCount.UNDATED -> stringResource(R.string.mod_pumpbt_count_undated, n)
}

@Composable
private fun warningText(w: PumpWarning): String = when (w) {
    PumpWarning.CLOCK_DRIFT -> stringResource(R.string.mod_pumpbt_warning_clock_drift)
    PumpWarning.FUTURE_EVENT -> stringResource(R.string.mod_pumpbt_warning_future)
    PumpWarning.NO_RESULT_BOLUS -> stringResource(R.string.mod_pumpbt_warning_no_result)
    PumpWarning.HELD_BOLUS -> stringResource(R.string.mod_pumpbt_warning_held)
    PumpWarning.UNPAIRED_MEAL -> stringResource(R.string.mod_pumpbt_warning_unpaired_meal)
}

/**
 * The pump's numbers from the kept snapshot (`pump.json`: what the status bar and the watch show) with one data time
 * for all: active insulin, reservoir in units (no percent: none over Bluetooth, an old cloud one would look fresh -
 * window decision 6), battery, basal, temporary basal. A temporary basal the snapshot cannot give a value for (another
 * IDD type) - "running, type N" from the link's values (process memory).
 */
@Composable
private fun PumpNowSection(pump: PumpNow?, values: PumpBtValues, now: Long) {
    if (pump == null) {
        SectionLabel(stringResource(R.string.mod_pumpbt_section_pump))
        BtBody(stringResource(R.string.mod_carelink_data_none))
        return
    }
    SectionLabel(stringResource(R.string.mod_carelink_section_pump, btAge(now, pump.dataAtMs)))
    val rows = ArrayList<Pair<String, String>>(6)
    // the active insulin with its own time; older than the plate's 30 min - no number (critic G2, C-2)
    PumpBtWindow.iobShown(pump, now)?.let { iob ->
        val value = when {
            iob.units == null -> stringResource(R.string.mod_pumpbt_iob_old, btAge(now, iob.atMs))
            iob.ownAge -> stringResource(R.string.mod_pumpbt_units, btUnits(iob.units)) + " · " + btAge(now, iob.atMs)
            else -> stringResource(R.string.mod_pumpbt_units, btUnits(iob.units))
        }
        rows += stringResource(R.string.mod_carelink_iob) to value
    }
    pump.reservoirUnits?.let { rows += stringResource(R.string.mod_carelink_reservoir) to stringResource(R.string.mod_pumpbt_units, btUnits(it)) }
    pump.batteryPercent?.let { rows += stringResource(R.string.mod_carelink_battery) to stringResource(R.string.mod_carelink_percent, it) }
    if (pump.suspended == true) {
        rows += stringResource(R.string.mod_pumpbt_basal_now) to stringResource(R.string.mod_pumpbt_delivery_stopped)
    } else {
        pump.basalRate?.let { rows += stringResource(R.string.mod_pumpbt_basal_now) to stringResource(R.string.mod_pumpbt_units_per_hour, btUnits(it)) }
    }
    val tempPercent = pump.tempBasal?.percent
    val tempRate = pump.tempBasal?.rate
    val tempLeft = pump.tempBasal?.minutesLeft
    val tempText = when {
        tempPercent != null -> tempValue(stringResource(R.string.mod_pumpbt_percent, tempPercent), tempLeft)
        tempRate != null -> tempValue(stringResource(R.string.mod_pumpbt_units_per_hour, btUnits(tempRate)), tempLeft)
        values.tempBasalType != null -> stringResource(R.string.mod_pumpbt_temp_basal_type, "0x%02X".format(values.tempBasalType))
        pump.basalKnown -> stringResource(R.string.mod_pumpbt_none)
        else -> null
    }
    tempText?.let { rows += stringResource(R.string.mod_pumpbt_temp_basal) to it }
    if (rows.isEmpty()) {
        BtBody(stringResource(R.string.mod_carelink_data_none))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, (title, value) -> SettingsItem(title = title, subtitle = value, position = btPosition(i, rows.size)) }
    }
}

/**
 * "Diagnostics" (folded): the link's stage, the name on the pump, the last problem, returns, the trial's buttons
 * ("Wait for the pump", "Stop" - with the source off), "Read again", "Pair again", "Cancel pairing", the key database,
 * the values the link read (model, firmware, serial number - not in the pump section) and the link journal.
 */
@Composable
private fun DiagnosticsSection(
    step: PumpBtWizardStep,
    state: PumpBtUiState,
    source: PumpBtSourceState,
    controls: PumpBtControls,
    journal: List<String>,
    actions: PumpBtScreenActions,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionLabel(stringResource(R.string.mod_pumpbt_section_diagnostics))
    SettingsItem(
        title = stringResource(if (open) R.string.mod_pumpbt_diagnostics_hide else R.string.mod_pumpbt_diagnostics_show),
        subtitle = stringResource(R.string.mod_pumpbt_diagnostics_hint),
        position = CardPosition.SINGLE,
        onClick = { open = !open },
    )
    if (!open) return
    LinkRows(state, source)
    // before the warnings are accepted nothing starts a link (decision 14)
    if (step != PumpBtWizardStep.WARNINGS) {
        DiagnosticsButtons(controls, actions)
        KeyRows(state, actions)
    }
    ValuesSection(source.link.values)
    // the journal's switch, Share and Clear write the link state: not before the warnings (critic G2, N-2)
    if (step != PumpBtWizardStep.WARNINGS) JournalSection(journal, source, actions)
}

@Composable
private fun DiagnosticsButtons(controls: PumpBtControls, actions: PumpBtScreenActions) {
    Spacer(Modifier.padding(top = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controls.trialButtons) {
            OutlinedButton(onClick = actions.onWaitForPump, enabled = controls.canStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mod_pumpbt_wait))
            }
            BtHint(stringResource(R.string.mod_pumpbt_wait_hint))
        } else {
            // the watchdog would start a stopped link again: the link stops only with permanent operation off
            BtHint(stringResource(R.string.mod_pumpbt_stop_by_switch))
        }
        OutlinedButton(onClick = actions.onStartPairing, enabled = controls.canPairAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.mod_pumpbt_pair_again))
        }
        if (controls.canCancelPairing) {
            OutlinedButton(onClick = actions.onCancelPairing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mod_pumpbt_cancel_pairing))
            }
            BtHint(stringResource(R.string.mod_pumpbt_cancel_pairing_hint))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (controls.trialButtons) {
                OutlinedButton(onClick = actions.onStop, enabled = controls.canStop) { Text(stringResource(R.string.mod_pumpbt_stop)) }
            }
            OutlinedButton(onClick = actions.onReadAgain, enabled = controls.canReadAgain) { Text(stringResource(R.string.mod_pumpbt_read_again)) }
        }
    }
}

@Composable
private fun LinkRows(state: PumpBtUiState, source: PumpBtSourceState) {
    val link = source.link
    Spacer(Modifier.padding(top = 8.dp))
    val rows = ArrayList<String>(4)
    rows += stringResource(R.string.mod_pumpbt_stage, stageText(link.stage, link))
    if (state.advertisedName.isNotEmpty()) rows += stringResource(R.string.mod_pumpbt_name_on_pump, state.advertisedName)
    link.lastProblem?.let { problem ->
        rows += stringResource(R.string.mod_pumpbt_last_problem, listOfNotNull(link.lastProblemAtMs?.let(::btTime), problem).joinToString(" "))
    }
    link.furthestStage?.let { rows += stringResource(R.string.mod_pumpbt_furthest_stage, stageText(it, link)) }
    BtRows(rows)
    if (link.stage == PumpBtStage.LOST) BtHint(stringResource(R.string.mod_pumpbt_lost_hint), Modifier.padding(top = 4.dp))
    if (link.returns.isNotEmpty()) {
        Spacer(Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.mod_pumpbt_returns_title), style = MaterialTheme.typography.bodyMedium)
        BtRows(
            link.returns.map { r ->
                val sec = r.afterMs / 1000
                stringResource(
                    R.string.mod_pumpbt_returned,
                    btTime(r.lostAtMs),
                    stringResource(R.string.mod_pumpbt_duration, (sec / 60).toInt(), (sec % 60).toInt()),
                )
            },
        )
    }
}

/** The key database: loaded (CRC) or not, the last failure, "Load from file" (the wizard downloads it by itself). */
@Composable
private fun KeyRows(state: PumpBtUiState, actions: PumpBtScreenActions) {
    Spacer(Modifier.padding(top = 12.dp))
    val crc = state.keyCrc
    val line = when {
        state.keyLoading -> stringResource(R.string.mod_pumpbt_key_loading)
        crc != null -> stringResource(R.string.mod_pumpbt_key_loaded, crc)
        else -> stringResource(R.string.mod_pumpbt_key_not_loaded)
    }
    SettingsItem(title = line, position = CardPosition.SINGLE)
    keyFailureText(state)?.let { BtWarning(it) }
    Spacer(Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onDownloadKey, enabled = !state.keyLoading) { Text(stringResource(R.string.mod_pumpbt_download_key)) }
        OutlinedButton(onClick = actions.onLoadKeyFile, enabled = !state.keyLoading) { Text(stringResource(R.string.mod_pumpbt_load_key_file)) }
    }
}

/** A temporary basal's value, with the minutes left only when the pump gave them (critic G2, N-6). */
@Composable
private fun tempValue(value: String, minutesLeft: Int?): String =
    if (minutesLeft == null || minutesLeft < 0) value else stringResource(R.string.mod_pumpbt_temp_basal_value, value, minutesLeft)

@Composable
internal fun keyFailureText(state: PumpBtUiState): String? = when (state.keyFailure) {
    null -> null
    PumpBtKey.Failure.NO_NETWORK -> stringResource(R.string.mod_pumpbt_key_error_network)
    PumpBtKey.Failure.HTTP -> stringResource(R.string.mod_pumpbt_key_error_http, state.keyFailureCode)
    PumpBtKey.Failure.NOT_FOUND -> stringResource(R.string.mod_pumpbt_key_error_not_found)
    PumpBtKey.Failure.CRC -> stringResource(R.string.mod_pumpbt_key_error_crc)
    PumpBtKey.Failure.WRONG_TYPE -> stringResource(R.string.mod_pumpbt_key_error_wrong_type)
    PumpBtKey.Failure.FILE_NOT_READ -> stringResource(R.string.mod_pumpbt_key_error_file)
}

@Composable
private fun stageText(stage: PumpBtStage, link: PumpBtLinkState): String = when (stage) {
    PumpBtStage.NOT_STARTED -> stringResource(R.string.mod_pumpbt_stage_not_started)
    PumpBtStage.ADVERTISING_PAIR -> stringResource(R.string.mod_pumpbt_stage_advertising)
    PumpBtStage.WAITING -> stringResource(R.string.mod_pumpbt_stage_waiting)
    PumpBtStage.PUMP_CONNECTED -> stringResource(R.string.mod_pumpbt_stage_connected)
    PumpBtStage.PAIRING -> stringResource(R.string.mod_pumpbt_stage_pairing)
    PumpBtStage.HANDSHAKE -> stringResource(R.string.mod_pumpbt_stage_handshake)
    PumpBtStage.READING -> stringResource(R.string.mod_pumpbt_stage_reading)
    PumpBtStage.DATA_READ -> stringResource(R.string.mod_pumpbt_stage_data_read)
    PumpBtStage.LOST -> stringResource(R.string.mod_pumpbt_stage_lost, link.lostAtMs?.let(::btTime) ?: "—")
    PumpBtStage.ERROR -> stringResource(R.string.mod_pumpbt_stage_error, link.error ?: "—")
    PumpBtStage.STOPPED -> stringResource(R.string.mod_pumpbt_stage_stopped)
}

/** What the link read (process memory): model, firmware, serial number, numbers, the last history records. */
@Composable
private fun ValuesSection(v: PumpBtValues) {
    val none = stringResource(R.string.mod_pumpbt_not_read)
    SectionLabel(stringResource(R.string.mod_pumpbt_section_values, v.readAtMs?.let(::btTime) ?: none))
    val temp = when {
        // a temp basal in percent is shown as percent, never as units per hour
        v.tempBasalPercent != null -> tempValue(stringResource(R.string.mod_pumpbt_percent, v.tempBasalPercent), v.tempBasalRemainingMin)
        v.tempBasalUnitsPerHour != null ->
            tempValue(stringResource(R.string.mod_pumpbt_units_per_hour, btUnits(v.tempBasalUnitsPerHour)), v.tempBasalRemainingMin)
        v.tempBasalType != null -> stringResource(R.string.mod_pumpbt_temp_basal_type, "0x%02X".format(v.tempBasalType))
        v.basalRead -> stringResource(R.string.mod_pumpbt_none)
        else -> none
    }
    val rows = listOf(
        stringResource(R.string.mod_pumpbt_model) to (v.model ?: none),
        stringResource(R.string.mod_pumpbt_firmware) to (v.firmware ?: none),
        stringResource(R.string.mod_pumpbt_serial) to (v.serial ?: none),
        stringResource(R.string.mod_pumpbt_iob) to (v.iobUnits?.let { stringResource(R.string.mod_pumpbt_units, btUnits(it)) } ?: none),
        stringResource(R.string.mod_pumpbt_reservoir) to (v.reservoirUnits?.let { stringResource(R.string.mod_pumpbt_units, btUnits(it)) } ?: none),
        stringResource(R.string.mod_pumpbt_battery) to (v.batteryPercent?.let { stringResource(R.string.mod_pumpbt_percent, it) } ?: none),
        stringResource(R.string.mod_pumpbt_basal_now) to (v.basalUnitsPerHour?.let { stringResource(R.string.mod_pumpbt_units_per_hour, btUnits(it)) } ?: none),
        stringResource(R.string.mod_pumpbt_temp_basal) to temp,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, (title, value) ->
            SettingsItem(title = title, subtitle = value, position = btPosition(i, rows.size))
        }
    }
    Spacer(Modifier.padding(top = 12.dp))
    Text(stringResource(R.string.mod_pumpbt_history), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    if (v.history.isEmpty()) {
        Text(none, style = MaterialTheme.typography.bodyMedium)
    } else {
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (h in v.history) {
                Text(
                    listOf(h.timeMs?.let(::btDateTime) ?: none, h.kind, h.value).filter { it.isNotEmpty() }.joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JournalSection(lines: List<String>, source: PumpBtSourceState, actions: PumpBtScreenActions) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_journal))
    // decision 13: the detailed journal, off by default; a warning when shared while the file holds detailed lines
    SettingsSwitchItem(
        title = stringResource(R.string.mod_pumpbt_verbose),
        subtitle = stringResource(R.string.mod_pumpbt_verbose_hint),
        checked = source.verbose,
        onCheckedChange = actions.onVerbose,
    )
    Spacer(Modifier.padding(top = 8.dp))
    if (lines.isEmpty()) {
        Text(stringResource(R.string.mod_pumpbt_journal_empty), style = MaterialTheme.typography.bodyMedium)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            for (line in lines) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
    var askClear by rememberSaveable { mutableStateOf(false) }
    var askShare by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (PumpBtWindow.shareWarning(source)) askShare = true else actions.onShareJournal() }) {
            Text(stringResource(R.string.mod_pumpbt_share_journal))
        }
        OutlinedButton(onClick = { askClear = true }) { Text(stringResource(R.string.mod_pumpbt_clear_journal)) }
    }
    if (askShare) {
        AlertDialog(
            onDismissRequest = { askShare = false },
            title = { Text(stringResource(R.string.mod_pumpbt_share_verbose_title)) },
            text = { Text(stringResource(R.string.mod_pumpbt_share_verbose_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askShare = false
                    actions.onShareJournal()
                }) { Text(stringResource(R.string.mod_pumpbt_share_verbose_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askShare = false }) { Text(stringResource(R.string.mod_pumpbt_cancel)) }
            },
        )
    }
    // the journal is the only record of the link: clearing only after a confirmation
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.mod_pumpbt_clear_title)) },
            text = { Text(stringResource(R.string.mod_pumpbt_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askClear = false
                    actions.onClearJournal()
                }) { Text(stringResource(R.string.mod_pumpbt_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askClear = false }) { Text(stringResource(R.string.mod_pumpbt_cancel)) }
            },
        )
    }
}

// MARK: - Helpers of the window's sections

@Composable
internal fun BtRows(rows: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, r -> SettingsItem(title = r, position = btPosition(i, rows.size)) }
    }
}

@Composable
internal fun BtHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun BtBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun BtWarning(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

internal fun btPosition(i: Int, n: Int): CardPosition = when {
    n == 1 -> CardPosition.SINGLE
    i == 0 -> CardPosition.TOP
    i == n - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm")
private val dateTimeSecondsFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

private fun btTime(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun btDateTime(ms: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun btDateTimeSeconds(ms: Long): String = dateTimeSecondsFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 1.25 - "1.25", 150.0 - "150"; the phone's decimal mark. */
internal fun btUnits(v: Double): String = DecimalFormat("0.##").format(v)

/** "just now", "12 min ago", "2 h 5 min ago", "over a day ago". */
@Composable
private fun btAge(nowMs: Long, atMs: Long): String {
    val min = ((nowMs - atMs).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        min < 1 -> stringResource(R.string.mod_carelink_age_now)
        min < 60 -> stringResource(R.string.mod_carelink_age_min, min)
        min < 24 * 60 -> stringResource(R.string.mod_carelink_age_hours, min / 60, min % 60)
        else -> stringResource(R.string.mod_carelink_age_day)
    }
}
