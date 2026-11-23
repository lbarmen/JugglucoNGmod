package tk.glucodata.mod.carelink

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ConnectedButtonGroup
import java.util.Locale

/** What the window asks of its activity: system screens, the sign-in window, closing. */
class CareLinkScreenActions(
    val onBack: () -> Unit,
    val onSignIn: () -> Unit,
    val onBattery: () -> Unit,
    val onNotifications: () -> Unit,
    val onSound: () -> Unit,
)

/**
 * JugglucoNGmod, CareLink (stage 4e): the drawing of the CareLink window, in upstream's settings style (their top bar,
 * section labels, setting rows, button groups). State and changes - [CareLinkViewModel]; nothing read or written here.
 */
@Composable
internal fun CareLinkScreen(model: CareLinkViewModel, actions: CareLinkScreenActions) {
    val state by model.state.collectAsStateWithLifecycle()
    var askSignOut by remember { mutableStateOf(false) }
    var pickInsulin by remember { mutableStateOf(false) }

    if (askSignOut) {
        AlertDialog(
            onDismissRequest = { askSignOut = false },
            title = { Text(stringResource(R.string.mod_carelink_sign_out_title)) },
            text = { Text(stringResource(R.string.mod_carelink_sign_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askSignOut = false
                    model.signOut()
                }) { Text(stringResource(R.string.mod_carelink_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { askSignOut = false }) { Text(stringResource(R.string.mod_carelink_cancel)) }
            },
        )
    }
    if (pickInsulin) {
        InsulinDialog(state, onPick = {
            pickInsulin = false
            model.setInsulin(it)
        }, onDismiss = { pickInsulin = false })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { AppTopBar(stringResource(R.string.mod_carelink_title), actions.onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        ) {
            StatusCard(state, actions, onSignOut = { askSignOut = true }, onPollNow = model::pollNow,
                onPollingOn = model::turnPollingOn)

            val pump = state.pump
            if (state.signedIn && pump != null) {
                // one data time for every pump number (critic of the texts 4d, C10): old numbers must not look fresh
                SectionLabel(stringResource(R.string.mod_carelink_section_pump, age(state.nowMs, pump.dataAtMs)))
                PumpRows(pump)
            }

            SectionLabel(stringResource(R.string.mod_carelink_section_settings))
            Choice(
                title = stringResource(R.string.mod_carelink_interval),
                hint = null,
                warning = null,
                options = CareLinkSettings.INTERVALS_MIN,
                selected = state.intervalMin,
                onPick = model::setInterval,
            )
            Spacer(Modifier.padding(top = 16.dp))
            Choice(
                title = stringResource(R.string.mod_carelink_warn_after),
                hint = stringResource(R.string.mod_carelink_warn_after_hint),
                // a threshold below "interval + 5 min" warns while all is well; said, not forbidden: the person wants
                // 5 min to test it (word 17.11; critic 4d, C1, critic of the texts 4d, C4)
                warning = if (state.warnTooSoon) {
                    stringResource(R.string.mod_carelink_warn_after_too_soon, state.intervalMin, state.warnMinQuiet)
                } else {
                    null
                },
                options = CareLinkSettings.WARN_AFTER_MIN,
                selected = state.warnAfterMin,
                onPick = model::setWarnAfter,
            )
            Spacer(Modifier.padding(top = 16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsItem(
                    title = stringResource(R.string.mod_carelink_insulin),
                    subtitle = state.insulinName ?: stringResource(R.string.mod_carelink_insulin_none),
                    icon = Symbols.ScienceFilled,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.TOP,
                    onClick = if (state.insulinChoices.isEmpty()) null else ({ pickInsulin = true }),
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.mod_carelink_write_carbs),
                    subtitle = stringResource(R.string.mod_carelink_write_carbs_hint),
                    checked = state.writeCarbs,
                    onCheckedChange = model::setWriteCarbs,
                    position = CardPosition.MIDDLE,
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.mod_carelink_nightscout),
                    subtitle = stringResource(R.string.mod_carelink_nightscout_hint),
                    checked = state.sendToNightscout,
                    onCheckedChange = model::setSendToNightscout,
                    position = CardPosition.BOTTOM,
                )
            }

            SectionLabel(stringResource(R.string.mod_carelink_section_phone))
            PhoneRows(state, actions)
        }
    }
}

@Composable
private fun StatusCard(
    state: CareLinkUiState,
    actions: CareLinkScreenActions,
    onSignOut: () -> Unit,
    onPollNow: () -> Unit,
    onPollingOn: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (title, color) = when (state.view) {
        CareLinkView.SIGNED_OUT -> stringResource(R.string.mod_carelink_status_signed_out) to colors.onSurface
        CareLinkView.NEED_LOGIN -> stringResource(R.string.mod_carelink_status_need_login) to colors.error
        CareLinkView.POLLING_OFF -> stringResource(R.string.mod_carelink_status_polling_off) to colors.error
        CareLinkView.WAITING -> stringResource(R.string.mod_carelink_status_waiting) to colors.onSurface
        CareLinkView.OK -> stringResource(R.string.mod_carelink_status_ok) to colors.primary
        CareLinkView.STALE -> stringResource(R.string.mod_carelink_status_stale) to colors.error
        // the answer came, the journal did not take it: not "the update failed" (critic of the texts 4d, C5)
        CareLinkView.ERROR -> stringResource(
            if (state.error?.kind == CareLinkErrorKind.WRITE) R.string.mod_carelink_status_error_write
            else R.string.mod_carelink_status_error,
        ) to colors.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = color)
            statusText(state)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (state.signedIn) {
                val data = state.lastDataAtMs
                Text(
                    if (data == null) stringResource(R.string.mod_carelink_data_none)
                    else stringResource(R.string.mod_carelink_data_age, age(state.nowMs, data)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                state.lastPollAtMs?.let {
                    Text(
                        stringResource(R.string.mod_carelink_poll_age, age(state.nowMs, it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.padding(top = 4.dp))
            if (state.signedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.mod_carelink_signed_in),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                    OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.mod_carelink_sign_out_button)) }
                }
                if (state.view == CareLinkView.POLLING_OFF) {
                    Button(onClick = onPollingOn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.mod_carelink_polling_on))
                    }
                } else {
                    TextButton(onClick = onPollNow) { Text(stringResource(R.string.mod_carelink_poll_now)) }
                }
            } else {
                Button(onClick = actions.onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mod_carelink_sign_in))
                }
                Text(
                    stringResource(R.string.mod_carelink_sign_in_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun statusText(state: CareLinkUiState): String? = when (state.view) {
    CareLinkView.SIGNED_OUT -> stringResource(R.string.mod_carelink_status_signed_out_text)
    CareLinkView.NEED_LOGIN -> stringResource(R.string.mod_carelink_status_need_login_text)
    CareLinkView.POLLING_OFF -> stringResource(R.string.mod_carelink_status_polling_off_text)
    CareLinkView.WAITING -> stringResource(R.string.mod_carelink_status_waiting_text)
    CareLinkView.OK -> null
    // no data ever since polling was turned on - the words of the "no data" notification (critic 4e, N2)
    CareLinkView.STALE -> state.lastDataAtMs?.let { stringResource(R.string.mod_carelink_status_stale_text, age(state.nowMs, it)) }
        ?: stringResource(R.string.mod_carelink_no_data_text)
    CareLinkView.ERROR -> errorText(state.error)
}

@Composable
private fun errorText(e: CareLinkError?): String {
    val text = when (e?.kind) {
        CareLinkErrorKind.NO_NETWORK -> stringResource(R.string.mod_carelink_error_network)
        CareLinkErrorKind.SERVER -> stringResource(R.string.mod_carelink_error_server, e.code)
        CareLinkErrorKind.BAD_ANSWER -> stringResource(R.string.mod_carelink_error_bad_answer)
        CareLinkErrorKind.STORAGE -> stringResource(R.string.mod_carelink_error_storage)
        CareLinkErrorKind.PARSE -> stringResource(R.string.mod_carelink_error_parse)
        CareLinkErrorKind.WRITE -> stringResource(R.string.mod_carelink_error_write)
        null -> stringResource(R.string.mod_carelink_error_parse)
    }
    // the exception class helps a fix (the person sends it), said to be a code; never a body or a key. A failed write
    // gets plain words only (critic of the texts 4d, C5): the class (SQLiteException...) tells the person nothing.
    val cls = e?.errorClass?.takeIf {
        it.isNotBlank() && e.kind != CareLinkErrorKind.SERVER && e.kind != CareLinkErrorKind.WRITE
    }
    return if (cls == null) text else stringResource(R.string.mod_carelink_error_code, text, cls)
}

@Composable
private fun PumpRows(pump: PumpNow) {
    val tint = MaterialTheme.colorScheme.tertiary
    val rows = ArrayList<Pair<String, String>>(3)
    pump.iobUnits?.let { iob ->
        rows += stringResource(R.string.mod_carelink_iob) to stringResource(R.string.mod_carelink_units, units(iob))
    }
    val reservoir = listOfNotNull(
        pump.reservoirUnits?.let { stringResource(R.string.mod_carelink_units, units(it)) },
        pump.reservoirPercent?.let { stringResource(R.string.mod_carelink_percent, it) },
    )
    if (reservoir.isNotEmpty()) rows += stringResource(R.string.mod_carelink_reservoir) to reservoir.joinToString(" · ")
    pump.batteryPercent?.let { rows += stringResource(R.string.mod_carelink_battery) to stringResource(R.string.mod_carelink_percent, it) }
    if (rows.isEmpty()) {
        Text(stringResource(R.string.mod_carelink_data_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, (title, value) ->
            SettingsItem(title = title, subtitle = value, iconTint = tint, position = position(i, rows.size))
        }
    }
}

@Composable
private fun PhoneRows(state: CareLinkUiState, actions: CareLinkScreenActions) {
    val tint = MaterialTheme.colorScheme.secondary
    val rows = ArrayList<PhoneRow>(3)
    if (!state.batteryExempt) {
        rows += PhoneRow(stringResource(R.string.mod_carelink_battery_exempt), stringResource(R.string.mod_carelink_battery_exempt_hint),
            Symbols.BatterySaverFilled, actions.onBattery)
    }
    if (!state.notificationsAllowed) {
        rows += PhoneRow(stringResource(R.string.mod_carelink_notifications),
            stringResource(R.string.mod_carelink_notifications_hint, state.warnAfterMin),
            Symbols.NotificationsFilled, actions.onNotifications)
    } else if (state.channelOff) {
        // the app may notify, our channel is off: Android drops the warnings silently (critic 4d, N6)
        rows += PhoneRow(stringResource(R.string.mod_carelink_channel_off), stringResource(R.string.mod_carelink_channel_off_hint),
            Symbols.NotificationsFilled, actions.onSound)
    }
    rows += PhoneRow(stringResource(R.string.mod_carelink_sound), stringResource(R.string.mod_carelink_sound_hint),
        Symbols.VolumeUpFilled, actions.onSound)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, r ->
            SettingsItem(title = r.title, subtitle = r.hint, icon = r.icon, iconTint = tint,
                position = position(i, rows.size), onClick = r.onClick)
        }
    }
}

private class PhoneRow(val title: String, val hint: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun Choice(title: String, hint: String?, warning: String?, options: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        ConnectedButtonGroup(
            options = options,
            selectedOption = selected,
            onOptionSelected = onPick,
            label = { Text(stringResource(R.string.mod_carelink_minutes, it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        warning?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun InsulinDialog(state: CareLinkUiState, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mod_carelink_insulin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.mod_carelink_insulin_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                state.insulinChoices.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = c.id == state.insulinId, role = Role.RadioButton, onClick = { onPick(c.id) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c.id == state.insulinId, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            // a tap on a type already chooses it: the only button closes (critic of the texts 4d, N4)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mod_carelink_close)) }
        },
    )
}

private fun position(i: Int, n: Int): CardPosition = when {
    n == 1 -> CardPosition.SINGLE
    i == 0 -> CardPosition.TOP
    i == n - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

/** 1.5 - "1.5", 150.0 - "150"; the phone's decimal mark. */
private fun units(v: Double): String =
    if (v == Math.floor(v)) String.format(Locale.getDefault(), "%.0f", v) else String.format(Locale.getDefault(), "%.1f", v)

@Composable
private fun age(nowMs: Long, atMs: Long): String {
    val min = ((nowMs - atMs).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        min < 1 -> stringResource(R.string.mod_carelink_age_now)
        min < 60 -> stringResource(R.string.mod_carelink_age_min, min)
        min < 24 * 60 -> stringResource(R.string.mod_carelink_age_hours, min / 60, min % 60)
        else -> stringResource(R.string.mod_carelink_age_day)
    }
}
