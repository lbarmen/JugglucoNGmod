package tk.glucodata.mod.pumpbt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import tk.glucodata.R
import tk.glucodata.mod.carelink.CareLinkNotifier

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2c): the notifications of the Bluetooth pump source, one
 * per [PumpBtAlertKind], on our channel `mod_carelink` with ids from 1 947 010 (the cloud's are 1 947 001-003). The
 * source decides when (once per type, per bolus, per day of drift, per lost bond - [PumpBtAlertRules]); here a repeat
 * of the same kind replaces its notification without a second sound ([NotificationCompat.Builder.setOnlyAlertOnce],
 * critic of the joints 1, N-6). Unknown record types gather in the text of the notification still shown (critic B2c,
 * N4); the card's reminder is posted anew, with its sound. A tap opens the pump window (the `mod` build), else the app.
 * Nothing personal in the text: record types in hex at most, no dose, no serial number. With the app's notifications
 * or our channel off nothing is posted and [show] throws: the source then does not mark the alert as told and tells it
 * again with a later cycle (critic B2c, C1).
 *
 * Build 19: the notification about another app's server in front ([PumpBtAlertKind.FOREIGN_SERVER]) goes on its own
 * channel [URGENT_CHANNEL] with high importance, so that it pops up on the screen: while that server stands the pump
 * may connect any minute (Samsung's own connectable advertisements let it in, phone 28.11 18:00) and Android erases the
 * pairing 30 s later; turning Bluetooth off and on now is the remedy. The importance of a channel cannot be raised
 * once made, hence a new channel. With that channel turned off it goes on the ordinary pump channel.
 */
internal object PumpBtNotifier {

    internal const val ID_BASE = 1_947_010

    /** The pump's urgent channel (build 19): high importance - a heads-up notification with sound. */
    internal const val URGENT_CHANNEL = "mod_pump_urgent"

    /** Whether [kind] goes on [URGENT_CHANNEL]. */
    internal fun urgent(kind: PumpBtAlertKind): Boolean = kind == PumpBtAlertKind.FOREIGN_SERVER

    /** The record types an unknown-record notification shows (its extras: they outlive the process). */
    private const val EXTRA_TYPES = "tk.glucodata.mod.pumpbt.TYPES"

    fun id(kind: PumpBtAlertKind): Int = ID_BASE + kind.ordinal

    /**
     * The source's alert ([PumpBtSource.alert]); any thread. Throws [IllegalStateException] when nothing was shown
     * (notifications off for the app, our channel off, refused, a failure): the source keeps the alert "not told".
     * The first catch-up card's reminder clock starts only once the card is shown.
     */
    fun show(context: Context, alert: PumpBtAlert) {
        val app = context.applicationContext
        val shown = try {
            val types = if (alert.kind == PumpBtAlertKind.UNKNOWN_RECORD) gatheredTypes(app, alert.recordType) else null
            val (title, text) = texts(app, alert, types)
            post(app, id(alert.kind), title, text, types, urgent(alert.kind))
        } catch (t: Throwable) {
            PumpBtLog.w("pump notification failed (${t.javaClass.simpleName})")
            false
        }
        if (!shown) throw IllegalStateException("not shown")
        if (alert.kind == PumpBtAlertKind.CONFIRM_BACKFILL) PumpBtWatchdog.cardTold(app)
    }

    /**
     * The watchdog's reminder of the waiting card: the shown one goes first, so that the new one sounds again (critic
     * B2c, N4). Throws as [show].
     */
    fun remindCard(context: Context) {
        val app = context.applicationContext
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(id(PumpBtAlertKind.CONFIRM_BACKFILL))
        } catch (t: Throwable) {
            PumpBtLog.w("pump notification not removed (${t.javaClass.simpleName})")
        }
        show(app, PumpBtAlert(PumpBtAlertKind.CONFIRM_BACKFILL))
    }

    /** Title and text of [alert] (en, ru in values-ru); [types] - the unknown record types to name (else its own). */
    internal fun texts(app: Context, alert: PumpBtAlert, types: List<Int>? = null): Pair<String, String> {
        val type = (types?.takeIf { it.isNotEmpty() } ?: listOfNotNull(alert.recordType))
            .joinToString(", ") { "0x%04X".format(it) }.ifEmpty { "?" }
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
            PumpBtAlertKind.FOREIGN_SERVER ->
                app.getString(R.string.mod_pumpbt_notify_front_title) to app.getString(R.string.mod_pumpbt_notify_front_text)
            PumpBtAlertKind.FOREIGN_APP ->
                app.getString(R.string.mod_pumpbt_notify_foreign_app_title) to app.getString(R.string.mod_pumpbt_notify_foreign_app_text)
        }
    }

    /** Takes the notification of [kind] away ([PumpBtSource.cancelAlert], build 17); never throws. */
    fun cancel(context: Context, kind: PumpBtAlertKind) {
        try {
            context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(id(kind))
        } catch (t: Throwable) {
            PumpBtLog.w("pump notification not removed (${t.javaClass.simpleName})")
        }
    }

    /** The types of the unknown-record notification still shown, plus [type] (once each, in the order seen). */
    private fun gatheredTypes(app: Context, type: Int?): List<Int> {
        val shown = try {
            app.getSystemService(NotificationManager::class.java)?.activeNotifications
                ?.firstOrNull { it.id == id(PumpBtAlertKind.UNKNOWN_RECORD) }
                ?.notification?.extras?.getIntArray(EXTRA_TYPES)?.toList()
        } catch (_: Throwable) {
            null
        } ?: emptyList()
        return (shown + listOfNotNull(type)).distinct()
    }

    /**
     * false - not shown (notifications off for the app, our channel off, or refused). [urgent] - on [URGENT_CHANNEL]
     * when it is on, else on the ordinary pump channel.
     */
    private fun post(app: Context, id: Int, title: String, text: String, types: List<Int>?, urgent: Boolean = false): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) {
            PumpBtLog.i("pump notification not shown: notifications are off")
            return false
        }
        val onUrgent = urgent && ensureUrgentChannel(app) &&
            nm.getNotificationChannel(URGENT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        if (urgent && !onUrgent) PumpBtLog.i("urgent pump channel off: the notification goes on the pump channel")
        if (!onUrgent) {
            CareLinkNotifier.ensureChannel(app)
            if (CareLinkNotifier.channelOff(app)) {
                PumpBtLog.i("pump notification not shown: the channel is off")
                return false
            }
        }
        val tap = tapIntent(app)?.let {
            PendingIntent.getActivity(app, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(app, if (onUrgent) URGENT_CHANNEL else CareLinkNotifier.CHANNEL)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .apply { if (urgent) setCategory(NotificationCompat.CATEGORY_ERROR) }
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .apply { if (!types.isNullOrEmpty()) addExtras(Bundle().apply { putIntArray(EXTRA_TYPES, types.toIntArray()) }) }
            .build()
        return try {
            nm.notify(id, n)
            true
        } catch (e: SecurityException) {
            PumpBtLog.w("pump notification refused (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Creates [URGENT_CHANNEL] (high importance) if it is not there or has an older name or text; made again with the
     * same id, Android keeps the person's importance and sound. false - could not be made.
     */
    internal fun ensureUrgentChannel(context: Context): Boolean = try {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm == null) {
            false
        } else {
            val name = app.getString(R.string.mod_pumpbt_urgent_channel_name)
            val description = app.getString(R.string.mod_pumpbt_urgent_channel_description)
            val existing = nm.getNotificationChannel(URGENT_CHANNEL)
            if (existing == null || existing.name?.toString() != name || existing.description != description) {
                nm.createNotificationChannel(
                    NotificationChannel(URGENT_CHANNEL, name, NotificationManager.IMPORTANCE_HIGH).apply {
                        this.description = description
                        enableVibration(true)
                    },
                )
            }
            true
        }
    } catch (t: Throwable) {
        PumpBtLog.w("urgent pump channel not made (${t.javaClass.simpleName})")
        false
    }

    /** The pump window when this build has it (the `mod` manifest), else the app's main window. */
    private fun tapIntent(app: Context): Intent? =
        PumpBtActivity.intentIfDeclared(app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: app.packageManager.getLaunchIntentForPackage(app.packageName)
}
