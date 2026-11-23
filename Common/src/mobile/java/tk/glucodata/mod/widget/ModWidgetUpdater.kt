package tk.glucodata.mod.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import tk.glucodata.MainActivity
import tk.glucodata.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * JugglucoNGmod widget: draws all our placed widgets, off the main thread, one pass at a time, and keeps the
 * "N min ago" alarm. Every entry point catches everything: a failure here must not take upstream's app down.
 */
object ModWidgetUpdater {

    const val ACTION_TICK = "tk.glucodata.mod.widget.TICK"

    /** Our own request codes ("MW" + n), unlike upstream's small numbers and unlike each other. */
    private const val REQUEST_OPEN_APP = 0x4D570001
    private const val REQUEST_TICK = 0x4D570002

    /** A widget the launcher has not measured yet: about 4 x 1 cells. */
    private val FALLBACK_SIZE_DP = SizeF(300f, 56f)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ModWidget").apply { isDaemon = true } }
    private val generation = AtomicLong()

    fun provider(context: Context) = ComponentName(context, ModWidgetProvider::class.java)

    fun widgetIds(context: Context): IntArray = try {
        AppWidgetManager.getInstance(context)?.getAppWidgetIds(provider(context)) ?: IntArray(0)
    } catch (_: Throwable) {
        IntArray(0)
    }

    /** Whether [id] is one of our widgets (the settings window is exported: any app could send any id). */
    fun isOurs(context: Context, id: Int): Boolean = try {
        id != AppWidgetManager.INVALID_APPWIDGET_ID &&
            AppWidgetManager.getInstance(context)?.getAppWidgetInfo(id)?.provider == provider(context)
    } catch (_: Throwable) {
        false
    }

    /**
     * Redraws every widget of ours. Requests that arrive while one is queued collapse into the newest; [pending]
     * (from a receiver's goAsync) is finished whatever happens, within the 10 s a receiver has.
     */
    fun requestAll(context: Context, pending: BroadcastReceiver.PendingResult? = null) {
        val app = context.applicationContext ?: context
        val gen = generation.incrementAndGet()
        try {
            executor.execute {
                try {
                    if (gen == generation.get()) drawAll(app)
                } catch (t: Throwable) {
                    ModWidgetLog.w("draw failed: ${t.javaClass.simpleName}")
                } finally {
                    try { pending?.finish() } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            ModWidgetLog.w("draw not queued: ${t.javaClass.simpleName}")
            try { pending?.finish() } catch (_: Throwable) {}
        }
    }

    private fun drawAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = widgetIds(context)
        if (ids.isEmpty()) {
            cancelTick(context)
            return
        }
        val now = System.currentTimeMillis()
        // unreadable data must not leave the last picture ("2 min ago" in color) frozen: draw "No value" instead
        val input = try {
            ModWidgetData.load(context, ModWidgetSettings.MAX_PERIOD_MS)
        } catch (t: Throwable) {
            ModWidgetLog.w("data failed: ${t.javaClass.simpleName}")
            ModWidgetInput.EMPTY_MMOL.copy(range = ModWidgetRange.defaults(ModWidgetData.isMmol()))
        }
        val texts = ModWidgetData.Texts(context)
        val timeout = ModWidgetData.timeoutMs()
        var drawn = 0
        for (id in ids) {
            try {
                val settings = ModWidgetSettingsStore.read(context, id)
                val frame = ModWidgetFrame.build(input, settings, now, timeout, texts)
                val options = manager.getAppWidgetOptions(id)
                try {
                    manager.updateAppWidget(id, views(context, options, frame))
                } catch (t: Throwable) {
                    // over the picture limit with two sizes: one, smaller picture, scaled by the ImageView
                    ModWidgetLog.w("widget redrawn smaller: ${t.javaClass.simpleName}")
                    manager.updateAppWidget(id, single(context, sizesDp(context, options).first(), frame, 0.5f))
                }
                drawn++
            } catch (t: Throwable) {
                ModWidgetLog.w("widget not drawn: ${t.javaClass.simpleName}")
            }
        }
        scheduleTick(context, ModWidgetMath.nextRedraw(input.readingTime, now, timeout))
        ModWidgetLog.i("drawn $drawn of ${ids.size}")
    }

    private fun views(context: Context, options: Bundle?, frame: ModWidgetFrame): RemoteViews {
        val sizes = sizesDp(context, options)
        val scale = pictureScale(context, sizes)
        if (Build.VERSION.SDK_INT >= 31 && sizes.size > 1) {
            return RemoteViews(sizes.associateWith { single(context, it, frame, scale) })
        }
        return single(context, sizes.first(), frame, scale)
    }

    /**
     * Below 1 when the pictures together would pass the screen's area (RemoteViews allows about 1.5 screens of
     * bitmaps): drawn smaller and scaled up by the ImageView instead of being refused.
     */
    internal fun pictureScale(context: Context, sizesDp: List<SizeF>): Float {
        val m = context.resources.displayMetrics
        val screen = m.widthPixels.toFloat() * m.heightPixels.toFloat()
        val total = sizesDp.sumOf { (it.width * m.density * it.height * m.density).toDouble() }.toFloat()
        return if (screen <= 0f || total <= screen) 1f else kotlin.math.sqrt(screen / total)
    }

    private fun single(context: Context, sizeDp: SizeF, frame: ModWidgetFrame, scale: Float = 1f): RemoteViews {
        val metrics = context.resources.displayMetrics
        val maxPx = max(metrics.widthPixels, metrics.heightPixels)
        val density = metrics.density * scale
        val w = ModWidgetRenderer.pixels(sizeDp.width, density, maxPx)
        val h = ModWidgetRenderer.pixels(sizeDp.height, density, maxPx)
        val bitmap = ModWidgetRenderer.render(frame, w, h, density)
        return RemoteViews(context.packageName, R.layout.mod_widget).apply {
            setImageViewBitmap(R.id.mod_widget_image, bitmap)
            setContentDescription(R.id.mod_widget_image, frame.description)
            setOnClickPendingIntent(R.id.mod_widget_root, openApp(context))
        }
    }

    /**
     * The sizes to draw, in dp. Android 12+ reports the real ones (portrait and landscape; at most two are drawn);
     * older launchers give a range - portrait is the narrow width and the tall height, landscape the other way.
     */
    @Suppress("DEPRECATION")
    internal fun sizesDp(context: Context, options: Bundle?): List<SizeF> {
        if (options != null && Build.VERSION.SDK_INT >= 31) {
            val reported = try {
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            } catch (_: Throwable) {
                null
            }?.filter { it.width >= 1f && it.height >= 1f }?.distinct().orEmpty()
            if (reported.isNotEmpty()) {
                val narrow = reported.minBy { it.width }
                val wide = reported.maxBy { it.width }
                return if (narrow == wide) listOf(narrow) else listOf(narrow, wide)
            }
        }
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val maxW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
        val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (minW <= 0 || maxH <= 0) return listOf(FALLBACK_SIZE_DP)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) listOf(SizeF(max(minW, maxW).toFloat(), max(1, minH).toFloat()))
        else listOf(SizeF(minW.toFloat(), maxH.toFloat()))
    }

    /** The portrait size, for the settings preview. */
    fun previewSizeDp(context: Context, id: Int): SizeF = try {
        val options = AppWidgetManager.getInstance(context)?.getAppWidgetOptions(id)
        sizesDp(context, options).first()
    } catch (_: Throwable) {
        FALLBACK_SIZE_DP
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, REQUEST_OPEN_APP, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun tickIntent(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, ModWidgetProvider::class.java).setAction(ACTION_TICK)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, REQUEST_TICK, intent, flags)
    }

    /**
     * The next "N min ago" redraw: an ordinary inexact, non-waking RTC alarm (no new permission; asleep it comes on
     * the next wake, which is fine for a text). Null - none: the reading is not fresh any more or there is none.
     */
    private fun scheduleTick(context: Context, at: Long?) {
        if (at == null) {
            cancelTick(context)
            return
        }
        try {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = tickIntent(context, true) ?: return
            alarms.set(AlarmManager.RTC, at, pi)
        } catch (t: Throwable) {
            ModWidgetLog.w("tick not set: ${t.javaClass.simpleName}")
        }
    }

    fun cancelTick(context: Context) {
        try {
            val pi = tickIntent(context, false) ?: return
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        } catch (t: Throwable) {
            ModWidgetLog.w("tick not cancelled: ${t.javaClass.simpleName}")
        }
    }
}
