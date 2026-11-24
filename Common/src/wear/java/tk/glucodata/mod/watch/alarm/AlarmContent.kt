package tk.glucodata.mod.watch.alarm

import android.content.Context
import android.content.Intent
import java.util.Locale
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.mod.watch.face.ModFaceSource
import tk.glucodata.ui.AlarmActivity

/** What our alarm screen draws. */
internal data class AlarmUi(
    /** The alert's name ("High"), or the message of an alert without a standard kind. */
    val label: String,
    /** The value as text, in the user's unit ("14.2", "252", "---"). */
    val value: String,
    /** mg/dL per minute for the arrow; NaN - no arrow. */
    val rate: Float,
    /** The value's and the arrow's colour, ARGB. */
    val valueColor: Int,
    /** The small line: the secondary value and the alert's threshold; null - none. */
    val info: String?,
    val snoozeText: String,
    val dismissText: String,
)

/** Our alarm screen's state: what upstream's `AlarmUiModel` holds, plus what the buttons send. */
internal data class AlarmModel(
    val alertTypeId: Int,
    val customAlertId: String?,
    val snoozeMinutes: Int,
    val ui: AlarmUi,
)

/**
 * JugglucoNGmod, watch block (part 8): the alarm screen's content read as upstream's watch `AlarmActivity.buildUiModel`
 * reads it - the same extras (their constants), the same current reading (`CurrentDisplaySource.resolveCurrent()`),
 * the same fallback to the value in the extras, the same label rule and the same snooze minutes
 * (`AlertRepository.loadConfig(type).defaultSnoozeMinutes`, 15 without one). Ours on top: the colour of our face (the
 * app's traffic light, [ModFaceSource.trafficColor]; also for the value from the extras) instead of their band colour
 * that leaves an in-range value neutral, and the threshold of a value alert in the small line.
 */
internal object AlarmContent {

    /** Upstream's `DEFAULT_SNOOZE_MINUTES` (private there). */
    const val DEFAULT_SNOOZE_MINUTES = 15

    /** Upstream's neutral value tone on a dark screen (as our face's fallback). */
    const val NEUTRAL = 0xFFE3E2DE.toInt()

    /** Upstream's `GLUCOSE_VALUE_REGEX` (private there). */
    private val GLUCOSE_VALUE_REGEX = Regex("\\d+(?:[.,]\\d+)?")

    /** Kinds whose `AlertConfig.threshold` is a glucose value in the display unit. */
    private val THRESHOLD_KINDS = setOf(
        AlertType.LOW, AlertType.HIGH, AlertType.VERY_LOW, AlertType.VERY_HIGH,
        AlertType.PRE_LOW, AlertType.PRE_HIGH, AlertType.PERSISTENT_HIGH,
    )

    fun build(context: Context, intent: Intent): AlarmModel {
        val alertTypeId = intent.getIntExtra(AlarmActivity.EXTRA_ALERT_TYPE_ID, -1)
        val alertType = AlertType.fromId(Notify.resolveAlertKind(alertTypeId))
        val snapshot = runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()
        val fallback = fallbackGlucose(intent)
        val rate = snapshot?.rate ?: intent.getFloatExtra(AlarmActivity.EXTRA_RATE, Float.NaN)
        val label = alertType?.let { context.getString(it.nameResId) }
            ?: intent.getStringExtra(AlarmActivity.EXTRA_ALARM_MESSAGE).orEmpty()
                .ifBlank { intent.getStringExtra(AlarmActivity.EXTRA_ALARM_TYPE).orEmpty() }
                .ifBlank { context.getString(R.string.alarms) }
        val config = alertType?.let { runCatching { AlertRepository.loadConfig(it) }.getOrNull() }
        val snoozeMinutes = config?.defaultSnoozeMinutes ?: DEFAULT_SNOOZE_MINUTES
        val isMmol = snapshot?.isMmol ?: (Applic.unit == 1)
        val colorValue = snapshot?.primaryValue ?: fallback?.let { parse(it) }
        val threshold = config?.threshold
            ?.takeIf { alertType in THRESHOLD_KINDS && it.isFinite() && it > 0f }
            ?.let { context.getString(R.string.mod_watch_alarm_threshold, format(it, isMmol)) }
        val info = listOfNotNull(snapshot?.secondaryStr?.takeIf { it.isNotBlank() }, threshold)
            .joinToString(INFO_SEPARATOR)
            .takeIf { it.isNotBlank() }
        return AlarmModel(
            alertTypeId = alertTypeId,
            customAlertId = intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID),
            snoozeMinutes = snoozeMinutes,
            ui = AlarmUi(
                label = label,
                value = snapshot?.primaryStr ?: fallback ?: NO_VALUE,
                rate = rate,
                valueColor = colorValue?.let { color(it, isMmol) } ?: NEUTRAL,
                info = info,
                snoozeText = context.getString(R.string.snooze),
                dismissText = context.getString(R.string.notification_dismiss_action_dismiss),
            ),
        )
    }

    /** Upstream's `fallbackGlucose`: the first number in the value extra, else in the message; null - "---". */
    fun fallbackGlucose(intent: Intent): String? {
        val source = intent.getStringExtra(AlarmActivity.EXTRA_GLUCOSE_VAL).orEmpty()
            .ifBlank { intent.getStringExtra(AlarmActivity.EXTRA_ALARM_MESSAGE).orEmpty() }
        return GLUCOSE_VALUE_REGEX.find(source)?.value?.trim()
    }

    /** Our face's colour of [value]; null when it cannot be had. */
    fun color(value: Float, isMmol: Boolean): Int? =
        if (!value.isFinite() || value <= 0f) null
        else runCatching { ModFaceSource.trafficColor(value, isMmol) }.getOrNull()

    /** A threshold as the app writes values: mmol/L with one decimal, mg/dL whole, the device's decimal sign. */
    fun format(value: Float, isMmol: Boolean): String =
        String.format(Locale.getDefault(), if (isMmol) "%.1f" else "%.0f", value)

    private fun parse(text: String): Float? = text.replace(',', '.').toFloatOrNull()

    /** Upstream's text without a value. */
    const val NO_VALUE = "---"

    private const val INFO_SEPARATOR = "  ·  "
}
