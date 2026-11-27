// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// core/MiniMedKitKotlin/src/main/kotlin/de/sgitaize/minimedkit/ReadOnlyPolicy.kt, by Simon Gutjahr.
// Modified for JugglucoNGmod: package name; English reasons; CGM RACP and CGM subscriptions removed (the trial does
// not read the Medtronic sensor); IDD Features readable; DevModeGuard removed.
//
// Read only. Every GATT write towards the pump has to pass this policy. It is an allowlist:
// anything that is not listed here explicitly is blocked. Writes are checked on the
// plaintext, i.e. before SAKE encryption. Characteristic reads and enabling
// notifications/indications do not change the pump state and are handled by their own lists.
// If in doubt whether a message writes, it is forbidden.
package tk.glucodata.mod.pumpbt.core

/** A write the app wants to send to the pump, in plaintext (before encryption). */
class WriteRequest(service: String, characteristic: String, val plaintext: ByteArray) {
    val service: String = PumpUuid.normalize(service)
    val characteristic: String = PumpUuid.normalize(characteristic)
}

/** Result of the policy check: allowed, or blocked with a reason for the log. */
sealed class PolicyDecision {
    data class Allowed(val reason: String) : PolicyDecision()
    data class Blocked(val reason: String) : PolicyDecision()

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * The app only reads. Every write to the pump must pass [check]; only report/read requests on the allowlist are
 * allowed, everything else is blocked.
 */
object ReadOnlyPolicy {
    /**
     * Characteristics we may subscribe to (CCCD write). Subscribing only asks the pump to
     * report values and does not change therapy or settings.
     */
    val subscribable: Set<String> = setOf(
        PumpUuid.RECORD_ACCESS_CP,
        PumpUuid.IDD_STATUS_READER_CP, PumpUuid.IDD_STATUS_CHANGED,
        PumpUuid.GST_BATTERY_LEVEL, PumpUuid.BATTERY_LEVEL, PumpUuid.IDD_HISTORY_DATA,
    ).map(PumpUuid::normalize).toSet()

    /** Characteristics we may read. */
    val readable: Set<String> = setOf(
        PumpUuid.IDD_STATUS, PumpUuid.IDD_STATUS_CHANGED, PumpUuid.IDD_FEATURES, PumpUuid.CGM_SESSION_START_TIME,
        PumpUuid.CGM_SESSION_RUN_TIME, PumpUuid.CGM_FEATURE, PumpUuid.SERIAL_NUMBER,
        PumpUuid.FIRMWARE_REVISION, PumpUuid.MODEL_NUMBER, PumpUuid.BATTERY_LEVEL,
        PumpUuid.GST_BATTERY_LEVEL, PumpUuid.SENSOR_EXPIRATION,
    ).map(PumpUuid::normalize).toSet()

    private val iddService = PumpUuid.normalize(PumpUuid.IDD_SERVICE)
    private val racp = PumpUuid.normalize(PumpUuid.RECORD_ACCESS_CP)
    private val srcp = PumpUuid.normalize(PumpUuid.IDD_STATUS_READER_CP)

    /** Decides whether a write may be sent. Unknown characteristics or opcodes are always blocked. */
    fun check(request: WriteRequest): PolicyDecision {
        val p = request.plaintext
        val s = request.service
        val c = request.characteristic
        return when {
            // IDD Record Access Control Point (pump history, IDS opcodes). Allowed are only
            // the reporting commands with filter type "sequence number" (0x0F), as used by
            // the official app: Report Stored Records (0x33) with Last (0x69),
            // First (0x66) or Within Range (0x5A + two u32), and Report Number of Stored
            // Records (0x5A) for All (0x33). Delete Stored Records (0x3C) and Abort (0x55)
            // are blocked.
            s == iddService && c == racp -> when {
                p.contentEquals(bytesOf(0x33, 0x69, 0x0F)) -> PolicyDecision.Allowed("IDD RACP: last history record")
                p.contentEquals(bytesOf(0x33, 0x66, 0x0F)) -> PolicyDecision.Allowed("IDD RACP: first history record")
                p.contentEquals(bytesOf(0x5A, 0x33, 0x0F)) -> PolicyDecision.Allowed("IDD RACP: number of history records")
                p.size == 11 && p.u8(0) == 0x33 && p.u8(1) == 0x5A && p.u8(2) == 0x0F ->
                    PolicyDecision.Allowed("IDD RACP: history range")
                else -> PolicyDecision.Blocked("IDD RACP: command not allowed ${p.toHex()}")
            }

            // IDD Status Reader Control Point: only the read-only "Get …" opcodes of
            // StatusReaderOpcode, without parameters. Reset Status (0x030C) is blocked.
            s == iddService && c == srcp -> {
                if (p.size != 2) return PolicyDecision.Blocked("IDD SRCP: unexpected length ${p.size}")
                val opcode = p.u8(0) or (p.u8(1) shl 8)
                val op = StatusReaderOpcode.of(opcode)
                if (op != null) PolicyDecision.Allowed("IDD SRCP: $op")
                else PolicyDecision.Blocked("IDD SRCP: opcode 0x%04x not allowed".format(opcode))
            }

            // Includes the CGM RACP, IDD Command Control Point (bolus, basal, suspend, settings, …),
            // CGM Specific Ops (calibration, session start/stop), session start time
            // writes, History & Trace, NOS, SSE, certificate and firmware services, Current Time.
            else -> PolicyDecision.Blocked("writing ${request.characteristic} is not allowed")
        }
    }

    /** Whether reading this characteristic is allowed. */
    fun mayRead(characteristic: String): Boolean = PumpUuid.normalize(characteristic) in readable

    /** Whether subscribing to notifications/indications of this characteristic is allowed. */
    fun maySubscribe(characteristic: String): Boolean = PumpUuid.normalize(characteristic) in subscribable
}
