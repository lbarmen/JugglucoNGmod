package tk.glucodata.mod.watch.face

import android.content.Context
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import tk.glucodata.R

/**
 * JugglucoNGmod, watch block (part 3a): the face's words, from our `mod_watch_` resources (English and Russian), so
 * the date reads "Tue 17 Nov" / "Вт 17 ноя" rather than the platform's long or dotted forms ("нояб.").
 */
data class FaceLabels(
    /** Seven short weekday names from Monday. */
    val days: List<String>,
    /** Twelve short month names from January. */
    val months: List<String>,
    /** A battery percent, `String.format` pattern with one integer ("%1$d%%"). */
    val percentFormat: String,
    /** The age of the value, `String.format` pattern with one integer ("%1$d min"). */
    val minutesFormat: String,
    /** The battery row's words before the two percents (part 3b). */
    val phone: String = "phone",
    val watch: String = "watch",
    /** The age under two minutes, one integer ("%1$d s"; 20.11). */
    val secondsFormat: String = "%1\$d s",
    /** The age from 100 minutes, one integer of whole hours ("%1$d h"; 20.11). */
    val hoursFormat: String = "%1\$d h",
    /** The pump's active insulin, one string - the number, "99+" or "-" ("%1$s U"; 20.11). */
    val iobFormat: String = "%1\$s U",
) {
    companion object {
        /** The labels for the context's current locale. */
        fun from(context: Context): FaceLabels {
            val res = context.resources
            return FaceLabels(
                res.getStringArray(R.array.mod_watch_face_days).toList(),
                res.getStringArray(R.array.mod_watch_face_months).toList(),
                res.getString(R.string.mod_watch_face_percent),
                res.getString(R.string.mod_watch_face_minutes),
                res.getString(R.string.mod_watch_face_phone),
                res.getString(R.string.mod_watch_face_watch),
                res.getString(R.string.mod_watch_face_seconds),
                res.getString(R.string.mod_watch_face_hours),
                res.getString(R.string.mod_watch_face_iob),
            )
        }
    }
}

/** Texts of the face: pure functions over a time, a zone and [FaceLabels]. */
object FaceText {

    /** The clock: "14:35" in 24-hour mode, else "2:35" with [dayPart] "pm" (upstream's face draws it so). */
    data class Clock(val text: String, val dayPart: String?)

    fun clock(epochMs: Long, zone: ZoneId, hour24: Boolean): Clock {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        if (hour24) return Clock(String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute), null)
        val hour12 = (time.hour % 12).let { if (it == 0) 12 else it }
        return Clock(String.format(Locale.ROOT, "%d:%02d", hour12, time.minute), if (time.hour >= 12) "pm" else "am")
    }

    /** "Tue 17 Nov": the weekday, the day of the month and the month, from [labels]. */
    fun date(epochMs: Long, zone: ZoneId, labels: FaceLabels): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val day = labels.days.getOrNull(date.dayOfWeek.value - 1).orEmpty()
        val month = labels.months.getOrNull(date.monthValue - 1).orEmpty()
        return listOf(day, date.dayOfMonth.toString(), month).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * "18 Nov" / "18 ноя": the day of the month and the short month, from [labels] (part 7e, the face's bottom row;
     * the weekday is left out). The painter splits it at the space.
     */
    fun dayAndMonth(epochMs: Long, zone: ZoneId, labels: FaceLabels): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val month = labels.months.getOrNull(date.monthValue - 1).orEmpty()
        return listOf(date.dayOfMonth.toString(), month).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** A battery as "64%" / "64 %"; [FaceValues.NONE] when unknown or too old. */
    fun battery(percent: Int?, labels: FaceLabels): String {
        if (percent == null || percent !in 0..100) return FaceValues.NONE
        return formatInt(labels.percentFormat, percent, "$percent%")
    }

    /** The battery row, "phone 64%   watch 81%" / "телефон 64 %   часы 81 %" (texts from [battery]). */
    fun batteries(phoneText: String, watchText: String, labels: FaceLabels): String =
        "${labels.phone} $phoneText   ${labels.watch} $watchText"

    /**
     * The age of the value in whole [minutes], "2 min" / "2 мин", from [FaceValues.HOURS_FROM_MINUTES] in whole hours,
     * "1 h" / "1 ч" (20.11); `null` when there has been no reading at all.
     */
    fun age(minutes: Int?, labels: FaceLabels): String? {
        if (minutes == null || minutes < 0) return null
        if (minutes >= FaceValues.HOURS_FROM_MINUTES) {
            val hours = minutes / 60
            return formatInt(labels.hoursFormat, hours, "$hours h")
        }
        return formatInt(labels.minutesFormat, minutes, "$minutes min")
    }

    /**
     * The age of the value [ageMs] old: with [seconds] (the screen lit) and under [FaceValues.SECONDS_UNTIL_MS] in whole
     * seconds, "45 s" / "45 с" (20.11), else as [age]; a reading from the future counts as 0; `null` - no reading.
     */
    fun ageOf(ageMs: Long?, seconds: Boolean, labels: FaceLabels): String? {
        if (ageMs == null) return null
        val ms = ageMs.coerceAtLeast(0L)
        if (seconds && ms < FaceValues.SECONDS_UNTIL_MS) {
            val s = (ms / 1000L).toInt()
            return formatInt(labels.secondsFormat, s, "$s s")
        }
        return age((ms / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), labels)
    }

    /** From this many tenths the active insulin is written [IOB_OVER] (100 units). */
    const val IOB_OVER_TENTHS = 1000
    const val IOB_OVER = "99+"

    /**
     * The pump's active insulin: always one decimal in the locale's separator, "0,0 ЕД" a known zero, "12,5 ЕД"; from
     * 100 units "99+ ЕД"; `null` [tenths] (unknown or too old) "— ЕД" (20.11; never upstream's "under 0.05 hidden" or
     * "3" for "3.0" - not for a dose decision).
     */
    fun iob(tenths: Int?, labels: FaceLabels, locale: Locale = Locale.getDefault()): String {
        val number = when {
            tenths == null || tenths < 0 -> FaceValues.NONE
            tenths >= IOB_OVER_TENTHS -> IOB_OVER
            else -> "${tenths / 10}${DecimalFormatSymbols.getInstance(locale).decimalSeparator}${tenths % 10}"
        }
        return try {
            String.format(Locale.ROOT, labels.iobFormat, number)
        } catch (_: Throwable) {
            "$number U"
        }
    }

    private fun formatInt(pattern: String, value: Int, fallback: String): String = try {
        String.format(Locale.ROOT, pattern, value)
    } catch (_: Throwable) {
        fallback
    }
}
