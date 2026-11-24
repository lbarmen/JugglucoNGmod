package tk.glucodata.mod.watch.face

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import tk.glucodata.R
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 3c): our own small editor of the watch face - "graph hours" 1 / 3 / 6 h
 * ([FaceSettings]), opened from the watch's app list as "Graph hours" (its own launcher entry, part 3c2). The face
 * has no "Customise" (no `wearableConfigurationAction`, Common/src/wearMod/AndroidManifest.xml): upstream's
 * `androidx.wear.watchface.editor.action.WATCH_FACE_EDITOR` would open their `WatchFaceConfigActivity`, and Wear OS
 * leaves a face with any other action out of the picker (seen on the watch 18.11.2026).
 *
 * A plain [Activity] with views made in code (no layout file, no Compose): a title, a line of explanation and three
 * choices. A tap keeps the choice and closes the editor; the face, in the same process, hears the change and draws the
 * graph again. Swipe or back closes it without a change. Declared only in the `mod` build's manifest.
 */
class FaceSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(build())
        } catch (t: Throwable) {
            ModWatchLog.w("face settings failed: ${t.javaClass.simpleName}")
            finish()
        }
    }

    /** Keeps [hours] and closes the editor; `RESULT_OK` only when the choice was written. */
    internal fun choose(hours: Int) {
        try {
            if (FaceSettings.setHours(applicationContext, hours)) setResult(RESULT_OK)
            else ModWatchLog.w("face settings not written")
        } catch (t: Throwable) {
            ModWatchLog.w("face settings not kept: ${t.javaClass.simpleName}")
        }
        finish()
    }

    private fun build(): View {
        val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.14f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, side, side, side)
        }
        column.addView(text(R.string.mod_watch_face_graph_setting, 17f))
        column.addView(text(R.string.mod_watch_face_graph_setting_description, 13f))
        val current = FaceSettings.hours(this)
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val minHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        FaceSettings.labels(this).zip(FaceGraph.HOURS).forEach { (label, hours) ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                tag = FaceSettings.KEY_GRAPH_HOURS + hours
                text = label
                textSize = 16f
                this.minHeight = minHeight
                setOnClickListener { choose(hours) }
            }
            group.addView(button)
            if (hours == current) group.check(button.id)
        }
        column.addView(group)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
    }

    private fun text(resId: Int, size: Float) = TextView(this).apply {
        setText(resId)
        textSize = size
        gravity = Gravity.CENTER
    }
}
