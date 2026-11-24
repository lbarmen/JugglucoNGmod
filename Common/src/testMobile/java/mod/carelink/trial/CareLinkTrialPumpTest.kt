package mod.carelink.trial

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import tk.glucodata.mod.carelink.trial.CareLinkTrial
import tk.glucodata.mod.carelink.trial.CareLinkTrialPump
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * JugglucoNGmod, CareLink trial (part 4p): the made-up pump's answer goes through the real parsing of 4c and gives
 * the events and the snapshot the step meant - here "the trial lies differently from the cloud" is caught. Robolectric:
 * Android's own org.json, as on the phone. Every value is made up (round doses, an invented time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkTrialPumpTest {

    private val zone: ZoneId = ZoneOffset.ofHours(5)
    private val start = 1_800_000_000_000L
    private var now = start
    private val pump get() = CareLinkTrial.pump

    @Before
    fun setUp() {
        CareLinkTrial.resetForTest()
    }

    private fun step(name: String, vararg args: Double): String = CareLinkTrial.step(name, args.toList(), now)

    private fun answer(shape: CareLinkTrialPump.Shape = CareLinkTrialPump.Shape.NORMAL, olderThan: Long? = null) =
        CareLinkConfig.parse(pump.answer(now, zone, shape, olderThan).toString())!!

    private fun events(): List<ClEvent> = CareLinkMarkers.parse(answer(), ZoneOffset.UTC)

    private fun pumpNow(shape: CareLinkTrialPump.Shape = CareLinkTrialPump.Shape.NORMAL): PumpNow? =
        CareLinkPumpNow.parse(answer(shape), now, ZoneOffset.UTC)

    private fun boluses() = events().filter { it.kind == ClKind.BOLUS }

    @Test
    fun answerLooksLikePumpDataWithAPumpZone() {
        val root = answer()
        assertTrue(CareLinkData.looksValid(root))
        assertEquals(now - CareLinkTrialPump.DATA_LAG_MS, CareLinkData.pumpDataAt(root))
        assertEquals(ZoneOffset.ofHours(5), CareLinkMarkers.offsetOf(root))
    }

    @Test
    fun normalBolusAtItsTime() {
        assertEquals("step: bolus 2.0 U", step("bolus"))
        val b = boluses().single()
        assertEquals("the pump offset gives the moment back, whatever the phone zone", now, b.atMs)
        assertEquals(2.0, b.dose!!, 1e-9)
        assertEquals(true, b.completed)
        assertEquals(ClEvent.BOLUS_FAST, b.bolusType)
        assertFalse(b.extended)
        assertFalse(b.interrupted)
    }

    @Test
    fun runningBolusThenDoneKeepsItsKey() {
        step("bolus-run", 3.0)
        val running = boluses().single()
        assertEquals(false, running.completed)
        assertEquals("running - the programmed dose", 3.0, running.dose!!, 1e-9)
        assertEquals(1.5, running.deliveredUnits!!, 1e-9)
        now += 90_000L
        step("bolus-done")
        val done = boluses().single()
        assertEquals(running.key, done.key)
        assertEquals(true, done.completed)
        assertEquals(3.0, done.dose!!, 1e-9)
        assertEquals("step bolus-done: no running bolus", step("bolus-done"))
    }

    @Test
    fun cancelledAndInterruptedBoluses() {
        step("bolus-cancel", 1.5)
        now += 60_000L
        step("bolus-part", 2.0)
        val (cancelled, part) = boluses()
        assertEquals(true, cancelled.completed)
        assertEquals("cancelled - nothing delivered", 0.0, cancelled.dose!!, 1e-9)
        assertEquals(1.5, cancelled.programmedUnits!!, 1e-9)
        assertTrue(part.interrupted)
        assertEquals(1.0, part.dose!!, 1e-9)
    }

    @Test
    fun squareAndDualWave() {
        step("square", 2.0, 60.0)
        now += 60_000L
        step("dual", 1.0, 1.5, 30.0)
        val (square, dual) = boluses()
        assertEquals("SQUARE", square.bolusType)
        assertTrue(square.extended)
        assertEquals(2.0, square.dose!!, 1e-9)
        assertEquals("DUAL_WAVE", dual.bolusType)
        assertTrue(dual.extended)
        assertEquals("fast and extended parts summed", 2.5, dual.dose!!, 1e-9)
    }

    @Test
    fun calculatorMealPairsWithItsBolus() {
        step("meal", 45.0, 4.5)
        val e = events()
        val meal = e.single { it.kind == ClKind.MEAL }
        val bolus = e.single { it.kind == ClKind.BOLUS }
        assertEquals(45.0, meal.grams!!, 1e-9)
        assertEquals(4.5, bolus.dose!!, 1e-9)
        assertTrue("carbs a moment before the bolus, inside the writer's window",
            bolus.atMs - meal.atMs in 1..10_000L)
    }

    @Test
    fun snapshotNormalBasalReservoirBatteryIob() {
        step("bolus", 2.0)
        now += 60 * 60_000L
        val p = pumpNow()!!
        assertEquals(now - CareLinkTrialPump.DATA_LAG_MS, p.dataAtMs)
        assertEquals(0.8, p.basalRate!!, 1e-9)
        assertNull(p.tempBasal)
        assertEquals(false, p.suspended)
        assertTrue(p.basalKnown)
        assertEquals(178.0, p.reservoirUnits!!, 1e-9)
        assertEquals(75, p.batteryPercent)
        assertEquals("2 U, 59 of 240 min gone", 1.5, p.iobUnits!!, 0.06)
        assertNotNull(p.iobAtMs)
    }

    @Test
    fun temporaryBasalAbsoluteAndPercentThenEnd() {
        step("temp", 1.2, 60.0)
        val abs = pumpNow()!!
        assertEquals(1.2, abs.tempBasal!!.rate!!, 1e-9)
        assertNull(abs.tempBasal!!.percent)
        assertEquals(60, abs.tempBasal!!.minutesLeft)
        assertEquals(1.2, CareLinkBasalWriter.rateOf(abs, 0.8)!!, 1e-9)
        step("temp-pct", 50.0, 30.0)
        val pct = pumpNow()!!
        assertEquals(50, pct.tempBasal!!.percent)
        assertEquals("half of the last normal rate", 0.4, CareLinkBasalWriter.rateOf(pct, 0.8)!!, 1e-9)
        now += 31 * 60_000L
        assertNull("a temp basal ends by itself", pumpNow()!!.tempBasal)
        step("temp", 2.0, 60.0)
        step("temp-end")
        assertNull(pumpNow()!!.tempBasal)
    }

    @Test
    fun stopResumeAndANewNormalRate() {
        step("suspend")
        val stopped = pumpNow()!!
        assertEquals(true, stopped.suspended)
        assertEquals(0.0, CareLinkBasalWriter.rateOf(stopped, 0.8)!!, 1e-9)
        step("resume")
        step("profile", 1.0)
        val after = pumpNow()!!
        assertEquals(false, after.suspended)
        assertEquals(1.0, after.basalRate!!, 1e-9)
    }

    @Test
    fun emptyAnswerIsNoDataButKeepsTheHistory() {
        step("bolus", 2.0)
        val root = answer(CareLinkTrialPump.Shape.EMPTY)
        assertTrue(CareLinkPumpNow.noPumpData(root))
        assertNull(pumpNow(CareLinkTrialPump.Shape.EMPTY))
        assertEquals(1, CareLinkMarkers.parse(root, zone).count { it.kind == ClKind.BOLUS })
    }

    @Test
    fun oldAnswerIsOlderThanTheLastSnapshot() {
        val last = pumpNow()!!
        now += 5 * 60_000L
        val old = CareLinkPumpNow.parse(answer(CareLinkTrialPump.Shape.OLD, last.dataAtMs), now, zone)!!
        assertTrue(CareLinkPumpNow.older(last, old))
    }

    @Test
    fun onlyTheLastDayAndNothingFromTheFuture() {
        step("bolus", 1.0)
        now += 26 * 3_600_000L
        step("bolus", 2.0)
        assertEquals(listOf(2.0), boluses().map { it.dose })
        now -= 60 * 60_000L
        assertTrue("a step after the clock went back is not answered yet", boluses().isEmpty())
    }

    @Test
    fun wrongNumbersAreRefused() {
        assertTrue(step("bolus", 40.0).startsWith("error:"))
        assertTrue(step("temp", 1.0, 0.0).startsWith("error:"))
        assertTrue(pump.boluses.isEmpty())
        assertNull(pump.temp)
    }
}
