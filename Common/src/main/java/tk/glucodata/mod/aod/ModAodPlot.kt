package tk.glucodata.mod.aod

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A reading or a forecast point, in display units (mmol/L or mg/dL). */
data class ModAodPoint(val timeMs: Long, val value: Float)

/**
 * JugglucoNGmod, phone always-on display (part 1): the chart without drawing. The window is the 3 hours upstream's
 * AOD service reads; one dot per 5 minutes and the vertical scale follow the watch face's rules (`FaceGraph`, the
 * owner asked for "as on the watch"): the target lines are always in view, the forecast takes at most a fifth of the
 * width. No Android: tested on the JVM.
 */
object ModAodPlot {

    /** Upstream's AOD reads 3 hours of history (`AODOverlayService.updateOverlayContent`). */
    const val WINDOW_MS = 3 * 3_600_000L

    /** One dot's bucket: 5 minutes of the clock. */
    const val BUCKET_MS = 5 * 60_000L

    /** The room right of now for the forecast, a share of the window (as the watch face, `FaceGraph.FORECAST_SHARE`). */
    const val FORECAST_SHARE = 0.25f

    /** Upstream's least span of the vertical scale: a calm hour must not look like mountains. */
    private const val MIN_SPAN_MMOL = 3f
    private const val MIN_SPAN_MGDL = 54f

    /** Air above and below the extremes, a share of the span, at least [MIN_PAD_MMOL] / [MIN_PAD_MGDL]. */
    private const val PAD_SHARE = 0.08f
    private const val MIN_PAD_MMOL = 0.3f
    private const val MIN_PAD_MGDL = 5f

    /**
     * What to draw: [dots] - the 5-minute means of the readings inside [fromMs]..[nowMs]; [forecast] - the forecast
     * after [nowMs], inside the window; [lastReading] - where the forecast line starts; the vertical scale
     * [bottom]..[top] holds every reading, the forecast and both [low] and [high]. [toMs] is [nowMs] without a forecast.
     */
    data class Plot(
        val fromMs: Long,
        val toMs: Long,
        val nowMs: Long,
        val dots: List<ModAodPoint>,
        val forecast: List<ModAodPoint>,
        val lastReading: ModAodPoint?,
        val bottom: Float,
        val top: Float,
        val low: Float,
        val high: Float,
    )

    /** A value the chart can show: finite and above 0.1 (as upstream's chart and notification). */
    fun usable(value: Float): Boolean = value.isFinite() && value > 0.1f

    /**
     * The plot of [points] for the [windowMs] up to [nowMs]: points before the window and unusable values are left
     * out. A [forecast] reaching past [nowMs] moves the right edge on by up to [FORECAST_SHARE] of the window; a longer
     * forecast is cut there.
     */
    fun plot(
        points: List<ModAodPoint>,
        nowMs: Long,
        low: Float,
        high: Float,
        isMmol: Boolean,
        forecast: List<ModAodPoint> = emptyList(),
        windowMs: Long = WINDOW_MS,
    ): Plot {
        val from = nowMs - windowMs
        val inWindow = points.filter { it.timeMs >= from && usable(it.value) }.sortedBy { it.timeMs }
        val ahead = forecast.filter { it.timeMs > nowMs && usable(it.value) }.sortedBy { it.timeMs }
        val to = if (ahead.isEmpty()) nowMs else nowMs + minOf(ahead.last().timeMs - nowMs, (windowMs * FORECAST_SHARE).toLong())
        val future = ahead.filter { it.timeMs <= to }
        val lo = minOf(low, high)
        val hi = maxOf(low, high)
        val all = inWindow + future
        var minValue = minOf(lo, all.minOfOrNull { it.value } ?: lo)
        var maxValue = maxOf(hi, all.maxOfOrNull { it.value } ?: hi)
        val minSpan = if (isMmol) MIN_SPAN_MMOL else MIN_SPAN_MGDL
        if (maxValue - minValue < minSpan) {
            val mid = (maxValue + minValue) / 2f
            minValue = mid - minSpan / 2f
            maxValue = mid + minSpan / 2f
        }
        val pad = maxOf((maxValue - minValue) * PAD_SHARE, if (isMmol) MIN_PAD_MMOL else MIN_PAD_MGDL)
        val bottom = maxOf(0f, minValue - pad)
        return Plot(from, to, nowMs, fiveMinuteMeans(inWindow), future, inWindow.lastOrNull(), bottom, maxValue + pad, lo, hi)
    }

    /**
     * One dot per 5 minutes of the clock (:00, :05, ...; buckets start on multiples of [BUCKET_MS] since the epoch,
     * full 5 minutes in every time zone), the mean of the usable values in it, at the bucket's middle; the newest
     * bucket (still filling) at its newest reading when that is earlier, so a dot never sits ahead of the readings.
     * A bucket without readings has no dot (a sensor gap stays visible). The watch face's rule (`FaceGraph`).
     */
    fun fiveMinuteMeans(points: List<ModAodPoint>): List<ModAodPoint> {
        val buckets = points.filter { usable(it.value) }
            .groupBy { Math.floorDiv(it.timeMs, BUCKET_MS) }
            .toSortedMap()
        val newest = buckets.keys.lastOrNull()
        return buckets.map { (bucket, inBucket) ->
            val middle = bucket * BUCKET_MS + BUCKET_MS / 2
            val time = if (bucket == newest) minOf(middle, inBucket.maxOf { it.timeMs }) else middle
            ModAodPoint(time, inBucket.map { it.value }.average().toFloat())
        }
    }

    /** Horizontal place of [timeMs], 0 (left, oldest) .. 1 (right edge); clamped to the box. */
    fun xFraction(plot: Plot, timeMs: Long): Float {
        val span = (plot.toMs - plot.fromMs).coerceAtLeast(1L)
        return ((timeMs - plot.fromMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Vertical place of [value], 0 (top) .. 1 (bottom); clamped to the box. */
    fun yFraction(plot: Plot, value: Float): Float {
        val span = (plot.top - plot.bottom).coerceAtLeast(0.001f)
        return (1f - (value - plot.bottom) / span).coerceIn(0f, 1f)
    }

    /** A full hour of local time inside the window: where its thin line goes and its label ("9", "21"; "9" pm). */
    data class HourMark(val timeMs: Long, val label: String)

    /**
     * Every full hour of local time in [zone] after [fromMs] up to and including [toMs], oldest first (the left edge
     * itself never has one). The label is the hour as the clock writes it: 0..23 in 24-hour mode, 1..12 otherwise.
     */
    fun hourMarks(fromMs: Long, toMs: Long, zone: ZoneId, hour24: Boolean): List<HourMark> {
        if (toMs <= fromMs) return emptyList()
        val marks = mutableListOf<HourMark>()
        var t = Instant.ofEpochMilli(fromMs).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        if (t.toInstant().toEpochMilli() <= fromMs) t = t.plusHours(1)
        while (marks.size <= MAX_HOUR_MARKS) {
            val ms = t.toInstant().toEpochMilli()
            if (ms > toMs) break
            val hour = t.hour
            val label = if (hour24) hour.toString() else (hour % 12).let { if (it == 0) 12 else it }.toString()
            marks += HourMark(ms, label)
            t = t.plusHours(1)
        }
        return marks
    }

    /** A guard against a broken window: never more lines than a day has hours. */
    private const val MAX_HOUR_MARKS = 24
}
