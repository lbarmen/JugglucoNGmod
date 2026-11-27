package tk.glucodata.mod.pumpbt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * JugglucoNGmod, pump over Bluetooth: the settings of the pump window ([PumpBtSettingsModel]) - the "no data"
 * threshold 20/30/60 min and why not less (the Bluetooth floor, [CareLinkNotifier.BLUETOOTH_MIN_WARN_MIN]), the insulin
 * type of pump boluses, the calculator's carbs, Nightscout; "Phone" - battery optimization, notifications, the sound of
 * our channel; and that the pump's own alarms do not come to the phone. The rows and the insulin dialog as in the old
 * CareLink window.
 */
@Composable
internal fun SettingsSection(state: PumpBtSettingsState, actions: PumpBtScreenActions) {
    var pickInsulin by rememberSaveable { mutableStateOf(false) }
    if (pickInsulin) {
        InsulinDialog(state, onPick = {
            pickInsulin = false
            actions.onInsulin(it)
        }, onDismiss = { pickInsulin = false })
    }
    SectionLabel(stringResource(R.string.mod_carelink_section_settings))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mod_carelink_warn_after), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        ConnectedButtonGroup(
            options = state.warnChoices,
            selectedOption = state.warnAfterMin,
            onOptionSelected = actions.onWarnAfter,
            label = { Text(stringResource(R.string.mod_carelink_minutes, it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        BtHint(stringResource(R.string.mod_carelink_warn_after_hint))
        BtHint(stringResource(R.string.mod_pumpbt_warn_min, CareLinkNotifier.BLUETOOTH_MIN_WARN_MIN))
    }
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
            onCheckedChange = actions.onWriteCarbs,
            position = CardPosition.MIDDLE,
        )
        SettingsSwitchItem(
            title = stringResource(R.string.mod_carelink_nightscout),
            subtitle = stringResource(R.string.mod_carelink_nightscout_hint),
            checked = state.sendToNightscout,
            onCheckedChange = actions.onNightscout,
            position = CardPosition.BOTTOM,
        )
    }
    SectionLabel(stringResource(R.string.mod_carelink_section_phone))
    PhoneRows(state, actions)
    BtHint(stringResource(R.string.mod_pumpbt_no_pump_alerts), Modifier.padding(top = 8.dp))
}

private class PhoneRow(val title: String, val hint: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun PhoneRows(state: PumpBtSettingsState, actions: PumpBtScreenActions) {
    val tint = MaterialTheme.colorScheme.secondary
    val rows = ArrayList<PhoneRow>(3)
    if (!state.batteryExempt) {
        rows += PhoneRow(
            stringResource(R.string.mod_carelink_battery_exempt), stringResource(R.string.mod_carelink_battery_exempt_hint),
            Symbols.BatterySaverFilled, actions.onBattery,
        )
    }
    if (!state.notificationsAllowed) {
        rows += PhoneRow(
            stringResource(R.string.mod_carelink_notifications),
            stringResource(R.string.mod_carelink_notifications_hint, state.warnAfterMin),
            Symbols.NotificationsFilled, actions.onNotifications,
        )
    } else if (state.channelOff) {
        // the app may notify, our channel is off: Android drops the warnings silently (critic 4d, N6)
        rows += PhoneRow(
            stringResource(R.string.mod_carelink_channel_off), stringResource(R.string.mod_carelink_channel_off_hint),
            Symbols.NotificationsFilled, actions.onSound,
        )
    }
    rows += PhoneRow(
        stringResource(R.string.mod_carelink_sound), stringResource(R.string.mod_carelink_sound_hint),
        Symbols.VolumeUpFilled, actions.onSound,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, r ->
            SettingsItem(title = r.title, subtitle = r.hint, icon = r.icon, iconTint = tint, position = btPosition(i, rows.size), onClick = r.onClick)
        }
    }
}

@Composable
private fun InsulinDialog(state: PumpBtSettingsState, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
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
