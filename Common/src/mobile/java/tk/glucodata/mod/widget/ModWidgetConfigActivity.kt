package tk.glucodata.mod.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tk.glucodata.R
import tk.glucodata.ui.JugglucoTheme
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** One placed widget in the "which widget?" list. */
data class ModWidgetChoice(val id: Int, val number: Int, val widthDp: Int, val heightDp: Int)

/** What the settings window shows; changed on the main thread only. */
class ModWidgetConfigState {
    var widgetId by mutableIntStateOf(AppWidgetManager.INVALID_APPWIDGET_ID)
    var settings by mutableStateOf(ModWidgetSettings.DEFAULT)
    var preview by mutableStateOf<Bitmap?>(null)
    var previewWidthDp by mutableFloatStateOf(300f)
    var previewHeightDp by mutableFloatStateOf(56f)
    var choices by mutableStateOf<List<ModWidgetChoice>>(emptyList())
}

class ModWidgetConfigActions(
    val onChange: (ModWidgetSettings) -> Unit,
    val onPick: (Int) -> Unit,
    val onDone: () -> Unit,
)

/**
 * JugglucoNGmod widget: the settings window, with a live preview drawn by the same function as the widget.
 *
 * Opened by the launcher when the widget is placed (`configure`) and from "Settings" on a long press of the widget
 * (`reconfigurable`), so it is exported with the APPWIDGET_CONFIGURE filter; an id that is not one of our widgets
 * closes it at once. With a placed widget it answers RESULT_OK straight away and draws the widget with the defaults
 * (with `configure` the system does not call onUpdate) - "Back" leaves a finished widget. Also opened by our app-icon
 * shortcut without an id: one widget - its settings, several - the list first.
 *
 * The drawing is in ModWidgetConfigScreen.kt; storage and upstream's data are read here and in plain files (upstream's
 * ArchitectureGateTests).
 */
class ModWidgetConfigActivity : ComponentActivity() {

    private val state = ModWidgetConfigState()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ModWidgetPreview").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var input: ModWidgetInput? = null
    private val refresh = object : Runnable {
        override fun run() {
            loadInput()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        try {
            val requested = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
            if (requested != AppWidgetManager.INVALID_APPWIDGET_ID) {
                if (!ModWidgetUpdater.isOurs(this, requested)) {
                    ModWidgetLog.w("configure: not our widget")
                    finish()
                    return
                }
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, requested))
                ModWidgetShortcut.publish(this)
                ModWidgetUpdater.requestAll(this)
                if (restricted()) {
                    finish()
                    return
                }
                select(requested)
            } else {
                if (restricted()) {
                    finish()
                    return
                }
                val ids = ModWidgetUpdater.widgetIds(this).sorted()
                when (ids.size) {
                    0 -> {
                        Toast.makeText(this, R.string.mod_widget_none, Toast.LENGTH_LONG).show()
                        finish()
                        return
                    }
                    1 -> select(ids[0])
                    else -> state.choices = ids.mapIndexed { i, id ->
                        val size = ModWidgetUpdater.previewSizeDp(this, id)
                        ModWidgetChoice(id, i + 1, size.width.roundToInt(), size.height.roundToInt())
                    }
                }
            }
        } catch (t: Throwable) {
            ModWidgetLog.w("configure failed: ${t.javaClass.simpleName}")
            finish()
            return
        }
        try {
            enableEdgeToEdge()
        } catch (_: Throwable) {
        }
        val themeMode = ModWidgetTheme.mode(this)
        val actions = ModWidgetConfigActions(onChange = ::change, onPick = ::select, onDone = ::finish)
        setContent {
            JugglucoTheme(themeMode = themeMode) {
                ModWidgetConfigScreen(state, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    private fun restricted(): Boolean = try {
        tk.glucodata.RestrictedMode.isLocked()
    } catch (t: Throwable) {
        ModWidgetLog.w("restricted mode not read: ${t.javaClass.simpleName}")
        false
    }

    private fun select(id: Int) {
        state.widgetId = id
        state.settings = ModWidgetSettingsStore.read(this, id)
        val size = ModWidgetUpdater.previewSizeDp(this, id)
        state.previewWidthDp = size.width
        state.previewHeightDp = size.height
        renderPreview()
    }

    private fun change(settings: ModWidgetSettings) {
        val id = state.widgetId
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val clean = settings.sanitized()
        state.settings = clean
        try {
            ModWidgetSettingsStore.write(this, id, clean)
        } catch (t: Throwable) {
            ModWidgetLog.w("settings not saved: ${t.javaClass.simpleName}")
        }
        ModWidgetUpdater.requestAll(this)
        renderPreview()
    }

    /** Upstream's data again (the preview stays live while the window is open). */
    private fun loadInput() {
        val app = applicationContext
        try {
            worker.execute {
                val loaded = try {
                    ModWidgetData.load(app, ModWidgetSettings.MAX_PERIOD_MS)
                } catch (t: Throwable) {
                    ModWidgetLog.w("preview data failed: ${t.javaClass.simpleName}")
                    null
                }
                input = loaded
                main.post { renderPreview() }
            }
        } catch (_: Throwable) {
        }
    }

    private fun renderPreview() {
        if (state.widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val settings = state.settings
        val widthDp = state.previewWidthDp
        val heightDp = state.previewHeightDp
        val app = applicationContext
        val metrics = resources.displayMetrics
        try {
            worker.execute {
                try {
                    val now = System.currentTimeMillis()
                    val live = input
                    val shown = if (live == null || (live.valueText == null && live.points.isEmpty())) {
                        ModWidgetInput.example(now, live?.range ?: ModWidgetData.range(ModWidgetData.isMmol()))
                    } else live
                    val frame = ModWidgetFrame.build(shown, settings, now, ModWidgetData.timeoutMs(), ModWidgetData.Texts(app))
                    val maxPx = maxOf(metrics.widthPixels, metrics.heightPixels)
                    val bitmap = ModWidgetRenderer.render(
                        frame,
                        ModWidgetRenderer.pixels(widthDp, metrics.density, maxPx),
                        ModWidgetRenderer.pixels(heightDp, metrics.density, maxPx),
                        metrics.density,
                    )
                    main.post { state.preview = bitmap }
                } catch (t: Throwable) {
                    ModWidgetLog.w("preview failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val REFRESH_MS = 30_000L
    }
}
