package tk.glucodata.mod.watch.face

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.R
import tk.glucodata.mod.watch.PhoneBatteryStore

/**
 * JugglucoNGmod, watch block (parts 3b, 3c): the face's one setting, "graph hours" 1 / 3 / 6 ([FaceGraph.HOURS], 3 by
 * default), kept in our own preferences file on the watch ([PhoneBatteryStore.PREFS]) and chosen in our own editor
 * ([FaceSettingsActivity], opened from its own app entry "Graph hours": the face has no "Customise").
 *
 * Not a `UserStyleSchema`: the library's on-watch editor (`EditorSession`) needs the request the system puts in the
 * editor's intent and a running face instance, so it cannot be opened directly; a plain preference can, it is read by
 * the face in the same process and is testable on the JVM. One place only - no style the companion app could set.
 */
object FaceSettings {

    /** The key in [PhoneBatteryStore.PREFS]. */
    const val KEY_GRAPH_HOURS = "face_graph_hours"

    fun prefs(context: Context): SharedPreferences = PhoneBatteryStore.prefs(context)

    /** The chosen graph hours; [FaceGraph.DEFAULT_HOURS] when none is kept, unknown or unreadable (before unlock). */
    fun hours(context: Context): Int = try {
        FaceGraph.sanitizeHours(prefs(context).getInt(KEY_GRAPH_HOURS, FaceGraph.DEFAULT_HOURS))
    } catch (_: Throwable) {
        FaceGraph.DEFAULT_HOURS
    }

    /** Keeps [hours] (anything not in [FaceGraph.HOURS] as the default); `true` when it was written. */
    fun setHours(context: Context, hours: Int): Boolean =
        prefs(context).edit().putInt(KEY_GRAPH_HOURS, FaceGraph.sanitizeHours(hours)).commit()

    /** The label of each choice, in the order of [FaceGraph.HOURS]. */
    fun labels(context: Context): List<String> {
        // R ids read here, in a function body, never through `const val` (upstream's "0x0" bug)
        val names = mapOf(
            1 to R.string.mod_watch_face_graph_1,
            3 to R.string.mod_watch_face_graph_3,
            6 to R.string.mod_watch_face_graph_6,
        )
        return FaceGraph.HOURS.map { context.getString(names.getValue(it)) }
    }
}
