package tk.glucodata.mod.watch.face

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * JugglucoNGmod, watch block (part 3a): the face's graph without drawing - the time window (1, 3 or 6 hours, a face
 * setting; 3 by default) and the vertical scale. The target range lines are always in view, so a calm in-range trace
 * still shows where "low" and "high" are, and a trace far above them is never cut off.
 */
object FaceGraph {

    val HOURS = listOf(1, 3, 6)
    const val DEFAULT_HOURS = 3

    /** Upstream's least span of the vertical scale (their sparkline): a calm hour must not look like mountains. */
    private const val MIN_SPAN_MMOL = 3f
    private const val MIN_SPAN_MGDL = 54f

    /** Air above and below the extremes, a share of the span, at least [MIN_PAD_MMOL] / [MIN_PAD_MGDL]. */
    private const val PAD_SHARE = 0.08f
    private const val MIN_PAD_MMOL = 0.3f
    private const val MIN_PAD_MGDL = 5f

    /**
     * What to draw: [points] inside [fromMs]..[toMs], oldest first; [dots] - their [fiveMinuteMeans], what the face
     * paints (part 7a); [forecast] - upstream's forecast after the readings, inside the window; the vertical scale
     * [bottom]..[top] (display units) holds every point, the forecast and both [low] and [high]. [nowMs] is where the
     * readings end: [toMs] itself without a forecast, before it with one.
     */
    data class Plot(
        val fromMs: Long,
        val toMs: Long,
        val points: List<FacePoint>,
        val bottom: Float,
        val top: Float,
        val low: Float,
        val high: Float,
        val dots: List<FacePoint> = emptyList(),
        val forecast: List<FacePoint> = emptyList(),
        val nowMs: Long = toMs,
    )

    /** A setting value as one of [HOURS]; anything else is [DEFAULT_HOURS]. */
    fun sanitizeHours(hours: Int): Int = if (hours in HOURS) hours else DEFAULT_HOURS

    fun windowMs(hours: Int): Long = sanitizeHours(hours) * 3_600_000L

    /**
     * The room right of now for the forecast, a share of the readings' window: a quarter of it, so the forecast takes
     * at most a fifth of the graph's width (upstream's chart on the watch gives it 22 %, `PREDICTION_MAX_FRACTION`);
     * a longer forecast is cut there.
     */
    const val FORECAST_SHARE = 0.25f

    /**
     * The plot of [points] for the [hours] up to [nowMs]: points before the window and unusable values are left out;
     * points a little ahead of [nowMs] (the phone's clock ahead of the watch's) stay and sit at the right edge of the
     * readings. A [forecast] reaching past [nowMs] moves the right edge on by up to [FORECAST_SHARE] of the window.
     */
    fun plot(
        points: List<FacePoint>,
        nowMs: Long,
        hours: Int,
        low: Float,
        high: Float,
        isMmol: Boolean,
        forecast: List<FacePoint> = emptyList(),
    ): Plot {
        val window = windowMs(hours)
        val from = nowMs - window
        val inWindow = points.filter { it.timeMs >= from && FaceValues.usable(it.value) }.sortedBy { it.timeMs }
        val ahead = forecast.filter { it.timeMs > nowMs && FaceValues.usable(it.value) }.sortedBy { it.timeMs }
        val to = if (ahead.isEmpty()) nowMs else nowMs + minOf(ahead.last().timeMs - nowMs, (window * FORECAST_SHARE).toLong())
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
        return Plot(from, to, inWindow, bottom, maxValue + pad, lo, hi, fiveMinuteMeans(inWindow), future, nowMs)
    }

    /** The length of one dot's bucket (part 7a, as xDrip's face draws its readings). */
    const val BUCKET_MS = 5 * 60_000L

    /**
     * One dot per 5 minutes of the clock (:00, :05, ...; buckets start on multiples of [BUCKET_MS] since the epoch,
     * which are full 5 minutes in every time zone), the mean of the usable values in it, at the bucket's middle; the
     * newest bucket (still filling) at its newest reading when that is earlier, so a dot never sits ahead of the
     * readings. A bucket without readings has no dot (a sensor gap stays visible). [points] oldest first or not.
     */
    fun fiveMinuteMeans(points: List<FacePoint>): List<FacePoint> {
        val buckets = points.filter { FaceValues.usable(it.value) }
            .groupBy { Math.floorDiv(it.timeMs, BUCKET_MS) }
            .toSortedMap()
        val newest = buckets.keys.lastOrNull()
        return buckets.map { (bucket, inBucket) ->
            val middle = bucket * BUCKET_MS + BUCKET_MS / 2
            val time = if (bucket == newest) minOf(middle, inBucket.maxOf { it.timeMs }) else middle
            FacePoint(time, inBucket.map { it.value }.average().toFloat())
        }
    }

    /** Readings further apart than this are not joined by a line (a sensor gap stays visible). */
    const val MAX_GAP_MS = 15 * 60_000L

    /** [points] (oldest first) cut into runs where no two neighbours are more than [maxGapMs] apart. */
    fun segments(points: List<FacePoint>, maxGapMs: Long = MAX_GAP_MS): List<List<FacePoint>> {
        val runs = mutableListOf<MutableList<FacePoint>>()
        for (p in points) {
            val run = runs.lastOrNull()
            if (run != null && p.timeMs - run.last().timeMs <= maxGapMs) run += p else runs += mutableListOf(p)
        }
        return runs
    }

    /** Horizontal place of [timeMs] in the plot, 0 (left, oldest) .. 1 (right, now); clamped to the box. */
    fun xFraction(plot: Plot, timeMs: Long): Float {
        val span = (plot.toMs - plot.fromMs).coerceAtLeast(1L)
        return ((timeMs - plot.fromMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Vertical place of [value] in the plot, 0 (top) .. 1 (bottom); clamped to the box. */
    fun yFraction(plot: Plot, value: Float): Float {
        val span = (plot.top - plot.bottom).coerceAtLeast(0.001f)
        return (1f - (value - plot.bottom) / span).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ hour lines (part 3c2)

    /** A full hour of local time inside the window: where its thin line goes and its label ("9", "21"; "9" pm). */
    data class HourMark(val timeMs: Long, val label: String)

    /**
     * Every full hour of local time in [zone] after [fromMs] up to and including [toMs], oldest first: one line for
     * a 1 h window, three for 3 h, six for 6 h (the left edge itself never has one). The label is the hour as the
     * face's clock writes it: 0..23 in 24-hour mode, 1..12 otherwise, without leading zero or am/pm.
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

    /** A guard against a broken window: never more lines than the longest window has hours. */
    private const val MAX_HOUR_MARKS = 24

    // ------------------------------------------------------------------ colour by range (part 3c2)

    /** Where a piece of the trace is: below the low line, in the target range (lines included), above the high line. */
    enum class Zone { BELOW, IN, ABOVE }

    /**
     * One straight piece of the trace inside one [zone]; [colorValue] is the value its colour is taken for (the same
     * colour as the number would have at that value): the piece's own reading, not a point on a target line.
     */
    data class Piece(val from: FacePoint, val to: FacePoint, val zone: Zone, val colorValue: Float)

    fun zoneOf(value: Float, low: Float, high: Float): Zone = when {
        value < minOf(low, high) -> Zone.BELOW
        value > maxOf(low, high) -> Zone.ABOVE
        else -> Zone.IN
    }

    /**
     * The step from [a] to [b] cut where it crosses the [low] and [high] lines (straight line between the two
     * readings), so each piece lies in one [Zone] - a step from below the range to above it gives three pieces.
     * A step that crosses nothing is one piece coloured by [b], as before part 3c2 (by [a] when [b] lies exactly on
     * the line it arrives at from outside).
     */
    fun splitByRange(a: FacePoint, b: FacePoint, low: Float, high: Float): List<Piece> {
        val lo = minOf(low, high)
        val hi = maxOf(low, high)
        val crossings = listOf(lo, hi)
            .filter { level -> (a.value < level && b.value > level) || (a.value > level && b.value < level) }
            .map { level ->
                val share = (level - a.value) / (b.value - a.value)
                FacePoint(a.timeMs + ((b.timeMs - a.timeMs) * share).toLong(), level)
            }
            .sortedBy { abs(it.value - a.value) }
        if (crossings.isEmpty()) {
            // a reading exactly on a line belongs to the range; a step ending on the line from outside keeps the
            // colour of where it comes from
            val zone = zoneOf((a.value + b.value) / 2f, lo, hi)
            return listOf(Piece(a, b, zone, if (zoneOf(b.value, lo, hi) == zone) b.value else a.value))
        }
        val points = listOf(a) + crossings + b
        return points.zipWithNext().map { (p, q) ->
            val mid = (p.value + q.value) / 2f
            // the end that is a real reading gives the colour; the middle piece of a double crossing has none
            val colorValue = when {
                q === b -> b.value
                p === a -> a.value
                else -> mid
            }
            Piece(p, q, zoneOf(mid, lo, hi), colorValue)
        }
    }
}
