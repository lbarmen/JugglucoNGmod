package tk.glucodata.mod.watch

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * JugglucoNGmod, watch block: our Data Layer messages on the watch (filter `/mod/` in
 * Common/src/wearMod/AndroidManifest.xml; upstream's MessageReceiver never sees these paths). Runs on a background
 * thread. A path we do not know yet is ignored: later parts add theirs here.
 *
 * `/mod/tick` (part 2): the phone battery goes to [PhoneBatteryStore] with the watch's time of receipt, the pump's
 * active insulin to [PumpIobStore] (watch face, 20.11; a tick without it removes the kept one; neither from a tick
 * older than one already taken, [TickOrder]), and the tick - not one sent for the active insulin alone -
 * wakes the heart rate and steps hand-over ([HealthTick], filled by part 4) and the history hole check
 * ([HistoryGapFill]).
 *
 * `/mod/alerts` (parts 7b, 7d): the phone's alert settings, written into upstream's alert preferences and native
 * thresholds of the watch unless overtaken ([WatchAlertStore]) - their Notify and AlertRepository read them at the
 * next alarm.
 */
class ModWearListener : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            handle(applicationContext, messageEvent.path, messageEvent.data)
        } catch (t: Throwable) {
            ModWatchLog.w("watch listener failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        /** One message received at [nowMs] (watch time); returns true when the path was ours and its message valid. */
        fun handle(context: Context, path: String?, data: ByteArray?, nowMs: Long = System.currentTimeMillis()): Boolean {
            when (path) {
                ModWatchMessages.PATH_TICK -> {
                    val tick = ModWatchMessages.decodeTick(data)
                    if (tick == null) {
                        ModWatchLog.w("watch listener: bad $path message")
                        return false
                    }
                    // critic 9, L3: a tick older than one already taken (two sending threads on the phone) leaves
                    // the battery and the active insulin as they are
                    val inOrder = try {
                        TickOrder.accept(context, tick.sentAtMs)
                    } catch (_: Throwable) {
                        true
                    }
                    if (inOrder) {
                        PhoneBatteryStore.save(context, tick, nowMs)
                        // the pump's active insulin for the face; a tick without it removes the kept one
                        try {
                            PumpIobStore.save(context, tick, nowMs)
                        } catch (t: Throwable) {
                            ModWatchLog.w("pump iob not kept: ${t.javaClass.simpleName}")
                        }
                    } else {
                        ModWatchLog.i("watch listener: tick out of order")
                    }
                    // critic 9, M1: the active insulin's own tick does not start the hand-over and the hole check
                    val cycle = tick.reason != ModWatchMessages.REASON_IOB
                    if (cycle) HealthTick.onTick(context, nowMs)
                    if (cycle) HistoryGapFill.onTick(context, nowMs)
                    NoOngoingActivity.claim()
                    return true
                }
                ModWatchMessages.PATH_ALERTS -> return WatchAlertStore.onMessage(context, data)
                ModWatchMessages.PATH_SNOOZE_DONE -> {
                    val done = ModWatchMessages.decodeSnoozeDone(data)
                    if (done == null) {
                        ModWatchLog.w("watch listener: bad $path message")
                        return false
                    }
                    SnoozeButton.onPhoneAnswer(context, done)
                    return true
                }
                else -> return false
            }
        }
    }
}
