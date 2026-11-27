package tk.glucodata.mod.pumpbt

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import tk.glucodata.R
import tk.glucodata.mod.carelink.CareLinkNotifier

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2c): the notifications of the Bluetooth pump source, one
 * per [PumpBtAlertKind], on our channel `mod_carelink` with ids from 1 947 010 (the cloud's are 1 947 001-003). The
 * source decides when (once per type, per bolus, per day of drift, per lost bond - [PumpBtAlertRules]); here a repeat
 * of the same kind replaces its notification without a second sound ([NotificationCompat.Builder.setOnlyAlertOnce],
 * critic of the joints 1, N-6). A tap opens the pump window (the `mod` build), else the app. Nothing personal in the
 * text: a record type in hex at most, no dose, no serial number. With the app's notifications or our channel off
 * nothing is posted (logged).
 */
internal object PumpBtNotifier {

    internal const val ID_BASE = 1_947_010

    fun id(kind: PumpBtAlertKind): Int = ID_BASE + kind.ordinal

    /** The source's alert ([PumpBtSource.alert]); any thread, never throws. */
    fun show(context: Context, alert: PumpBtAlert) {
        val app = context.applicationContext
        try {
            if (alert.kind == PumpBtAlertKind.CONFIRM_BACKFILL) PumpBtWatchdog.cardTold(app)
            val (title, text) = texts(app, alert)
            post(app, id(alert.kind), title, text)
        } catch (t: Throwable) {
            PumpBtLog.w("pump notification failed (${t.javaClass.simpleName})")
        }
    }

    /** Title and text of [alert] (en, ru in values-ru). */
    internal fun texts(app: Context, alert: PumpBtAlert): Pair<String, String> {
        val type = alert.recordType?.let { "0x%04X".format(it) } ?: "?"
        return when (alert.kind) {
            PumpBtAlertKind.UNKNOWN_RECORD ->
                app.getString(R.string.mod_pumpbt_notify_unknown_title) to app.getString(R.string.mod_pumpbt_notify_unknown_text, type)
            PumpBtAlertKind.UNPARSED_BOLUS ->
                app.getString(R.string.mod_pumpbt_notify_unparsed_title) to app.getString(R.string.mod_pumpbt_notify_unparsed_text, type)
            PumpBtAlertKind.CONFIRM_BACKFILL ->
                app.getString(R.string.mod_pumpbt_notify_backfill_title) to app.getString(R.string.mod_pumpbt_notify_backfill_text)
            PumpBtAlertKind.NO_RESULT_BOLUS ->
                app.getString(R.string.mod_pumpbt_notify_no_result_title) to app.getString(R.string.mod_pumpbt_notify_no_result_text)
            PumpBtAlertKind.CLOCK_DRIFT ->
                app.getString(R.string.mod_pumpbt_notify_drift_title) to app.getString(R.string.mod_pumpbt_notify_drift_text)
            PumpBtAlertKind.BOND_LOST ->
                app.getString(R.string.mod_pumpbt_notify_bond_lost_title) to app.getString(R.string.mod_pumpbt_notify_bond_lost_text)
        }
    }

    /** false - not shown (notifications off for the app, our channel off, or refused). */
    private fun post(app: Context, id: Int, title: String, text: String): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) {
            PumpBtLog.i("pump notification not shown: notifications are off")
            return false
        }
        CareLinkNotifier.ensureChannel(app)
        if (CareLinkNotifier.channelOff(app)) {
            PumpBtLog.i("pump notification not shown: the channel is off")
            return false
        }
        val tap = tapIntent(app)?.let {
            PendingIntent.getActivity(app, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(app, CareLinkNotifier.CHANNEL)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        return try {
            nm.notify(id, n)
            true
        } catch (e: SecurityException) {
            PumpBtLog.w("pump notification refused (${e.javaClass.simpleName})")
            false
        }
    }

    /** The pump window when this build has it (the `mod` manifest), else the app's main window. */
    private fun tapIntent(app: Context): Intent? =
        PumpBtActivity.intentIfDeclared(app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: app.packageManager.getLaunchIntentForPackage(app.packageName)
}
