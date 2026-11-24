package tk.glucodata.mod.watch.face

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseSmoothing
import tk.glucodata.GlucoseValueTone
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.glucosecomplication.ComplicationRenderer
import tk.glucodata.mod.watch.ModBattery
import tk.glucodata.mod.watch.PhoneBatteryStore
import tk.glucodata.mod.watch.PumpIobStore
import tk.glucodata.ui.WearPrediction
import tk.glucodata.ui.WearSensorSelection

/**
 * JugglucoNGmod, watch block (part 3a): the face's data from upstream's own sources - the same ones their face and
 * complications read, with our age limit. Thin on purpose: every rule is in [FaceValues], [FaceGraph] and [FaceModel].
 *
 * - current value: `CurrentDisplaySource.resolveCurrent(maxAgeMillis, sensor)` with [FaceValues.SOURCE_MAX_AGE_MS],
 *   not `GlucoseComplicationData.currentReading()` (drops everything older than 330 s); without a live value, or
 *   when history is newer than it by more than [FaceValues.LIVE_BEHIND_MS], the newest synced point resolved as
 *   their complications do (`GlucoseComplicationData.syncedReading`);
 * - the change: the uncalibrated lane value of that reading against history ([FaceReading.deltaValue]);
 * - history: `NotificationHistorySource.getDisplayHistory` for our window (their sparkline is fixed at 90 minutes),
 *   on the lane of the sensor's view mode, smoothed for the graph as their sparkline is;
 * - sensor `WearSensorSelection.resolve()`, units `ComplicationRenderer.isMmol()`, colour [trafficColor] (as the
 *   app, part 7a; the arrow and the change take it too, part 7l), range `Natives.targetlow/targethigh` with
 *   `GlucoseRangeColors` defaults (as `ComplicationRenderer.drawSparkline`), clock mode `Applic.hour24` (as `WatchRenderer.watchtime`);
 * - batteries: [PhoneBatteryStore] (from `/mod/tick`) and [ModBattery] (this watch);
 * - the pump's active insulin: [PumpIobStore] (from `/mod/tick`, only the pump's number; never upstream's journal
 *   computation on the watch, `WearJournalIob`);
 * - forecast: upstream's `WearPrediction.forecast` of the lane, only with their "Prediction" setting on (as their
 *   chart screen on the watch, `ChartScreen`); the dim mode draws it as well (part 7l).
 *
 * Every upstream call is guarded: a failure there leaves that part empty, never the face crashed.
 */
internal object ModFaceSource : FaceSource {

    override fun snapshot(context: Context, nowMs: Long, graphHours: Int): FaceSnapshot {
        val hours = FaceGraph.sanitizeHours(graphHours)
        val isMmol = ComplicationRenderer.isMmol()
        val sensor = guard { WearSensorSelection.resolve() }
        val viewMode = guard { CurrentDisplaySource.resolveViewModeForSensor(sensor) } ?: 0
        val from = nowMs - maxOf(FaceGraph.windowMs(hours), FaceValues.HISTORY_MIN_MS)
        val points = guard { NotificationHistorySource.getDisplayHistory(from, isMmol, sensor) }.orEmpty()
        val lane = points
            .map { GlucosePoint(it.timestamp, FaceValues.laneValue(it.value, it.rawValue, viewMode), it.rawValue) }
        val history = lane.map { FacePoint(it.timestamp, it.value) }
        val graph = smoothed(context, lane, nowMs).map { FacePoint(it.timestamp, it.value) }
        // the lane's own value is in `value`, so upstream projects the lane the user looks at
        val forecast = if (guard { WearPrediction.isEnabled() } == true) {
            guard { WearPrediction.forecast(lane, isMmol) }.orEmpty()
                .filter { FaceValues.usable(it.value) }
                .map { FacePoint(it.timestamp, it.value) }
        } else emptyList()
        val limits = limits(isMmol)
        return FaceSnapshot(
            nowMs = nowMs,
            isMmol = isMmol,
            hour24 = guard { Applic.hour24 } ?: true,
            graphHours = hours,
            current = current(sensor, isMmol, nowMs, points, lane, limits),
            history = history,
            graphPoints = graph,
            low = limits.low,
            high = limits.high,
            phoneBatteryPercent = guard { PhoneBatteryStore.read(context, nowMs) },
            watchBatteryPercent = guard { ModBattery.read(context)?.percent },
            forecast = forecast,
            pumpIob = guard { PumpIobStore.read(context) }?.let { FacePumpIob(it.tenths, it.valueAtMs) },
        )
    }

    /**
     * The live value, unless history already holds a newer reading (the live source lagging the sync): then the
     * newest synced point, so the number, its colour and its age are of the same, newest reading.
     */
    private fun current(
        sensor: String?,
        isMmol: Boolean,
        nowMs: Long,
        points: List<GlucosePoint>,
        lane: List<GlucosePoint>,
        limits: Limits,
    ): FaceReading? {
        val newest = newestIndex(lane)
        val live = guard { CurrentDisplaySource.resolveCurrent(FaceValues.SOURCE_MAX_AGE_MS, sensor) }
            ?.takeIf { FaceValues.usable(it.primaryValue) && it.primaryStr.isNotBlank() && it.timeMillis > 0L }
            ?.takeIf { !FaceValues.liveIsBehind(it.timeMillis, newest?.let { i -> lane[i].timestamp }) }
        val shown = live ?: synced(sensor, nowMs, newest?.let { points[it] }) ?: return null
        val color = guard { trafficColor(shown.primaryValue, isMmol, limits) } ?: NEUTRAL
        return FaceReading(shown.timeMillis, shown.primaryValue, shown.primaryStr, shown.rate, color,
            deltaValue = FaceValues.laneValue(shown.autoValue, shown.rawValue, shown.viewMode))
    }

    /** Index of the newest usable point of the [lane]. */
    private fun newestIndex(lane: List<GlucosePoint>): Int? =
        lane.indices.filter { FaceValues.usable(lane[it].value) && lane[it].timestamp > 0L }
            .maxByOrNull { lane[it].timestamp }

    /**
     * The [newest] synced point, young enough, through upstream's resolver (calibration, view mode). It goes in with
     * its own auto value, not the lane's (upstream: "raw-primary peers must not become auto").
     */
    private fun synced(sensor: String?, nowMs: Long, newest: GlucosePoint?): CurrentDisplaySource.Snapshot? {
        if (newest == null || nowMs - newest.timestamp > FaceValues.SOURCE_MAX_AGE_MS) return null
        return guard {
            CurrentDisplaySource.resolveIncomingReading(
                reading = LiveReadingLanes.stock(newest.value, newest.rawValue),
                rate = Float.NaN,
                targetTimeMillis = newest.timestamp,
                preferredSensorId = sensor,
            )
        }?.takeIf { FaceValues.usable(it.primaryValue) && it.primaryStr.isNotBlank() }
    }

    /** The user's graph smoothing, as upstream's sparkline applies it. */
    private fun smoothed(context: Context, points: List<GlucosePoint>, nowMs: Long): List<GlucosePoint> {
        val minutes = guard { DataSmoothing.graphSmoothingMinutes(context) } ?: 0
        val collapse = guard { DataSmoothing.graphCollapseMinutes(context) } ?: 0
        if (minutes <= 0 && collapse <= 0) return points
        return guard {
            GlucoseSmoothing.smooth(
                points = points,
                smoothingMinutes = minutes,
                collapseMinutes = collapse,
                timestamp = { it.timestamp },
                value = { it.value },
                rawValue = { it.rawValue },
                withValues = { point, auto, raw -> GlucosePoint(point.timestamp, auto, raw) },
                nowMillis = nowMs,
            )
        } ?: points
    }

    /** The four limits of upstream's traffic light in display units (`Natives`, `GlucoseRangeColors` defaults). */
    data class Limits(val low: Float, val high: Float, val veryLow: Float, val veryHigh: Float)

    /**
     * The limits as the user set them, four native calls: read once per snapshot, off the main thread; the renderer
     * keeps them with the snapshot for the dots' colours (critic 7, N7).
     */
    fun limits(isMmol: Boolean) = Limits(
        FaceValues.threshold(guard { Natives.targetlow() }, GlucoseRangeColors.defaultLow(isMmol)),
        FaceValues.threshold(guard { Natives.targethigh() }, GlucoseRangeColors.defaultHigh(isMmol)),
        FaceValues.threshold(guard { Natives.alarmverylow() }, GlucoseRangeColors.defaultVeryLow(isMmol)),
        FaceValues.threshold(guard { Natives.alarmveryhigh() }, GlucoseRangeColors.defaultVeryHigh(isMmol)),
    )

    /**
     * The colour of [value] as the app paints it (part 7a; the trace since part 5b): upstream's traffic light with
     * their value range colours switched on (`GlucoseValueTone.valueColorArgb` with `enabled = true`, the thresholds
     * of `ComplicationRenderer.valueColor`), whatever that setting is - in the range green, out of it yellow, past
     * very low / very high red.
     */
    fun trafficColor(value: Float, isMmol: Boolean): Int = trafficColor(value, isMmol, limits(isMmol))

    /** [trafficColor] by limits already read ([limits]): no native call, for every dot of every frame. */
    fun trafficColor(value: Float, isMmol: Boolean, l: Limits): Int {
        return GlucoseValueTone.valueColorArgb(
            value = value,
            isDark = true,
            isMmol = isMmol,
            targetLow = l.low,
            targetHigh = l.high,
            veryLowThreshold = l.veryLow,
            veryHighThreshold = l.veryHigh,
            fallbackArgb = NEUTRAL,
            enabled = true,
        )
    }

    /** Upstream's neutral value tone on a dark face (their renderer's fallback). */
    private const val NEUTRAL = 0xFFE3E2DE.toInt()

    private inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }
}
