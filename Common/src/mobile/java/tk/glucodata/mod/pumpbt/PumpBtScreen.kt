package tk.glucodata.mod.pumpbt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tk.glucodata.R
import tk.glucodata.mod.carelink.IntakeOutcome
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the trial window asks of its activity. */
internal class PumpBtScreenActions(
    val onBack: () -> Unit,
    val onAllow: () -> Unit,
    /** The trial's "Start pairing", and with the source on "Pair again" (a running link switches to pairing). */
    val onStartPairing: () -> Unit,
    val onWaitForPump: () -> Unit,
    val onStop: () -> Unit,
    val onReadAgain: () -> Unit,
    val onLoadKey: () -> Unit,
    val onLoadKeyFile: () -> Unit,
    val onVariant: (PumpBtNameVariant) -> Unit,
    val onShareJournal: () -> Unit,
    val onClearJournal: () -> Unit,
    /** "Permanent operation": true - [PumpBtSource.enable] (after the screen's confirmation), false - [PumpBtSource.disable]. */
    val onPermanent: (Boolean) -> Unit,
    /** The first catch-up card's "Write" with the keys of exactly the list shown. */
    val onWriteBackfill: (List<String>) -> Unit,
    val onDeclineBackfill: () -> Unit,
    val onVerbose: (Boolean) -> Unit,
)

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the drawing of the trial window in upstream's settings style (top bar,
 * section labels, setting rows). State - [PumpBtTrial] (phone checks, key) and [PumpBtSource.state] (the source and its
 * link); what is pressable - [PumpBtWindow]. Nothing read or written here.
 */
@Composable
internal fun PumpBtScreen(
    stateFlow: StateFlow<PumpBtUiState>,
    sourceFlow: StateFlow<PumpBtSourceState>,
    journalFlow: StateFlow<List<String>>,
    actions: PumpBtScreenActions,
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val source by sourceFlow.collectAsStateWithLifecycle()
    val journal by journalFlow.collectAsStateWithLifecycle()
    val controls = PumpBtWindow.controls(state, source)
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { AppTopBar(stringResource(R.string.mod_pumpbt_title), actions.onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        ) {
            PhoneSection(state, actions)
            KeySection(state, actions)
            PermanentSection(state, source, controls, actions)
            PumpBtWindow.card(source)?.let { BackfillSection(it, actions) }
            PumpSection(state, source, controls, actions)
            if (PumpBtWindow.dataShown(source)) DataSection(source)
            ValuesSection(source.link.values)
            JournalSection(journal, source, actions)
        }
    }
}

@Composable
private fun PhoneSection(state: PumpBtUiState, actions: PumpBtScreenActions) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_phone))
    val rows = ArrayList<String>(2)
    when {
        !state.hasBluetooth -> rows += stringResource(R.string.mod_pumpbt_no_bluetooth)
        !state.bluetoothOn -> rows += stringResource(R.string.mod_pumpbt_bluetooth_off)
        else -> rows += stringResource(
            R.string.mod_pumpbt_advertising_supported,
            stringResource(if (state.advertisingSupported == true) R.string.mod_pumpbt_yes else R.string.mod_pumpbt_no),
        )
    }
    if (!state.permissionsGranted) rows += stringResource(R.string.mod_pumpbt_no_permissions)
    Rows(rows)
    if (!state.permissionsGranted) {
        Spacer(Modifier.padding(top = 8.dp))
        Button(onClick = actions.onAllow) { Text(stringResource(R.string.mod_pumpbt_allow)) }
    }
}

@Composable
private fun KeySection(state: PumpBtUiState, actions: PumpBtScreenActions) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_key))
    val crc = state.keyCrc
    val line = when {
        state.keyLoading -> stringResource(R.string.mod_pumpbt_key_loading)
        crc != null -> stringResource(R.string.mod_pumpbt_key_loaded, crc)
        else -> stringResource(R.string.mod_pumpbt_key_not_loaded)
    }
    SettingsItem(title = line, position = CardPosition.SINGLE)
    keyFailureText(state)?.let { Warning(it) }
    Spacer(Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = actions.onLoadKey, enabled = !state.keyLoading) { Text(stringResource(R.string.mod_pumpbt_load_key)) }
        OutlinedButton(onClick = actions.onLoadKeyFile, enabled = !state.keyLoading) { Text(stringResource(R.string.mod_pumpbt_load_key_file)) }
    }
    Hint(stringResource(R.string.mod_pumpbt_load_key_hint))
}

@Composable
private fun keyFailureText(state: PumpBtUiState): String? = when (state.keyFailure) {
    null -> null
    PumpBtKey.Failure.NO_NETWORK -> stringResource(R.string.mod_pumpbt_key_error_network)
    PumpBtKey.Failure.HTTP -> stringResource(R.string.mod_pumpbt_key_error_http, state.keyFailureCode)
    PumpBtKey.Failure.NOT_FOUND -> stringResource(R.string.mod_pumpbt_key_error_not_found)
    PumpBtKey.Failure.CRC -> stringResource(R.string.mod_pumpbt_key_error_crc)
    PumpBtKey.Failure.WRONG_TYPE -> stringResource(R.string.mod_pumpbt_key_error_wrong_type)
    PumpBtKey.Failure.FILE_NOT_READ -> stringResource(R.string.mod_pumpbt_key_error_file)
}

/** The "Permanent operation" switch (decision 14: off by default, turned on here by the owner), its warnings, the lost bond. */
@Composable
private fun PermanentSection(state: PumpBtUiState, source: PumpBtSourceState, controls: PumpBtControls, actions: PumpBtScreenActions) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_permanent))
    var askOn by rememberSaveable { mutableStateOf(false) }
    SettingsSwitchItem(
        title = stringResource(R.string.mod_pumpbt_permanent),
        subtitle = stringResource(if (source.enabled) R.string.mod_pumpbt_permanent_on else R.string.mod_pumpbt_permanent_off),
        checked = source.enabled,
        onCheckedChange = { want -> if (want) askOn = true else actions.onPermanent(false) },
        enabled = source.enabled || controls.canTurnOn,
    )
    PumpBtWindow.turnOnHint(state, source)?.let { Hint(turnOnHintText(it)) }
    if (!source.sensorBluetooth) Warning(stringResource(R.string.mod_pumpbt_sensor_bluetooth_off))
    if (source.memoryLoadFailed) Warning(stringResource(R.string.mod_pumpbt_memory_load_failed))
    if (PumpBtWindow.bondAdvice(source)) BondLostCard(state, controls, actions)
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

@Composable
private fun turnOnHintText(hint: PumpBtTurnOnHint): String = when (hint) {
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
        Body(stringResource(R.string.mod_pumpbt_backfill_found, card.bolusesBefore, card.mealsBefore))
        Body(stringResource(R.string.mod_pumpbt_backfill_in_journal, card.alreadyInJournal))
        if (shown.isEmpty()) {
            Body(stringResource(R.string.mod_pumpbt_backfill_nothing))
        } else {
            Body(stringResource(R.string.mod_pumpbt_backfill_to_write, shown.size))
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (item in shown) {
                    val amounts = listOfNotNull(
                        item.units?.let { stringResource(R.string.mod_pumpbt_units, units(it)) },
                        item.grams?.let { stringResource(R.string.mod_pumpbt_grams, units(it)) },
                    )
                    Text(
                        (listOf(dateTime(item.atMs)) + amounts).joinToString("  "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (card.oldNotFound > 0) Body(stringResource(R.string.mod_pumpbt_backfill_old_not_found, card.oldNotFound))
        if (card.mealsNotWritten > 0) Body(stringResource(R.string.mod_pumpbt_backfill_meals_not_written, card.mealsNotWritten))
        Hint(stringResource(R.string.mod_pumpbt_backfill_hint))
    }
    if (note == PumpBtAnswer.REJECTED) Warning(stringResource(R.string.mod_pumpbt_backfill_rejected))
    if (note == PumpBtAnswer.NOT_TAKEN) Warning(stringResource(R.string.mod_pumpbt_backfill_not_taken))
    if (changed) {
        Warning(stringResource(R.string.mod_pumpbt_backfill_changed))
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

@Composable
private fun PumpSection(state: PumpBtUiState, source: PumpBtSourceState, controls: PumpBtControls, actions: PumpBtScreenActions) {
    val link = source.link
    SectionLabel(stringResource(R.string.mod_pumpbt_section_pump))
    val rows = ArrayList<String>(4)
    rows += stringResource(R.string.mod_pumpbt_stage, stageText(link.stage, link))
    rows += stringResource(R.string.mod_pumpbt_name_on_pump, state.advertisedName)
    link.lastProblem?.let { problem ->
        rows += stringResource(R.string.mod_pumpbt_last_problem, listOfNotNull(link.lastProblemAtMs?.let(::time), problem).joinToString(" "))
    }
    link.furthestStage?.let { rows += stringResource(R.string.mod_pumpbt_furthest_stage, stageText(it, link)) }
    Rows(rows)
    if (link.stage == PumpBtStage.LOST) {
        Spacer(Modifier.padding(top = 4.dp))
        Hint(stringResource(R.string.mod_pumpbt_lost_hint))
    }
    if (PumpBtWindow.pairWaitShown(link)) {
        Spacer(Modifier.padding(top = 4.dp))
        PairWait(state.pairingStartedAtMs)
    }
    if (PumpBtWindow.pairingAdvice(link, state.pairingStartedAtMs)) {
        Warning(stringResource(R.string.mod_pumpbt_pairing_failed, state.advertisedName))
    }
    if (link.returns.isNotEmpty()) {
        Spacer(Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.mod_pumpbt_returns_title), style = MaterialTheme.typography.bodyMedium)
        Rows(
            link.returns.map { r ->
                val sec = r.afterMs / 1000
                stringResource(
                    R.string.mod_pumpbt_returned,
                    time(r.lostAtMs),
                    stringResource(R.string.mod_pumpbt_duration, (sec / 60).toInt(), (sec % 60).toInt()),
                )
            },
        )
    }

    if (controls.trialButtons) {
        Spacer(Modifier.padding(top = 12.dp))
        VariantChoice(state.variant, enabled = controls.variantEditable, onPick = actions.onVariant)
        Hint(stringResource(R.string.mod_pumpbt_variant_hint))
    }

    Spacer(Modifier.padding(top = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controls.trialButtons) {
            Hint(stringResource(R.string.mod_pumpbt_stop_minimed_hint))
            Button(onClick = actions.onStartPairing, enabled = controls.canStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mod_pumpbt_start_pairing))
            }
            Hint(stringResource(R.string.mod_pumpbt_start_pairing_hint))
            OutlinedButton(onClick = actions.onWaitForPump, enabled = controls.canStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mod_pumpbt_wait))
            }
            Hint(stringResource(R.string.mod_pumpbt_wait_hint))
        } else {
            // the watchdog would start a stopped link again: the link stops only with permanent operation off
            Hint(stringResource(R.string.mod_pumpbt_stop_by_switch))
            if (controls.pairOffered && !PumpBtWindow.bondAdvice(source)) {
                Hint(stringResource(R.string.mod_pumpbt_stop_minimed_hint))
                Button(onClick = actions.onStartPairing, enabled = controls.canPair, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mod_pumpbt_start_pairing))
                }
                Hint(stringResource(R.string.mod_pumpbt_start_pairing_hint))
            }
        }
        if (state.keyCrc == null) Warning(stringResource(R.string.mod_pumpbt_need_key))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (controls.trialButtons) {
                OutlinedButton(onClick = actions.onStop, enabled = controls.canStop) { Text(stringResource(R.string.mod_pumpbt_stop)) }
            }
            OutlinedButton(onClick = actions.onReadAgain, enabled = controls.canReadAgain) { Text(stringResource(R.string.mod_pumpbt_read_again)) }
        }
    }
}

/**
 * Decision 12: how long the phone has waited for the pump to pair ("usually up to a minute"); it does not stop by
 * itself. Counted from [startedAtMs] (when the window started the pairing), else from when this window first showed
 * the wait - kept over a turned screen (critic B2b, N4).
 */
@Composable
private fun PairWait(startedAtMs: Long?) {
    val firstShown = rememberSaveable { System.currentTimeMillis() }
    val since = startedAtMs ?: firstShown
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val sec = ((now - since) / 1000).coerceAtLeast(0)
    Hint(
        stringResource(
            R.string.mod_pumpbt_pair_wait,
            stringResource(R.string.mod_pumpbt_duration, (sec / 60).toInt(), (sec % 60).toInt()),
        ),
    )
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
    PumpBtStage.LOST -> stringResource(R.string.mod_pumpbt_stage_lost, link.lostAtMs?.let(::time) ?: "—")
    PumpBtStage.ERROR -> stringResource(R.string.mod_pumpbt_stage_error, link.error ?: "—")
    PumpBtStage.STOPPED -> stringResource(R.string.mod_pumpbt_stage_stopped)
}

@Composable
private fun VariantChoice(selected: PumpBtNameVariant, enabled: Boolean, onPick: (PumpBtNameVariant) -> Unit) {
    Column {
        for ((variant, text) in listOf(
            PumpBtNameVariant.A to stringResource(R.string.mod_pumpbt_variant_a),
            PumpBtNameVariant.B to stringResource(R.string.mod_pumpbt_variant_b),
        )) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = variant == selected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(variant) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = variant == selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
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
        if (outcome == null) stringResource(R.string.mod_pumpbt_last_reading, dateTimeSeconds(at))
        else stringResource(R.string.mod_pumpbt_last_reading_outcome, dateTimeSeconds(at), outcome)
    }
    if (source.records > 0) rows += stringResource(R.string.mod_pumpbt_records_saved, source.records)
    if (source.enabled && source.backfill == BackfillState.NOT_READY) rows += stringResource(R.string.mod_pumpbt_first_read_pending)
    for ((count, n) in PumpBtWindow.counts(source.counters)) rows += countText(count, n)
    Rows(rows)
    for (w in PumpBtWindow.warnings(source.warnings)) Warning(warningText(w))
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

@Composable
private fun ValuesSection(v: PumpBtValues) {
    val none = stringResource(R.string.mod_pumpbt_not_read)
    SectionLabel(stringResource(R.string.mod_pumpbt_section_values, v.readAtMs?.let(::time) ?: none))
    val temp = when {
        // a temp basal in percent is shown as percent, never as units per hour
        v.tempBasalPercent != null -> stringResource(
            R.string.mod_pumpbt_temp_basal_value,
            stringResource(R.string.mod_pumpbt_percent, v.tempBasalPercent),
            v.tempBasalRemainingMin ?: 0,
        )
        v.tempBasalUnitsPerHour != null -> stringResource(
            R.string.mod_pumpbt_temp_basal_value,
            stringResource(R.string.mod_pumpbt_units_per_hour, units(v.tempBasalUnitsPerHour)),
            v.tempBasalRemainingMin ?: 0,
        )
        v.basalRead -> stringResource(R.string.mod_pumpbt_none)
        else -> none
    }
    val rows = listOf(
        stringResource(R.string.mod_pumpbt_model) to (v.model ?: none),
        stringResource(R.string.mod_pumpbt_firmware) to (v.firmware ?: none),
        stringResource(R.string.mod_pumpbt_serial) to (v.serial ?: none),
        stringResource(R.string.mod_pumpbt_iob) to (v.iobUnits?.let { stringResource(R.string.mod_pumpbt_units, units(it)) } ?: none),
        stringResource(R.string.mod_pumpbt_reservoir) to (v.reservoirUnits?.let { stringResource(R.string.mod_pumpbt_units, units(it)) } ?: none),
        stringResource(R.string.mod_pumpbt_battery) to (v.batteryPercent?.let { stringResource(R.string.mod_pumpbt_percent, it) } ?: none),
        stringResource(R.string.mod_pumpbt_basal_now) to (v.basalUnitsPerHour?.let { stringResource(R.string.mod_pumpbt_units_per_hour, units(it)) } ?: none),
        stringResource(R.string.mod_pumpbt_temp_basal) to temp,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, (title, value) ->
            SettingsItem(title = title, subtitle = value, position = position(i, rows.size))
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
                    listOf(h.timeMs?.let(::dateTime) ?: none, h.kind, h.value).filter { it.isNotEmpty() }.joinToString("  "),
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
    // the journal is the only record of a trial: clearing only after a confirmation
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

@Composable
private fun Rows(rows: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, r -> SettingsItem(title = r, position = position(i, rows.size)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Warning(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

private fun position(i: Int, n: Int): CardPosition = when {
    n == 1 -> CardPosition.SINGLE
    i == 0 -> CardPosition.TOP
    i == n - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm")
private val dateTimeSecondsFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

private fun time(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun dateTime(ms: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun dateTimeSeconds(ms: Long): String = dateTimeSecondsFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 1.25 - "1.25", 150.0 - "150"; the phone's decimal mark. */
private fun units(v: Double): String = DecimalFormat("0.##").format(v)
