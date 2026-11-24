package tk.glucodata.mod.watch

import android.content.Context
import android.net.Uri
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthDataMap
import tk.glucodata.mod.watch.db.ModWatchDatabase

/**
 * JugglucoNGmod, watch block (part 5): heart rate and steps batches from the watch into our database on the phone.
 *
 * Everything runs on our own single thread ([executor]): the Data Layer listener only copies the items (`freeze()`)
 * and returns, so `/mod/snooze` of a ringing alert, which comes through the same listener thread, never waits behind
 * a database write (critic of the joints, C4). Each item: read ([ModHealthDataMap.read]), stored whole in one
 * transaction ([tk.glucodata.mod.watch.db.ModWatchDao.store]), then deleted from the Data Layer; a batch of a newer
 * watch app is left for a newer phone app (counted), and when the database does not open nothing is deleted - the
 * start-up [drainAsync] takes what is left, and so does a drain after the next batch that stores (part 5b).
 *
 * Batches are taken even while upstream's watch companion switch is off: this is our own channel from our watch app,
 * not upstream's sending (decision of the dispatcher, joints critic N7) - otherwise they would pile up on the watch.
 * Nothing personal goes to the log: counts and exception classes only.
 */
object ModWatchStore {

    private const val DELETE_WAIT_SECONDS = 10L
    private const val PREFS = "mod_watch_phone"
    /** The paths of the newer items left in place (a set; part 5 kept a count under "newer_batches"). */
    private const val KEY_NEWER = "newer_batch_paths"

    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "mod-watch-store").apply { isDaemon = true } }
    }

    @Volatile private var database: ModWatchDatabase? = null
    private val openLock = Any()

    /** Our database, opened once; `null` when it cannot be opened (logged). Not on the main thread. */
    fun db(context: Context): ModWatchDatabase? {
        database?.let { return it }
        return synchronized(openLock) {
            database ?: try {
                ModWatchDatabase.open(context).also {
                    it.openHelper.writableDatabase // opens now, so a broken file shows here and not in a later call
                    database = it
                }
            } catch (t: Throwable) {
                ModWatchLog.w("watch db not opened: ${t.javaClass.simpleName}")
                null
            }
        }
    }

    /** From the listener: [items] (already frozen) go to our thread; returns at once. */
    fun receiveAsync(context: Context, items: List<DataItem>, runOn: Executor = executor) {
        if (items.isEmpty()) return
        val app = context.applicationContext ?: context
        try {
            runOn.execute { receive(app, items) }
        } catch (t: Throwable) {
            ModWatchLog.w("watch batches not queued: ${t.javaClass.simpleName}")
        }
    }

    /** At start (our starter): the batch items still in the Data Layer, as Glucove's `drain()`. */
    fun drainAsync(context: Context) {
        val app = context.applicationContext ?: context
        try {
            executor.execute { drain(app) }
        } catch (t: Throwable) {
            ModWatchLog.w("watch drain not queued: ${t.javaClass.simpleName}")
        }
    }

    private fun drain(context: Context) {
        try {
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME)
                .path(ModWatchMessages.PATH_HEALTH_BATCH_PREFIX).build()
            val buffer = Tasks.await(
                Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_PREFIX),
                DELETE_WAIT_SECONDS, TimeUnit.SECONDS,
            )
            val items = try {
                buffer.filter { ModHealthBatch.seqFromPath(it.uri.path) != null }.map { it.freeze() }
            } finally {
                buffer.release()
            }
            if (items.isNotEmpty()) {
                ModWatchLog.i("watch drain items=${items.size}")
                receive(context, items, fromDrain = true)
            }
        } catch (t: Throwable) {
            val line = drainFailure(t)
            if (line == null) ModWatchLog.i("watch drain skipped: Wearable API not on this phone") else ModWatchLog.w(line)
        }
    }

    /** Google Play services' status "API not connected": no Wearable API on this phone (as upstream's MessageSender). */
    private const val API_UNAVAILABLE_STATUS = 17

    /**
     * The drain's failure for the log: its class and, under the `ExecutionException` of `Tasks.await`, the class of the
     * cause (with its status code for an `ApiException`) - never values. `null` when it is no error: the Wearable API
     * is not on this phone (an emulator, a phone without Wear OS), so there is nothing to drain.
     */
    internal fun drainFailure(t: Throwable): String? {
        val cause = t.cause
        val api = (t as? ApiException) ?: (cause as? ApiException)
        if (api?.statusCode == API_UNAVAILABLE_STATUS) return null
        val why = cause?.let { " cause=${it.javaClass.simpleName}" + ((it as? ApiException)?.let { e -> " status=${e.statusCode}" } ?: "") }
        return "watch drain failed: ${t.javaClass.simpleName}" + why.orEmpty()
    }

    /**
     * Items left behind in this process for a failed database or write (critic 5, N-5-1): taken again by a drain once
     * a later item stores - not only at the next process start (upstream's process lives for weeks).
     */
    @Volatile private var leftBehind = false

    /** Reads, stores and deletes each item (on our thread); [fromDrain] - never queues another drain itself. */
    private fun receive(context: Context, items: List<DataItem>, fromDrain: Boolean = false) {
        var stored = 0
        var repeats = 0
        var kept = 0
        for (item in items) {
            try {
                val decoded = ModHealthDataMap.read(DataMapItem.fromDataItem(item).dataMap)
                val outcome = ModWatchIntake.outcome(decoded) { batch ->
                    val dao = db(context)?.dao() ?: return@outcome false
                    val result = dao.store(batch, System.currentTimeMillis())
                    if (result.repeat) repeats++ else stored++
                    true
                }
                when {
                    outcome == ModWatchIntake.Outcome.DELETE -> {
                        delete(context, item)
                        noteNewer(context, item.uri.path, newer = false)
                    }
                    decoded is ModHealthBatch.Decoded.Newer -> {
                        kept++
                        noteNewer(context, item.uri.path, newer = true)
                    }
                    else -> {
                        kept++
                        leftBehind = true
                    }
                }
                if (decoded is ModHealthBatch.Decoded.Broken) ModWatchLog.w("watch batch broken: ${decoded.reason}")
            } catch (t: Throwable) {
                kept++
                leftBehind = true
                ModWatchLog.w("watch batch failed: ${t.javaClass.simpleName}")
            }
        }
        ModWatchLog.i("watch batches stored=$stored repeats=$repeats kept=$kept")
        if (!fromDrain && stored + repeats > 0 && leftBehind) {
            leftBehind = false
            drainAsync(context)
        }
    }

    private fun delete(context: Context, item: DataItem) {
        try {
            Tasks.await(Wearable.getDataClient(context).deleteDataItems(item.uri), DELETE_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            // it comes again: the batch is known by its number and send time, the rows by their fields
            ModWatchLog.w("watch batch not deleted: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Keeps the paths of the items of a newer watch app left in place (critic 5, N-5-2): a set, so the same item seen
     * by every drain counts once, and an item stored later (the phone app updated) leaves it.
     */
    private fun noteNewer(context: Context, path: String?, newer: Boolean) {
        if (path == null) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val paths = prefs.getStringSet(KEY_NEWER, emptySet()).orEmpty()
            val next = ModWatchIntake.newerPaths(paths, path, newer)
            if (next != paths) prefs.edit().putStringSet(KEY_NEWER, next).apply()
        } catch (_: Throwable) {
        }
        if (newer) ModWatchLog.w("watch batch of a newer format left in place")
    }

    /** How many batches of a newer watch app are left in place (shown on the check screen). */
    fun newerBatches(context: Context): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_NEWER, emptySet()).orEmpty().size
    } catch (_: Throwable) {
        0
    }

    /** The check screen's data (blocking; not on the main thread); `null` when the database does not open. */
    fun summary(context: Context, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()):
        ModWatchIntake.Summary? {
        val dao = db(context)?.dao() ?: return null
        val (dayStart, dayEnd) = ModWatchIntake.dayBounds(nowMs, zone)
        return ModWatchIntake.summary(
            latestHeartRate = ModWatchIntake.newestHeartRate(dao.latest(ModHealthBatch.KIND_HR1), dao.latest(ModHealthBatch.KIND_HR5)),
            latestSteps = dao.latestBetween(ModHealthBatch.KIND_STEPS_DAY, dayStart, dayEnd),
            latestBattery = dao.latest(ModHealthBatch.KIND_BATTERY),
            lastBatchAt = dao.lastReceivedAt(),
            nowMs = nowMs,
            zone = zone,
        )
    }
}
