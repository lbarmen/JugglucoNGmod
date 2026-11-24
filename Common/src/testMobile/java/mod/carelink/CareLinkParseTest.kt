package mod.carelink

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.mod.carelink.CareLinkBasalWriter
import tk.glucodata.mod.carelink.CareLinkConfig
import tk.glucodata.mod.carelink.CareLinkData
import tk.glucodata.mod.carelink.CareLinkMarkers
import tk.glucodata.mod.carelink.CareLinkPumpNow
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.TempBasal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * JugglucoNGmod, CareLink 4c: parsing of the `display/message` answer - Glucove's CareLinkDataTest,
 * CareLinkMarkersTest and CareLinkPumpNowTest moved to org.json, plus JSON null, numbers as strings and empty strings.
 * Robolectric: Android's own org.json, as on the phone (JSON-java of plain JUnit differs on JSON null).
 * Field names follow the field list of the Glucove probe; every value is made up (dates of January 2026, round doses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkParseTest {

    private val phone = ZoneId.of("Asia/Yekaterinburg")
    private val now = 1_768_040_000_000L
    private val dataAt = 1_768_035_600_000L

    private fun ms(local: String, offsetH: Int = 3) =
        LocalDateTime.parse(local).toInstant(ZoneOffset.ofHours(offsetH)).toEpochMilli()

    private fun json(text: String): JSONObject = CareLinkConfig.parse(text)!!

    private fun root(patient: String) =
        json("""{"metadata": {"clientDateTime": "2026-01-10T12:00:00.123+03:00"}, "patientData": {$patient}}""")

    // ---------------------------------------------------------------- CareLinkData

    @Test
    fun answerIsPumpDataWithMarkersOrDataTime() {
        assertTrue(CareLinkData.looksValid(root(""""markers": []""")))
        assertTrue(CareLinkData.looksValid(root(""""lastMedicalDeviceDataUpdateServerTime": $dataAt""")))
        assertTrue("old answers: fields at the root", CareLinkData.looksValid(json("""{"markers": []}""")))
        assertFalse(CareLinkData.looksValid(root(""""medicalDeviceFamily": "NGP"""")))
        assertFalse("JSON null is no list", CareLinkData.looksValid(root(""""markers": null""")))
        assertFalse(CareLinkData.looksValid(json("{}")))
    }

    @Test
    fun pumpDataTimeAsNumberOrString() {
        assertEquals(dataAt, CareLinkData.pumpDataAt(root(""""lastMedicalDeviceDataUpdateServerTime": $dataAt""")))
        assertEquals(dataAt, CareLinkData.pumpDataAt(root(""""lastMedicalDeviceDataUpdateServerTime": "$dataAt"""")))
        assertNull(CareLinkData.pumpDataAt(root(""""lastMedicalDeviceDataUpdateServerTime": 0""")))
        assertNull(CareLinkData.pumpDataAt(root(""""lastMedicalDeviceDataUpdateServerTime": null""")))
        assertNull(CareLinkData.pumpDataAt(root(""""lastMedicalDeviceDataUpdateServerTime": """"")))
        assertNull(CareLinkData.pumpDataAt(root("")))
    }

    // ---------------------------------------------------------------- CareLinkMarkers

    private val markers = """
        {"metadata": {"kind": "BLENGP", "version": 4, "clientDateTime": "2026-01-10T12:00:00.123+03:00"},
         "patientData": {
           "lastMedicalDeviceDataUpdateServerTime": $dataAt,
           "markers": [
             {"type": "MEAL", "timestamp": "2026-01-10T08:00:01", "data": {"dataValues": {"amount": 40.0}}},
             {"type": "INSULIN", "timestamp": "2026-01-10T08:00:03",
              "data": {"dataValues": {"programmedFastAmount": "4.0", "deliveredFastAmount": "4.0", "completed": true, "bolusType": "FAST"}}},
             {"type": "INSULIN", "timestamp": "2026-01-10T08:00:03.000",
              "data": {"dataValues": {"programmedFastAmount": "1.0", "deliveredFastAmount": 0.5, "completed": false}}},
             {"type": "INSULIN", "timestamp": "2026-01-10T10:30:00",
              "data": {"dataValues": {"programmedFastAmount": "3.0", "deliveredFastAmount": "1.5", "completed": "true"}}},
             {"type": "BG_READING", "timestamp": "2026-01-10T10:29:30", "data": {"dataValues": {"unitValue": "101", "bgUnits": "MMOL_L"}}},
             {"type": "AUTO_MODE_STATUS", "timestamp": "2026-01-10T07:00:00", "data": {"dataValues": {}}},
             {"type": "INSULIN"},
             {"timestamp": "2026-01-10T07:00:00"},
             {"type": "", "timestamp": "2026-01-10T07:00:00"},
             {"type": "MEAL", "timestamp": "not a time"},
             {"type": "MEAL", "timestamp": null}
           ]
         }}
    """.trimIndent()

    private fun events(text: String = markers, zone: ZoneId = phone) = CareLinkMarkers.parse(json(text), zone)

    @Test
    fun eventsByTimeKeyTypeTimeNumberZoneFromClientDateTime() {
        val e = events()
        assertEquals("markers without type or readable time are skipped", 6, e.size)
        val ins = e.filter { it.kind == ClKind.BOLUS }
        assertEquals(
            listOf("INSULIN|2026-01-10T08:00:03|0", "INSULIN|2026-01-10T08:00:03|1", "INSULIN|2026-01-10T10:30:00|0"),
            ins.map { it.key },
        )
        assertEquals(ms("2026-01-10T08:00:03"), ins[0].atMs)
        assertEquals(e.sortedBy { it.atMs }, e)
        assertEquals(ClKind.OTHER, e.first().kind)
        assertEquals("AUTO_MODE_STATUS|2026-01-10T07:00:00|0", e.first().key)
        assertEquals(ClKind.BG, e.single { it.key.startsWith("BG_READING") }.kind)
    }

    @Test
    fun doseAsStringOrNumberRunningIsProgrammedInterruptedIsDelivered() {
        val ins = events().filter { it.kind == ClKind.BOLUS }
        assertEquals(4.0, ins[0].dose!!, 1e-9)
        assertFalse(ins[0].interrupted)
        assertFalse(ins[0].extended)
        assertEquals("running - programmed", 1.0, ins[1].dose!!, 1e-9)
        assertEquals(0.5, ins[1].deliveredUnits!!, 1e-9)
        assertEquals(false, ins[1].completed)
        assertFalse("still running - not interrupted", ins[1].interrupted)
        assertEquals("completed as a string", true, ins[2].completed)
        assertTrue(ins[2].interrupted)
        assertEquals(1.5, ins[2].dose!!, 1e-9)
    }

    @Test
    fun calculatorCarbs() {
        assertEquals(40.0, events().single { it.kind == ClKind.MEAL }.grams!!, 1e-9)
    }

    @Test
    fun noClientDateTimePhoneZone() {
        val noOffset = markers.replace("\"clientDateTime\": \"2026-01-10T12:00:00.123+03:00\"", "\"clientDateTime\": null")
        val ins = events(noOffset).first { it.kind == ClKind.BOLUS }
        assertEquals("Yekaterinburg +5", ms("2026-01-10T08:00:03", 5), ins.atMs)
        assertNull(CareLinkMarkers.offsetOf(json(noOffset)))
        assertNull(CareLinkMarkers.offsetOf(root("").put("metadata", JSONObject().put("clientDateTime", ""))))
    }

    @Test
    fun pumpOffsetWinsOverThePhoneZone() {
        val other = markers.replace("+03:00", "-02:00")
        val ins = events(other).first { it.kind == ClKind.BOLUS }
        assertEquals(ms("2026-01-10T08:00:03", -2), ins.atMs)
    }

    private fun bolus(values: String): ClEvent = events(
        """{"metadata": {"clientDateTime": "2026-01-10T12:00:00+03:00"}, "patientData": {"markers": [
             {"type": "INSULIN", "timestamp": "2026-01-10T09:00:00", "data": {"dataValues": {$values}}}]}}""",
    ).single()

    @Test
    fun dualWaveSumsFastAndExtendedParts() {
        val b = bolus(
            """"programmedFastAmount": 2.0, "programmedExtendedAmount": "1.5", "deliveredFastAmount": 2.0,
               "deliveredExtendedAmount": 0.5, "completed": false, "bolusType": "DUAL_WAVE"""",
        )
        assertEquals(3.5, b.programmedUnits!!, 1e-9)
        assertEquals(2.5, b.deliveredUnits!!, 1e-9)
        assertTrue(b.extended)
        assertEquals("running - programmed sum", 3.5, b.dose!!, 1e-9)
    }

    @Test
    fun jsonNullEmptyStringAndPaddedNumber() {
        val b = bolus(""""programmedFastAmount": " 2.5 ", "deliveredFastAmount": null, "completed": null, "bolusType": """"")
        assertEquals(2.5, b.programmedUnits!!, 1e-9)
        assertNull(b.deliveredUnits)
        assertNull(b.completed)
        assertNull("empty type - unknown, not extended", b.bolusType)
        assertFalse(b.extended)
        assertEquals("no delivered yet - programmed", 2.5, b.dose!!, 1e-9)
        val empty = bolus(""""programmedFastAmount": "", "deliveredFastAmount": "abc", "completed": "maybe"""")
        assertNull(empty.programmedUnits)
        assertNull(empty.deliveredUnits)
        assertNull(empty.completed)
        assertNull(empty.dose)
        val upper = bolus(""""deliveredFastAmount": 1, "completed": "TRUE"""")
        assertEquals(true, upper.completed)
        assertEquals(1.0, upper.dose!!, 1e-9)
    }

    @Test
    fun mealWithNullOrStringAmount() {
        fun grams(v: String) = events(
            """{"patientData": {"markers": [{"type": "MEAL", "timestamp": "2026-01-10T09:00:00", "data": {"dataValues": {"amount": $v}}}]}}""",
            ZoneOffset.UTC,
        ).single().grams
        assertEquals(30.0, grams("\"30\"")!!, 1e-9)
        assertNull(grams("null"))
        assertNull(grams("\"\""))
        assertNull(grams("-5"))
    }

    @Test
    fun noMarkersNoEvents() {
        assertTrue(CareLinkMarkers.parse(root(""""markers": null"""), phone).isEmpty())
        assertTrue(CareLinkMarkers.parse(root(""), phone).isEmpty())
        assertTrue(CareLinkMarkers.parse(root(""""markers": [1, "x", null]"""), phone).isEmpty())
    }

    // ---------------------------------------------------------------- CareLinkPumpNow

    private val live = """
        "lastMedicalDeviceDataUpdateServerTime": $dataAt,
        "activeInsulin": {"amount": 5.699999809, "datetime": "2026-01-10T11:55:00"},
        "reservoirRemainingUnits": 214.80000305, "reservoirLevelPercent": 72, "pumpBatteryLevelPercent": 50,
        "pumpSuspended": false,
        "basal": {"activeBasalPattern": "BASAL1", "basalRate": 0.7000000179}
    """.trimIndent()

    private fun pump(patient: String) = CareLinkPumpNow.parse(root(patient), now, phone)

    @Test
    fun normalAnswerTenthsIobTimeByAnswerOffset() {
        val p = pump(live)!!
        assertEquals(dataAt, p.dataAtMs)
        assertEquals(5.7, p.iobUnits!!, 1e-9)
        assertEquals(ms("2026-01-10T11:55:00"), p.iobAtMs)
        assertEquals(214.8, p.reservoirUnits!!, 1e-9)
        assertEquals(72, p.reservoirPercent)
        assertEquals(50, p.batteryPercent)
        assertEquals(0.7, p.basalRate!!, 1e-9)
        assertNull(p.tempBasal)
        assertEquals(false, p.suspended)
    }

    @Test
    fun numbersAsStringsAndJsonNull() {
        val p = pump(
            """"lastMedicalDeviceDataUpdateServerTime": "$dataAt", "activeInsulin": {"amount": "2.25", "datetime": null},
               "reservoirRemainingUnits": "120.0", "reservoirLevelPercent": null, "pumpBatteryLevelPercent": "75",
               "pumpSuspended": "false", "basal": {"basalRate": "0.85", "tempBasalType": null}""",
        )!!
        assertEquals(dataAt, p.dataAtMs)
        assertEquals(2.3, p.iobUnits!!, 1e-9)
        assertEquals("no datetime - the data time of this answer", dataAt, p.iobAtMs)
        assertEquals(120.0, p.reservoirUnits!!, 1e-9)
        assertNull(p.reservoirPercent)
        assertEquals(75, p.batteryPercent)
        assertEquals(0.85, p.basalRate!!, 1e-9)
        assertNull(p.tempBasal)
        assertEquals(false, p.suspended)
    }

    @Test
    fun noDataTimeSnapshotAtNow() {
        assertEquals(now, pump(""""pumpBatteryLevelPercent": 50""")!!.dataAtMs)
    }

    @Test
    fun dataTimeAndIobTimeNotLaterThanNow() {
        val future = live.replace("$dataAt", "${now + 3_600_000L}")
            .replace("2026-01-10T11:55:00", "2026-01-10T13:30:00")
        val p = pump(future)!!
        assertEquals(now, p.dataAtMs)
        // 13:30+03:00 is 10:30 UTC; now is about 10:13 UTC: more than 5 min ahead - dropped
        assertNull(p.iobUnits)
        assertNull(p.iobAtMs)
        val nowLocal = LocalDateTime.ofEpochSecond(now / 1000 + 120, 0, ZoneOffset.ofHours(3)).toString()
        val near = pump(live.replace("2026-01-10T11:55:00", nowLocal))!!
        assertEquals("2 min ahead - kept, stamped now", now, near.iobAtMs)
        assertEquals(5.7, near.iobUnits!!, 1e-9)
    }

    @Test
    fun iobWithoutDatetimeTakesTheDataTime() {
        val noTime = """"lastMedicalDeviceDataUpdateServerTime": $dataAt, "activeInsulin": {"amount": 1.5}"""
        val p = pump(noTime)!!
        assertEquals(dataAt, p.iobAtMs)
        assertEquals(now, pump(""""activeInsulin": {"amount": 1.5}""")!!.iobAtMs)
        assertEquals(dataAt, pump(noTime.replace("1.5}", """1.5, "datetime": "not a time"}"""))!!.iobAtMs)
        val next = CareLinkPumpNow.merge(p, PumpNow(dataAtMs = dataAt + 600_000L, reservoirUnits = 99.0))!!
        assertEquals("the old number keeps its own time", dataAt, next.iobAtMs)
    }

    @Test
    fun olderSnapshotRefusedEqualTimeTaken() {
        val prev = PumpNow(dataAtMs = 100L, iobUnits = 2.3, iobAtMs = 100L, reservoirUnits = 100.0)
        val old = PumpNow(dataAtMs = 99L, iobUnits = 9.0, iobAtMs = 99L, reservoirUnits = 150.0)
        assertTrue(CareLinkPumpNow.older(prev, old))
        assertEquals(prev, CareLinkPumpNow.merge(prev, old))
        val same = old.copy(dataAtMs = 100L)
        assertFalse(CareLinkPumpNow.older(prev, same))
        assertEquals(150.0, CareLinkPumpNow.merge(prev, same)!!.reservoirUnits!!, 1e-9)
        assertFalse(CareLinkPumpNow.older(null, old))
        assertEquals(old, CareLinkPumpNow.merge(null, old))
    }

    @Test
    fun emptyAnswerReservoirAndBatteryMinusOne() {
        val r = root(""""reservoirRemainingUnits": -1, "pumpBatteryLevelPercent": -1, "pumpSuspended": true,
            "activeInsulin": {"amount": 3.0, "datetime": "2026-01-10T11:55:00"}""")
        assertTrue(CareLinkPumpNow.noPumpData(r))
        assertNull("no data: the stop flag is not believed either", CareLinkPumpNow.parse(r, now, phone))
    }

    @Test
    fun emptyAnswerZeroesAndIobMinusOneAsStrings() {
        val r = root(""""reservoirRemainingUnits": "0", "pumpBatteryLevelPercent": 0, "activeInsulin": {"amount": -1}""")
        assertTrue(CareLinkPumpNow.noPumpData(r))
        assertNull(CareLinkPumpNow.parse(r, now, phone))
    }

    @Test
    fun emptyReservoirWithLiveBatteryIsReal() {
        val r = root(""""reservoirRemainingUnits": 0, "pumpBatteryLevelPercent": 50""")
        assertFalse(CareLinkPumpNow.noPumpData(r))
        val p = CareLinkPumpNow.parse(r, now, phone)!!
        assertEquals(0.0, p.reservoirUnits!!, 1e-9)
        assertEquals(50, p.batteryPercent)
    }

    @Test
    fun noReservoirOrBatteryFieldsIsNotEmpty() {
        val r = root(""""activeInsulin": {"amount": 1.0, "datetime": "2026-01-10T11:55:00"}, "reservoirRemainingUnits": null""")
        assertFalse(CareLinkPumpNow.noPumpData(r))
        val p = CareLinkPumpNow.parse(r, now, phone)!!
        assertEquals(1.0, p.iobUnits!!, 1e-9)
        assertNull(p.reservoirUnits)
        assertNull(p.batteryPercent)
        assertFalse(CareLinkPumpNow.noPumpData(root("")))
        assertNull("no field at all - no snapshot", pump(""))
    }

    @Test
    fun iobMinusOneWithLiveFields() {
        val p = pump(live.replace("5.699999809", "-1"))!!
        assertNull(p.iobUnits)
        assertNull(p.iobAtMs)
        assertEquals(214.8, p.reservoirUnits!!, 1e-9)
    }

    @Test
    fun mergeKeepsTheLastIobWithItsTime() {
        val prev = PumpNow(1L, iobUnits = 2.3, iobAtMs = 10L, reservoirUnits = 100.0, reservoirPercent = 40,
            batteryPercent = 80, basalRate = 0.7, suspended = false)
        val new = PumpNow(2L, reservoirUnits = 99.5)
        val m = CareLinkPumpNow.merge(prev, new)!!
        assertEquals(2L, m.dataAtMs)
        assertEquals(2.3, m.iobUnits!!, 1e-9)
        assertEquals(10L, m.iobAtMs)
        assertEquals(99.5, m.reservoirUnits!!, 1e-9)
        assertEquals(40, m.reservoirPercent)
        assertEquals(80, m.batteryPercent)
        assertEquals(0.7, m.basalRate!!, 1e-9)
        assertEquals(false, m.suspended)
        val fresh = CareLinkPumpNow.merge(prev, new.copy(iobUnits = 1.1))!!
        assertEquals(1.1, fresh.iobUnits!!, 1e-9)
        assertNull(fresh.iobAtMs)
        assertEquals("empty answer - the last snapshot", prev, CareLinkPumpNow.merge(prev, null))
        assertNull(CareLinkPumpNow.merge(null, null))
    }

    @Test
    fun temporaryBasalAbsoluteAndPercent() {
        val abs = live.replace(
            """"basalRate": 0.7000000179}""",
            """"basalRate": 0.6000000238, "tempBasalRate": 0.6000000238, "tempBasalType": "ABSOLUTE", "tempBasalPercentage": 0},
               "pumpBannerState": [{"type": "TEMP_BASAL", "timeRemaining": 56}]""",
        )
        assertEquals(TempBasal(0.6, null, 56, absolute = true), pump(abs)!!.tempBasal)
        val pct = live.replace(
            """"basalRate": 0.7000000179}""",
            """"basalRate": 0.7, "tempBasalRate": 0, "tempBasalType": "PERCENTAGE", "tempBasalPercentage": "80"},
               "pumpBannerState": [{"type": "TEMP_BASAL", "timeRemaining": "595"}]""",
        )
        assertEquals(TempBasal(0.0, 80, 595), pump(pct)!!.tempBasal)
    }

    @Test
    fun bannerWithoutFieldsAndFieldsWithoutBanner() {
        val banner = live.replace("0.7000000179}", """0.7}, "pumpBannerState": [{"type": "DELIVERY_SUSPEND"}, {"type": "TEMP_BASAL", "timeRemaining": 30}]""")
        assertEquals(TempBasal(null, null, 30), pump(banner)!!.tempBasal)
        val fields = live.replace("0.7000000179}", """0.7, "tempBasalType": "ABSOLUTE", "tempBasalRate": 0.3}""")
        assertEquals(TempBasal(0.3, null, null, absolute = true), pump(fields)!!.tempBasal)
        val other = live.replace("0.7000000179}", """0.7}, "pumpBannerState": [{"type": "DELIVERY_SUSPEND"}]""")
        assertNull(pump(other)!!.tempBasal)
        val emptyType = live.replace("0.7000000179}", """0.7, "tempBasalType": "", "tempBasalPercentage": 50}""")
        assertNull("empty type and no banner - no temporary basal", pump(emptyType)!!.tempBasal)
        // critic 4d2, N4: the banner alone with 0 % - not a 0 % temporary basal (it would be a 0 U/h segment, a stop in
        // Nightscout); the basal rate stands. A stated percentage type with 0 % is believed.
        val bannerZero = live.replace(
            "0.7000000179}",
            """0.7, "tempBasalRate": 0, "tempBasalPercentage": 0}, "pumpBannerState": [{"type": "TEMP_BASAL", "timeRemaining": 30}]""",
        )
        val p = pump(bannerZero)!!
        assertEquals(TempBasal(0.0, null, 30), p.tempBasal)
        assertEquals(0.7, CareLinkBasalWriter.rateOf(p, 0.9)!!, 1e-9)
        val statedZero = live.replace(
            "0.7000000179}",
            """0.7, "tempBasalType": "PERCENTAGE", "tempBasalPercentage": 0}, "pumpBannerState": [{"type": "TEMP_BASAL", "timeRemaining": 30}]""",
        )
        assertEquals(0, pump(statedZero)!!.tempBasal!!.percent)
    }

    @Test
    fun stopByFlagOrBanner() {
        assertEquals(true, pump(live.replace("\"pumpSuspended\": false", "\"pumpSuspended\": true"))!!.suspended)
        assertEquals(true, pump(live.replace("\"pumpSuspended\": false", "\"pumpSuspended\": \"true\""))!!.suspended)
        val banner = live.replace("0.7000000179}", """0}, "pumpBannerState": [{"type": "DELIVERY_SUSPEND"}]""")
            .replace("\"pumpSuspended\": false,", "")
        val b = pump(banner)!!
        assertEquals(true, b.suspended)
        assertEquals(0.0, b.basalRate!!, 1e-9)
        assertNull("no flag, no banner - unknown", pump(live.replace("\"pumpSuspended\": false,", ""))!!.suspended)
    }

    @Test
    fun noBasalObjectNoBasalAndMergeKeepsTheTemporary() {
        // the phone was reconnecting to the pump: basal and banners gone - no data, not the end of a temporary basal
        val gone = live.replace(""",
"basal": {"activeBasalPattern": "BASAL1", "basalRate": 0.7000000179}""", "") + """, "pumpBannerState": [{"type": "TEMP_BASAL", "timeRemaining": 20}]"""
        val g = pump(gone)!!
        assertNull(g.basalRate)
        assertNull(g.tempBasal)
        assertEquals(214.8, g.reservoirUnits!!, 1e-9)
        val prev = PumpNow(1L, basalRate = 1.05, tempBasal = TempBasal(0.0, 80, 193))
        assertEquals(prev.tempBasal, CareLinkPumpNow.merge(prev, g.copy(dataAtMs = 2L))!!.tempBasal)
        assertEquals(1.05, CareLinkPumpNow.merge(prev, g.copy(dataAtMs = 2L))!!.basalRate!!, 1e-9)
        assertNull("with the basal object - what came", CareLinkPumpNow.merge(prev, PumpNow(3L, basalRate = 0.9))!!.tempBasal)
    }

    @Test
    fun basalObjectWithoutRateIsKnown() {
        // critic 4c, C4: the basal object came without basalRate (stop, temporary basal) - known, not a gap
        val noRate = live.replace("\"basalRate\": 0.7000000179}", "\"tempBasalType\": \"ABSOLUTE\", \"tempBasalRate\": 0.4}")
        val t = pump(noRate)!!
        assertNull(t.basalRate)
        assertTrue(t.basalKnown)
        assertEquals(TempBasal(0.4, null, null, absolute = true), t.tempBasal)
        val stop = live.replace("\"basalRate\": 0.7000000179}", "\"basalRate\": null}")
            .replace("\"pumpSuspended\": false", "\"pumpSuspended\": true")
        val st = pump(stop)!!
        assertTrue(st.basalKnown)
        assertEquals(true, st.suspended)
        assertNull(st.basalRate)
        val gone = live.replace(""",
"basal": {"activeBasalPattern": "BASAL1", "basalRate": 0.7000000179}""", "")
        assertFalse("no basal object - a gap", pump(gone)!!.basalKnown)
        // merge: with the basal object the temporary basal is what came, also with no rate
        val prev = PumpNow(1L, basalRate = 1.05, tempBasal = TempBasal(0.0, 80, 193))
        val m = CareLinkPumpNow.merge(prev, st.copy(dataAtMs = 2L))!!
        assertNull(m.tempBasal)
        assertEquals("the last rate stays for the window", 1.05, m.basalRate!!, 1e-9)
        assertTrue(m.basalKnown)
    }

    @Test
    fun emptyAnswerByIobMinusOneAndOneZeroField() {
        // critic 4c, N6: one of reservoir/battery missing, the other 0, active insulin -1 - still "no data"
        assertTrue(CareLinkPumpNow.noPumpData(root(""""reservoirRemainingUnits": 0, "activeInsulin": {"amount": -1}""")))
        assertTrue(CareLinkPumpNow.noPumpData(root(""""pumpBatteryLevelPercent": "0", "reservoirRemainingUnits": null, "activeInsulin": {"amount": "-1"}""")))
        assertNull(CareLinkPumpNow.parse(root(""""pumpBatteryLevelPercent": 0, "pumpSuspended": true, "activeInsulin": {"amount": -1}"""), now, phone))
        assertFalse("a live battery - a real empty reservoir",
            CareLinkPumpNow.noPumpData(root(""""reservoirRemainingUnits": 0, "pumpBatteryLevelPercent": 40, "activeInsulin": {"amount": -1}""")))
        assertFalse("no reservoir and no battery at all - not this rule",
            CareLinkPumpNow.noPumpData(root(""""activeInsulin": {"amount": -1}""")))
    }

    @Test
    fun utcClientDateTimeIsNoPumpZone() {
        // critic 4c, N7: "Z" or "+00:00" is not taken as the pump's zone - the phone zone is
        assertNull(CareLinkMarkers.offsetOf(json("""{"metadata": {"clientDateTime": "2026-01-10T12:00:00.000Z"}}""")))
        assertNull(CareLinkMarkers.offsetOf(json("""{"metadata": {"clientDateTime": "2026-01-10T12:00:00+00:00"}}""")))
        assertEquals(ZoneOffset.ofHours(3), CareLinkMarkers.offsetOf(json("""{"metadata": {"clientDateTime": "2026-01-10T12:00:00+03:00"}}""")))
    }

    @Test
    fun snapshotSurvivesTheStateStoreFormat() {
        val p = PumpNow(dataAt, 1.2, dataAt - 60_000L, 100.5, 40, 80, 0.75, TempBasal(0.0, 120, 30), true)
        assertEquals(p, PumpNow.fromJson(json(p.toJson().toString())))
        val abs = p.copy(tempBasal = TempBasal(null, null, 30, absolute = true))
        assertEquals("critic 4d, C2: the absolute mark survives", abs, PumpNow.fromJson(json(abs.toJson().toString())))
        val bare = PumpNow(dataAt)
        assertEquals(bare, PumpNow.fromJson(json(bare.toJson().toString())))
        val stop = PumpNow(dataAt, suspended = true, basalKnown = true)
        assertEquals(stop, PumpNow.fromJson(json(stop.toJson().toString())))
        // written by 4c (no basalKnown): known when a rate or a temporary basal is there
        assertTrue(PumpNow.fromJson(json("""{"dataAtMs": 5, "basalRate": 0.5}"""))!!.basalKnown)
        assertFalse(PumpNow.fromJson(json("""{"dataAtMs": 5}"""))!!.basalKnown)
        assertNull(PumpNow.fromJson(json("{}")))
        assertNull(PumpNow.fromJson(null))
    }
}
