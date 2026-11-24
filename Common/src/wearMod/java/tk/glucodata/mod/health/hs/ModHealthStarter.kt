package tk.glucodata.mod.health.hs

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import tk.glucodata.mod.health.HealthSources
import tk.glucodata.mod.watch.FreshReadingAlerts
import tk.glucodata.mod.watch.ModWatchLog
import tk.glucodata.mod.watch.NoOngoingActivity

/**
 * JugglucoNGmod, watch block (part 4b): starts our heart rate and steps code with the watch process, without a hook in
 * upstream's Application - Android creates every declared provider at process start. It only registers the Health
 * Services source ([HealthSources.current]); nothing is read, called or subscribed here (no disk, no binder). What
 * must wait for upstream's Application (part 7j's claim, part 7o's early alert check) is posted behind it.
 *
 * Registered always, with or without permissions (critic 4a, finding L8): a process woken by a `/mod/tick` must find
 * the source, or neither the status row ("no permissions" included) nor the watch battery would go to the phone.
 * Not exported; it serves no data.
 */
class ModHealthStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            HealthSources.current = ModHealthServicesSource
        } catch (t: Throwable) {
            ModWatchLog.w("health source not registered: ${t.javaClass.simpleName}")
        }
        // part 7j: no ongoing activity (no white dot on the watch faces) - after upstream's registration in onCreate
        NoOngoingActivity.claimAfterStart()
        // part 7o: the watch's alerts look at a new reading at once, not at upstream's next 15 s tick
        FreshReadingAlerts.startAfterApplication()
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
