package tk.glucodata.mod.watch

import android.content.SharedPreferences
import kotlin.math.roundToInt
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.WearToggleSync
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertSoundVolume
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.alerts.MAX_ALERT_DURATION_SECONDS
import tk.glucodata.alerts.MIN_ALERT_DURATION_SECONDS
import tk.glucodata.alerts.maxSoundDelaySecondsFor

/**
 * JugglucoNGmod, watch block (parts 7b, 7d): each alert on the watch follows the phone's alert settings.
 *
 * The watch raises its own alerts with upstream's Notify and AlertRepository, which read its own `tk.glucodata.alerts`
 * file and its own native settings, and upstream sends the watch only "enabled" (WearToggleSync); the watch has no
 * screen for the rest. So the alarm on the watch ran the 5 s default without a repeat, at the watch's own thresholds,
 * whatever the phone said. Our phone sends [Settings] per standard alert ([ModWatchMessages.PATH_ALERTS]) and the
 * watch writes them the way upstream's own code stores them ([apply]).
 *
 * What travels (checked in upstream's code, 18.11.2026): alarm duration (`_alarmDur`), sound and vibration on/off
 * (`_sound`, `_vibration`), vibration pattern (`_haptic`), sound volume (`_soundVolumePercent`), sound delay
 * (`_soundDelayEnabled`, `_soundDelay`), repeat (`_retryOn`, `_retryInt`, `_retryCnt`), default snooze (`_snooze`) -
 * part 7b; and in part 7d the delivery "notification / alarm / both" (`_delivery`, Notify.getDeliveryMode), the active
 * hours (`_timeRange`, `_startHour`, `_startMin`, `_endHour`, `_endMin`, AlertConfig.isActiveNow) and the threshold
 * ([ThresholdStore]: the native low/high/very/forecast levels through upstream's own Natives.setAlertConfig, the
 * persistent-high level under `_threshold`). Not sent: the own melody (a file on the phone), flash and "override Do
 * not disturb" (the watch has no flash and always overrides), and "enabled" - never written by the watch, it stays
 * upstream's toggle sync (critic finding M1 of part 7b).
 *
 * Without its "enabled" key, upstream's AlertRepository reads none of a prefs-only alert's settings (defaults); the
 * key appears when their toggle sync turns the alert on, and their save then writes the defaults over ours. So the
 * phone sends again a few seconds after an on/off change and the watch writes ours again after their save
 * (WatchAlertStore) - the values Notify reads by key (duration, sound, pattern, volume, delay, delivery) act at once.
 *
 * Thresholds go as mg/L (mmol/L x 180, mg/dL x 10 - upstream's native unit, settings.hpp convfactor), so phone and
 * watch may show different units. Upstream's sync never carries them back: the native levels lie outside the shared
 * range of datbackup.cpp (Tings::alow..ahigh and the profiles are before `update` / after `mealvar`), and
 * netinfo.cpp ontbytesettings takes only the unit.
 *
 * Body after the version byte: entry count (byte), then per entry its length (byte) and [ENTRY_BYTES] of fields: kind,
 * flags (bit 0 sound, 1 vibration, 2 sound delay, 3 repeat, 4 active hours), duration s, pattern code ([HAPTIC_CODES],
 * 0 = unknown), volume % (bytes); sound delay s, repeat interval min, repeat count, snooze min (unsigned shorts) - the
 * [BASE_ENTRY_BYTES] of part 7b; then delivery code ([DELIVERY_CODES], 0 = unknown), start hour, start minute, end
 * hour, end minute (bytes, [TIME_UNSET] = not set, another value out of range = unknown) and threshold mg/L (unsigned
 * short, 0xffff = none). After the entries: [TRAILER_SENT_AT] and the phone time of the send (ms, long), so the watch
 * drops a message overtaken by a newer one. Big-endian. A longer entry keeps its known fields and skips the tail; an
 * entry of 7b's length has its 7d fields unknown; a short or cut entry makes the whole message `null`. An entry of a
 * kind that is not a standard alert is skipped, a field out of its range is dropped (the watch keeps its own value) -
 * never an exception.
 */
object ModAlertSettings {

    /** The fields of one entry part 7b wrote; an entry at least this long is read. */
    const val BASE_ENTRY_BYTES = 5 + 4 * 2

    /** The fields of one entry this version writes and reads. */
    const val ENTRY_BYTES = BASE_ENTRY_BYTES + 1 + 4 + 2

    /** Repeat interval and count, default snooze: what the watch accepts (the phone offers far less). */
    const val MAX_RETRY_INTERVAL_MINUTES = 24 * 60
    const val MAX_RETRY_COUNT = 100
    const val MAX_SNOOZE_MINUTES = 24 * 60

    /**
     * Thresholds the watch accepts, mg/L: 36-360 mg/dL = 2-20 mmol/L, the widest range of upstream's threshold slider
     * (AlertSettingsScreen.getThresholdRange); every per-kind range lies inside.
     */
    const val MIN_THRESHOLD_MG_PER_L = 360
    const val MAX_THRESHOLD_MG_PER_L = 3600

    /** A time field the phone has not set (upstream stores no key then). */
    const val TIME_UNSET = -1

    /** Tag of the send time after the entries. */
    const val TRAILER_SENT_AT = 0x54

    /** The phone's alert preferences file and the watch's (upstream's AlertRepository.PREFS_NAME, Notify.mksound). */
    const val ALERT_PREFS = "tk.glucodata.alerts"

    /** Vibration pattern codes on the wire: index + 1. Names, not enum order, so an upstream reorder keeps them. */
    private val HAPTIC_CODES = listOf("SOFT", "STEADY", "STRONG", "ESCALATING")

    /** Delivery codes on the wire: index + 1, by name as [HAPTIC_CODES]. */
    private val DELIVERY_CODES = listOf("NOTIFICATION_ONLY", "SYSTEM_ALARM", "BOTH")

    private const val FLAG_SOUND = 1
    private const val FLAG_VIBRATION = 2
    private const val FLAG_SOUND_DELAY = 4
    private const val FLAG_RETRY = 8
    private const val FLAG_TIME_RANGE = 16

    private const val TIME_UNSET_BYTE = 0xff
    private const val TIME_UNKNOWN_BYTE = 0xfe
    private const val NO_SHORT = 0xffff

    /** Alerts whose on/off and threshold AlertRepository.loadConfig takes from the native side, the rest from the file. */
    val NATIVE_BACKED: Set<AlertType> = setOf(
        AlertType.LOW, AlertType.HIGH, AlertType.VERY_LOW, AlertType.VERY_HIGH,
        AlertType.PRE_LOW, AlertType.PRE_HIGH, AlertType.LOSS,
    )

    /** Native-backed alerts with a level (signal loss has none: Natives.setAlertConfig ignores it there). */
    private val NATIVE_THRESHOLD = NATIVE_BACKED - AlertType.LOSS

    /**
     * One alert's settings; a `null` field is not known (not sent or out of range) and left as it is. The time fields
     * hold [TIME_UNSET] when the phone has none; [thresholdMgPerL] is mg/L.
     */
    data class Settings(
        val kind: Int,
        val soundEnabled: Boolean,
        val vibrationEnabled: Boolean,
        val soundDelayEnabled: Boolean,
        val retryEnabled: Boolean,
        val alarmDurationSeconds: Int?,
        val hapticProfile: HapticProfile?,
        val soundVolumePercent: Int?,
        val soundDelaySeconds: Int?,
        val retryIntervalMinutes: Int?,
        val retryCount: Int?,
        val defaultSnoozeMinutes: Int?,
        val deliveryMode: AlertDeliveryMode? = null,
        val timeRangeEnabled: Boolean? = null,
        val activeStartHour: Int? = null,
        val activeStartMinute: Int? = null,
        val activeEndHour: Int? = null,
        val activeEndMinute: Int? = null,
        val thresholdMgPerL: Int? = null,
    )

    /**
     * A decoded message: its entries, the phone time of the send (`null` from a phone of part 7b) and the kinds whose
     * threshold came out of range (dropped - the watch keeps its own; for the log, without the value).
     */
    data class Message(val settings: List<Settings>, val sentAtMs: Long?, val outOfRangeKinds: List<Int> = emptyList())

    /** [value] in [mmol] (mmol/L, else mg/dL) as mg/L, rounded. */
    fun toMgPerL(value: Float, mmol: Boolean): Int = (value * if (mmol) 180f else 10f).roundToInt()

    /** [mgPerL] in mmol/L when [mmol], else in mg/dL. */
    fun fromMgPerL(mgPerL: Int, mmol: Boolean): Float = mgPerL / if (mmol) 180f else 10f

    /** The part of [config] that travels; its threshold is in mmol/L when [thresholdMmol], else mg/dL. */
    fun fromConfig(config: AlertConfig, thresholdMmol: Boolean = thresholdIsMmol(config.type)): Settings = Settings(
        kind = config.type.id,
        soundEnabled = config.soundEnabled,
        vibrationEnabled = config.vibrationEnabled,
        soundDelayEnabled = config.soundDelayEnabled,
        retryEnabled = config.retryEnabled,
        alarmDurationSeconds = config.alarmDurationSeconds,
        hapticProfile = config.hapticProfile,
        soundVolumePercent = config.soundVolumePercent,
        soundDelaySeconds = config.soundDelaySeconds,
        retryIntervalMinutes = config.retryIntervalMinutes,
        retryCount = config.retryCount,
        defaultSnoozeMinutes = config.defaultSnoozeMinutes,
        deliveryMode = config.deliveryMode,
        timeRangeEnabled = config.timeRangeEnabled,
        activeStartHour = config.activeStartHour ?: TIME_UNSET,
        activeStartMinute = config.activeStartMinute ?: TIME_UNSET,
        activeEndHour = config.activeEndHour ?: TIME_UNSET,
        activeEndMinute = config.activeEndMinute ?: TIME_UNSET,
        thresholdMgPerL = config.threshold?.takeIf { it.isFinite() }?.let { toMgPerL(it, thresholdMmol) },
    )

    /**
     * The unit upstream keeps [type]'s threshold in on this device: the native unit for a native-backed alert
     * (Natives.alarmlow and friends convert with it), Applic.unit for the file (AlertRepository's defaults).
     */
    fun thresholdIsMmol(type: AlertType): Boolean =
        if (type in NATIVE_BACKED) Natives.getunit() == 1 else Applic.unit == 1

    /** Phone: the settings of every standard alert, as upstream's AlertRepository holds them; one that fails is left out. */
    fun collect(): List<Settings> = AlertType.settingsEntries.mapNotNull { type ->
        try {
            fromConfig(AlertRepository.loadConfig(type))
        } catch (t: Throwable) {
            ModWatchLog.w("alert settings: kind ${type.id} not read: ${t.javaClass.simpleName}")
            null
        }
    }

    fun encode(list: List<Settings>, sentAtMs: Long? = null): ByteArray {
        val entries = list.take(0xff)
        val trailer = if (sentAtMs != null) 1 + Long.SIZE_BYTES else 0
        val buffer = ModWatchMessages.allocate(1 + entries.size * (1 + ENTRY_BYTES) + trailer).put(entries.size.toByte())
        for (s in entries) {
            // a negative count means "no repeat" on the phone (Notify: retriesUsed >= count at once); 0 would be
            // "without limit" on the watch, so it goes as repeat off, the count clamped into range
            val noRepeat = s.retryCount != null && s.retryCount < 0
            var flags = 0
            if (s.soundEnabled) flags = flags or FLAG_SOUND
            if (s.vibrationEnabled) flags = flags or FLAG_VIBRATION
            if (s.soundDelayEnabled) flags = flags or FLAG_SOUND_DELAY
            if (s.retryEnabled && !noRepeat) flags = flags or FLAG_RETRY
            if (s.timeRangeEnabled == true) flags = flags or FLAG_TIME_RANGE
            buffer.put(ENTRY_BYTES.toByte())
                .put(u8(s.kind))
                .put(flags.toByte())
                .put(u8(s.alarmDurationSeconds))
                .put(code(HAPTIC_CODES, s.hapticProfile?.name))
                .put(u8(s.soundVolumePercent))
                .putShort(u16(s.soundDelaySeconds))
                // "constantly" is an interval of 0 or below on the phone; 0 keeps its meaning
                .putShort(u16(s.retryIntervalMinutes?.coerceAtLeast(0)))
                .putShort(u16(s.retryCount?.let { if (noRepeat) 1 else it.coerceAtMost(MAX_RETRY_COUNT) }))
                .putShort(u16(s.defaultSnoozeMinutes))
                .put(code(DELIVERY_CODES, s.deliveryMode?.name))
                .put(timeByte(s.activeStartHour))
                .put(timeByte(s.activeStartMinute))
                .put(timeByte(s.activeEndHour))
                .put(timeByte(s.activeEndMinute))
                .putShort(u16(s.thresholdMgPerL))
        }
        if (sentAtMs != null) buffer.put(TRAILER_SENT_AT.toByte()).putLong(sentAtMs)
        return buffer.array()
    }

    /** The entries of [data]; `null` when the message is empty, cut, of an unknown version or not ours. */
    fun decode(data: ByteArray?): List<Settings>? = decodeMessage(data)?.settings

    /** As [decode], with the send time. */
    fun decodeMessage(data: ByteArray?): Message? {
        val body = ModWatchMessages.open(data, 1) ?: return null
        val count = body.get().toInt() and 0xff
        val result = ArrayList<Settings>(count)
        val outOfRange = ArrayList<Int>()
        repeat(count) {
            if (body.remaining() < 1) return null
            val length = body.get().toInt() and 0xff
            if (length < BASE_ENTRY_BYTES || body.remaining() < length) return null
            val end = body.position() + length
            val kind = body.get().toInt() and 0xff
            val flags = body.get().toInt() and 0xff
            val duration = body.get().toInt() and 0xff
            val haptic = body.get().toInt() and 0xff
            val volume = body.get().toInt() and 0xff
            val delay = body.short.toInt() and 0xffff
            val interval = body.short.toInt() and 0xffff
            val retries = body.short.toInt() and 0xffff
            val snooze = body.short.toInt() and 0xffff
            val full = length >= ENTRY_BYTES
            val delivery = if (full) body.get().toInt() and 0xff else 0
            val startHour = if (full) body.get().toInt() and 0xff else TIME_UNKNOWN_BYTE
            val startMinute = if (full) body.get().toInt() and 0xff else TIME_UNKNOWN_BYTE
            val endHour = if (full) body.get().toInt() and 0xff else TIME_UNKNOWN_BYTE
            val endMinute = if (full) body.get().toInt() and 0xff else TIME_UNKNOWN_BYTE
            val threshold = if (full) body.short.toInt() and 0xffff else NO_SHORT
            body.position(end)
            val type = AlertType.settingsEntries.firstOrNull { it.id == kind } ?: return@repeat
            val thresholdInRange = threshold in MIN_THRESHOLD_MG_PER_L..MAX_THRESHOLD_MG_PER_L
            if (threshold != NO_SHORT && !thresholdInRange) outOfRange += kind
            result += Settings(
                kind = kind,
                soundEnabled = flags and FLAG_SOUND != 0,
                vibrationEnabled = flags and FLAG_VIBRATION != 0,
                soundDelayEnabled = flags and FLAG_SOUND_DELAY != 0,
                retryEnabled = flags and FLAG_RETRY != 0,
                alarmDurationSeconds = duration.takeIf { it in MIN_ALERT_DURATION_SECONDS..MAX_ALERT_DURATION_SECONDS },
                hapticProfile = HAPTIC_CODES.getOrNull(haptic - 1)
                    ?.let { name -> HapticProfile.entries.firstOrNull { it.name == name } },
                soundVolumePercent = volume.takeIf { it in AlertSoundVolume.MIN_PERCENT..AlertSoundVolume.MAX_PERCENT },
                soundDelaySeconds = delay.takeIf { it in 0..maxSoundDelaySecondsFor(type) },
                retryIntervalMinutes = interval.takeIf { it in 0..MAX_RETRY_INTERVAL_MINUTES },
                retryCount = retries.takeIf { it in 0..MAX_RETRY_COUNT },
                defaultSnoozeMinutes = snooze.takeIf { it in 1..MAX_SNOOZE_MINUTES },
                deliveryMode = DELIVERY_CODES.getOrNull(delivery - 1)
                    ?.let { name -> AlertDeliveryMode.entries.firstOrNull { it.name == name } },
                timeRangeEnabled = if (full) flags and FLAG_TIME_RANGE != 0 else null,
                activeStartHour = time(startHour, 23),
                activeStartMinute = time(startMinute, 59),
                activeEndHour = time(endHour, 23),
                activeEndMinute = time(endMinute, 59),
                thresholdMgPerL = threshold.takeIf { thresholdInRange },
            )
        }
        var sentAt: Long? = null
        if (body.remaining() >= 1 + Long.SIZE_BYTES && (body.get().toInt() and 0xff) == TRAILER_SENT_AT) {
            sentAt = body.long.takeIf { it > 0 }
        }
        return Message(result, sentAt, outOfRange)
    }

    /**
     * Upstream's preference keys this message carries, by kind (AlertRepository's key functions, Notify); a
     * native-backed alert's threshold is set natively on the watch, its key only marks a change on the phone.
     */
    fun keys(kind: Int): List<String> = SUFFIXES.map { "alert_${kind}_$it" } + AlertSoundVolume.preferenceKey(kind)

    private val SUFFIXES = listOf(
        "alarmDur", "sound", "vibration", "haptic", "soundDelayEnabled", "soundDelay",
        "retryOn", "retryInt", "retryCnt", "snooze",
        "delivery", "timeRange", "startHour", "startMin", "endHour", "endMin", "threshold",
    )

    /**
     * Phone: a change of [key] in the alert preferences sends the settings at once - a key this message carries
     * (upstream's save also writes a native-backed alert's threshold there) or an alert's "enabled", after which the
     * watch's toggle sync writes defaults over a prefs-only alert's settings.
     */
    fun triggersSend(key: String?): Boolean {
        if (key == null) return false
        return AlertType.settingsEntries.any { type -> key in keys(type.id) || key == "alert_${type.id}_enabled" }
    }

    /**
     * Where the watch reads and sets a native-backed alert's threshold (mg/L). An interface so tests can stand in for
     * the native side.
     */
    interface ThresholdStore {
        /** [type]'s threshold the watch acts on, mg/L; `null` when it has none or it cannot be read. */
        fun read(type: AlertType): Int?

        /** Sets [type]'s threshold to [mgPerL]; its on/off stays what upstream holds. */
        fun write(type: AlertType, mgPerL: Int)
    }

    /**
     * Upstream's own way: read through AlertRepository.loadConfig (which runs their one-time threshold migration
     * first, so it cannot later replace ours), set through Natives.setAlertConfig - what their AlertRepository.saveConfig
     * calls. That call takes on/off too: we pass the state the phone last sent through their toggle sync
     * (WearToggleSync.knownEnabled - set before their own apply runs, so a toggle arriving at the same moment is not
     * undone), else the state the watch holds now. Never a value of ours. The call also writes back the on/off of the
     * kind's native neighbours as it read them; a toggle of a neighbour landing in between is caught by the watch's
     * check afterwards (WatchAlertStore.checkToggles).
     */
    object UpstreamThresholds : ThresholdStore {
        override fun read(type: AlertType): Int? {
            val value = AlertRepository.loadConfig(type).threshold ?: return null
            return if (value.isFinite()) toMgPerL(value, Natives.getunit() == 1) else null
        }

        override fun write(type: AlertType, mgPerL: Int) {
            val enabled = WearToggleSync.knownEnabled(WearToggleSync.SCOPE_ALERT, type.id.toString())
                ?: AlertRepository.loadConfig(type).enabled
            Natives.setAlertConfig(type.id, fromMgPerL(mgPerL, Natives.getunit() == 1), enabled)
        }
    }

    /**
     * Watch: writes [list] into [prefs] (upstream's alert preferences) under upstream's keys and the native thresholds
     * through [thresholds] - only the values that differ, so a repeat of the same settings writes nothing. Never
     * writes "enabled". Returns how many values changed.
     */
    fun apply(prefs: SharedPreferences, list: List<Settings>, thresholds: ThresholdStore = UpstreamThresholds): Int {
        val editor = prefs.edit()
        var changed = 0
        fun putInt(key: String, value: Int?) {
            if (value == null) return
            if (prefs.contains(key) && runCatching { prefs.getInt(key, 0) }.getOrNull() == value) return
            editor.putInt(key, value)
            changed++
        }
        fun putBoolean(key: String, value: Boolean?) {
            if (value == null) return
            if (prefs.contains(key) && runCatching { prefs.getBoolean(key, false) }.getOrNull() == value) return
            editor.putBoolean(key, value)
            changed++
        }
        fun putString(key: String, value: String?) {
            if (value == null) return
            if (prefs.contains(key) && runCatching { prefs.getString(key, null) }.getOrNull() == value) return
            editor.putString(key, value)
            changed++
        }
        fun putTime(key: String, value: Int?) {
            when (value) {
                null -> return
                // as upstream's saveToPrefs: a field not set has no key
                TIME_UNSET -> if (prefs.contains(key)) {
                    editor.remove(key)
                    changed++
                }
                else -> putInt(key, value)
            }
        }
        for (s in list) {
            val k = "alert_${s.kind}_"
            putInt(k + "alarmDur", s.alarmDurationSeconds)
            putBoolean(k + "sound", s.soundEnabled)
            putBoolean(k + "vibration", s.vibrationEnabled)
            putString(k + "haptic", s.hapticProfile?.name)
            putInt(AlertSoundVolume.preferenceKey(s.kind), s.soundVolumePercent)
            putBoolean(k + "soundDelayEnabled", s.soundDelayEnabled)
            putInt(k + "soundDelay", s.soundDelaySeconds)
            putBoolean(k + "retryOn", s.retryEnabled)
            putInt(k + "retryInt", s.retryIntervalMinutes)
            putInt(k + "retryCnt", s.retryCount)
            putInt(k + "snooze", s.defaultSnoozeMinutes)
            putString(k + "delivery", s.deliveryMode?.name)
            putBoolean(k + "timeRange", s.timeRangeEnabled)
            putTime(k + "startHour", s.activeStartHour)
            putTime(k + "startMin", s.activeStartMinute)
            putTime(k + "endHour", s.activeEndHour)
            putTime(k + "endMin", s.activeEndMinute)
            val mgPerL = s.thresholdMgPerL?.takeIf { it in MIN_THRESHOLD_MG_PER_L..MAX_THRESHOLD_MG_PER_L } ?: continue
            val type = AlertType.fromId(s.kind) ?: continue
            if (type in NATIVE_THRESHOLD) {
                if (applyNativeThreshold(thresholds, type, mgPerL)) changed++
            } else if (AlertDefaults.defaultConfig(type, false).threshold != null) {
                // a prefs-only alert with a level (persistent high): upstream's key, in this device's display unit
                val mmol = Applic.unit == 1
                val key = k + "threshold"
                val current = if (prefs.contains(key)) runCatching { prefs.getFloat(key, 0f) }.getOrNull()
                else AlertDefaults.defaultConfig(type, mmol).threshold
                if (current == null || !current.isFinite() || toMgPerL(current, mmol) != mgPerL) {
                    editor.putFloat(key, fromMgPerL(mgPerL, mmol))
                    changed++
                }
            }
        }
        if (changed > 0) editor.apply()
        return changed
    }

    /** Sets [type]'s native threshold when it differs from [mgPerL]; true when it was set. Never throws. */
    private fun applyNativeThreshold(thresholds: ThresholdStore, type: AlertType, mgPerL: Int): Boolean = try {
        if (thresholds.read(type) == mgPerL) {
            false
        } else {
            thresholds.write(type, mgPerL)
            true
        }
    } catch (t: Throwable) {
        ModWatchLog.w("alert settings: threshold of kind ${type.id} not set: ${t.javaClass.simpleName}")
        false
    }

    /** `null` goes as 0, which neither byte field (duration, volume) accepts. */
    private fun u8(value: Int?): Byte = (value ?: 0).coerceIn(0, 0xff).toByte()

    /** `null` and anything too big go as 0xffff, which no short field accepts: the watch keeps its own value. */
    private fun u16(value: Int?): Short = (value ?: NO_SHORT).coerceIn(0, NO_SHORT).toShort()

    private fun code(names: List<String>, name: String?): Byte = ((name?.let { names.indexOf(it) } ?: -1) + 1).toByte()

    private fun timeByte(value: Int?): Byte {
        val v = value ?: return TIME_UNKNOWN_BYTE.toByte()
        return when (v) {
            TIME_UNSET -> TIME_UNSET_BYTE
            in 0..59 -> v
            else -> TIME_UNKNOWN_BYTE
        }.toByte()
    }

    private fun time(raw: Int, max: Int): Int? = when (raw) {
        TIME_UNSET_BYTE -> TIME_UNSET
        in 0..max -> raw
        else -> null
    }
}
