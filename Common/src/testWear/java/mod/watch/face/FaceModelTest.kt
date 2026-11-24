package mod.watch.face

import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceLabels
import tk.glucodata.mod.watch.face.FaceModel
import tk.glucodata.mod.watch.face.FacePoint
import tk.glucodata.mod.watch.face.FaceReading
import tk.glucodata.mod.watch.face.FaceSnapshot
import tk.glucodata.mod.watch.face.FaceValues

/** JugglucoNGmod, watch block (part 3a): a snapshot turned into what the face draws. Made-up values. */
class FaceModelTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val zone = ZoneId.of("UTC")
    private val labels = FaceLabels(
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
        "%1\$d%%", "%1\$d min",
    )
    private val color = 0xFF33AA55.toInt()
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    /** A reading [ageMin] minutes old and history every minute before it, 0.1 lower per 5 minutes back. */
    private fun snapshot(ageMin: Int?, phone: Int? = 64, watch: Int? = 81, historyFrom: Int = ageMin ?: 0): FaceSnapshot {
        val current = ageMin?.let { FaceReading(now - it * minute, 6.4f, "6.4", 1.2f, color) }
        val history = (0..60).map { FacePoint(now - (historyFrom + it) * minute, 6.4f - it * 0.02f) }.reversed()
        return FaceSnapshot(now, isMmol = true, hour24 = true, graphHours = 3, current = current, history = history,
            graphPoints = history, low = 3.9f, high = 10f, phoneBatteryPercent = phone, watchBatteryPercent = watch)
    }

    @Test
    fun aFreshValueWithItsChangeArrowAndColour() {
        val model = FaceModel.build(snapshot(2), zone, labels)
        assertTrue(model.hasValue)
        assertEquals("6.4", model.valueText)
        assertEquals(color, model.valueColor)
        assertEquals(1.2f, model.rate)
        assertEquals("+0.1", model.deltaText)
        assertEquals(2, model.ageMinutes)
        assertEquals("2 min", model.ageText)
        assertFalse(model.ageAlarm)
        assertEquals(64, model.phoneBattery)
        assertEquals(81, model.watchBattery)
        assertEquals(3.9f, model.graph.low)
        assertEquals(10f, model.graph.high)
    }

    @Test
    fun aFifteenMinuteOldValueIsStillShownWithARedAge() {
        val model = FaceModel.build(snapshot(15), zone, labels)
        assertTrue(model.hasValue)
        assertEquals("6.4", model.valueText)
        assertTrue(model.ageAlarm)
        assertEquals("15 min", model.ageText)
    }

    @Test
    fun afterTwentyMinutesADashAndTheAgeButNoArrowChangeOrColour() {
        val model = FaceModel.build(snapshot(21), zone, labels)
        assertFalse(model.hasValue)
        assertEquals(FaceValues.NONE, model.valueText)
        assertNull(model.valueColor)
        assertTrue(model.rate.isNaN())
        assertNull(model.deltaText)
        assertEquals("21 min", model.ageText)
        assertTrue(model.ageAlarm)
    }

    @Test
    fun noCurrentValueTheAgeComesFromHistory() {
        val model = FaceModel.build(snapshot(null, historyFrom = 40), zone, labels)
        assertFalse(model.hasValue)
        assertEquals(FaceValues.NONE, model.valueText)
        assertEquals(40, model.ageMinutes)
    }

    @Test
    fun nothingAtAllNoAgeAndNoBatteries() {
        val empty = FaceSnapshot(now, true, true, 3, null, emptyList(), emptyList(), 3.9f, 10f, null, null)
        val model = FaceModel.build(empty, zone, labels)
        assertFalse(model.hasValue)
        assertNull(model.ageMinutes)
        assertNull(model.ageText)
        assertFalse(model.ageAlarm)
        assertNull(model.phoneBattery)
        assertNull(model.watchBattery)
        assertTrue(model.graph.points.isEmpty())
    }

    @Test
    fun aValueFromTheFutureIsShownAsFresh() {
        val ahead = snapshot(0).copy(current = FaceReading(now + 3 * minute, 6.4f, "6.4", 0f, color))
        val model = FaceModel.build(ahead, zone, labels)
        assertTrue(model.hasValue)
        assertEquals(0, model.ageMinutes)
        assertFalse(model.ageAlarm)
    }

    @Test
    fun aBrokenValueIsNotShown() {
        val broken = snapshot(2).copy(current = FaceReading(now - 2 * minute, Float.NaN, "", 0f, color))
        assertFalse(FaceModel.build(broken, zone, labels).hasValue)
    }

    @Test
    fun aCalibratedValueChangesOnlyByItsUncalibratedLane() {
        // calibration lifts the sensor's 6.0 to 6.8 on the screen; history (never calibrated) is a flat 6.0
        val flat = (2..60).map { FacePoint(now - it * minute, 6.0f) }.reversed()
        val calibrated = FaceReading(now - 2 * minute, 6.8f, "6.8", 0f, color, deltaValue = 6.0f)
        val snapshot = snapshot(2).copy(current = calibrated, history = flat, graphPoints = flat)
        val model = FaceModel.build(snapshot, zone, labels)
        assertEquals("6.8", model.valueText)
        assertEquals("0.0", model.deltaText)
        // without the lane value it would read as a rise that is not there
        val naive = snapshot.copy(current = calibrated.copy(deltaValue = 6.8f))
        assertEquals("+0.8", FaceModel.build(naive, zone, labels).deltaText)
    }

    @Test
    fun theAgeIsOfTheShownValueNotOfANewerPointOfHistory() {
        // a live value 15 minutes old next to history that already has a reading 2 minutes old
        val snapshot = snapshot(15, historyFrom = 2)
        val model = FaceModel.build(snapshot, zone, labels)
        assertTrue(model.hasValue)
        assertEquals(15, model.ageMinutes)
        assertEquals("15 min", model.ageText)
        assertTrue(model.ageAlarm)
    }

    @Test
    fun aBatteryOutside0To100IsUnknown() {
        val model = FaceModel.build(snapshot(2, phone = 101, watch = -1), zone, labels)
        assertNull(model.phoneBattery)
        assertNull(model.watchBattery)
        assertEquals(0, FaceModel.build(snapshot(2, phone = 0), zone, labels).phoneBattery)
    }

    private val forecast = (5..60 step 5).map { FacePoint(now + it * minute, 6.4f - it * 0.03f) }

    @Test
    fun theForecastOnlyFromAValueOnTheScreenThatIsNotOld() {
        val fresh = FaceModel.build(snapshot(2).copy(forecast = forecast), zone, labels)
        assertTrue(fresh.hasForecast)
        assertEquals(now + 45 * minute, fresh.graph.toMs)
        for (age in listOf(11, 21)) {
            val old = FaceModel.build(snapshot(age).copy(forecast = forecast), zone, labels)
            assertFalse("age $age", old.hasForecast)
            assertEquals(now, old.graph.toMs)
        }
        val noValue = FaceModel.build(snapshot(null).copy(forecast = forecast), zone, labels)
        assertFalse(noValue.hasForecast)
        // their setting off: the source gives none
        assertFalse(FaceModel.build(snapshot(2), zone, labels).hasForecast)
    }

    @Test
    fun clockAndDate() {
        val model = FaceModel.build(snapshot(2), zone, labels)
        // 1_800_000_000_000 ms is Fri 15 Jan 2027 08:00 UTC
        assertEquals("08:00", model.clock.text)
        assertEquals("15 Jan", model.dateText)
    }
}
