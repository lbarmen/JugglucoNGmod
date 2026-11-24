package mod.watch.face

import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import tk.glucodata.mod.watch.PhoneBatteryStore
import tk.glucodata.mod.watch.face.FaceGraph
import tk.glucodata.mod.watch.face.FaceSettings
import tk.glucodata.mod.watch.face.FaceSettingsActivity

/** JugglucoNGmod, watch block (parts 3b, 3c): the face's one setting, graph hours 1 / 3 / 6, 3 by default, and our editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FaceSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun nothingKeptIsThreeHours() {
        assertEquals(3, FaceSettings.hours(context))
    }

    @Test
    fun aKeptChoiceIsReadBackAndAnythingElseIsTheDefault() {
        FaceGraph.HOURS.forEach {
            assertTrue(FaceSettings.setHours(context, it))
            assertEquals(it, FaceSettings.hours(context))
        }
        FaceSettings.setHours(context, 12)
        assertEquals(FaceGraph.DEFAULT_HOURS, FaceSettings.hours(context))
        // a value put there by something else is sanitized on reading
        FaceSettings.prefs(context).edit().putInt(FaceSettings.KEY_GRAPH_HOURS, 0).commit()
        assertEquals(FaceGraph.DEFAULT_HOURS, FaceSettings.hours(context))
        FaceSettings.prefs(context).edit().putString(FaceSettings.KEY_GRAPH_HOURS, "6h").commit()
        assertEquals(FaceGraph.DEFAULT_HOURS, FaceSettings.hours(context))
    }

    @Test
    fun theLabelsFollowTheHours() {
        assertEquals(listOf("1 hour", "3 hours", "6 hours"), FaceSettings.labels(context))
    }

    @Test
    fun theEditorShowsTheChoicesWithTheKeptOneChecked() {
        FaceSettings.setHours(context, 6)
        val activity = Robolectric.buildActivity(FaceSettingsActivity::class.java).setup().get()
        val root = activity.window.decorView
        val group = findAll(root, RadioGroup::class.java).single()
        val buttons = findAll(group, RadioButton::class.java)
        assertEquals(listOf("1 hour", "3 hours", "6 hours"), buttons.map { it.text.toString() })
        assertEquals(listOf(false, false, true), buttons.map { it.isChecked })
        val texts = findAll(root, TextView::class.java).filter { it !is RadioButton }.map { it.text.toString() }
        assertTrue(texts.toString(), "Graph hours" in texts)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun aTapKeepsTheChoiceAndClosesTheEditor() {
        val activity = Robolectric.buildActivity(FaceSettingsActivity::class.java).setup().get()
        val one = activity.window.decorView.findViewWithTag<RadioButton>(FaceSettings.KEY_GRAPH_HOURS + 1)
        one.performClick()
        assertEquals(1, FaceSettings.hours(context))
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }

    @Test
    fun theFaceHearsTheChange() {
        var heard = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == FaceSettings.KEY_GRAPH_HOURS) heard++
        }
        FaceSettings.prefs(context).registerOnSharedPreferenceChangeListener(listener)
        FaceSettings.setHours(context, 1)
        FaceSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
        assertEquals(1, heard)
    }

    private fun <T : android.view.View> findAll(view: android.view.View, type: Class<T>): List<T> {
        val found = mutableListOf<T>()
        if (type.isInstance(view)) found += type.cast(view)!!
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) found += findAll(view.getChildAt(i), type)
        }
        return found
    }
}
