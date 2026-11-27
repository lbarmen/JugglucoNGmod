// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// core/MiniMedKitKotlin/src/main/kotlin/de/sgitaize/minimedkit/History.kt, by Simon Gutjahr.
// Modified for JugglucoNGmod: package name; bolus programmed parts (0x005A, 0x0066), all fields of the bolus delivered
// parts (0x0069, 0x0096), Therapy Context (0xF004); a body that does not match its format is returned as Unparsed
// instead of throwing; IEEE-11073 special values as null; raw records (RawHistoryRecord) and the pump-local reference
// (HistoryReferenceLocal) replace the time-zone based timeline.
// IDD History Data records (IDS + Medtronic events), read via the IDD RACP.
// Record layout and event formats from OpenMinimed (PythonPumpConnector history/data.py,
// Documentation/idd-service.md). Records arrive SAKE-encrypted and are decrypted first.
package tk.glucodata.mod.pumpbt.core

import java.time.LocalDateTime
import kotlin.math.roundToLong

/** Decoded IDD history event types the app uses (Medtronic types 0xF0xx and IDS types). Null numbers - special value. */
sealed class HistoryEvent {
    /**
     * Pump clock reference (local time, no time zone). Later records count their
     * relative offset (seconds) from the latest reference.
     */
    data class ReferenceTime(val time: LocalDateTime) : HistoryEvent()

    /**
     * Bolus programmed, part 1 (0x005A); same layout as [BolusDelivered]. Bolus type (IDS): 0x0F undetermined,
     * 0x33 fast (normal), 0x3C extended (square), 0xFF multiwave (dual).
     */
    data class BolusProgrammed(
        val id: Int, val bolusType: Int, val fastUnits: Double?, val extendedUnits: Double?, val durationMinutes: Int,
    ) : HistoryEvent()

    /** Bolus programmed, part 2 (0x0066): flags and, with flag bit 2, the activation type (see [BolusDeliveredDetails]). */
    data class BolusProgrammedDetails(val flags: Int, val activationType: Int?) : HistoryEvent()

    /** Bolus delivered, part 1 (0x0069). Bolus type as in [BolusProgrammed]. */
    data class BolusDelivered(
        val id: Int, val bolusType: Int, val fastUnits: Double?, val extendedUnits: Double?, val durationMinutes: Int,
    ) : HistoryEvent()

    /**
     * Bolus delivered, part 2 (0x0096; follows part 1). Activation type (IDS): 0x0F undetermined, 0x33 manual,
     * 0x3C recommended (bolus wizard), 0x55 recommendation changed by the user, 0x5A commanded (SmartGuard auto
     * correction). End reason: 0x0F undetermined, 0x33 all delivered, 0x3C cancelled, 0x55 error - the 740G writes 0x0F
     * also for a fully delivered bolus, so "completed" is never taken from it.
     */
    data class BolusDeliveredDetails(
        val flags: Int, val startOffsetSec: Long, val activationType: Int?, val endReason: Int?, val annunciationId: Int?,
    ) : HistoryEvent()

    /**
     * Therapy Context (0xF004, Medtronic): flags (bit 0 sensor on, 1 basal running, 2 auto mode, 3 delivery stopped,
     * 4 temp basal) and the fields they announce. Temp basal type: 0x0F undetermined, 0x33 absolute, 0x3C percent.
     */
    data class TherapyContext(
        val flags: Int, val basalRate: Double?, val stopReason: Int?, val tempBasalType: Int?, val tempBasalValue: Double?,
    ) : HistoryEvent()

    /** SmartGuard auto basal (micro bolus). */
    data class AutoBasal(val units: Double?) : HistoryEvent()
    data class Meal(val grams: Double?) : HistoryEvent()
    data class BgReading(val mgdl: Double?) : HistoryEvent()
    data class Calibration(val mgdl: Double?, val accepted: Boolean) : HistoryEvent()
    data class BasalRateChanged(val oldUnitsPerHour: Double?, val newUnitsPerHour: Double?) : HistoryEvent()
    data class DeliveryStopped(val reason: Int) : HistoryEvent()
    data class DeliveryRestarted(val reason: Int) : HistoryEvent()

    /** Sensor value from the pump history; null for special states (warm-up, out of range…). */
    data class SensorGlucose(val mgdl: Int?) : HistoryEvent()

    /**
     * Pump alert/alarm (Annunciation Consolidated). Status: 0x33 pending, 0x3C snoozed,
     * 0x55 confirmed. Type without the 0xF000 marker.
     */
    data class Annunciation(val id: Int, val type: Int, val status: Int) : HistoryEvent()

    /** A type without a decoder here (see PumpEvents.KNOWN_TYPES for the ones skipped on purpose). */
    data class Other(val type: Int) : HistoryEvent()

    /**
     * The body did not match the format of its type (a byte too many or too few, an invalid date). [reason] is
     * English and holds no values from the body.
     */
    data class Unparsed(val type: Int, val reason: String) : HistoryEvent()
}

/** One IDD history record: event type, sequence number, offset to the reference time and the decoded event. */
data class HistoryRecord(
    val eventType: Int,
    val sequence: Long,
    /** Seconds since the latest reference time event. */
    val relativeOffset: Int,
    val event: HistoryEvent,
) {
    companion object {
        /** Header size: type (u16), sequence (u32), offset (u16). */
        const val HEADER_SIZE = 8

        /**
         * Parses a decrypted history record. The pump sends no E2E-CRC on history records (its IDD Features start
         * with FFFF), so there is nothing to check. Throws only if [data] is shorter than the 8-byte header; a body
         * that does not match its format gives [HistoryEvent.Unparsed].
         */
        fun parse(data: ByteArray): HistoryRecord {
            val r = ByteReader(data)
            val type = r.u16()
            val seq = r.u32()
            val offset = r.u16()
            val event = try {
                parseEvent(type, r)
            } catch (e: ParseError) {
                HistoryEvent.Unparsed(type, reason(e))
            } catch (e: RuntimeException) {
                HistoryEvent.Unparsed(type, "unreadable body")
            }
            return HistoryRecord(type, seq, offset, event)
        }

        /** kg/L as used by the pump for BG values → mg/dL. */
        internal fun mgdl(kgPerL: Double): Double = (kgPerL * 100_000).roundToLong().toDouble()

        private fun reason(e: ParseError): String = when (e) {
            is ParseError.TooShort -> "body too short"
            is ParseError.TrailingBytes -> "${e.count} bytes too many"
            is ParseError.LengthMismatch -> "invalid date or length"
            else -> "unreadable body"
        }

        private fun parseEvent(type: Int, r: ByteReader): HistoryEvent = when (type) {
            0xF00E -> { // NGP reference time: recording reason + date-time
                r.u8()
                val time = readDateTime(r)
                r.expectEnd()
                HistoryEvent.ReferenceTime(time)
            }
            0x005A -> { // Bolus Programmed P1: id, type, fast, extended, duration
                val id = r.u16()
                val bolusType = r.u8()
                val fast = r.float32OrNull()
                val ext = r.float32OrNull()
                val duration = r.u16()
                r.expectEnd()
                HistoryEvent.BolusProgrammed(id, bolusType, fast, ext, duration)
            }
            0x0066 -> { // Bolus Programmed P2: flags, [delay u16], [template u8], [activation u8]
                val flags = r.u8()
                if (flags and 0x01 != 0) r.u16() // delay
                if (flags and 0x02 != 0) r.u8() // template number
                val activation = if (flags and 0x04 != 0) r.u8() else null
                r.expectEnd()
                HistoryEvent.BolusProgrammedDetails(flags, activation)
            }
            0x0069 -> { // Bolus Delivered P1
                val id = r.u16()
                val bolusType = r.u8()
                val fast = r.float32OrNull()
                val ext = r.float32OrNull()
                val duration = r.u16()
                r.expectEnd()
                HistoryEvent.BolusDelivered(id, bolusType, fast, ext, duration)
            }
            0x0096 -> { // Bolus Delivered P2: flags, start time offset, [activation], [end reason], [annunciation]
                val flags = r.u8()
                val start = r.u32()
                val activation = if (flags and 0x01 != 0) r.u8() else null
                val endReason = if (flags and 0x02 != 0) r.u8() else null
                val annunciation = if (flags and 0x04 != 0) r.u16() else null
                r.expectEnd()
                HistoryEvent.BolusDeliveredDetails(flags, start, activation, endReason, annunciation)
            }
            0xF004 -> { // Therapy Context: flags, [basal rate f32], [stop reason u8], [temp basal type u8 + value f32]
                val flags = r.u8()
                val rate = if (flags and 0x02 != 0) r.float32OrNull() else null
                val stopReason = if (flags and 0x08 != 0) r.u8() else null
                var tempType: Int? = null
                var tempValue: Double? = null
                if (flags and 0x10 != 0) {
                    tempType = r.u8()
                    tempValue = r.float32OrNull()
                }
                r.expectEnd()
                HistoryEvent.TherapyContext(flags, rate, stopReason, tempType, tempValue)
            }
            0xF001 -> { // Auto basal delivery
                r.u8()
                val units = r.float32OrNull()
                r.expectEnd()
                HistoryEvent.AutoBasal(units)
            }
            0xF005 -> { // Meal
                val grams = r.sfloatOrNull()
                r.expectEnd()
                HistoryEvent.Meal(grams)
            }
            0xF007 -> { // BG reading
                r.u16() // time offset
                val bg = r.sfloatOrNull()
                r.expectEnd()
                HistoryEvent.BgReading(bg?.let(::mgdl))
            }
            0xF008, 0xF009 -> { // Calibration complete / rejected
                r.u16()
                val bg = r.sfloatOrNull()
                r.expectEnd()
                HistoryEvent.Calibration(bg?.let(::mgdl), type == 0xF008)
            }
            0x0099 -> { // Delivered basal rate changed
                val flags = r.u8()
                val old = r.float32OrNull()
                val new = r.float32OrNull()
                if (flags and 0x01 != 0) r.u8()
                r.expectEnd()
                HistoryEvent.BasalRateChanged(old, new)
            }
            0xF00A -> HistoryEvent.DeliveryStopped(r.u8()).also { r.expectEnd() }
            0xF00B -> HistoryEvent.DeliveryRestarted(r.u8()).also { r.expectEnd() }
            0xF00C -> { // SG measurement: time offset, SG, ISIG, V counter
                r.u16()
                val sg = r.u16()
                r.u16()
                r.u16()
                r.expectEnd()
                // Values from 0x0300 on encode sensor states, not glucose.
                HistoryEvent.SensorGlucose(if (sg < 0x0300 && sg in 40..400) sg else null)
            }
            0xF010 -> { // Annunciation Consolidated: flags, id, type (0xF…), status, timestamp, aux…
                r.u8()
                val id = r.u16()
                val annType = r.u16()
                val status = r.u8()
                // The auxiliary data that follows depends on the type; it is not needed here.
                HistoryEvent.Annunciation(id, annType and 0x0FFF, status)
            }
            else -> HistoryEvent.Other(type)
        }
    }
}

/** The latest pump reference time (0xF00E): its record number and the pump clock it gave (local, no time zone). */
internal data class HistoryReferenceLocal(val sequence: Long, val time: LocalDateTime)

/** A history record as it came: header read, body raw (the memory keeps it, [parse] decodes it). */
internal data class RawHistoryRecord(val sequence: Long, val type: Int, val offsetSec: Int, val body: ByteArray) {

    /** Header + body, as it came (the memory keeps the hex of this). */
    fun toPlain(): ByteArray =
        bytesOf(type and 0xFF, (type shr 8) and 0xFF) + le32(sequence) +
            bytesOf(offsetSec and 0xFF, (offsetSec shr 8) and 0xFF) + body

    /** Decodes the record; never throws (a body that does not match - [HistoryEvent.Unparsed]). */
    fun parse(): HistoryRecord = HistoryRecord.parse(toPlain())

    override fun equals(other: Any?): Boolean =
        this === other || (other is RawHistoryRecord && sequence == other.sequence && type == other.type &&
            offsetSec == other.offsetSec && body.contentEquals(other.body))

    override fun hashCode(): Int = ((sequence.hashCode() * 31 + type) * 31 + offsetSec) * 31 + body.contentHashCode()

    /** Header values and body size only (the body may hold doses). */
    override fun toString(): String = "RawHistoryRecord(sequence=$sequence, type=0x%04x, offsetSec=$offsetSec, body=${body.size} bytes)".format(type)

    companion object {
        /** null - shorter than the 8-byte header. */
        fun fromPlain(plain: ByteArray): RawHistoryRecord? {
            if (plain.size < HistoryRecord.HEADER_SIZE) return null
            val r = ByteReader(plain)
            val type = r.u16()
            val seq = r.u32()
            val offset = r.u16()
            return RawHistoryRecord(seq, type, offset, plain.copyOfRange(HistoryRecord.HEADER_SIZE, plain.size))
        }
    }
}
