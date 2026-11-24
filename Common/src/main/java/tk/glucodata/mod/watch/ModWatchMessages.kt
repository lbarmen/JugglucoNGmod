package tk.glucodata.mod.watch

import java.nio.ByteBuffer
import tk.glucodata.alerts.AlertType

/**
 * JugglucoNGmod, watch block: the Data Layer contract between our phone and watch code - paths, capabilities and the
 * byte format of our messages. One class for both sides (src/main), tested once for both test tasks.
 *
 * Paths start with [PATH_PREFIX]: upstream's MessageReceiver filters list their own prefixes only, so it never gets
 * ours; our listeners (watch: ModWearListener, phone: ModPhoneListener) filter on `/mod/`.
 *
 * Every message is one version byte ([VERSION]) and then the fields of that path, big-endian. A reader takes the
 * fields it knows and ignores a longer tail, so a later part can append fields without breaking an older peer; a
 * message that is empty, too short, of version 0 or of a newer version is `null` - never an exception.
 */
object ModWatchMessages {

    const val VERSION: Int = 1

    const val PATH_PREFIX = "/mod/"
    /** Watch -> phone: the snooze button was pressed. Body: press time (ms, long). */
    const val PATH_SNOOZE = "/mod/snooze"
    /**
     * Phone -> watch: answer to [PATH_SNOOZE]. Body: snoozed kind (int: an AlertType id, [NO_ALERT] = none, [LATE] =
     * the phone did not get to it in time) and phone time (ms, long).
     */
    const val PATH_SNOOZE_DONE = "/mod/snooze/done"
    /**
     * Phone -> watch, every 5 minutes (and on a battery step, at start, when the watch appears, when the pump's active
     * insulin changed): wake-up and phone battery. Body: battery percent (byte, 0-100 or [BATTERY_UNKNOWN]), charging
     * (byte, 0/1), phone time (ms, long), reason (byte, [REASON_CYCLE] ...; a reason this code does not know is kept as
     * it is); then, only when the phone has one, the pump's active insulin: [IOB_FIELD] (byte), tenths of a unit
     * (int), its age in seconds at sending (int). An older watch reads the first fields and ignores the rest; a tick
     * without the active insulin (an older phone too) means "none".
     */
    const val PATH_TICK = "/mod/tick"
    /**
     * Phone -> watch, with a tick when changed (or an hour old, or the watch new) and a few seconds after an alert
     * setting or on/off changed on the phone (parts 7b, 7d): per standard alert duration, sound, vibration, volume,
     * sound delay, repeat, default snooze, delivery, active hours and threshold, and the send time. Body:
     * [ModAlertSettings].
     */
    const val PATH_ALERTS = "/mod/alerts"
    /** Watch -> phone (DataClient): heart rate and steps batches, `/mod/health/batch/<number>` (part 4). */
    const val PATH_HEALTH_BATCH_PREFIX = "/mod/health/batch/"

    /** Capability the phone app declares (upstream's mobile/res/values/wear.xml); the watch looks for it. */
    const val CAPABILITY_PHONE = "juggluco"
    /** Capability the watch app declares (upstream's wear/res/values/wear.xml); the phone looks for it. */
    const val CAPABILITY_WATCH = "jugglucowatch"

    /** Kind in a [SnoozeDone] when no standard alert was going on. */
    const val NO_ALERT = -1
    /**
     * Kind in a [SnoozeDone] when the phone's main thread did not run the snooze in time (nothing was snoozed, and
     * nothing will be). A new value of the same field, so still version 1: a reader without it drops the message as
     * broken (below [NO_ALERT]) and stays silent, which is also the right answer to "late".
     */
    const val LATE = -2

    data class SnoozeRequest(val pressedAtMs: Long)

    /**
     * [snoozedKind] - the alert kind that was snoozed (AlertType id), `null` when none was snoozed; [late] - the phone
     * did not get to the press in time (then [snoozedKind] is `null`).
     */
    data class SnoozeDone(val snoozedKind: Int?, val atMs: Long, val late: Boolean = false)

    fun encodeSnooze(request: SnoozeRequest): ByteArray =
        allocate(Long.SIZE_BYTES).putLong(request.pressedAtMs).array()

    fun decodeSnooze(data: ByteArray?): SnoozeRequest? {
        val body = open(data, Long.SIZE_BYTES) ?: return null
        val at = body.long
        return if (at < 0) null else SnoozeRequest(at)
    }

    fun encodeSnoozeDone(done: SnoozeDone): ByteArray =
        allocate(Int.SIZE_BYTES + Long.SIZE_BYTES)
            .putInt(if (done.late) LATE else done.snoozedKind ?: NO_ALERT)
            .putLong(done.atMs)
            .array()

    fun decodeSnoozeDone(data: ByteArray?): SnoozeDone? {
        val body = open(data, Int.SIZE_BYTES + Long.SIZE_BYTES) ?: return null
        val kind = body.int
        val at = body.long
        if (at < 0) return null
        return when {
            kind == NO_ALERT -> SnoozeDone(null, at)
            kind == LATE -> SnoozeDone(null, at, late = true)
            isStandardKind(kind) -> SnoozeDone(kind, at)
            else -> null
        }
    }

    /** Battery percent in a [Tick] when the phone could not read it. */
    const val BATTERY_UNKNOWN = -1

    /** [Tick] reasons: the 5-minute cycle, a battery step, the phone app's start, our watch appeared. */
    const val REASON_CYCLE = 1
    const val REASON_BATTERY = 2
    const val REASON_START = 3
    const val REASON_WATCH = 4
    /**
     * The pump's active insulin changed on the phone (other tenths, or it came or went; at most once a minute). The
     * watch keeps the battery and the active insulin of such a tick, but it does not start the heart rate hand-over
     * or the history hole check (critic 9, M1).
     */
    const val REASON_IOB = 5

    private const val TICK_BYTES = 1 + 1 + Long.SIZE_BYTES + 1

    /** The mark before the active insulin in a tick ('I'): a tail without it is not read as one. */
    const val IOB_FIELD: Int = 0x49
    private const val IOB_BYTES = 1 + Int.SIZE_BYTES + Int.SIZE_BYTES

    /** The most tenths of a unit a tick carries (the face writes "99+" from 100 units). */
    const val IOB_MAX_TENTHS = 99_999

    /** The oldest active insulin a tick carries, seconds (a week: the phone keeps one much shorter). */
    const val IOB_MAX_AGE_SEC = 7 * 24 * 3600

    /**
     * The pump's active insulin as the phone has it: [tenths] of a unit (0 - a known zero) and its [ageSec] at
     * sending, counted on the phone's clock (the watch adds the time since receipt on its own clock).
     */
    data class PumpIob(val tenths: Int, val ageSec: Int)

    /**
     * [batteryPercent] - phone battery 0-100, `null` when unknown; [charging] - the phone is charging (or full on the
     * charger); [sentAtMs] - phone time; [reason] - one of the REASON_ codes (an unknown one is passed through);
     * [iob] - the pump's active insulin, `null` - the phone has none.
     */
    data class Tick(
        val batteryPercent: Int?,
        val charging: Boolean,
        val sentAtMs: Long,
        val reason: Int,
        val iob: PumpIob? = null,
    )

    fun encodeTick(tick: Tick): ByteArray {
        val percent = tick.batteryPercent?.takeIf { it in 0..100 } ?: BATTERY_UNKNOWN
        val iob = tick.iob
        val buffer = allocate(TICK_BYTES + if (iob != null) IOB_BYTES else 0)
            .put(percent.toByte())
            .put((if (tick.charging) 1 else 0).toByte())
            .putLong(tick.sentAtMs)
            .put(tick.reason.coerceIn(0, 0xff).toByte())
        if (iob != null) {
            buffer.put(IOB_FIELD.toByte())
                .putInt(iob.tenths.coerceIn(0, IOB_MAX_TENTHS))
                .putInt(iob.ageSec.coerceIn(0, IOB_MAX_AGE_SEC))
        }
        return buffer.array()
    }

    fun decodeTick(data: ByteArray?): Tick? {
        val body = open(data, TICK_BYTES) ?: return null
        val percent = body.get().toInt()
        val charging = body.get().toInt()
        val at = body.long
        val reason = body.get().toInt() and 0xff
        if (percent != BATTERY_UNKNOWN && percent !in 0..100) return null
        if (charging != 0 && charging != 1) return null
        if (at < 0) return null
        return Tick(if (percent == BATTERY_UNKNOWN) null else percent, charging == 1, at, reason, decodeIob(body))
    }

    /** The active insulin after the tick's fields: `null` when there is none, it is cut short or out of range. */
    private fun decodeIob(body: ByteBuffer): PumpIob? {
        if (body.remaining() < IOB_BYTES) return null
        if ((body.get().toInt() and 0xff) != IOB_FIELD) return null
        val tenths = body.int
        val age = body.int
        if (tenths !in 0..IOB_MAX_TENTHS || age !in 0..IOB_MAX_AGE_SEC) return null
        return PumpIob(tenths, age)
    }

    /** [kind] is one of the standard alert kinds a snooze can name. */
    fun isStandardKind(kind: Int): Boolean = AlertType.settingsEntries.any { it.id == kind }

    /** A message buffer: the version byte written, room for [bodyBytes] of fields. */
    fun allocate(bodyBytes: Int): ByteBuffer =
        ByteBuffer.allocate(1 + bodyBytes).put(VERSION.toByte())

    /**
     * The fields of [data], positioned after the version byte, when it holds at least [bodyBytes] of them and a
     * version this code knows; `null` otherwise. Bytes after the known fields are left for the caller to ignore.
     */
    fun open(data: ByteArray?, bodyBytes: Int): ByteBuffer? {
        if (data == null || data.size < 1 + bodyBytes) return null
        val version = data[0].toInt() and 0xff
        if (version < 1 || version > VERSION) return null
        return ByteBuffer.wrap(data, 1, data.size - 1)
    }
}
