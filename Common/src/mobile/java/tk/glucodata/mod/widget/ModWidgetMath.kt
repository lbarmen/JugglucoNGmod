package tk.glucodata.mod.widget

import tk.glucodata.GlucoseChartGap
import tk.glucodata.GlucoseDelta
import kotlin.math.max
import kotlin.math.min

/** One reading of the chart, in display units (mmol/L or mg/dL), already resolved for the view mode. */
data class ModWidgetPoint(val time: Long, val value: Float)

/** Whether there is a current number to show. */
enum class ModWidgetFreshness { FRESH, STALE, NONE }

/**
 * JugglucoNGmod widget: the pure rules - which readings count, the 5-minute averages, the 5-minute delta, the age of
 * the reading, the chart scale and the redraw alarm. No Android, no upstream state: tested on the JVM.
 */
object ModWidgetMath {
    const val MINUTE_MS = 60_000L
    const val FIVE_MIN_MS = 5L * MINUTE_MS

    /** A reading stamped later than this ahead of the clock (a clock or time zone change) is not used. */
    const val FUTURE_TOLERANCE_MS = MINUTE_MS

    /** Two readings closer than this are one reading stamped twice (stored vs live time of the same value). */
    const val SAME_READING_MS = 30_000L

    /**
     * Readings that count: a finite value above 0.1 (as upstream's notification delta and dashboard), not more than
     * a minute in the future; sorted by time; of near-duplicates (under [SAME_READING_MS] apart) the later one.
     */
    fun usable(points: List<ModWidgetPoint>, now: Long): List<ModWidgetPoint> {
        val byTime = java.util.TreeMap<Long, ModWidgetPoint>()
        for (p in points) {
            if (!p.value.isFinite() || p.value <= 0.1f) continue
            if (p.time <= 0L || p.time > now + FUTURE_TOLERANCE_MS) continue
            byTime[p.time] = p
        }
        val out = ArrayList<ModWidgetPoint>(byTime.size)
        var groupStart = Long.MIN_VALUE
        for (p in byTime.values) {
            // measured from the first of the group: a chain 0, 25, 50, 75 s is two readings, not one
            if (out.isNotEmpty() && p.time - groupStart < SAME_READING_MS) {
                out[out.size - 1] = p
            } else {
                out.add(p)
                groupStart = p.time
            }
        }
        return out
    }

    /** Readings inside the chart period: from [now] minus [periodMs] on. */
    fun inPeriod(points: List<ModWidgetPoint>, now: Long, periodMs: Long): List<ModWidgetPoint> {
        val start = now - periodMs
        return points.filter { it.time >= start }
    }

    /**
     * One dot per clock five minutes (:00, :05, ... counted from the epoch, so they match the local clock in every
     * time zone whose offset is a multiple of 5 minutes): the mean value of the readings in it, at the mean time of
     * those readings. The last, unfinished five minutes is the mean of what has come so far. Five minutes without a
     * reading has no dot. Input sorted by time (as [usable] gives it).
     */
    fun fiveMinuteAverages(points: List<ModWidgetPoint>): List<ModWidgetPoint> {
        val out = ArrayList<ModWidgetPoint>()
        var bucket = Long.MIN_VALUE
        var sumValue = 0.0
        var sumTime = 0.0
        var count = 0
        fun flush() {
            if (count > 0) out.add(ModWidgetPoint((sumTime / count).toLong(), (sumValue / count).toFloat()))
            sumValue = 0.0; sumTime = 0.0; count = 0
        }
        for (p in points) {
            val b = Math.floorDiv(p.time, FIVE_MIN_MS)
            if (b != bucket) {
                flush()
                bucket = b
            }
            sumValue += p.value
            sumTime += p.time.toDouble()
            count++
        }
        flush()
        return out
    }

    /** The chart line: split where readings are further apart than upstream's chart gap rule. */
    fun lineRuns(points: List<ModWidgetPoint>, gapMs: Long = GlucoseChartGap.THRESHOLD_MS): List<List<ModWidgetPoint>> {
        val runs = ArrayList<List<ModWidgetPoint>>()
        var current = ArrayList<ModWidgetPoint>()
        for (p in points) {
            val last = current.lastOrNull()
            if (last != null && p.time - last.time > gapMs) {
                runs.add(current)
                current = ArrayList()
            }
            current.add(p)
        }
        if (current.isNotEmpty()) runs.add(current)
        return runs
    }

    /** What the chart draws for the chosen dot style: averages, every reading, or every reading as a line. */
    fun chartPoints(usable: List<ModWidgetPoint>, now: Long, settings: ModWidgetSettings): List<ModWidgetPoint> {
        val inPeriod = inPeriod(usable, now, settings.periodMillis)
        return when (settings.dots) {
            // the first five minutes of the period is averaged from all its readings, not only those after the cut
            ModWidgetDots.AVERAGE_5MIN -> {
                val firstSlot = Math.floorDiv(now - settings.periodMillis, FIVE_MIN_MS) * FIVE_MIN_MS
                fiveMinuteAverages(usable.filter { it.time >= firstSlot })
            }
            ModWidgetDots.EACH, ModWidgetDots.LINE -> inPeriod
        }
    }

    /**
     * Change over 5 minutes, always 5 (not upstream's 1-or-5 setting: the widget's delta goes with its 5-minute
     * dots). The newest reading against the first one at least 4.5 min older, walking back as upstream's
     * notification does; upstream's [GlucoseDelta] scales the pair to 5 minutes and gives NaN for a pair under
     * 2.5 min or over 20 min apart. Input as [usable] gives it.
     */
    fun fiveMinuteDelta(points: List<ModWidgetPoint>): Float {
        if (points.size < 2) return Float.NaN
        val newest = points.last()
        val minGap = GlucoseDelta.minGapMillis(GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
        for (i in points.size - 2 downTo 0) {
            val p = points[i]
            if (newest.time - p.time >= minGap) {
                return GlucoseDelta.delta(newest.time, newest.value, p.time, p.value, GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
            }
        }
        return Float.NaN
    }

    /**
     * The delta next to the number: only when the newest reading is the number shown (within a minute of
     * [readingTime]) - otherwise the pair says nothing about the current reading (a lagging history, another sensor).
     */
    fun deltaForReading(points: List<ModWidgetPoint>, readingTime: Long): Float {
        val newest = points.lastOrNull() ?: return Float.NaN
        if (kotlin.math.abs(newest.time - readingTime) > MINUTE_MS) return Float.NaN
        return fiveMinuteDelta(points)
    }

    /**
     * Fresh while the reading is at most [timeoutMs] old (the exact threshold counts as fresh, as upstream's
     * DisplayDataState); a reading stamped more than a minute ahead is not a current reading at all.
     */
    fun freshness(readingTime: Long, now: Long, timeoutMs: Long): ModWidgetFreshness {
        if (readingTime <= 0L || readingTime > now + FUTURE_TOLERANCE_MS) return ModWidgetFreshness.NONE
        return if (now - readingTime <= timeoutMs) ModWidgetFreshness.FRESH else ModWidgetFreshness.STALE
    }

    /** Whole minutes since the reading; 0 is "just now" (also for a reading up to a minute ahead). */
    fun ageMinutes(readingTime: Long, now: Long): Int = (max(0L, now - readingTime) / MINUTE_MS).toInt()

    /**
     * When to redraw next so "N min ago" does not freeze: the next whole minute of the reading's age, but never later
     * than the moment it stops being fresh ([readingTime] + [timeoutMs] + 1 ms - upstream's 330 s is not a whole
     * number of minutes, so the last alarm is exactly that deadline). Null when the reading is not fresh: the
     * "no new value since" text does not change, and the next reading wakes the widget by upstream's broadcast.
     */
    fun nextRedraw(readingTime: Long, now: Long, timeoutMs: Long): Long? {
        if (freshness(readingTime, now, timeoutMs) != ModWidgetFreshness.FRESH) return null
        val age = max(0L, now - readingTime)
        val nextMinute = readingTime + (age / MINUTE_MS + 1) * MINUTE_MS
        val deadline = readingTime + timeoutMs + 1L
        return min(nextMinute, deadline)
    }

    /** Vertical range of the chart. */
    data class Scale(val min: Float, val max: Float) {
        /** Y for [value] between [top] and [bottom] pixels; values outside the scale sit at its edge. */
        fun y(value: Float, top: Float, bottom: Float): Float {
            val span = (max - min).takeIf { it > 0f } ?: 1f
            val f = ((value - min) / span).coerceIn(0f, 1f)
            return bottom - f * (bottom - top)
        }
    }

    /**
     * From the lower of (data, low limit) to the higher of (data, high limit), with a margin: the target range is
     * always on the chart, so a dot's height against the range does not jump as the data changes.
     */
    fun verticalScale(values: List<Float>, low: Float, high: Float): Scale {
        var lo = low
        var hi = high
        for (v in values) {
            if (!v.isFinite()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (hi <= lo) hi = lo + 1f
        val pad = (hi - lo) * 0.08f
        return Scale(lo - pad, hi + pad)
    }

    /** X for [time]: [now] at [right], [now] minus the period at [left]; outside - at the edge. */
    fun x(time: Long, now: Long, periodMs: Long, left: Float, right: Float): Float {
        val f = ((time - (now - periodMs)).toFloat() / periodMs.toFloat()).coerceIn(0f, 1f)
        return left + f * (right - left)
    }

    /** Words for the trend of [rateMgdlPerMin] (upstream's rate unit), for TalkBack; null when there is no rate. */
    enum class Trend { RISING_FAST, RISING, RISING_SLOWLY, STABLE, FALLING_SLOWLY, FALLING, FALLING_FAST }

    fun trend(rateMgdlPerMin: Float): Trend? {
        if (!rateMgdlPerMin.isFinite()) return null
        val r = rateMgdlPerMin
        return when {
            r >= 2f -> Trend.RISING_FAST
            r >= 1f -> Trend.RISING
            r > 0.5f -> Trend.RISING_SLOWLY
            r <= -2f -> Trend.FALLING_FAST
            r <= -1f -> Trend.FALLING
            r < -0.5f -> Trend.FALLING_SLOWLY
            else -> Trend.STABLE
        }
    }
}
