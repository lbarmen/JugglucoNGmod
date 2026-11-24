package tk.glucodata.mod.watch

import com.google.android.gms.wearable.DataEvent
import java.time.Instant
import java.time.ZoneId
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.watch.db.ModWatchSample

/**
 * JugglucoNGmod, watch block (part 5): the rules of taking heart rate and steps batches on the phone, pure functions
 * without the database or Google Play services ([ModWatchStore] does the work). The rules of the format are in
 * [ModHealthBatch.Sample]: a batch taken before is dropped whole (by number and send time, in
 * [tk.glucodata.mod.watch.db.ModWatchDao.store]), a row equal in every field to one taken is a repeat, a late
 * heart rate row ([ModHealthBatch.KIND_HR1], or [ModHealthBatch.KIND_HR5] from an older watch app) is merged by the
 * counts ([ModHealthBatch.mergeHeartRate]).
 */
object ModWatchIntake {

    /** Between signatures in [ModWatchSample.parts] (never inside one: extras of the watch carry `;` and `=`). */
    const val PART_SEPARATOR = "|"

    /** What one incoming row does to the table. */
    sealed interface Action {
        data class Insert(val row: ModWatchSample) : Action
        data class Replace(val row: ModWatchSample) : Action
        data object Drop : Action
    }

    /** One row's identity in every field but kind and time (those are the table's key): value and extra. */
    fun signature(sample: ModHealthBatch.Sample): String =
        sample.value.toString() + "/" + sample.extra.replace(PART_SEPARATOR, " ")

    private fun partsOf(row: ModWatchSample): List<String> =
        if (row.parts.isEmpty()) listOf(signature(row.sample())) else row.parts.split(PART_SEPARATOR)

    /**
     * The row [incoming] of batch [seq] against the [existing] row of the same kind and time: a new time is inserted;
     * a row equal to one already in it (the existing row itself or any row merged into it) is a repeat and dropped;
     * a heart rate row ([ModHealthBatch.HR_KINDS]) with other numbers is merged into the existing one; any other kind
     * keeps the first row of a time.
     */
    fun decide(existing: ModWatchSample?, incoming: ModHealthBatch.Sample, seq: Long, receivedAt: Long): Action {
        val signature = signature(incoming)
        val heartRate = ModHealthBatch.isHeartRate(incoming.kind)
        if (existing == null) {
            return Action.Insert(
                ModWatchSample(
                    kind = incoming.kind, at = incoming.timeMs, value = incoming.value.toDouble(), extra = incoming.extra,
                    batchSeq = seq, receivedAt = receivedAt,
                    parts = if (heartRate) signature else "",
                ),
            )
        }
        val parts = partsOf(existing)
        if (signature in parts) return Action.Drop
        if (!heartRate) return Action.Drop
        val merged = ModHealthBatch.mergeHeartRate(existing.sample(), incoming) ?: return Action.Drop
        return Action.Replace(
            existing.copy(
                value = merged.value.toDouble(), extra = merged.extra, batchSeq = seq, receivedAt = receivedAt,
                parts = (parts + signature).joinToString(PART_SEPARATOR),
            ),
        )
    }

    // ------------------------------------------------------------------ Data Layer items

    /**
     * A data event of the listener worth taking: a changed item at a batch path. Deleted items are skipped - they
     * come after our own delete of a stored batch.
     */
    fun wanted(type: Int, path: String?): Boolean =
        type == DataEvent.TYPE_CHANGED && ModHealthBatch.seqFromPath(path) != null

    /** What happens to the Data Layer item once read. */
    enum class Outcome {
        /** Stored (or a repeat, or junk): delete the item. */
        DELETE,

        /** Leave it: a newer watch app (a newer phone app will take it), or the database failed (next start). */
        KEEP,
    }

    /**
     * The item of [decoded] after the phone tried to [store] it: [store] returns `false` when the database is not
     * there or failed. A batch of a newer format is kept for a newer phone app; a broken one is deleted (it will
     * never read).
     */
    fun outcome(decoded: ModHealthBatch.Decoded, store: (ModHealthBatch) -> Boolean): Outcome = when (decoded) {
        is ModHealthBatch.Decoded.Ok -> if (store(decoded.batch)) Outcome.DELETE else Outcome.KEEP
        is ModHealthBatch.Decoded.Newer -> Outcome.KEEP
        is ModHealthBatch.Decoded.Broken -> Outcome.DELETE
    }

    /**
     * The paths of the items of a newer watch app left in place, after the item at [path] was read: added when it is
     * [newer] (once, however often a drain sees it), removed when it was stored or deleted (critic 5, N-5-2).
     */
    fun newerPaths(paths: Set<String>, path: String, newer: Boolean): Set<String> =
        if (newer) paths + path else paths - path

    // ------------------------------------------------------------------ the check screen

    /** The phone's day around [nowMs] in [zone]: its start and the next day's start (ms of the epoch). */
    fun dayBounds(nowMs: Long, zone: ZoneId): Pair<Long, Long> {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** What the check screen shows; `null` fields - no data of that kind. */
    data class Summary(
        /** The newest heart rate mean (1 minute; 5 minutes from an older watch app), beats per minute, and its window start. */
        val heartRate: Int?,
        val heartRateAt: Long?,
        /** Today's steps: the last day total of today. */
        val stepsToday: Int?,
        val watchBattery: Int?,
        val watchCharging: Boolean,
        /** When the last batch was stored. */
        val lastBatchAt: Long?,
        /** Watch time of [watchBattery] (part 5b: the screen shows it, old data must not look fresh). */
        val watchBatteryAt: Long? = null,
    ) {
        val empty: Boolean
            get() = heartRate == null && stepsToday == null && watchBattery == null && lastBatchAt == null
    }

    /** The unit of an age on the check screen ([ago]). */
    enum class AgoUnit { MINUTES, HOURS, DAYS }

    /**
     * An age of [ms] for the screen (critic 5, N-5-5): whole minutes under an hour, whole hours under two days, then
     * whole days - never "4320 min". A negative age (clocks apart) is 0 minutes.
     */
    fun ago(ms: Long): Pair<AgoUnit, Long> {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 60 -> AgoUnit.MINUTES to minutes
            minutes < 48 * 60 -> AgoUnit.HOURS to minutes / 60
            else -> AgoUnit.DAYS to minutes / (24 * 60)
        }
    }

    /**
     * The newest heart rate row of the two kinds ([ModHealthBatch.KIND_HR1] of this watch app, [ModHealthBatch.KIND_HR5]
     * of an older one) by window start; on the same start the 1-minute row (the newer app's).
     */
    fun newestHeartRate(latestHr1: ModWatchSample?, latestHr5: ModWatchSample?): ModWatchSample? = when {
        latestHr1 == null -> latestHr5
        latestHr5 == null -> latestHr1
        latestHr5.at > latestHr1.at -> latestHr5
        else -> latestHr1
    }

    /**
     * The check screen from the newest rows: [latestHeartRate] (of either heart rate kind, [newestHeartRate]) and
     * [latestBattery] whenever they were, [latestSteps] - the
     * newest day total with its time inside today ([dayBounds]). The day total is already the sum since the watch's
     * day start, so it is shown as it is (the differences of totals are for later graphs). A total whose watch day
     * started a whole day before today still counts yesterday (the watch has not passed its midnight yet) and is not
     * today's - the shared rule [ModHealthBatch.countsDayOf], which also holds across a time zone change.
     */
    fun summary(
        latestHeartRate: ModWatchSample?,
        latestSteps: ModWatchSample?,
        latestBattery: ModWatchSample?,
        lastBatchAt: Long?,
        nowMs: Long,
        zone: ZoneId,
    ): Summary {
        val (dayStart, dayEnd) = dayBounds(nowMs, zone)
        val steps = latestSteps?.takeIf { it.kind == ModHealthBatch.KIND_STEPS_DAY && it.at in dayStart until dayEnd }
            ?.takeIf { row -> ModHealthBatch.StepsTotal.of(row.sample())?.let { ModHealthBatch.countsDayOf(it, dayStart) } != false }
        val hr = latestHeartRate?.takeIf { ModHealthBatch.isHeartRate(it.kind) && it.value.isFinite() && it.value > 0.0 }
        val battery = latestBattery?.takeIf { it.kind == ModHealthBatch.KIND_BATTERY && it.value in 0.0..100.0 }
        return Summary(
            heartRate = hr?.value?.let { Math.round(it).toInt() },
            heartRateAt = hr?.at,
            stepsToday = steps?.value?.takeIf { it.isFinite() && it >= 0.0 }?.let { Math.round(it).toInt() },
            watchBattery = battery?.value?.let { Math.round(it).toInt() },
            watchCharging = battery?.extra == ModHealthBatch.CHARGING,
            lastBatchAt = lastBatchAt,
            watchBatteryAt = battery?.at,
        )
    }
}
