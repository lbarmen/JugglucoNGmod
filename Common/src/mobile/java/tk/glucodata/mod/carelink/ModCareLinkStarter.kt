package tk.glucodata.mod.carelink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import tk.glucodata.R

/**
 * JugglucoNGmod, pump data (stage 4; the cloud left in part D1): starts our code without a hook in upstream files.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml, so it runs in the phone `mod` build alone; upstream's
 * own builds compile this class but never start it, and the watch never sees it. Android creates content providers
 * on every process start, before Application.onCreate: onCreate here stays cheap, touches none of upstream's
 * singletons and never throws (a crash here would take the whole app down before any screen).
 *
 * Posted to the main thread (it runs after Application.onCreate, when WorkManager has upstream's configuration), then
 * on a background thread: the one-time removal of what the CareLink cloud left ([CareLinkCloudCleanup]) and the pump
 * shortcut (long press on the icon; cheap and repeatable).
 */
class ModCareLinkStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            val app = context?.applicationContext ?: return true
            Handler(Looper.getMainLooper()).post {
                try {
                    startBackground(Runnable { background(app) })
                } catch (t: Throwable) {
                    CareLinkLog.w("start steps not run (${t.javaClass.simpleName})")
                }
            }
        } catch (t: Throwable) {
            // our code must not take upstream's app down
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val TAG = "ModCareLink"

        internal const val SHORTCUT_ID = "mod-carelink"

        /** How the background steps start: a thread of their own (tests run them in place). */
        @Volatile
        internal var startBackground: (Runnable) -> Unit = { Thread(it, "mod-carelink-start").start() }

        /** The background steps of a process start, each never throwing. */
        internal fun background(app: Context) {
            CareLinkCloudCleanup.run(app)
            installShortcut(app)
        }

        /**
         * The pump shortcut on a long press of the launcher icon (id [SHORTCUT_ID] kept), opening the pump window. Only
         * where the window is declared (the `mod` build); pushed, not set - other dynamic shortcuts of the app stay.
         * Never throws.
         */
        internal fun installShortcut(app: Context) {
            try {
                val intent = tk.glucodata.mod.pumpbt.PumpBtActivity.intentIfDeclared(app) ?: return
                val shortcut = ShortcutInfoCompat.Builder(app, SHORTCUT_ID)
                    .setShortLabel(app.getString(R.string.mod_carelink_shortcut_short))
                    .setLongLabel(app.getString(R.string.mod_carelink_shortcut_long))
                    .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                    .setIntent(intent.setAction(Intent.ACTION_VIEW))
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
            } catch (t: Throwable) {
                CareLinkLog.w("shortcut not installed (${t.javaClass.simpleName})")
            }
        }
    }
}
