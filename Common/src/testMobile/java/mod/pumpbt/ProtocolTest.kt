// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// core/MiniMedKitKotlin/src/test/kotlin/de/sgitaize/minimedkit/ProtocolTest.kt, by Simon Gutjahr.
// Parser vectors: OpenMinimed PythonPumpConnector (780G payloads, GPL-3.0).
// Modified for JugglucoNGmod: package; JUnit asserts instead of kotlin.test; the Nightscout, JSON and Pebble cases
// removed (those parts are not ported); IEEE-11073 special values (made-up bytes) added.
package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.mod.pumpbt.core.ActiveBasalRate
import tk.glucodata.mod.pumpbt.core.CgmMeasurement
import tk.glucodata.mod.pumpbt.core.CgmSessionStartTime
import tk.glucodata.mod.pumpbt.core.Checksums
import tk.glucodata.mod.pumpbt.core.InsulinOnBoard
import tk.glucodata.mod.pumpbt.core.MedFloat
import tk.glucodata.mod.pumpbt.core.ParseError
import tk.glucodata.mod.pumpbt.core.PumpStatus
import tk.glucodata.mod.pumpbt.core.TherapyAlgorithmStates
import tk.glucodata.mod.pumpbt.core.TimeInRange
import tk.glucodata.mod.pumpbt.core.bytesOf
import java.time.ZoneOffset

class ProtocolTest {
    @Test
    fun e2eCrc() {
        assertTrue(Checksums.hasValidE2eCrc(hex("ea07031c0a1f2a80ff0873")))
        assertFalse(Checksums.hasValidE2eCrc(hex("ea07031c0a1f2a80ff0874")))
    }

    @Test
    fun medFloat() {
        assertEquals(-177.7, MedFloat.decode16(0xF90F), 0.0)
        assertEquals(0.025, MedFloat.decode32(0xF82625A0L), 0.0)
    }

    @Test
    fun medFloatSpecialValuesAreNoValue() {
        for (raw in listOf(0x007FFFFFL, 0x00800000L, 0x007FFFFEL, 0x00800002L, 0x00800001L)) {
            assertNull("FLOAT 0x%08x".format(raw), MedFloat.decode32OrNull(raw))
        }
        for (raw in listOf(0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801)) {
            assertNull("SFLOAT 0x%04x".format(raw), MedFloat.decode16OrNull(raw))
        }
        assertEquals(0.025, MedFloat.decode32OrNull(0xF82625A0L)!!, 0.0)
        assertEquals(-177.7, MedFloat.decode16OrNull(0xF90F)!!, 0.0)
        // the same mantissa with another exponent is a number
        assertEquals(838.8607, MedFloat.decode32OrNull(0xFC7FFFFFL)!!, 1e-9)
    }

    @Test
    fun cgmMeasurement() {
        val m = CgmMeasurement.parse(hex("0ec3f900f40b000074e00a00e0f1"))
        assertEquals(249.0, m.glucoseMgdl, 0.0)
        assertEquals(3060, m.timeOffsetMinutes)
        assertEquals(1.16, m.trendMgdlPerMin!!, 0.0)
        assertEquals(10.0, m.quality!!, 0.0)

        val m2 = CgmMeasurement.parse(hex("0ec38d00e803000010e00a00d9af"))
        assertEquals(141.0, m2.glucoseMgdl, 0.0)
        assertEquals(1000, m2.timeOffsetMinutes)

        try {
            CgmMeasurement.parse(hex("0ec3f900f40b000074e00a00e0f2"))
            fail("a wrong E2E-CRC must fail")
        } catch (_: ParseError) {
        }
    }

    @Test
    fun sessionStartTime() {
        val raw = hex("ea0704040c323680ff")
        val crc = Checksums.e2eCrc(raw)
        val t = CgmSessionStartTime.parse(raw + bytesOf(crc and 0xFF, crc shr 8))
        assertEquals(2026, t.localTime.year)
        assertEquals(12, t.localTime.hour)
        assertNull(t.timeZoneOffsetMinutes)
        assertEquals(1775307054L, t.instant(ZoneOffset.UTC).epochSecond)
    }

    @Test
    fun pumpStatus() {
        val s = PumpStatus.parse(hex("5596e80d28fb010300"))
        assertEquals(PumpStatus.RUN, s.therapyControlState)
        assertEquals(PumpStatus.OPERATIONAL_READY, s.operationalState)
        assertEquals(26.25, s.reservoirUnits!!, 0.0001) // 0xfb280de8 = 2625000e-5
        assertTrue(s.reservoirAttached)
        assertTrue(s.sensorOn)
        assertFalse(s.isSuspended)

        // reservoir: FLOAT NaN → no value
        assertNull(PumpStatus.parse(hex("5596ffff7f00010300")).reservoirUnits)
    }

    @Test
    fun insulinOnBoard() {
        val iob = InsulinOnBoard.parse(hex("fc0300c05c15fa"))
        assertEquals(1.4, iob.units!!, 0.0001)
        assertNull(iob.remainingDurationMinutes)

        assertNull(InsulinOnBoard.parse(hex("fc0300ffff7f00")).units) // NaN
    }

    @Test
    fun timeInRange() {
        val tir = TimeInRange.parse(hex("0204001c024664"))
        assertEquals(28, tir.abovePercent)
        assertEquals(2, tir.belowPercent)
        assertEquals(70, tir.inRangePercent)
        assertEquals(100, tir.smartGuardPercent)
    }

    @Test
    fun therapyAlgorithmStates() {
        val tas = TherapyAlgorithmStates.parse(hex("fe0301000200"))
        assertEquals(TherapyAlgorithmStates.AUTO_BASAL, tas.shieldState)
        assertEquals("SmartGuard", tas.modeDescription)
    }

    @Test
    fun activeBasal() {
        val b = ActiveBasalRate.parse(hex("6a03000200000000"))
        assertEquals(2, b.profileNumber)
        assertEquals(0.0, b.unitsPerHour!!, 0.0)
        assertNull(b.tempBasalUnitsPerHour)

        // rate NRes, temp basal (flag 0x01): type, rate NaN, programmed 60 min, 30 min left
        val special = ActiveBasalRate.parse(hex("6a0301" + "02" + "00008000" + "33" + "ffff7f00" + "3c00" + "1e00"))
        assertNull(special.unitsPerHour)
        assertNull(special.tempBasalUnitsPerHour)
        assertEquals(30, special.tempBasalRemainingMinutes)
    }
}
