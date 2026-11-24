package mod.watch.face

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.mod.watch.face.FacePoint
import tk.glucodata.mod.watch.face.FaceReading
import tk.glucodata.mod.watch.face.FaceValues

/** JugglucoNGmod, watch block (part 3a): the face's change and age rules. Made-up values. */
class FaceValuesTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    private fun reading(value: Float, at: Long = now) = FaceReading(at, value, value.toString(), 0f, 0)

    // ---- change against the earlier reading ----

    @Test
    fun aRiseInMmolHasAPlusSignAndTheLocaleComma() {
        val previous = FacePoint(now - 5 * minute, 6.1f)
        assertEquals("+0,3", FaceValues.deltaText(reading(6.4f), previous, isMmol = true))
        Locale.setDefault(Locale.US)
        assertEquals("+0.3", FaceValues.deltaText(reading(6.4f), previous, isMmol = true))
    }

    @Test
    fun aFallInMgdlIsAWholeNumberWithARealMinus() {
        val previous = FacePoint(now - 5 * minute, 126f)
        assertEquals("−6", FaceValues.deltaText(reading(120f), previous, isMmol = false))
    }

    @Test
    fun noChangeHasNoSign() {
        assertEquals("0", FaceValues.deltaText(reading(110f), FacePoint(now - 5 * minute, 110f), isMmol = false))
        assertEquals("0,0", FaceValues.deltaText(reading(5.5f), FacePoint(now - 5 * minute, 5.5f), isMmol = true))
    }

    @Test
    fun noEarlierReadingMeansNoChange() {
        assertNull(FaceValues.deltaText(reading(6.4f), null, isMmol = true))
        assertNull(FaceValues.deltaText(null, FacePoint(now - 5 * minute, 6.1f), isMmol = true))
        assertNull(FaceValues.previousFor(emptyList(), now))
    }

    @Test
    fun theEarlierReadingIsTheFirstOneFiveMinutesBackAsUpstreamWalks() {
        val history = (1..8).map { FacePoint(now - it * minute, 5.0f + it * 0.1f) }
        // 4 minutes back is closer than upstream's 4.5-minute gap; 5 minutes back is the one
        assertEquals(FacePoint(now - 5 * minute, 5.5f), FaceValues.previousFor(history, now))
        // sentinel values are skipped
        val withHole = listOf(FacePoint(now - 6 * minute, 5.2f), FacePoint(now - 5 * minute, 0f))
        assertEquals(FacePoint(now - 6 * minute, 5.2f), FaceValues.previousFor(withHole, now))
    }

    @Test
    fun aPairTooFarApartGivesNoChange() {
        // upstream's GlucoseDelta: further than 20 minutes says nothing about the current change
        assertNull(FaceValues.deltaText(reading(6.4f), FacePoint(now - 25 * minute, 6.1f), isMmol = true))
    }

    // ---- age ----

    @Test
    fun ageInWholeMinutesTurnsRedAfterTenAndGivesWayAfterTwenty() {
        val cases = listOf(0 to (false to false), 9 to (false to false), 10 to (false to false),
            11 to (true to false), 20 to (true to false), 21 to (true to true))
        for ((minutes, expected) in cases) {
            val age = FaceValues.ageMinutes(now, now - minutes * minute)
            assertEquals(minutes, age)
            assertEquals("alarm at $minutes", expected.first, FaceValues.isAgeAlarm(age))
            assertEquals("no value at $minutes", expected.second, FaceValues.isNoValue(age))
        }
        // 10 min 59 s is still "10 min", not red; 20 min 59 s still has its value
        assertFalse(FaceValues.isAgeAlarm(FaceValues.ageMinutes(now, now - 10 * minute - 59_000L)))
        assertFalse(FaceValues.isNoValue(FaceValues.ageMinutes(now, now - 20 * minute - 59_000L)))
    }

    @Test
    fun theChangeUsesTheLaneValueNotTheShownOne() {
        val history = listOf(FacePoint(now - 5 * minute, 6.0f))
        val calibrated = FaceReading(now, 6.8f, "6.8", 0f, 0, deltaValue = 6.0f)
        assertEquals("0,0", FaceValues.deltaText(calibrated, FaceValues.previousFor(history, now), true))
        assertNull(FaceValues.deltaText(calibrated.copy(deltaValue = Float.NaN), history[0], true))
    }

    @Test
    fun aLiveValueMoreThanAMinuteBehindHistoryIsLeftBehind() {
        val live = now - 15 * minute
        assertTrue(FaceValues.liveIsBehind(live, now - 2 * minute))
        assertFalse(FaceValues.liveIsBehind(live, live + minute))
        assertFalse(FaceValues.liveIsBehind(live, live - 5 * minute))
        assertFalse(FaceValues.liveIsBehind(live, null))
    }

    @Test
    fun aReadingFromTheFutureCountsAsFresh() {
        assertEquals(0, FaceValues.ageMinutes(now, now + 3 * minute))
        assertEquals(0, FaceValues.ageMinutes(now, Long.MAX_VALUE))
        assertFalse(FaceValues.isAgeAlarm(FaceValues.ageMinutes(now, now + 3 * minute)))
    }

    @Test
    fun theSourceAsksForEveryReadingShownAsTwentyMinutes() {
        assertTrue(FaceValues.SOURCE_MAX_AGE_MS > 20 * minute + 59_000L)
        assertTrue(FaceValues.SOURCE_MAX_AGE_MS > tk.glucodata.Notify.glucosetimeout)
    }

    // ---- small helpers ----

    @Test
    fun lastReadingTimeTakesTheNewerOfCurrentAndHistory() {
        val history = listOf(FacePoint(now - 40 * minute, 6f), FacePoint(now - 30 * minute, 0f))
        assertEquals(now - 40 * minute, FaceValues.lastReadingTime(null, history))
        assertEquals(now - 2 * minute, FaceValues.lastReadingTime(reading(6f, now - 2 * minute), history))
        assertNull(FaceValues.lastReadingTime(null, emptyList()))
    }

    @Test
    fun thresholdsFallBackWhenUnsetOrBroken() {
        assertEquals(3.9f, FaceValues.threshold(null, 3.9f))
        assertEquals(3.9f, FaceValues.threshold(0f, 3.9f))
        assertEquals(3.9f, FaceValues.threshold(Float.NaN, 3.9f))
        assertEquals(4.4f, FaceValues.threshold(4.4f, 3.9f))
    }

    @Test
    fun theRawLaneOnlyForRawViewModes() {
        assertEquals(6.0f, FaceValues.laneValue(6.0f, 5.0f, 0))
        assertEquals(5.0f, FaceValues.laneValue(6.0f, 5.0f, 1))
        assertEquals(5.0f, FaceValues.laneValue(6.0f, 5.0f, 3))
        assertEquals(6.0f, FaceValues.laneValue(6.0f, 0f, 1))
    }
}
