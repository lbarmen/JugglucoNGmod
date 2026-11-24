package tk.glucodata.mod.watch.face

import tk.glucodata.GlucoseDelta

/**
 * JugglucoNGmod, watch block (part 3a): the face's value rules, pure functions (watch plan, "Watch face").
 *
 * Age is counted in whole minutes, as the face shows it: the age turns red after [AGE_ALARM_MINUTES] ("11 min" is the
 * first red one) and after [NO_VALUE_MINUTES] the value gives way to "-" ("21 min" is the first without a value).
 */
object FaceValues {

    /** The age turns red when it is more than this many whole minutes. */
    const val AGE_ALARM_MINUTES = 10

    /** No value is shown when the reading is more than this many whole minutes old. */
    const val NO_VALUE_MINUTES = 20

    /**
     * How old a reading the source asks upstream for: every reading shown as "20 min" (up to 20:59) must still
     * come back. Upstream's default (`Notify.glucosetimeout`, 330 s) would drop it after 5.5 minutes.
     */
    const val SOURCE_MAX_AGE_MS = (NO_VALUE_MINUTES + 1) * 60_000L

    /**
     * The least history a source loads, whatever the graph window: enough to find the last reading for the age
     * after the value is gone and the earlier reading for the change.
     */
    const val HISTORY_MIN_MS = 60 * 60_000L

    /** The change is upstream's "Δ" over their default 5-minute interval (the phone's notification and hero card). */
    const val DELTA_INTERVAL_MINUTES = GlucoseDelta.DEFAULT_INTERVAL_MINUTES

    /** The "no value" text, in place of the number, a missing battery and a missing change alike. */
    const val NONE = "—"

    /**
     * The age is written in seconds below this ("119 s", then "2 min"; watch face, 20.11), only while the screen is
     * lit: the dim mode draws once a minute and would leave the seconds up to 59 s behind.
     */
    const val SECONDS_UNTIL_MS = 2 * 60_000L

    /** From this many whole minutes the age is written in whole hours ("99 min", then "1 h"; 20.11, 1a with fixes). */
    const val HOURS_FROM_MINUTES = 100

    /** A frame a second while the age is written in seconds, else a frame a minute (upstream's face too). */
    const val SECONDS_FRAME_MS = 1_000L
    const val MINUTE_FRAME_MS = 60_000L

    /**
     * How often the lit face is drawn ([ambient] - the dim mode, which the system draws once a minute whatever this
     * says): every second while the age ([ageMs], `null` - no reading) is written in seconds, else every minute.
     */
    fun frameDelayMs(ageMs: Long?, ambient: Boolean): Long =
        if (secondsShown(ageMs, ambient)) SECONDS_FRAME_MS else MINUTE_FRAME_MS

    /** The age [ageMs] is written in seconds: the screen lit ([ambient] false) and younger than [SECONDS_UNTIL_MS]. */
    fun secondsShown(ageMs: Long?, ambient: Boolean): Boolean =
        !ambient && ageMs != null && ageMs < SECONDS_UNTIL_MS

    /** The pump's active insulin is fresh (light blue) while younger than this many minutes (20.11). */
    const val IOB_FRESH_MINUTES = 15

    /** ... stale (dim blue) while younger than this, after it "- U" (the phone's slot lasts 30 minutes too). */
    const val IOB_UNKNOWN_MINUTES = 30

    /** How the face shows the pump's active insulin by its age. */
    enum class IobState { FRESH, STALE, UNKNOWN }

    /**
     * The state of an active insulin [ageMs] old (whole minutes, as the glucose age): under [IOB_FRESH_MINUTES] fresh,
     * under [IOB_UNKNOWN_MINUTES] stale, else unknown; a time ahead of the watch's clock by more than a minute (the
     * clock set back after receipt) is unknown too - its age cannot be told.
     */
    fun iobState(ageMs: Long): IobState = when {
        ageMs < -60_000L -> IobState.UNKNOWN
        ageMs < IOB_FRESH_MINUTES * 60_000L -> IobState.FRESH
        ageMs < IOB_UNKNOWN_MINUTES * 60_000L -> IobState.STALE
        else -> IobState.UNKNOWN
    }

    /** Whole minutes from [readingMs] to [nowMs]; a reading "from the future" (clocks apart) counts as 0. */
    fun ageMinutes(nowMs: Long, readingMs: Long): Int {
        val age = nowMs - readingMs
        return if (age <= 0L) 0 else (age / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * How far history may be ahead of the live value before the live value counts as left behind: on the watch the
     * live source can lag the synced history (upstream, `GlucoseComplicationData.kt`), and an old live value must not
     * hide a newer reading.
     */
    const val LIVE_BEHIND_MS = 60_000L

    /** The live value at [liveMs] is behind history whose newest reading is at [newestHistoryMs]. */
    fun liveIsBehind(liveMs: Long, newestHistoryMs: Long?): Boolean =
        newestHistoryMs != null && newestHistoryMs - liveMs > LIVE_BEHIND_MS

    fun isAgeAlarm(ageMinutes: Int): Boolean = ageMinutes > AGE_ALARM_MINUTES

    fun isNoValue(ageMinutes: Int): Boolean = ageMinutes > NO_VALUE_MINUTES

    /** The time of the last reading: [current] or, without one, the newest usable point of [history]. */
    fun lastReadingTime(current: FaceReading?, history: List<FacePoint>): Long? {
        val newest = newestPoint(history)?.timeMs
        val currentTime = current?.timeMs?.takeIf { it > 0L }
        return when {
            currentTime == null -> newest
            newest == null -> currentTime
            else -> maxOf(currentTime, newest)
        }
    }

    /** The newest point of [history] with a usable value. */
    fun newestPoint(history: List<FacePoint>): FacePoint? =
        history.filter { usable(it.value) && it.timeMs > 0L }.maxByOrNull { it.timeMs }

    /**
     * The earlier reading the change is measured against, as upstream's notification walks back
     * (`Notify.java`, "Signed change over the selected interval"): the newest usable point at least
     * `GlucoseDelta.minGapMillis` older than [anchorMs].
     */
    fun previousFor(history: List<FacePoint>, anchorMs: Long, intervalMinutes: Int = DELTA_INTERVAL_MINUTES): FacePoint? {
        val minGap = GlucoseDelta.minGapMillis(intervalMinutes)
        return history.filter { usable(it.value) && anchorMs - it.timeMs >= minGap }.maxByOrNull { it.timeMs }
    }

    /**
     * The signed change of [current] against [previous], formatted by upstream (`GlucoseDelta.format`: "+0,3",
     * "−1", "0"; mmol always one decimal, the locale's separator, a real minus sign); `null` when there is no
     * earlier reading or the pair is too close or too far apart for upstream's rule. Both sides uncalibrated:
     * [FaceReading.deltaValue] against history.
     */
    fun deltaText(
        current: FaceReading?,
        previous: FacePoint?,
        isMmol: Boolean,
        intervalMinutes: Int = DELTA_INTERVAL_MINUTES,
    ): String? {
        if (current == null || previous == null || !usable(current.deltaValue)) return null
        val delta = GlucoseDelta.delta(current.timeMs, current.deltaValue, previous.timeMs, previous.value,
            intervalMinutes)
        return GlucoseDelta.format(delta, isMmol).takeIf { it.isNotEmpty() }
    }

    /** A threshold from upstream's settings when it is usable, else [fallback] (as their renderer does). */
    fun threshold(raw: Float?, fallback: Float): Float =
        raw?.takeIf { it.isFinite() && it > 0f } ?: fallback

    /** Upstream's guard for an empty or sentinel value (their walk-backs skip `value <= 0.1`). */
    fun usable(value: Float): Boolean = value.isFinite() && value > 0.1f

    /** The lane the user looks at: raw for view modes 1 and 3 when the point has one, else auto (as their graph). */
    fun laneValue(value: Float, rawValue: Float, viewMode: Int): Float =
        if ((viewMode == 1 || viewMode == 3) && rawValue.isFinite() && rawValue > 0f) rawValue else value
}
