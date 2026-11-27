package tk.glucodata.mod.carelink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import tk.glucodata.R

/**
 * JugglucoNGmod, pump data (stage 4c, 4e; part D1 - the CareLink cloud left the app): two notifications on channel
 * `mod_carelink`, both the Bluetooth pump source's ([PumpSourceSwitch]).
 * - "No pump data for over N min" (N - the threshold, not the age at the moment of showing: the notification is not
 *   updated, a frozen age would understate it hours later; critic of the texts 4d, B2): the newest pump data is older
 *   than the warning threshold ([CareLinkSettings.warnAfterMin], 30 min by default, at least
 *   [BLUETOOTH_MIN_WARN_MIN]) while the source is on; once, until the data comes back, then it goes away by itself.
 * - "Pump records are not saved": the journal or basal write has failed cycle after cycle for longer than the same
 *   threshold (critic 4d2, C3: since a failed write moves the data time, "no data" no longer covers it); goes away
 *   after a cycle that wrote fine.
 * A tap opens the pump window (in a build without it - the app). Nothing personal in the text. With the app's
 * notifications or our channel turned off nothing is posted and nothing is marked shown (critic 4d, N6): the next
 * check posts once they are on again.
 */
object CareLinkNotifier {

    const val CHANNEL = "mod_carelink"
    internal const val ID_NO_DATA = 1_947_002
    internal const val ID_WRITE_FAIL = 1_947_003

    /** The default warning threshold (the pump window offers 20/30/60 min). */
    const val NO_DATA_MS = CareLinkSettings.DEFAULT_WARN_MIN * 60_000L

    /** No data ever since the source was turned on: "no data" comes this much later than the threshold. */
    internal const val FIRST_DATA_MARGIN_MS = 10 * 60_000L

    /**
     * The lowest "no data" threshold while the Bluetooth pump source is on (critic B2c, C2): the pump brings data about
     * every 8 min (journal of the trial, 27.11) and the watchdog checks every 5 min, so 20 min means two reads missed in
     * a row; a lower threshold stored earlier would warn and take it back cycle after cycle. A higher one is kept.
     */
    internal const val BLUETOOTH_MIN_WARN_MIN = 20

    /**
     * After a pump cycle: check the writes and the data age. Each step on its own (critic 4d2, N2): a failure in the
     * write check must not skip the "no data" check.
     */
    fun afterPoll(context: Context, state: CareLinkState, nowMs: Long) {
        val app = context.applicationContext
        try {
            checkWrites(app, state, nowMs)
        } catch (t: Throwable) {
            CareLinkLog.w("write check failed (${t.javaClass.simpleName})")
        }
        checkNoData(app, state, nowMs) // has its own try
    }

    /**
     * "Pump records are not saved": a cycle that wrote fine clears the mark and the notification; a write error starts
     * the clock (first failing cycle) or, past the threshold, shows the notification once. A cycle with nothing to write
     * keeps the write error in the state while the clock runs (critic 4d, N1; 4d2, N1). Source off meanwhile - nothing
     * (critic 4d, N2). Marked shown while the notifications are off - the mark goes (critic 4d2, N4, see [blocked]).
     * Alive while the Bluetooth pump source is on.
     */
    internal fun checkWrites(context: Context, state: CareLinkState, nowMs: Long) {
        val app = context.applicationContext
        if (!PumpSourceSwitch.bluetoothOn(app)) return
        val store = CareLinkStateStore(app)
        when {
            state.status == CareLinkStatus.OK -> {
                if (store.writeFailSinceMs != null) store.writeFailSinceMs = null
                if (store.writeFailShown) {
                    cancel(app, ID_WRITE_FAIL)
                    store.writeFailShown = false
                }
            }
            state.status == CareLinkStatus.ERROR && state.lastError?.kind == CareLinkErrorKind.WRITE -> {
                val since = store.writeFailSinceMs ?: nowMs.also { store.writeFailSinceMs = it }
                val minutes = ((nowMs - since) / 60_000L).toInt()
                if (nowMs - since < CareLinkSettings(app).warnAfterMs) return
                if (store.writeFailShown && !blocked(app)) return
                val posted = post(app, ID_WRITE_FAIL, app.getString(R.string.mod_carelink_write_fail_title),
                    app.getString(R.string.mod_carelink_write_fail_text, minutes))
                if (posted != store.writeFailShown) store.writeFailShown = posted
            }
        }
    }

    /**
     * "No pump data for over N min": shown once when the newest pump data is older than the warning threshold while
     * the Bluetooth pump source is on; taken away when data is fresh again or the source is off. Called after every
     * pump cycle, by the source's watchdog without a cycle and by the pump window when the threshold changes. The base
     * is the newest data or, when none has come since the source was turned on, that moment
     * ([PumpSourceSwitch.onSinceMs]) 10 min later ([FIRST_DATA_MARGIN_MS]) - older data does not make the source's first
     * minutes "no data". The threshold is read each time and is at least [BLUETOOTH_MIN_WARN_MIN] (the title names the
     * threshold in force).
     */
    fun checkNoData(context: Context, state: CareLinkState = CareLinkStateStore(context).read(),
                    nowMs: Long = CareLinkClock.now()) {
        val app = context.applicationContext
        try {
            val store = CareLinkStateStore(app)
            val settings = CareLinkSettings(app)
            val live = PumpSourceSwitch.bluetoothOn(app)
            val last = if (!live) null else
                listOfNotNull(state.lastDataAtMs, PumpSourceSwitch.onSinceMs(app)?.plus(FIRST_DATA_MARGIN_MS)).maxOrNull()
            val warnMin = maxOf(settings.warnAfterMin, BLUETOOTH_MIN_WARN_MIN)
            val stale = live && last != null && nowMs - last >= warnMin * 60_000L
            if (stale) {
                if (store.noDataShown && !blocked(app)) return
                val posted = post(app, ID_NO_DATA, app.getString(R.string.mod_carelink_no_data_title, warnMin),
                    app.getString(R.string.mod_carelink_no_data_text))
                if (posted != store.noDataShown) store.noDataShown = posted
            } else if (store.noDataShown) {
                cancel(app, ID_NO_DATA)
                store.noDataShown = false
            }
        } catch (t: Throwable) {
            CareLinkLog.w("no-data check failed (${t.javaClass.simpleName})")
        }
    }

    /** Every notification of the channel goes away, with its marks and clock. */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        try {
            cancel(app, ID_NO_DATA)
            cancel(app, ID_WRITE_FAIL)
            CareLinkStateStore(app).apply {
                noDataShown = false
                writeFailShown = false
                writeFailSinceMs = null
            }
        } catch (t: Throwable) {
            CareLinkLog.w("notifications not removed (${t.javaClass.simpleName})")
        }
    }

    /** false - not shown (notifications off for the app, our channel off, or refused). */
    private fun post(app: Context, id: Int, title: String, text: String): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) {
            CareLinkLog.i("notification not shown: notifications are off")
            return false
        }
        ensureChannel(app)
        if (channelOff(app)) {
            CareLinkLog.i("notification not shown: the CareLink channel is off")
            return false
        }
        val tap = tapIntent(app)?.let {
            PendingIntent.getActivity(app, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(app, CHANNEL)
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
            CareLinkLog.w("notification refused (${e.javaClass.simpleName})")
            false
        }
    }

    /** The pump window when this build has it (the `mod` manifest), else the app's main window. */
    internal fun tapIntent(app: Context): Intent? =
        tk.glucodata.mod.pumpbt.PumpBtActivity.intentIfDeclared(app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: app.packageManager.getLaunchIntentForPackage(app.packageName)

    /**
     * The person turned channel [CHANNEL] off in the system (critic 4d, N6): Android then drops our notifications
     * silently. false when the channel is not made yet (it is made on and so not off).
     */
    internal fun channelOff(context: Context): Boolean {
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return false
        return nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /**
     * The app's notifications or our channel are off. A notification marked shown is then gone (Android removes it):
     * the checks drop the mark, so the first check after they are on again posts it (critic 4d2, N4). A swipe or a tap
     * keeps the mark - not posted again until the data comes back.
     */
    private fun blocked(app: Context): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        return !nm.areNotificationsEnabled() || channelOff(app)
    }

    private fun cancel(app: Context, id: Int) {
        app.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /**
     * Creates channel [CHANNEL] if it is not there. Internal for the pump window: its "sound and vibration"
     * button opens the channel's system screen, which needs the channel to exist first (critic of the joints, N4).
     */
    internal fun ensureChannel(context: Context) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.mod_carelink_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = app.getString(R.string.mod_carelink_channel_description) },
        )
    }
}
