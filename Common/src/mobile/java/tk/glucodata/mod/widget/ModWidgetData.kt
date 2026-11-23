package tk.glucodata.mod.widget

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.NotificationChartDrawer
import tk.glucodata.NotificationChartModelSource
import tk.glucodata.R
import tk.glucodata.WidgetDisplaySource
import tk.glucodata.chart.HistoryChartModel
import java.util.Date

/**
 * JugglucoNGmod widget: reads upstream's state - only their read functions, as their own widget does
 * (`ExpressiveAppWidget.loadWidgetState`) - into a [ModWidgetInput].
 *
 * One value for everything (number, dots, delta, color): the number is their `primaryStr`/`primaryValue` (as their
 * lock-screen overlay); the chart readings are their resolved chart ([NotificationChartModelSource.build] - the same
 * builder their notification and dashboard use), so the view mode (auto / raw), a recorded value and today's
 * calibration are decided by them, in one place, and the widget calls no calibration itself.
 */
object ModWidgetData {

    /** History read beyond the chart period: the first five minutes of the chart are averaged from full data. */
    private const val LOOKBACK_MS = 30L * 60L * 1000L

    fun load(context: Context, periodMs: Long): ModWidgetInput {
        val isMmol = Applic.unit == 1
        val range = range(isMmol)
        val current = WidgetDisplaySource.resolveWidgetSnapshot()
        val window = periodMs + LOOKBACK_MS
        val history = WidgetDisplaySource.resolveChartHistory(current, window)
        val serial = current?.sensorId ?: WidgetDisplaySource.resolveActiveSensorSerial()
        val resolved = WidgetDisplaySource.resolveDisplaySnapshot(current, history, serial)
        val viewMode = resolved?.viewMode ?: WidgetDisplaySource.resolveViewMode(serial)
        val dataState = WidgetDisplaySource.resolveDataState(resolved, history, serial)
        val hasCalibration = WidgetDisplaySource.hasCalibration(serial, viewMode)
        val startMs = (System.currentTimeMillis() - window).coerceAtLeast(0L)
        val model = try {
            NotificationChartModelSource.build(
                context, history, serial, viewMode, hasCalibration,
                emptyList<NotificationChartDrawer.PeerSeries>(), startMs,
            )
        } catch (t: Throwable) {
            ModWidgetLog.w("chart model failed: ${t.javaClass.simpleName}")
            null
        }
        val readingTime = when {
            resolved != null && resolved.timeMillis > 0L -> resolved.timeMillis
            else -> dataState.latestTimestampMillis
        }
        val value = resolved?.primaryValue ?: Float.NaN
        val points = withShownReading(primaryPoints(model), readingTime, value)
        // the delta as upstream's notification: their trend points ending at the reading shown (with "fewer readings"
        // the number is the last of a finished interval), the lane of the view mode, unsmoothed
        val isRaw = viewMode == 1 || viewMode == 3
        val trend = try {
            tk.glucodata.DisplayTrendSource.resolveTrendPoints(
                tk.glucodata.DisplayTrendSource.endingAtShown(history, resolved), resolved, serial,
            )
        } catch (t: Throwable) {
            ModWidgetLog.w("trend points failed: ${t.javaClass.simpleName}")
            emptyList()
        }
        val deltaPoints = trend.map { p -> ModWidgetPoint(p.timestamp, if (isRaw && p.rawValue > 0f) p.rawValue else p.value) }
        if (dataState.isFresh) {
            // As upstream's widget: their one deadline alarm redraws every surface when this number expires.
            tk.glucodata.GlucoseUpdateBroadcaster.armFreshnessDeadline(dataState.latestTimestampMillis)
        }
        return ModWidgetInput(
            sensorPresent = dataState.sensorPresent,
            valueText = resolved?.primaryStr?.takeIf { it.isNotBlank() && it != "--" },
            value = value,
            rate = resolved?.rate ?: Float.NaN,
            readingTime = readingTime,
            points = points,
            range = range,
            deltaPoints = deltaPoints,
        )
    }

    /**
     * Their chart may end before the number shown (their "fewer readings" keeps the open five minutes back): the
     * number itself - the same value, same mode and calibration - is then the newest dot, so the last, unfinished
     * five minutes always has its dot.
     */
    internal fun withShownReading(points: List<ModWidgetPoint>, readingTime: Long, value: Float): List<ModWidgetPoint> {
        if (readingTime <= 0L || !value.isFinite() || value <= 0.1f) return points
        val newest = points.lastOrNull()?.time ?: Long.MIN_VALUE
        if (newest >= readingTime - ModWidgetMath.MINUTE_MS) return points
        return points + ModWidgetPoint(readingTime, value)
    }

    /** The main line of the primary sensor, one value per timestamp (runs share their boundary point). */
    internal fun primaryPoints(model: HistoryChartModel?): List<ModWidgetPoint> {
        val primary = model?.primary ?: return emptyList()
        val out = ArrayList<ModWidgetPoint>()
        for (run in primary.runs) for (p in run.points) out.add(ModWidgetPoint(p.timestamp, p.value))
        return out
    }

    /** The user's limits as their chart reads them (display units), defaults where unset. */
    fun range(isMmol: Boolean): ModWidgetRange {
        val d = ModWidgetRange.defaults(isMmol)
        return try {
            fun pick(v: Float, fallback: Float) = if (v.isFinite() && v > 0f) v else fallback
            ModWidgetRange(
                pick(Natives.targetlow(), d.low),
                pick(Natives.targethigh(), d.high),
                pick(Natives.alarmverylow(), d.veryLow),
                pick(Natives.alarmveryhigh(), d.veryHigh),
                isMmol,
            )
        } catch (_: Throwable) {
            d
        }
    }

    fun isMmol(): Boolean = try { Applic.unit == 1 } catch (_: Throwable) { true }

    fun timeoutMs(): Long = Notify.glucosetimeout

    /** Texts from resources (their "No value" / "No new value since", ours for the rest). */
    class Texts(private val context: Context) : ModWidgetTexts {
        override fun justNow() = context.getString(R.string.mod_widget_just_now)
        override fun minutesAgo(minutes: Int) = context.getString(R.string.mod_widget_minutes_ago, minutes)
        /** Their text "No new value since " with our own time format (theirs is one shared, unsynchronised formatter). */
        override fun stale(readingTime: Long): String =
            context.getString(R.string.nonewvalue) + android.text.format.DateFormat.getTimeFormat(context).format(Date(readingTime))
        override fun noValue() = context.getString(R.string.novalue)
        override fun unit(isMmol: Boolean) = context.getString(if (isMmol) R.string.unit_mmol else R.string.unit_mg)
        override fun trend(trend: ModWidgetMath.Trend) = context.getString(
            when (trend) {
                ModWidgetMath.Trend.RISING_FAST -> R.string.mod_widget_trend_rising_fast
                ModWidgetMath.Trend.RISING -> R.string.mod_widget_trend_rising
                ModWidgetMath.Trend.RISING_SLOWLY -> R.string.mod_widget_trend_rising_slowly
                ModWidgetMath.Trend.STABLE -> R.string.mod_widget_trend_stable
                ModWidgetMath.Trend.FALLING_SLOWLY -> R.string.mod_widget_trend_falling_slowly
                ModWidgetMath.Trend.FALLING -> R.string.mod_widget_trend_falling
                ModWidgetMath.Trend.FALLING_FAST -> R.string.mod_widget_trend_falling_fast
            }
        )
        override fun example() = context.getString(R.string.mod_widget_example)
    }
}
