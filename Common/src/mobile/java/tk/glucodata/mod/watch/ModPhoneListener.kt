package tk.glucodata.mod.watch

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit
import tk.glucodata.MessageSender

/**
 * JugglucoNGmod, watch block: our Data Layer messages on the phone (filter `/mod/` in
 * Common/src/mobileMod/AndroidManifest.xml; upstream's MessageReceiver never sees these paths). Runs on a background
 * thread; upstream's alert calls go to the main thread ([OngoingAlert.snoozeOngoingOnMain]).
 *
 * `/mod/snooze` (the watch's button): the standard alert going on here is snoozed as upstream's "Snooze" does, and
 * `/mod/snooze/done` goes back to the same watch - "snoozed kind N", "no alert" or "late". A repeated message is
 * harmless: upstream's action times drop a second acknowledgement. Ignored, like upstream's own receiver does, while the user
 * has the watch companion switched off on the phone. Paths of later parts are ignored until they are added here.
 *
 * CAPABILITY_CHANGED of the watch app's capability (part 2, filter in the same manifest): our watch app became
 * reachable - an extra `/mod/tick` and the tick cycle back to every 5 minutes ([WatchTicker.onWatchAppeared]), or a
 * freshly installed watch would wait up to an hour for its first tick.
 */
class ModPhoneListener : WearableListenerService() {

    /**
     * DATA_CHANGED of `/mod/health/batch/<seq>` (part 5, filter in the same manifest): heart rate and steps batches.
     * Only copied here (`freeze()` - the buffer closes when this returns) and handed to our own thread
     * ([ModWatchStore]); deleted items (after our own delete) are skipped. Taken even with upstream's companion switch
     * off - our own channel, see [ModWatchStore].
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            val items = dataEvents.filter { ModWatchIntake.wanted(it.type, it.dataItem.uri.path) }
                .map { it.dataItem.freeze() }
            ModWatchStore.receiveAsync(applicationContext, items)
        } catch (t: Throwable) {
            ModWatchLog.w("phone listener: data failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        try {
            if (capabilityInfo.name != ModWatchMessages.CAPABILITY_WATCH || capabilityInfo.nodes.isEmpty()) return
            WatchTicker.onWatchAppeared(applicationContext) // on our own thread: /mod/snooze is not held up
        } catch (t: Throwable) {
            ModWatchLog.w("phone listener: capability failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            if (!messageEvent.path.startsWith(ModWatchMessages.PATH_PREFIX)) return
            if (!MessageSender.outgoingAllowed()) {
                ModWatchLog.i("phone listener: watch companion off, message ignored")
                return
            }
            when (messageEvent.path) {
                ModWatchMessages.PATH_SNOOZE -> {
                    val reply = answerSnooze(messageEvent.data) { OngoingAlert.snoozeOngoingOnMain() } ?: return
                    Tasks.await(
                        Wearable.getMessageClient(applicationContext)
                            .sendMessage(messageEvent.sourceNodeId, ModWatchMessages.PATH_SNOOZE_DONE, reply),
                        SEND_WAIT_SECONDS, TimeUnit.SECONDS,
                    )
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("phone listener failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val SEND_WAIT_SECONDS = 10L

        /**
         * A press older (or, with the two clocks apart, newer) than this is not acted on: a message held back by Play
         * services must not snooze an alert that started after the press.
         */
        const val MAX_PRESS_AGE_MS = 2 * 60_000L

        /**
         * The `/mod/snooze/done` answer to a `/mod/snooze` message; `null` (nothing snoozed, no answer) when the
         * message is not valid or the press is stale. [snooze] snoozes the alert going on and returns its kind, `null`
         * when there is none, [OngoingAlert.LATE] when the main thread did not get to it. The log has no alert kind:
         * with logcat's time it would read as "a low at 03:12".
         */
        fun answerSnooze(
            data: ByteArray?,
            nowMs: Long = System.currentTimeMillis(),
            snooze: () -> Int?,
        ): ByteArray? {
            val request = ModWatchMessages.decodeSnooze(data)
            if (request == null) {
                ModWatchLog.w("phone listener: bad snooze message")
                return null
            }
            if (Math.abs(nowMs - request.pressedAtMs) > MAX_PRESS_AGE_MS) {
                ModWatchLog.w("phone listener: stale snooze message ignored")
                return null
            }
            val done = when (val kind = snooze()) {
                null -> ModWatchMessages.SnoozeDone(null, nowMs)
                OngoingAlert.LATE -> ModWatchMessages.SnoozeDone(null, nowMs, late = true)
                else -> ModWatchMessages.SnoozeDone(kind, nowMs)
            }
            ModWatchLog.i("phone listener: " + when {
                done.late -> "late"
                done.snoozedKind != null -> "snoozed"
                else -> "no alert"
            })
            return ModWatchMessages.encodeSnoozeDone(done)
        }
    }
}
