package tk.glucodata.mod.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDeltaSource
import tk.glucodata.GlucoseDelta
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NotificationChartDrawer
import tk.glucodata.NotificationChartModelSource
import tk.glucodata.NotificationPredictionAccess
import tk.glucodata.NotificationPredictionSeries
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.api.ApiGlucoseSourceRegistry
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.watch.ModWatchMessages

/**
 * JugglucoNGmod, phone always-on display (part 1, the owner's variant B, 26.11): our whole block in upstream's overlay.
 * Upstream's `AODOverlayService.updateOverlayContent` hands over what it has just resolved (one hook, `aod-block`):
 * the readings, the number as their app shows it, the arrow's rate, the "no new value" text. We hide their text row
 * (number, arrow, delta, status) and put our block - number, arrow, change, the reading's time and the pump's active
 * insulin, chart - into their chart view. Their service keeps everything else: when to show, the burn-in steps, the light sensor, the refresh,
 * the position.
 *
 * Their settings that still act: chart on/off and size, text size, arrow on/off, the delta interval, the "Bottom"
 * position, opacity. Font, weight and "show delta" do not (our block has its own look, as on the watch).
 *
 * Samsung's always-on display keeps the screen in DOZE_SUSPEND and takes a new frame only when the system wakes it for
 * a moment (probe on a Galaxy S24 Ultra, One UI 6.1, 26.11: about 2 s every one to a few minutes). So the small line
 * shows the reading's clock time, not its age - a frozen frame must not claim to be fresh - and a refresh that would
 * draw the same picture is skipped ([sameDrawing]). A changed block, while the screen dozes, also puts upstream's
 * window up again at most once a minute ([ModAodWindow]): a window added anew is what Samsung shows (as for xDrip+).
 */
object ModAodBlock {

    private const val TAG = "ModAod"
    private const val PREFS = "tk.glucodata_preferences"

    /** What the block on screen was drawn from, and that block, while upstream's view still shows it. */
    private var lastKey: DrawKey? = null
    private var lastBitmap: WeakReference<Bitmap>? = null

    /** Upstream's AOD number: 22 dp at 2x times their text size (`NotificationChartDrawer.drawGlucoseText`). */
    private const val NUMBER_DP = 22f * 2f

    /** Upstream's chart view: 200 dp times their chart size (`AODOverlayService.updateOverlayContent`). */
    private const val CHART_DP = 200f

    /**
     * Draws our block into [view] (upstream's `aod_overlay`) and returns true; false (their text row shown again,
     * their own drawing goes on) when anything fails. [light] - their palette: light on the black always-on screen,
     * dark over a light lock-screen wallpaper. [history] - their 3-hour chart readings, [deltaHistory] - the readings
     * their delta uses, [shown] - the number they resolved ([valueText] formatted, [rate] for the arrow), [stale] -
     * their "no new value since" text or null.
     */
    @JvmStatic
    fun show(
        context: Context,
        view: View,
        light: Boolean,
        history: List<GlucosePoint>,
        deltaHistory: List<GlucosePoint>,
        shown: CurrentDisplaySource.Snapshot?,
        valueText: String,
        rate: Float,
        stale: String?,
        isMmol: Boolean,
        viewMode: Int,
        hasCalibration: Boolean,
        sensorId: String?,
        startMs: Long,
        nowMs: Long,
    ): Boolean {
        val textRow = view.findViewById<View>(R.id.aod_text_container)
        val chartView = view.findViewById<ImageView>(R.id.notification_chart) ?: return false
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val dm = context.resources.displayMetrics
            val textScale = prefs.getFloat("aod_text_scale", 1.5f)
            val chartScale = prefs.getFloat("aod_chart_scale", 1.5f)
            val showChart = prefs.getBoolean("aod_show_chart", true)
            val showArrow = prefs.getBoolean("aod_show_arrow", true)
            val range = range(isMmol)
            val fresh = stale == null && shown != null
            val hour24 = android.text.format.DateFormat.is24HourFormat(context)
            val locale = Locale.getDefault()
            val zone = ZoneId.systemDefault()
            val deltaInterval = prefs.getInt(DisplayDeltaSource.INTERVAL, GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (hour24) "Hm" else "hm")
            val iob = if (fresh) {
                pumpIob(context, nowMs)?.let {
                    val label = context.getString(R.string.mod_aod_iob)
                    iobPart(it.iobUnits, it.timestampMillis, nowMs, zone, pattern, locale, label)
                }
            } else null
            val key = DrawKey(
                light, dm.widthPixels, dm.density, dm.scaledDensity, textScale, chartScale, showChart, showArrow, isMmol,
                range, deltaInterval, viewMode, hasCalibration, sensorId, valueText, if (fresh) rate else Float.NaN, stale,
                shown?.timeMillis ?: 0L, history.size, history.lastOrNull()?.timestamp ?: 0L,
                history.lastOrNull()?.value ?: Float.NaN, deltaHistory.lastOrNull()?.timestamp ?: 0L,
                UiRefreshBus.revision.value, nowMs / 60_000L, hour24, locale, zone, iob,
            )
            val onScreen = (chartView.drawable as? BitmapDrawable)?.bitmap
            if (sameDrawing(lastKey, key, onScreen != null && onScreen === lastBitmap?.get())) {
                chartView.visibility = View.VISIBLE
                textRow?.visibility = View.GONE
                return true
            }
            val points = chartPoints(context, history, sensorId, viewMode, hasCalibration, startMs, shown?.takeIf { fresh })
            val forecast = if (showChart) forecast(context, history, isMmol, viewMode, hasCalibration, sensorId, range) else emptyList()
            val plot = ModAodPlot.plot(points, nowMs, range.low, range.high, isMmol, forecast)
            val sub = if (fresh && shown != null) {
                val delta = DisplayDeltaSource.format(
                    true, deltaHistory, sensorId, shown.timeMillis, viewMode, isMmol, deltaInterval, nowMs, Notify.glucosetimeout,
                )
                subLine(delta, readingTime(shown.timeMillis, zone, pattern, locale), iob)
            } else null
            val value = ModAodPainter.Value(
                text = numberText(valueText),
                rate = if (fresh && showArrow) rate else Float.NaN,
                sub = sub,
                stale = stale,
            )
            val palette = if (light) ModAodStyle.ON_BLACK else ModAodStyle.ON_LIGHT
            val zoneColor = { v: Float ->
                GlucoseRangeColors.colorForValue(
                    v, range.low, range.high, range.veryLow, range.veryHigh,
                    GlucoseRangeColors.inRange(palette.darkTheme), palette.darkTheme, isMmol,
                )
            }
            val bitmap = ModAodPainter(dm.density, dm.scaledDensity).blockBitmap(
                dm.widthPixels,
                NUMBER_DP * dm.density * textScale,
                if (showChart) (CHART_DP * dm.density * chartScale).toInt() else 0,
                value, plot, isMmol, palette, zoneColor, zone, hour24, locale,
            )
            chartView.layoutParams = chartView.layoutParams?.also { it.height = bitmap.height }
            chartView.setImageBitmap(bitmap)
            chartView.visibility = View.VISIBLE
            textRow?.visibility = View.GONE
            lastKey = key
            lastBitmap = WeakReference(bitmap)
            ModAodWindow.reattachIfDozing(context, view)
            true
        } catch (t: Throwable) {
            Log.stack(TAG, "block failed, upstream's look", t)
            lastKey = null
            lastBitmap = null
            textRow?.visibility = View.VISIBLE
            false
        }
    }

    /**
     * Everything the drawn block depends on. The minute stands for the clock: the 3-hour window slides by about one
     * pixel in ten seconds, so a redraw inside the same minute with the same readings would show the same picture.
     * Upstream's refresh-bus revision ([dataRevision]) catches data changes inside the history (backfill,
     * calibration) at once; anything else not in here (a journal entry moving the forecast) shows at the next minute.
     * [iob] is the pump's part of the small line as drawn: a new number, the 10-minute step and the 30-minute end
     * each change it.
     */
    internal data class DrawKey(
        val light: Boolean,
        val widthPx: Int,
        val density: Float,
        val scaledDensity: Float,
        val textScale: Float,
        val chartScale: Float,
        val showChart: Boolean,
        val showArrow: Boolean,
        val isMmol: Boolean,
        val range: Range,
        val deltaInterval: Int,
        val viewMode: Int,
        val hasCalibration: Boolean,
        val sensorId: String?,
        val valueText: String,
        val rate: Float,
        val stale: String?,
        val shownTime: Long,
        val historySize: Int,
        val historyLastTime: Long,
        val historyLastValue: Float,
        val deltaLastTime: Long,
        val dataRevision: Long,
        val minute: Long,
        val hour24: Boolean,
        val locale: Locale,
        val zone: ZoneId,
        val iob: String?,
    )

    /** The refresh would draw what upstream's view already shows: the same inputs and that view still holds our block. */
    internal fun sameDrawing(last: DrawKey?, next: DrawKey, viewHoldsLast: Boolean): Boolean =
        viewHoldsLast && last == next

    /**
     * The clock time of the shown reading in the phone's own format ([pattern] from Android, "HH:mm" or "h:mm a");
     * a pattern letter java.time does not know falls back to the plain 24- or 12-hour form.
     */
    internal fun readingTime(timeMs: Long, zone: ZoneId, pattern: String, locale: Locale): String {
        val at = Instant.ofEpochMilli(timeMs).atZone(zone)
        return try {
            DateTimeFormatter.ofPattern(pattern, locale).format(at)
        } catch (_: RuntimeException) {
            DateTimeFormatter.ofPattern(if (pattern.contains('H') || pattern.contains('k')) "HH:mm" else "h:mm a", locale).format(at)
        }
    }

    /** Upstream's number without the secondary values they append after " · " (our block shows the primary only). */
    internal fun numberText(valueText: String): String? =
        valueText.substringBefore(" · ").trim().takeIf { it.isNotEmpty() }

    /** "+0,2 · 17:01 · IOB 2,4": the change, the reading's time and the pump's active insulin; any may be missing. */
    internal fun subLine(delta: String, time: String, iob: String? = null): String? =
        listOf(delta, time, iob.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }

    /** Pump data older than this shows its own reading time: "IOB 2,4 (16:40)". */
    internal const val IOB_OLD_MS = 10L * 60_000L

    /** A pump time further ahead of the phone's clock is not believed (as our CareLink code, `CareLinkPumpNow`). */
    internal const val IOB_FUTURE_MS = 5L * 60_000L

    /**
     * The pump's active insulin for the small line: "IOB 2,4" ([label] is "IOB" or the phone's language), "IOB 0" for
     * zero tenths, "IOB 2,4 (16:40)" when the pump's reading [atMs] is more than [IOB_OLD_MS] before [nowMs] (time as
     * [readingTime]); null - no number, a broken one, one past the slot's own 30 minutes
     * ([ApiIobSnapshot.FRESHNESS_WINDOW_MS]) or more than [IOB_FUTURE_MS] ahead of the clock (a time ahead by less
     * counts as new). The tenths are the watch face's (`PumpIobPhone.pumpOnly`, `FaceText.iob`): rounded half up, the
     * same upper limit, the locale's decimal separator.
     */
    internal fun iobPart(
        units: Float?,
        atMs: Long?,
        nowMs: Long,
        zone: ZoneId,
        pattern: String,
        locale: Locale,
        label: String,
    ): String? {
        if (units == null || !units.isFinite() || units < 0f || atMs == null || atMs <= 0L) return null
        val age = nowMs - atMs
        if (age > ApiIobSnapshot.FRESHNESS_WINDOW_MS || -age > IOB_FUTURE_MS) return null
        val tenths = (units * 10f).roundToInt().coerceAtMost(ModWatchMessages.IOB_MAX_TENTHS)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val number = if (tenths == 0) "0" else "${tenths / 10}$separator${tenths % 10}"
        val old = if (age > IOB_OLD_MS) " (${readingTime(atMs, zone, pattern, locale)})" else ""
        return "$label $number$old"
    }

    /**
     * The pump's number in upstream's remote snapshot ([ApiIobSnapshot], filled by our CareLink code and later the
     * pump's Bluetooth; its time is the pump's, fresh for 30 min) - read as the watch face reads it
     * (`mod/watch/PumpIobPhone.slot`, phone-only code this file cannot call): nothing while upstream's HTTP API
     * follower is on, since the slot then holds another phone's journal numbers. Never throws.
     */
    private fun pumpIob(context: Context, nowMs: Long): ApiIobSnapshot.RemoteIob? = try {
        val followerOn = try {
            ApiGlucoseSourceRegistry.loadConfig(context).enabled
        } catch (_: Throwable) {
            true // unknown: the number may not be the pump's
        }
        if (followerOn) null else ApiIobSnapshot.fresh(nowMs)
    } catch (_: Throwable) {
        null
    }

    internal data class Range(val low: Float, val high: Float, val veryLow: Float, val veryHigh: Float)

    /** The user's limits as their chart reads them (display units), their defaults where unset (as our widget). */
    private fun range(isMmol: Boolean): Range {
        val d = Range(
            GlucoseRangeColors.defaultLow(isMmol), GlucoseRangeColors.defaultHigh(isMmol),
            GlucoseRangeColors.defaultVeryLow(isMmol), GlucoseRangeColors.defaultVeryHigh(isMmol),
        )
        return try {
            fun pick(v: Float, fallback: Float) = if (v.isFinite() && v > 0f) v else fallback
            Range(pick(Natives.targetlow(), d.low), pick(Natives.targethigh(), d.high),
                pick(Natives.alarmverylow(), d.veryLow), pick(Natives.alarmveryhigh(), d.veryHigh))
        } catch (_: Throwable) {
            d
        }
    }

    /**
     * The chart's readings as their chart and our widget take them: the main line of their resolved chart model
     * (view mode and calibration decided once), plus the number shown when the history lags behind it.
     */
    private fun chartPoints(
        context: Context,
        history: List<GlucosePoint>,
        sensorId: String?,
        viewMode: Int,
        hasCalibration: Boolean,
        startMs: Long,
        shown: CurrentDisplaySource.Snapshot?,
    ): List<ModAodPoint> {
        val model = try {
            NotificationChartModelSource.build(
                context, history, sensorId, viewMode, hasCalibration, emptyList<NotificationChartDrawer.PeerSeries>(), startMs,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "chart model failed: ${t.javaClass.simpleName}")
            null
        }
        val points = model?.primary?.runs?.flatMap { run -> run.points.map { ModAodPoint(it.timestamp, it.value) } }
            ?: history.map { ModAodPoint(it.timestamp, if (viewMode == 1 || viewMode == 3) it.rawValue else it.value) }
        return withShownReading(points, shown?.timeMillis ?: 0L, shown?.primaryValue ?: Float.NaN)
    }

    /** The number shown as the newest dot when their history ends more than a minute before it. */
    internal fun withShownReading(points: List<ModAodPoint>, readingTime: Long, value: Float): List<ModAodPoint> {
        if (readingTime <= 0L || !ModAodPlot.usable(value)) return points
        val newest = points.maxOfOrNull { it.timeMs } ?: Long.MIN_VALUE
        if (newest >= readingTime - 60_000L) return points
        return points + ModAodPoint(readingTime, value)
    }

    /** Upstream's forecast, the same calculation and settings as their chart; the one line of the main reading. */
    private fun forecast(
        context: Context,
        history: List<GlucosePoint>,
        isMmol: Boolean,
        viewMode: Int,
        hasCalibration: Boolean,
        sensorId: String?,
        range: Range,
    ): List<ModAodPoint> {
        val series = NotificationPredictionAccess.buildPredictionSeries(
            context, history, isMmol, viewMode, hasCalibration, sensorId, range.low, range.high,
        )
        return primaryForecast(series.map { s -> s.kind to s.points.map { ModAodPoint(it.timestamp, it.value) } }, viewMode)
    }

    /**
     * Of upstream's forecast lines the one of the main reading: the calibrated one when there is one (their bridge
     * drops it without a calibration), else raw in the raw view modes (1, 3) and auto in the others - their
     * `NotificationChartDrawer.isPrimaryPrediction`.
     */
    internal fun primaryForecast(series: List<Pair<Int, List<ModAodPoint>>>, viewMode: Int): List<ModAodPoint> {
        series.firstOrNull { it.first == NotificationPredictionSeries.KIND_CALIBRATED }?.let { return it.second }
        val raw = viewMode == 1 || viewMode == 3
        val kind = if (raw) NotificationPredictionSeries.KIND_RAW else NotificationPredictionSeries.KIND_AUTO
        return series.firstOrNull { it.first == kind }?.second ?: emptyList()
    }
}
