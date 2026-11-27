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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.mod.carelink.CareLinkConfig
import tk.glucodata.mod.carelink.CareLinkPumpNow
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.TempBasal
import java.io.File

/**
 * JugglucoNGmod, CareLink 4c: what stays of the cloud answer's parsing tests once the cloud is gone - the bolus dose
 * rules of [ClEvent], the order and merging of [PumpNow] snapshots and the stored snapshot format, now on direct
 * values. Robolectric: Android's own org.json, as on the phone (JSON-java of plain JUnit differs on JSON null).
 * Every value is made up (round doses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkParseTest {

    private val dataAt = 1_768_035_600_000L

    private fun json(text: String): JSONObject = CareLinkConfig.parse(text)!!

    private fun bolus(programmed: Double?, delivered: Double?, completed: Boolean?, type: String? = null) =
        ClEvent(ClKind.BOLUS, "INSULIN|2026-01-10T08:00:03|0", dataAt, programmed, delivered, completed, type)

    // ---------------------------------------------------------------- ClEvent

    @Test
    fun doseAsStringOrNumberRunningIsProgrammedInterruptedIsDelivered() {
        val done = bolus(4.0, 4.0, true, ClEvent.BOLUS_FAST)
        assertEquals(4.0, done.dose!!, 1e-9)
        assertFalse(done.interrupted)
        assertFalse(done.extended)
        val running = bolus(1.0, 0.5, false)
        assertEquals("running - programmed", 1.0, running.dose!!, 1e-9)
        assertFalse("still running - not interrupted", running.interrupted)
        val stopped = bolus(3.0, 1.5, true)
        assertTrue(stopped.interrupted)
        assertEquals(1.5, stopped.dose!!, 1e-9)
        assertFalse("closer than the pump step - not interrupted", bolus(1.0, 0.998, true).interrupted)
        val noDelivered = bolus(2.5, null, null)
        assertEquals("no delivered yet - programmed", 2.5, noDelivered.dose!!, 1e-9)
        assertFalse("no type - unknown, not extended", noDelivered.extended)
        assertNull(bolus(null, null, null).dose)
        val dual = bolus(3.5, 2.5, false, "DUAL_WAVE")
        assertTrue(dual.extended)
        assertEquals("running - programmed sum", 3.5, dual.dose!!, 1e-9)
    }

    // ---------------------------------------------------------------- CareLinkPumpNow

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
    fun mergeKeepsTheLastIobWithItsTime() {
        val prev = PumpNow(1L, iobUnits = 2.3, iobAtMs = 10L, reservoirUnits = 100.0, batteryPercent = 80,
            basalRate = 0.7, suspended = false)
        val new = PumpNow(2L, reservoirUnits = 99.5)
        val m = CareLinkPumpNow.merge(prev, new)!!
        assertEquals(2L, m.dataAtMs)
        assertEquals(2.3, m.iobUnits!!, 1e-9)
        assertEquals(10L, m.iobAtMs)
        assertEquals(99.5, m.reservoirUnits!!, 1e-9)
        assertEquals(80, m.batteryPercent)
        assertEquals(0.7, m.basalRate!!, 1e-9)
        assertEquals(false, m.suspended)
        val fresh = CareLinkPumpNow.merge(prev, new.copy(iobUnits = 1.1))!!
        assertEquals(1.1, fresh.iobUnits!!, 1e-9)
        assertNull(fresh.iobAtMs)
        assertEquals("no new snapshot - the last one", prev, CareLinkPumpNow.merge(prev, null))
        assertNull(CareLinkPumpNow.merge(null, null))
    }

    @Test
    fun noBasalObjectNoBasalAndMergeKeepsTheTemporary() {
        // nothing known about the basal: a gap - not the end of a temporary basal
        val gap = PumpNow(2L, iobUnits = 5.7, reservoirUnits = 214.8, basalKnown = false)
        val prev = PumpNow(1L, basalRate = 1.05, tempBasal = TempBasal(0.0, 80, 193))
        assertEquals(prev.tempBasal, CareLinkPumpNow.merge(prev, gap)!!.tempBasal)
        assertEquals(1.05, CareLinkPumpNow.merge(prev, gap)!!.basalRate!!, 1e-9)
        assertTrue(CareLinkPumpNow.merge(prev, gap)!!.basalKnown)
        assertNull("with the basal known - what came", CareLinkPumpNow.merge(prev, PumpNow(3L, basalRate = 0.9))!!.tempBasal)
        // critic 4c, C4: the basal known without a rate (a stop) - the temporary basal is what came, the last rate stays
        val stop = PumpNow(2L, suspended = true, basalKnown = true)
        val m = CareLinkPumpNow.merge(prev, stop)!!
        assertNull(m.tempBasal)
        assertEquals("the last rate stays for the window", 1.05, m.basalRate!!, 1e-9)
        assertTrue(m.basalKnown)
        assertEquals(true, m.suspended)
    }

    @Test
    fun snapshotSurvivesTheStateStoreFormat() {
        val p = PumpNow(dataAt, 1.2, dataAt - 60_000L, 100.5, 80, 0.75, TempBasal(0.0, 120, 30), true)
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

    @Test
    fun anOldReservoirPercentInPumpJsonIsReadSilently() {
        // the cloud's reservoir percentage is gone (no such number over Bluetooth): a pump.json written before still
        // carries it - the snapshot reads as if it was not there, and the next write drops it
        val app: Application = RuntimeEnvironment.getApplication()
        val file = File(File(app.noBackupFilesDir, CareLinkStateStore.DIR), CareLinkStateStore.PUMP_FILE)
        file.parentFile!!.mkdirs()
        file.writeText(
            """{"dataAtMs": $dataAt, "reservoirUnits": 100.5, "reservoirPercent": 72, "batteryPercent": 80,
               "basalRate": 0.75, "basalKnown": true}""",
            Charsets.UTF_8,
        )
        val store = CareLinkStateStore(app)
        val s = store.read()
        assertEquals(PumpNow(dataAt, reservoirUnits = 100.5, batteryPercent = 80, basalRate = 0.75), s.pumpNow)
        store.write(s)
        assertFalse(file.readText(Charsets.UTF_8).contains("reservoirPercent"))
        assertEquals(s.pumpNow, store.read().pumpNow)
        assertFalse(PumpNow(dataAt, reservoirUnits = 1.0).toJson().has("reservoirPercent"))
    }
}
