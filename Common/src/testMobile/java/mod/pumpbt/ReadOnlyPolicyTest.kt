// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// core/MiniMedKitKotlin/src/test/kotlin/de/sgitaize/minimedkit/ReadOnlyPolicyTest.kt, by Simon Gutjahr.
// Modified for JugglucoNGmod: package; JUnit asserts instead of kotlin.test; CGM RACP and CGM subscriptions are now
// blocked; IDD Features readable; DevModeGuard test removed; more blocked cases (Reset Status, Delete, Abort, wrong
// length, unknown characteristic).
package mod.pumpbt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.core.PumpUuid
import tk.glucodata.mod.pumpbt.core.ReadOnlyPolicy
import tk.glucodata.mod.pumpbt.core.StatusReaderOpcode
import tk.glucodata.mod.pumpbt.core.WriteRequest
import tk.glucodata.mod.pumpbt.core.toHex

class ReadOnlyPolicyTest {
    private fun allowed(service: String, char: String, hexValue: String): Boolean =
        ReadOnlyPolicy.check(WriteRequest(service, char, hex(hexValue))).isAllowed

    @Test
    fun allowsOnlyReadingCommands() {
        assertTrue(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "33690f"))
        assertTrue(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "33660f"))
        assertTrue(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "5a330f"))
        assertTrue(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "335a0f0f41070036410700"))
        // Full 128-bit form of a SIG UUID and lowercase Medtronic UUIDs are the same characteristic.
        assertTrue(allowed(PumpUuid.IDD_SERVICE.lowercase(), "00002a52-0000-1000-8000-00805f9b34fb", "33690f"))
        for (op in StatusReaderOpcode.entries) {
            assertTrue("$op", allowed(PumpUuid.IDD_SERVICE.lowercase(), PumpUuid.IDD_STATUS_READER_CP, op.request.toHex()))
        }
    }

    @Test
    fun blocksEverythingElse() {
        // CGM RACP: not needed by the trial, so nothing at all - not even "last record"
        assertFalse(allowed(PumpUuid.CGM_SERVICE, PumpUuid.RECORD_ACCESS_CP, "0106"))
        assertFalse(allowed(PumpUuid.CGM_SERVICE, PumpUuid.RECORD_ACCESS_CP, "0201"))
        assertFalse(allowed(PumpUuid.CGM_SERVICE, PumpUuid.RECORD_ACCESS_CP, "0300"))
        // IDD Status Reader: Reset Status (0x030C), unknown opcodes, parameters appended, wrong length
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, "0c03"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, "f30300"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, "3003"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, "f3"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, ""))
        // Command CP (bolus, basal, suspend, settings) - always blocked, even with a "read" opcode
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_COMMAND_CP, "8e14"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_COMMAND_CP, "f303"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.IDD_COMMAND_DATA, "00"))
        // IDD RACP: delete (all / range), abort, other filters, truncated or too long range
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "3c33"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "3c330f"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "3c5a0f0f41070036410700"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "550f"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "55"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "33693c"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "335a0f0f410700"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "335a0f0f4107003641070000"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, "33690f00"))
        // CGM specific ops (calibration, session start/stop), session start time write
        assertFalse(allowed(PumpUuid.CGM_SERVICE, PumpUuid.CGM_SPECIFIC_OPS_CP, "1a"))
        assertFalse(allowed(PumpUuid.CGM_SERVICE, PumpUuid.CGM_SESSION_START_TIME, "00"))
        // Same characteristic in a different service must not inherit permissions
        assertFalse(allowed("00000300-0000-1000-0000-009132591325", PumpUuid.RECORD_ACCESS_CP, "33690f"))
        assertFalse(allowed(PumpUuid.CGM_SERVICE, PumpUuid.IDD_STATUS_READER_CP, "f303"))
        // characteristics nobody knows
        assertFalse(allowed("1805", "2A2B", "00"))
        assertFalse(allowed(PumpUuid.IDD_SERVICE, "00001234-0000-1000-0000-009132591325", "33690f"))
    }

    @Test
    fun readAndSubscribeLists() {
        assertTrue(ReadOnlyPolicy.mayRead(PumpUuid.IDD_STATUS.lowercase()))
        assertTrue(ReadOnlyPolicy.mayRead(PumpUuid.IDD_FEATURES))
        assertTrue(ReadOnlyPolicy.mayRead(PumpUuid.MODEL_NUMBER))
        assertTrue(ReadOnlyPolicy.mayRead("00002a25-0000-1000-8000-00805f9b34fb"))
        assertFalse(ReadOnlyPolicy.mayRead(PumpUuid.IDD_COMMAND_DATA))
        assertFalse(ReadOnlyPolicy.mayRead(PumpUuid.IDD_COMMAND_CP))
        assertTrue(ReadOnlyPolicy.maySubscribe(PumpUuid.IDD_STATUS_READER_CP))
        assertTrue(ReadOnlyPolicy.maySubscribe(PumpUuid.IDD_HISTORY_DATA))
        assertFalse(ReadOnlyPolicy.maySubscribe(PumpUuid.CGM_MEASUREMENT))
        assertFalse(ReadOnlyPolicy.maySubscribe("00002aa7-0000-1000-8000-00805f9b34fb"))
        assertFalse(ReadOnlyPolicy.maySubscribe(PumpUuid.IDD_COMMAND_CP))
        assertFalse(ReadOnlyPolicy.maySubscribe(PumpUuid.IDD_COMMAND_DATA))
    }
}
