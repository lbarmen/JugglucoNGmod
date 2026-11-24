package tk.glucodata.mod.health

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4): the queue to the phone. Each batch is a Data Layer item of its own,
 * `/mod/health/batch/<seq>` ([ModHealthBatch.path]); when the phone is not near, the Data Layer keeps it and delivers
 * it on the next connection. The phone deletes the item once stored (part 5). The batch number grows and survives a
 * restart of the process ([HealthState.nextSeq]).
 *
 * Not urgent (owner's decision 20.11): the system batches the items of routine sends (deliveries, ticks) and wakes
 * the phone less; heart rate, steps and sleep are history, a delay of minutes costs nothing. Only the "Send now"
 * button ([HealthHandOver.sendNow]) asks for urgent - the owner waits for it on the screen.
 */
object HealthSender {

    /** Puts one batch into the Data Layer and waits for the local accept; throws when it was not accepted. */
    fun interface Sink {
        fun put(batch: ModHealthBatch)
    }

    /** [samples] rows in [items] batches from [files] outbox files went; [failed] - a put failed (the rest waits). */
    data class Result(val samples: Int, val items: Int, val files: Int, val failed: Boolean)

    private val sendLock = Any()

    /**
     * The real sink: DataClient.putDataItem, waiting up to [HealthConfig.PUT_TIMEOUT_MS] for the local accept; [urgent]
     * only for the "Send now" button. Not on the main thread.
     */
    fun dataClient(context: Context, urgent: Boolean = false): Sink = Sink { batch ->
        val request = PutDataMapRequest.create(batch.path)
        ModHealthDataMap.write(batch, request.dataMap)
        if (urgent) request.setUrgent()
        Tasks.await(
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest()),
            HealthConfig.PUT_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * After a delivery: send unless a send or attempt was less than [HealthConfig.SEND_AFTER_DATA_GAP_MS] ago. The
     * attempt counts too: a failing link is not hammered on every delivery, and a tick's hand-over (which marks its
     * attempt before its flush) keeps the delivery answering that flush from sending on its own.
     */
    fun sendIfDue(core: HealthCore, source: HealthSource?, sink: Sink, nowMs: Long): Result? {
        val state = core.state
        if (!dueAfterData(maxOf(state.lastSentAt, state.lastAttemptAt), nowMs)) return null
        return send(core, source, sink, nowMs)
    }

    /**
     * A send went well less than [HealthConfig.SEND_AFTER_DATA_GAP_MS] before [nowMs] and nothing waits in the queue
     * since (a failed send leaves its file, so a retry is never skipped): a tick has nothing to add (critic 7k, N3).
     */
    fun sentJustNow(core: HealthCore, nowMs: Long): Boolean {
        val last = core.state.lastSentAt
        return last > 0 && nowMs - last in 0 until HealthConfig.SEND_AFTER_DATA_GAP_MS && core.buffer.count() == 0
    }

    /** A delivery at [nowMs] sends: nothing went yet, the last send or attempt [lastMs] is a minute or more ago, or the clock went back. */
    fun dueAfterData(lastMs: Long, nowMs: Long): Boolean =
        lastMs <= 0 || nowMs - lastMs !in 0 until HealthConfig.SEND_AFTER_DATA_GAP_MS

    /**
     * Sends all that waits: the service rows due ([HealthStatus]), then every outbox file, oldest first, cut into
     * batches of at most [maxBytes]. A file is deleted when all its batches were accepted; on a failure the send
     * stops and the rest goes next time. Files are started only within [budgetMs] (clock of [core]).
     */
    fun send(
        core: HealthCore,
        source: HealthSource?,
        sink: Sink,
        nowMs: Long,
        forceStatus: Boolean = false,
        maxBytes: Int = ModHealthBatch.MAX_BYTES,
        budgetMs: Long = HealthConfig.SEND_BUDGET_MS,
    ): Result = synchronized(sendLock) {
        val state = core.state
        val buffer = core.buffer
        state.lastAttemptAt = nowMs
        buffer.append(HealthStatus.samples(core, source, nowMs, forceStatus))
        val dropped = buffer.trim()
        if (dropped > 0) ModWatchLog.w("health queue trimmed files=$dropped")
        val files = buffer.takeOutbox(nowMs)
        if (files.isEmpty()) return@synchronized Result(0, 0, 0, false)
        val started = core.clock()
        var samples = 0
        var items = 0
        var doneFiles = 0
        try {
            for (file in files) {
                if (core.clock() - started > budgetMs) break
                for (chunk in ModHealthBatch.chunks(buffer.read(file).filter { it.kind in ModHealthBatch.KINDS }, maxBytes)) {
                    sink.put(ModHealthBatch.forSending(state.nextSeq(), nowMs, chunk))
                    items++
                    samples += chunk.size
                }
                buffer.done(file)
                doneFiles++
            }
            state.lastSentAt = nowMs
            state.lastError = ""
            ModWatchLog.i("health sent rows=$samples items=$items files=$doneFiles of ${files.size}")
            Result(samples, items, doneFiles, false)
        } catch (e: Exception) {
            state.lastError = e.javaClass.simpleName
            ModWatchLog.w("health send failed: ${e.javaClass.simpleName} items=$items")
            Result(samples, items, doneFiles, true)
        }
    }
}
