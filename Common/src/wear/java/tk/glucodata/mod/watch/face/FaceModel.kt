package tk.glucodata.mod.watch.face

import java.time.ZoneId

/**
 * JugglucoNGmod, watch block (part 3a): what the face draws, made from a [FaceSnapshot] by pure rules - the renderer
 * (part 3b) only places it with [FaceLayout] and paints it.
 *
 * [hasValue] true - [ageText] is the age of that value (never of another, newer point); false - [valueText] is
 * [FaceValues.NONE], no arrow ([rate] NaN), no change, no colour; [ageText] still says how old the last reading is
 * (`null` when there has been none in the loaded history).
 */
data class FaceModel(
    val clock: FaceText.Clock,
    /** The day of the month and the short month, "18 Nov" / "18 ноя" (part 7e; the weekday and day before). */
    val dateText: String,
    val hasValue: Boolean,
    val valueText: String,
    /** The value's colour as the app paints it ([FaceReading.color]); `null` without a value (the neutral tone). */
    val valueColor: Int?,
    /** mg/dL per minute for upstream's arrow; NaN - no arrow. */
    val rate: Float,
    val deltaText: String?,
    val ageMinutes: Int?,
    val ageText: String?,
    /** The age in the alarm colour (red): more than [FaceValues.AGE_ALARM_MINUTES]. */
    val ageAlarm: Boolean,
    val graph: FaceGraph.Plot,
    /** Battery percents (0..100), `null` - unknown or too old: no icon (part 7a). */
    val phoneBattery: Int?,
    val watchBattery: Int?,
    /** The full hours of local time in the graph's window, for its thin hour lines (part 3c2). */
    val hourMarks: List<FaceGraph.HourMark> = emptyList(),
    /** The age in ms of what [ageText] is about (`null` - no reading): the lit face's frame rate follows it (20.11). */
    val ageMs: Long? = null,
    /**
     * The pump's active insulin, the third row beside the number (20.11, 1a with fixes): "2,3 ЕД", "99+ ЕД", "— ЕД";
     * `null` - none (two rows, as before).
     */
    val iobText: String? = null,
    /** How [iobText] is coloured: fresh light blue, stale dim blue, unknown ("— ЕД") as stale. */
    val iobState: FaceValues.IobState? = null,
) {
    /** The graph has a forecast to draw. */
    val hasForecast: Boolean get() = graph.forecast.isNotEmpty()

    companion object {
        /**
         * The model at the snapshot's time; [ambient] - the dim mode: the age in minutes there, in seconds under two
         * minutes when the screen is lit (20.11).
         */
        fun build(snapshot: FaceSnapshot, zone: ZoneId, labels: FaceLabels, ambient: Boolean = false): FaceModel {
            val now = snapshot.nowMs
            // The value itself must be young enough, not only the newest point of history.
            val shown = snapshot.current?.takeIf {
                FaceValues.usable(it.value) && it.text.isNotBlank() &&
                    !FaceValues.isNoValue(FaceValues.ageMinutes(now, it.timeMs))
            }
            val hasValue = shown != null
            // The age belongs to the number on the screen; only without one it is the newest reading known.
            val lastTime = shown?.timeMs ?: FaceValues.lastReadingTime(snapshot.current, snapshot.history)
            val age = lastTime?.let { FaceValues.ageMinutes(now, it) }
            val previous = shown?.let { FaceValues.previousFor(snapshot.history, it.timeMs) }
            // a forecast only from a value on the screen that is not yet in the alarm colour: from older readings it
            // would claim where glucose goes from a past that no longer describes now
            val forecast = if (hasValue && age != null && !FaceValues.isAgeAlarm(age)) snapshot.forecast else emptyList()
            val graph = FaceGraph.plot(snapshot.graphPoints, now, snapshot.graphHours, snapshot.low, snapshot.high,
                snapshot.isMmol, forecast)
            val ageMs = lastTime?.let { now - it }
            // only the pump's number (PumpIobStore); its age counts on with the watch's clock, never re-computed
            val iobState = snapshot.pumpIob?.let { FaceValues.iobState(now - it.valueAtMs) }
            val iobText = snapshot.pumpIob?.let {
                FaceText.iob(if (iobState == FaceValues.IobState.UNKNOWN) null else it.tenths, labels)
            }
            return FaceModel(
                clock = FaceText.clock(now, zone, snapshot.hour24),
                dateText = FaceText.dayAndMonth(now, zone, labels),
                hasValue = hasValue,
                valueText = shown?.text ?: FaceValues.NONE,
                valueColor = shown?.color,
                rate = shown?.rate?.takeIf { it.isFinite() } ?: Float.NaN,
                deltaText = FaceValues.deltaText(shown, previous, snapshot.isMmol),
                ageMinutes = age,
                ageText = FaceText.ageOf(ageMs, seconds = !ambient, labels),
                ageAlarm = age != null && FaceValues.isAgeAlarm(age),
                graph = graph,
                phoneBattery = snapshot.phoneBatteryPercent?.takeIf { it in 0..100 },
                watchBattery = snapshot.watchBatteryPercent?.takeIf { it in 0..100 },
                hourMarks = FaceGraph.hourMarks(graph.fromMs, graph.toMs, zone, snapshot.hour24),
                ageMs = ageMs,
                iobText = iobText,
                iobState = iobState,
            )
        }
    }
}
