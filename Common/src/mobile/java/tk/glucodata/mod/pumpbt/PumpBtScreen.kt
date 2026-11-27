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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.flow.StateFlow
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the trial window asks of its activity. */
internal class PumpBtScreenActions(
    val onBack: () -> Unit,
    val onAllow: () -> Unit,
    val onStartPairing: () -> Unit,
    val onWaitForPump: () -> Unit,
    val onStop: () -> Unit,
    val onReadAgain: () -> Unit,
    val onLoadKey: () -> Unit,
    val onLoadKeyFile: () -> Unit,
    val onVariant: (PumpBtNameVariant) -> Unit,
    val onShareJournal: () -> Unit,
    val onClearJournal: () -> Unit,
)

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the drawing of the trial window in upstream's settings style (top bar,
 * section labels, setting rows). State - [PumpBtTrial]; nothing read or written here.
 */
@Composable
internal fun PumpBtScreen(stateFlow: StateFlow<PumpBtUiState>, journalFlow: StateFlow<List<String>>, actions: PumpBtScreenActions) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val journal by journalFlow.collectAsStateWithLifecycle()
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
            PumpSection(state, actions)
            ValuesSection(state.link.values)
            JournalSection(journal, actions)
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
    keyFailureText(state)?.let {
        Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
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

@Composable
private fun PumpSection(state: PumpBtUiState, actions: PumpBtScreenActions) {
    val link = state.link
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

    Spacer(Modifier.padding(top = 12.dp))
    VariantChoice(state.variant, enabled = !link.running, onPick = actions.onVariant)
    Hint(stringResource(R.string.mod_pumpbt_variant_hint))

    Spacer(Modifier.padding(top = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(stringResource(R.string.mod_pumpbt_stop_minimed_hint))
        Button(onClick = actions.onStartPairing, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.mod_pumpbt_start_pairing))
        }
        Hint(stringResource(R.string.mod_pumpbt_start_pairing_hint))
        OutlinedButton(onClick = actions.onWaitForPump, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.mod_pumpbt_wait))
        }
        Hint(stringResource(R.string.mod_pumpbt_wait_hint))
        if (state.keyCrc == null) {
            Text(stringResource(R.string.mod_pumpbt_need_key), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onStop, enabled = link.running) { Text(stringResource(R.string.mod_pumpbt_stop)) }
            OutlinedButton(onClick = actions.onReadAgain, enabled = link.running) { Text(stringResource(R.string.mod_pumpbt_read_again)) }
        }
    }
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

@Composable
private fun ValuesSection(v: PumpBtValues) {
    val none = stringResource(R.string.mod_pumpbt_not_read)
    SectionLabel(stringResource(R.string.mod_pumpbt_section_values, v.readAtMs?.let(::time) ?: none))
    val temp = when {
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
private fun JournalSection(lines: List<String>, actions: PumpBtScreenActions) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_journal))
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.onShareJournal) { Text(stringResource(R.string.mod_pumpbt_share_journal)) }
        OutlinedButton(onClick = { askClear = true }) { Text(stringResource(R.string.mod_pumpbt_clear_journal)) }
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

private fun position(i: Int, n: Int): CardPosition = when {
    n == 1 -> CardPosition.SINGLE
    i == 0 -> CardPosition.TOP
    i == n - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun time(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun dateTime(ms: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 1.25 - "1.25", 150.0 - "150"; the phone's decimal mark. */
private fun units(v: Double): String = DecimalFormat("0.##").format(v)
