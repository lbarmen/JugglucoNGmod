package tk.glucodata.mod.pumpbt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.ui.components.SectionLabel

/**
 * JugglucoNGmod, pump over Bluetooth: the pairing wizard at the top of the pump window (plan G2b, point 1; steps -
 * [PumpBtWindow.wizardStep], their lines and buttons - [PumpBtWindow.wizardLines], [PumpBtWindow.wizardButtons]). The
 * warnings before the first pairing (series brief, decision 14), the phone, the start (MiniMed Mobile stopped and
 * unpaired on the pump, "Mobile NNNNNN" chosen on the pump, the request confirmed on the phone), the key database
 * downloaded by the start (decision 11; Retry, Cancel, Load from file), the pairing with its wait time and the advice
 * after a failed one (decision 12; the other name only then), paired - "Permanent operation" or "Pair again".
 */
@Composable
internal fun WizardSection(
    step: PumpBtWizardStep,
    state: PumpBtUiState,
    source: PumpBtSourceState,
    actions: PumpBtScreenActions,
    onTurnOn: () -> Unit,
) {
    SectionLabel(stringResource(R.string.mod_pumpbt_section_wizard))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stepTitle(step), style = MaterialTheme.typography.titleMedium)
            WizardLines(step, state, source)
            Spacer(Modifier.padding(top = 4.dp))
            val controls = PumpBtWindow.controls(state, source)
            PumpBtWindow.wizardButtons(step, state, source).forEachIndexed { i, button ->
                val enabled = when (button) {
                    PumpBtWizardButton.START -> controls.canPairAgain
                    PumpBtWizardButton.RETRY, PumpBtWizardButton.LOAD_FILE -> !state.keyLoading
                    PumpBtWizardButton.TURN_ON -> controls.canTurnOn
                    PumpBtWizardButton.PAIR_AGAIN -> controls.canPairAgain
                    else -> true
                }
                val onClick: () -> Unit = when (button) {
                    PumpBtWizardButton.ACCEPT -> actions.onAcceptWarnings
                    PumpBtWizardButton.ALLOW -> actions.onAllow
                    PumpBtWizardButton.START, PumpBtWizardButton.PAIR_AGAIN -> actions.onStartPairing
                    // the start that waited for the key: a pairing, or the diagnostics' "Wait for the pump"
                    PumpBtWizardButton.RETRY -> if (state.pendingStart == false) actions.onWaitForPump else actions.onStartPairing
                    PumpBtWizardButton.CANCEL -> actions.onCancelKey
                    PumpBtWizardButton.LOAD_FILE -> actions.onLoadKeyFile
                    PumpBtWizardButton.OTHER_NAME -> actions.onTryOtherName
                    PumpBtWizardButton.STOP -> actions.onStop
                    PumpBtWizardButton.TURN_ON -> onTurnOn
                }
                if (i == 0) {
                    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(buttonText(button)) }
                } else {
                    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(buttonText(button)) }
                }
            }
        }
    }
}

/** The text lines of a wizard step ([PumpBtWindow.wizardLines]). */
@Composable
private fun WizardLines(step: PumpBtWizardStep, state: PumpBtUiState, source: PumpBtSourceState) {
    val numbered = step == PumpBtWizardStep.START
    PumpBtWindow.wizardLines(step, state, source).forEachIndexed { i, line ->
        val text = lineText(line, step, state, source)
        when {
            line == PumpBtWizardLine.PAIR_WAIT -> PairWait(state.pairingStartedAtMs)
            line == PumpBtWizardLine.PAIRING_FAILED || line == PumpBtWizardLine.KEY_FAILED ||
                line == PumpBtWizardLine.FRONT_BLOCKS_PAIRING -> BtWarning(text)
            line == PumpBtWizardLine.KEY_NEEDS_INTERNET || line == PumpBtWizardLine.TURN_ON_HINT ||
                line == PumpBtWizardLine.OTHER_NAME_HINT -> BtHint(text)
            step == PumpBtWizardStep.WARNINGS -> BtBody("• $text")
            numbered -> BtBody("${i + 1}. $text")
            else -> BtBody(text)
        }
    }
}

/**
 * "Pair again" with the source on ([PumpBtWindow.pairAgainShown]: the lost-bond card, the diagnostics): the wizard's
 * PAIRING lines - the wait, the pump steps, the advice after a failed one - and "Cancel pairing" in sight (critic G2, C-4).
 * The other name is not offered here: with the source on a pairing is always variant A (finding 11).
 */
@Composable
internal fun PairingAgainCard(state: PumpBtUiState, source: PumpBtSourceState, actions: PumpBtScreenActions) {
    Spacer(Modifier.padding(top = 8.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mod_pumpbt_wizard_pairing_title), style = MaterialTheme.typography.titleMedium)
            WizardLines(PumpBtWizardStep.PAIRING, state, source)
            Spacer(Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = actions.onCancelPairing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mod_pumpbt_cancel_pairing))
            }
            BtHint(stringResource(R.string.mod_pumpbt_cancel_pairing_hint))
        }
    }
}

@Composable
private fun stepTitle(step: PumpBtWizardStep): String = stringResource(
    when (step) {
        PumpBtWizardStep.WARNINGS -> R.string.mod_pumpbt_wizard_warnings_title
        PumpBtWizardStep.PHONE -> R.string.mod_pumpbt_wizard_phone_title
        PumpBtWizardStep.START -> R.string.mod_pumpbt_wizard_start_title
        PumpBtWizardStep.KEY -> R.string.mod_pumpbt_wizard_key_title
        PumpBtWizardStep.PAIRING -> R.string.mod_pumpbt_wizard_pairing_title
        PumpBtWizardStep.PAIRED, PumpBtWizardStep.NONE -> R.string.mod_pumpbt_wizard_paired_title
    },
)

@Composable
private fun lineText(line: PumpBtWizardLine, step: PumpBtWizardStep, state: PumpBtUiState, source: PumpBtSourceState): String =
    when (line) {
        PumpBtWizardLine.FAMILY -> stringResource(R.string.mod_pumpbt_warn_family)
        PumpBtWizardLine.NOT_MEDICAL -> stringResource(R.string.mod_pumpbt_warn_not_medical)
        PumpBtWizardLine.UNPAIR_MINIMED_LOSES -> stringResource(R.string.mod_pumpbt_warn_unpair_minimed)
        PumpBtWizardLine.NOT_CHECKED -> stringResource(R.string.mod_pumpbt_warn_not_checked)
        PumpBtWizardLine.GO_BACK -> stringResource(R.string.mod_pumpbt_warn_go_back)
        PumpBtWizardLine.NO_BLUETOOTH -> stringResource(R.string.mod_pumpbt_no_bluetooth)
        PumpBtWizardLine.BLUETOOTH_OFF -> stringResource(R.string.mod_pumpbt_bluetooth_off)
        PumpBtWizardLine.NO_PERMISSION -> stringResource(R.string.mod_pumpbt_no_permissions)
        PumpBtWizardLine.NO_ADVERTISING -> stringResource(R.string.mod_pumpbt_no_advertising)
        PumpBtWizardLine.STOP_MINIMED -> stringResource(R.string.mod_pumpbt_stop_minimed_hint)
        PumpBtWizardLine.UNPAIR_ON_PUMP -> stringResource(R.string.mod_pumpbt_step_unpair_on_pump)
        PumpBtWizardLine.PAIR_ON_PUMP -> stringResource(
            if (step == PumpBtWizardStep.START) R.string.mod_pumpbt_step_pair_after_start else R.string.mod_pumpbt_step_pair_on_pump,
            state.advertisedName,
        )
        PumpBtWizardLine.CONFIRM_ON_PHONE -> stringResource(R.string.mod_pumpbt_step_confirm)
        PumpBtWizardLine.KEY_NEEDS_INTERNET -> stringResource(R.string.mod_pumpbt_key_needs_internet)
        PumpBtWizardLine.KEY_LOADING -> stringResource(R.string.mod_pumpbt_key_loading)
        PumpBtWizardLine.KEY_FAILED -> stringResource(R.string.mod_pumpbt_key_failed, keyFailureText(state) ?: "—")
        PumpBtWizardLine.FRONT_BLOCKS_PAIRING -> stringResource(R.string.mod_pumpbt_front_pairing)
        PumpBtWizardLine.PAIR_WAIT -> ""
        PumpBtWizardLine.PAIRING_FAILED -> stringResource(R.string.mod_pumpbt_pairing_failed, state.advertisedName)
        PumpBtWizardLine.OTHER_NAME_HINT -> stringResource(R.string.mod_pumpbt_variant_hint)
        PumpBtWizardLine.OTHER_NAME_ON -> stringResource(R.string.mod_pumpbt_variant_b)
        PumpBtWizardLine.PAIRED -> stringResource(R.string.mod_pumpbt_paired_text)
        PumpBtWizardLine.TURN_ON_HINT -> PumpBtWindow.turnOnHint(state, source)?.let { turnOnHintText(it) } ?: ""
    }

@Composable
private fun buttonText(button: PumpBtWizardButton): String = stringResource(
    when (button) {
        PumpBtWizardButton.ACCEPT -> R.string.mod_pumpbt_accept
        PumpBtWizardButton.ALLOW -> R.string.mod_pumpbt_allow
        PumpBtWizardButton.START -> R.string.mod_pumpbt_start_pairing
        PumpBtWizardButton.RETRY -> R.string.mod_pumpbt_retry
        PumpBtWizardButton.CANCEL -> R.string.mod_pumpbt_cancel
        PumpBtWizardButton.LOAD_FILE -> R.string.mod_pumpbt_load_key_file
        PumpBtWizardButton.OTHER_NAME -> R.string.mod_pumpbt_other_name
        PumpBtWizardButton.STOP -> R.string.mod_pumpbt_stop
        PumpBtWizardButton.TURN_ON -> R.string.mod_pumpbt_turn_on
        PumpBtWizardButton.PAIR_AGAIN -> R.string.mod_pumpbt_pair_again
    },
)

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
    BtBody(
        stringResource(
            R.string.mod_pumpbt_pair_wait,
            stringResource(R.string.mod_pumpbt_duration, (sec / 60).toInt(), (sec % 60).toInt()),
        ),
    )
}
