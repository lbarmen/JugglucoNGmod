package tk.glucodata.mod.watch.alarm

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 8): puts our alarm screen in upstream's slot at process start, without a hook in
 * their Application - Android creates every declared provider before `Application.onCreate`, so the claim is posted
 * behind it ([ModAlarmScreen.claimAfterStart]). A provider of its own, apart from the health starter: a failure of one
 * never stops the other. Declared only in Common/src/wearMod/AndroidManifest.xml; not exported, it serves no data.
 */
class ModAlarmStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            context?.let {
                // the Application exists before any provider: follow the alarm screens from the first activity on
                AlarmScreens.register(it)
                ModAlarmScreen.claimAfterStart(it)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alarm screen starter failed: ${t.javaClass.simpleName}")
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
