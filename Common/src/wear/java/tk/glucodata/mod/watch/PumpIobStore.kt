package tk.glucodata.mod.watch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JugglucoNGmod, watch block (watch face, 20.11 decision "1a with fixes"): the pump's active insulin from the last
 * `/mod/tick` ([ModWatchMessages.PumpIob]), kept in our preferences file ([PhoneBatteryStore.PREFS]) as the number and
 * its own time on the watch's clock (receipt time less the age the phone sent), so the face counts its age on and on
 * between ticks. A tick without the field (the phone has none, or an older phone) removes it: the face then shows two
 * rows. The face's freshness rules (15 / 30 minutes) are in `FaceValues`; this store keeps a number of any age.
 *
 * A changed number (another value, its time moved by more than [SAME_TIME_MS], or gone) bumps [revision]: the face
 * reads it at once, as a new reading. The log has the fact and the age, never the dose.
 */
object PumpIobStore {

    private const val KEY_TENTHS = "pump_iob_tenths"
    private const val KEY_AT = "pump_iob_at"

    /** Times this close count as the same number's (the age is sent in whole seconds, the delivery takes some). */
    const val SAME_TIME_MS = 60_000L

    /** [tenths] of a unit, [valueAtMs] - the number's own time on the watch's clock. */
    data class PumpIob(val tenths: Int, val valueAtMs: Long)

    private val _revision = MutableStateFlow(0L)

    /** Bumped when the kept number changed; the face collects it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Keeps the active insulin of [tick] received at [receivedAtMs] (watch time), or removes it when there is none. */
    fun save(context: Context, tick: ModWatchMessages.Tick, receivedAtMs: Long) {
        val next = fromTick(tick, receivedAtMs)
        val old = read(context)
        val edit = PhoneBatteryStore.prefs(context).edit()
        if (next == null) edit.remove(KEY_TENTHS).remove(KEY_AT)
        else edit.putInt(KEY_TENTHS, next.tenths).putLong(KEY_AT, next.valueAtMs)
        edit.apply()
        if (changed(old, next)) {
            ModWatchLog.i(if (next == null) "pump iob: none" else "pump iob: received age=${tick.iob?.ageSec} s")
            _revision.value = _revision.value + 1L
        }
    }

    /** The kept number, any age; `null` - none. */
    fun read(context: Context): PumpIob? {
        val prefs = PhoneBatteryStore.prefs(context)
        if (!prefs.contains(KEY_AT) || !prefs.contains(KEY_TENTHS)) return null
        val tenths = prefs.getInt(KEY_TENTHS, -1)
        if (tenths !in 0..ModWatchMessages.IOB_MAX_TENTHS) return null
        return PumpIob(tenths, prefs.getLong(KEY_AT, 0L))
    }

    /** The number of [tick] on the watch's clock: its time is [receivedAtMs] less the age the phone sent. */
    fun fromTick(tick: ModWatchMessages.Tick, receivedAtMs: Long): PumpIob? =
        tick.iob?.let { PumpIob(it.tenths, receivedAtMs - it.ageSec * 1000L) }

    /** [next] differs from [old] for the face: there or not, another value, or a time moved by more than [SAME_TIME_MS]. */
    fun changed(old: PumpIob?, next: PumpIob?): Boolean {
        if (old == null || next == null) return old != next
        return old.tenths != next.tenths || Math.abs(old.valueAtMs - next.valueAtMs) > SAME_TIME_MS
    }
}

/**
 * JugglucoNGmod, watch block (critic 9, L3): `/mod/tick` in the phone's order. The phone sends ticks from two threads
 * (the 5-minute cycle and the extra ticks), so an older one can arrive after a newer one; it must not put back the
 * older battery or active insulin. A tick is taken when its phone time ([ModWatchMessages.Tick.sentAtMs]) is not older
 * than the last taken - or older by more than [CLOCK_JUMP_MS]: the phone's clock was set back, and refusing would
 * leave the face without new ticks until the clock caught up.
 */
object TickOrder {

    private const val KEY_SENT_AT = "tick_sent_at"

    /** Older than the last taken by more than this: the phone's clock moved back, the tick is taken. */
    const val CLOCK_JUMP_MS = 10 * 60_000L

    /** Whether the tick sent at [sentAtMs] (phone time) is taken; a taken one becomes the last. */
    fun accept(context: Context, sentAtMs: Long): Boolean {
        val prefs = PhoneBatteryStore.prefs(context)
        val last = if (prefs.contains(KEY_SENT_AT)) prefs.getLong(KEY_SENT_AT, 0L) else null
        if (!inOrder(last, sentAtMs)) return false
        prefs.edit().putLong(KEY_SENT_AT, sentAtMs).apply()
        return true
    }

    /** The rule of [accept]: [lastMs] - the last taken tick's phone time, `null` - none yet. */
    fun inOrder(lastMs: Long?, sentAtMs: Long): Boolean =
        lastMs == null || sentAtMs >= lastMs || lastMs - sentAtMs > CLOCK_JUMP_MS
}
