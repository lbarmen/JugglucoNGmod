package tk.glucodata.mod.watch

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tk.glucodata.WearMessagePath

/**
 * JugglucoNGmod, watch block: our messages from the watch to the phone. The phone is every reachable node with the
 * phone app's capability ([ModWatchMessages.CAPABILITY_PHONE]); none - nothing is sent, and the caller hears it.
 *
 * Off the main thread, on one short-lived worker: the button activity is already gone when it runs, and the process
 * may stop before it ends - a repeated press is harmless, so no retry queue.
 */
object WatchPhoneLink {

    private const val WAIT_SECONDS = 10L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ModWatchLink").apply { isDaemon = true } }

    /**
     * Sends `/mod/snooze` with [pressedAtMs] to the phone; returns at once. [onNoPhone] runs on the worker when no
     * phone got it: no reachable node, every send failed, or the lookup itself failed or timed out.
     */
    fun sendSnooze(context: Context, pressedAtMs: Long, onNoPhone: () -> Unit = {}) {
        val app = context.applicationContext ?: context
        val data = ModWatchMessages.encodeSnooze(ModWatchMessages.SnoozeRequest(pressedAtMs))
        worker.execute {
            if (!send(app, ModWatchMessages.PATH_SNOOZE, data)) {
                try {
                    onNoPhone()
                } catch (t: Throwable) {
                    ModWatchLog.w("no phone callback failed: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Sends upstream's history request [data] (`/sync2/req`, built by [HistoryGapFill.encodeRequest]) to the phone
     * and waits for it on the caller's thread - never the main one. Returns true when a phone took it.
     */
    fun sendSyncRequest(context: Context, data: ByteArray): Boolean =
        send(context.applicationContext ?: context, WearMessagePath.SYNC2_REQ.wire, data)

    /** Returns true when at least one phone node took the message. */
    private fun send(context: Context, path: String, data: ByteArray): Boolean {
        try {
            val info = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(ModWatchMessages.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
                WAIT_SECONDS, TimeUnit.SECONDS,
            )
            val nodes = info.nodes
            if (nodes.isEmpty()) {
                ModWatchLog.i("send $path: no phone")
                return false
            }
            val client = Wearable.getMessageClient(context)
            var sent = 0
            for (node in nodes) {
                try {
                    Tasks.await(client.sendMessage(node.id, path, data), WAIT_SECONDS, TimeUnit.SECONDS)
                    sent++
                } catch (t: Throwable) {
                    ModWatchLog.w("send $path: node failed: ${t.javaClass.simpleName}")
                }
            }
            ModWatchLog.i("send $path: $sent of ${nodes.size}")
            return sent > 0
        } catch (t: Throwable) {
            ModWatchLog.w("send $path failed: ${t.javaClass.simpleName}")
            return false
        }
    }
}
