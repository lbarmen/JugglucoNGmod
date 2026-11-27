package tk.glucodata.mod.carelink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import tk.glucodata.R

/**
 * JugglucoNGmod, CareLink (stage 4c, 4e): three notifications on channel `mod_carelink`.
 * - "Sign in to CareLink again": the refresh key was refused; once, until a sign-in.
 * - "No pump data for over N min" (N - the threshold, not the age at the moment of showing: the notification is not
 *   updated, a frozen age would understate it hours later; critic of the texts 4d, B2): the newest pump data is older
 *   than the warning threshold ([CareLinkSettings.warnAfterMin], 30 min by default) while the session lives; once,
 *   until the data comes back, then it goes away by itself.
 * - "Pump records are not saved": the journal or basal write has failed poll after poll for longer than the same
 *   threshold (critic 4d2, C3: since a failed write moves the data time, "no data" no longer covers it); goes away
 *   after a poll that wrote fine.
 * A tap opens the CareLink window (4e; in a build without it - the app). Nothing personal in the text. With the app's
 * notifications or our channel turned off nothing is posted and nothing is marked shown (critic 4d, N6): the next
 * check posts once they are on again.
 * Part B1 (pump over Bluetooth): while the Bluetooth pump source is on, "no data" and "not saved" are alive for it
 * (whatever the CareLink session) and "sign in again" is never shown ([PumpSourceSwitch]).
 */
object CareLinkNotifier {

    const val CHANNEL = "mod_carelink"
    internal const val ID_NEED_LOGIN = 1_947_001
    internal const val ID_NO_DATA = 1_947_002
    internal const val ID_WRITE_FAIL = 1_947_003

    /** The default warning threshold (the person can choose 5-60 min in the CareLink window). */
    const val NO_DATA_MS = CareLinkSettings.DEFAULT_WARN_MIN * 60_000L

    /** No data ever since polling was turned on: "no data" comes this much later than the threshold. */
    internal const val FIRST_DATA_MARGIN_MS = 10 * 60_000L

    /**
     * The lowest "no data" threshold while the Bluetooth pump source is on (critic B2c, C2): the pump brings data about
     * every 8 min (journal of the trial, 27.11) and the watchdog checks every 5 min, so 20 min means two reads missed in
     * a row; a lower threshold chosen for the cloud would warn and take it back cycle after cycle. A higher one is kept.
     */
    internal const val BLUETOOTH_MIN_WARN_MIN = 20

    /**
     * The sign-in is gone: show "sign in again" once; "no data" and "not saved" are replaced by it. Not while the
     * Bluetooth pump source is on (series brief, decision 17): the cloud asks for nothing then, and "no data" and "not
     * saved" are the source's.
     */
    fun needLogin(context: Context) {
        val app = context.applicationContext
        try {
            if (PumpSourceSwitch.bluetoothOn(app)) return
            val store = CareLinkStateStore(app)
            cancel(app, ID_NO_DATA)
            store.noDataShown = false
            cancel(app, ID_WRITE_FAIL)
            store.writeFailShown = false
            store.writeFailSinceMs = null
            if (store.needLoginShown) return
            if (post(app, ID_NEED_LOGIN, app.getString(R.string.mod_carelink_need_login_title),
                    app.getString(R.string.mod_carelink_need_login_text))
            ) {
                store.needLoginShown = true
            }
        } catch (t: Throwable) {
            CareLinkLog.w("need-login notification failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * After a poll that reached the cloud or failed on the way: the sign-in works, check the data age and the writes.
     * Each step on its own (critic 4d2, N2): while polls run, this is the only "no data" check, so a failure in the
     * write check must not skip it.
     */
    fun afterPoll(context: Context, state: CareLinkState, nowMs: Long) {
        val app = context.applicationContext
        try {
            if (state.status == CareLinkStatus.OK || state.status == CareLinkStatus.ERROR) {
                val store = CareLinkStateStore(app)
                if (store.needLoginShown) {
                    cancel(app, ID_NEED_LOGIN)
                    store.needLoginShown = false
                }
            }
        } catch (t: Throwable) {
            CareLinkLog.w("need-login removal failed (${t.javaClass.simpleName})")
        }
        try {
            checkWrites(app, state, nowMs)
        } catch (t: Throwable) {
            CareLinkLog.w("write check failed (${t.javaClass.simpleName})")
        }
        checkNoData(app, state, nowMs) // has its own try
    }

    /**
     * "Pump records are not saved": a poll that wrote fine clears the mark and the notification; a write error starts
     * the clock (first failing poll) or, past the threshold, shows the notification once. Other errors (no network, the
     * cloud) leave the clock as it is - nothing was tried to be written; so does an older answer, which keeps the write
     * error in the state while the clock runs (critic 4d, N1; 4d2, N1). Signed out meanwhile - nothing (critic 4d, N2:
     * a clock started after the sign-out's clear would give the next sign-in's first failure the whole pause). Marked
     * shown while the notifications are off - the mark goes (critic 4d2, N4, see [blocked]). Alive while polling is on
     * or the Bluetooth pump source is on (part B1: signed out of CareLink, the source still writes).
     */
    internal fun checkWrites(context: Context, state: CareLinkState, nowMs: Long) {
        val app = context.applicationContext
        if (!CareLinkSettings(app).enabled && !PumpSourceSwitch.bluetoothOn(app)) return
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
     * polling is on and the session lives; taken away when data is fresh again or the link is off. Called after every
     * poll that reached the cloud or failed on the way, by the alarm when no poll runs ([checkNoDataOnAlarm]) and by
     * the CareLink window when the threshold changes. No data ever since the sign-in - counted from it, 10 min later
     * ([FIRST_DATA_MARGIN_MS]). The threshold is read each time.
     *
     * While the Bluetooth pump source is on (part B1) it is alive whatever the cloud's session and status, and its
     * watchdog calls this without a poll; the base is then the newest data or, when none has come since the source was
     * turned on, that moment ([PumpSourceSwitch.onSinceMs]) with the same margin - data the cloud brought before does
     * not make the source's first minutes "no data". The threshold is then at least [BLUETOOTH_MIN_WARN_MIN] (the title
     * names the threshold in force).
     */
    fun checkNoData(context: Context, state: CareLinkState = CareLinkStateStore(context).read(),
                    nowMs: Long = CareLinkClock.now()) {
        val app = context.applicationContext
        try {
            val store = CareLinkStateStore(app)
            val settings = CareLinkSettings(app)
            val bluetooth = PumpSourceSwitch.bluetoothOn(app)
            val live = bluetooth || settings.enabled && CareLinkAuth.hasSession(app) &&
                (state.status == CareLinkStatus.OK || state.status == CareLinkStatus.ERROR)
            // no pump data at all since polling was turned on: counted from then, with a margin for the first polls
            // (critic of the joints 4e, N4: a cloud giving only empty answers after a sign-in must not stay silent)
            val last = if (bluetooth) {
                listOfNotNull(state.lastDataAtMs, PumpSourceSwitch.onSinceMs(app)?.plus(FIRST_DATA_MARGIN_MS)).maxOrNull()
            } else {
                state.lastDataAtMs ?: settings.enabledSinceMs?.plus(FIRST_DATA_MARGIN_MS)
            }
            val warnMin = if (bluetooth) maxOf(settings.warnAfterMin, BLUETOOTH_MIN_WARN_MIN) else settings.warnAfterMin
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

    /**
     * The alarm's "no data" check (critic 4d, C1). The alarm comes an interval after the last poll, so the data is then
     * "interval + upload delay" old: checked here, a threshold of 5 min with polls every 5 min warned on almost every
     * alarm and the poll a second later took it back. So the alarm checks only when no poll runs: none queued (off, no
     * session) or none for over 1.5 intervals (WorkManager waits for a network that is not there). Otherwise the poll
     * it has just queued checks after it ([afterPoll]). A poll finished since the previous alarm ([prevAlarmAtMs], wall
     * clock; null - none known) also means polls run (critic 4d2, N3): the alarm may come late - after a reboot, a
     * change of the interval, or Doze letting it through once in 9 min - and the age of the last poll alone would then
     * check before the poll and take it back a second later.
     */
    internal fun checkNoDataOnAlarm(context: Context, pollQueued: Boolean, prevAlarmAtMs: Long? = null,
                                    state: CareLinkState = CareLinkStateStore(context).read(),
                                    nowMs: Long = CareLinkClock.now()) {
        val app = context.applicationContext
        try {
            val last = state.lastPollAtMs
            val interval = CareLinkSettings(app).intervalMin * 60_000L
            val recent = last != null && nowMs - last in 0..interval * 3 / 2
            val sinceAlarm = last != null && prevAlarmAtMs != null && prevAlarmAtMs <= nowMs && last in prevAlarmAtMs..nowMs
            if (pollQueued && (recent || sinceAlarm)) return
            checkNoData(app, state, nowMs)
        } catch (t: Throwable) {
            CareLinkLog.w("alarm no-data check failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * A sign-in: "sign in again" goes away, and so does a write failure of a former session - its clock would give the
     * new session's first failure the whole pause (critic 4d, N2).
     */
    fun loggedIn(context: Context) {
        val app = context.applicationContext
        try {
            cancel(app, ID_NEED_LOGIN)
            cancel(app, ID_WRITE_FAIL)
            CareLinkStateStore(app).apply {
                needLoginShown = false
                writeFailShown = false
                writeFailSinceMs = null
            }
        } catch (t: Throwable) {
            CareLinkLog.w("notification not removed (${t.javaClass.simpleName})")
        }
    }

    /**
     * Only "sign in again" goes (a sign-in or sign-out of CareLink while the Bluetooth pump source is on): "no data" and
     * "not saved", with their marks and clock, are the source's then (part B1).
     */
    internal fun cancelNeedLogin(context: Context) {
        val app = context.applicationContext
        try {
            cancel(app, ID_NEED_LOGIN)
            CareLinkStateStore(app).needLoginShown = false
        } catch (t: Throwable) {
            CareLinkLog.w("notification not removed (${t.javaClass.simpleName})")
        }
    }

    /** Sign-out: every CareLink notification goes away. */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        try {
            cancel(app, ID_NEED_LOGIN)
            cancel(app, ID_NO_DATA)
            cancel(app, ID_WRITE_FAIL)
            CareLinkStateStore(app).apply {
                needLoginShown = false
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

    /** The CareLink window when this build has it (the `mod` manifest), else the app's main window. */
    internal fun tapIntent(app: Context): Intent? =
        CareLinkActivity.intentIfDeclared(app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
     * Creates channel [CHANNEL] if it is not there. Internal for the CareLink window (4e): its "sound and vibration"
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
