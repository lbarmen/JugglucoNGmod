package tk.glucodata.mod.aod

import android.content.Context
import android.view.View
import android.widget.ImageView
import java.time.ZoneId
import java.util.Locale
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

/**
 * JugglucoNGmod, phone always-on display (part 1, the owner's variant B, 26.11): our whole block in upstream's overlay.
 * Upstream's `AODOverlayService.updateOverlayContent` hands over what it has just resolved (one hook, `aod-block`):
 * the readings, the number as their app shows it, the arrow's rate, the "no new value" text. We hide their text row
 * (number, arrow, delta, status) and put our block - number, arrow, change and age, chart - into their chart view.
 * Their service keeps everything else: when to show, the burn-in steps, the light sensor, the refresh, the position.
 *
 * Their settings that still act: chart on/off and size, text size, arrow on/off, the delta interval, the "Bottom"
 * position, opacity. Font, weight and "show delta" do not (our block has its own look, as on the watch).
 */
object ModAodBlock {

    private const val TAG = "ModAod"
    private const val PREFS = "tk.glucodata_preferences"

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
            val points = chartPoints(context, history, sensorId, viewMode, hasCalibration, startMs, shown?.takeIf { fresh })
            val forecast = if (showChart) forecast(context, history, isMmol, viewMode, hasCalibration, sensorId, range) else emptyList()
            val plot = ModAodPlot.plot(points, nowMs, range.low, range.high, isMmol, forecast)
            val sub = if (fresh && shown != null) {
                val delta = DisplayDeltaSource.format(
                    true, deltaHistory, sensorId, shown.timeMillis, viewMode, isMmol,
                    prefs.getInt(DisplayDeltaSource.INTERVAL, GlucoseDelta.DEFAULT_INTERVAL_MINUTES), nowMs, Notify.glucosetimeout,
                )
                val minutes = ((nowMs - shown.timeMillis).coerceAtLeast(0L) / 60_000L).toInt()
                subLine(delta, context.getString(R.string.minutes_short_format, minutes))
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
                value, plot, isMmol, palette, zoneColor, ZoneId.systemDefault(),
                android.text.format.DateFormat.is24HourFormat(context), Locale.getDefault(),
            )
            chartView.layoutParams = chartView.layoutParams?.also { it.height = bitmap.height }
            chartView.setImageBitmap(bitmap)
            chartView.visibility = View.VISIBLE
            textRow?.visibility = View.GONE
            true
        } catch (t: Throwable) {
            Log.stack(TAG, "block failed, upstream's look", t)
            textRow?.visibility = View.VISIBLE
            false
        }
    }

    /** Upstream's number without the secondary values they append after " · " (our block shows the primary only). */
    internal fun numberText(valueText: String): String? =
        valueText.substringBefore(" · ").trim().takeIf { it.isNotEmpty() }

    /** "+0,2 · 2 мин": the change and the age; either may be missing. */
    internal fun subLine(delta: String, age: String): String? =
        listOf(delta, age).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }

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
