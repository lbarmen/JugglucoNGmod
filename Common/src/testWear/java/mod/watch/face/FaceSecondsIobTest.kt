package mod.watch.face

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.watch.face.FaceLabels
import tk.glucodata.mod.watch.face.FaceModel
import tk.glucodata.mod.watch.face.FacePoint
import tk.glucodata.mod.watch.face.FacePumpIob
import tk.glucodata.mod.watch.face.FaceReading
import tk.glucodata.mod.watch.face.FaceSnapshot
import tk.glucodata.mod.watch.face.FaceText
import tk.glucodata.mod.watch.face.FaceValues
import tk.glucodata.mod.watch.face.FaceValues.IobState
import tk.glucodata.mod.watch.face.ModFaceRenderer

/**
 * JugglucoNGmod, watch face (20.11, "1a with fixes"): the age in seconds under two minutes and in hours from 100
 * minutes, the frame rate that follows it, the pump's active insulin (format, freshness, only the pump's number) and
 * the dim mode's one read a minute. English and Russian labels from our resources. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FaceSecondsIobTest {

    private val now = 1_800_000_000_000L
    private val second = 1_000L
    private val minute = 60_000L
    private val zone = ZoneId.of("UTC")
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    private fun labels() = FaceLabels.from(ApplicationProvider.getApplicationContext())

    // ------------------------------------------------------------------ age

    @Test
    @Config(qualifiers = "ru")
    fun theAgeInSecondsMinutesAndHoursInRussian() {
        val l = labels()
        assertEquals("0 с", FaceText.ageOf(0L, true, l))
        assertEquals("59 с", FaceText.ageOf(59 * second + 999, true, l))
        assertEquals("119 с", FaceText.ageOf(119 * second + 999, true, l))
        assertEquals("2 мин", FaceText.ageOf(120 * second, true, l))
        assertEquals("99 мин", FaceText.ageOf(99 * minute + 59 * second, true, l))
        assertEquals("1 ч", FaceText.ageOf(100 * minute, true, l))
        assertEquals("1 ч", FaceText.ageOf(119 * minute, true, l))
        assertEquals("2 ч", FaceText.ageOf(120 * minute, true, l))
        // the dim mode: minutes only
        assertEquals("0 мин", FaceText.ageOf(45 * second, false, l))
        assertEquals("1 мин", FaceText.ageOf(119 * second, false, l))
        // from the future: 0; no reading: none
        assertEquals("0 с", FaceText.ageOf(-5 * second, true, l))
        assertNull(FaceText.ageOf(null, true, l))
    }

    @Test
    @Config(qualifiers = "en")
    fun theAgeInEnglish() {
        val l = labels()
        assertEquals("45 s", FaceText.ageOf(45 * second, true, l))
        assertEquals("12 min", FaceText.ageOf(12 * minute, true, l))
        assertEquals("1 h", FaceText.ageOf(100 * minute, true, l))
        assertEquals("1 h", FaceText.age(100, l))
        assertEquals("99 min", FaceText.age(99, l))
    }

    @Test
    fun aFrameASecondOnlyWhileTheLitAgeIsInSeconds() {
        assertEquals(1_000L, FaceValues.frameDelayMs(0L, ambient = false))
        assertEquals(1_000L, FaceValues.frameDelayMs(119 * second + 999, ambient = false))
        assertEquals(60_000L, FaceValues.frameDelayMs(120 * second, ambient = false))
        assertEquals(60_000L, FaceValues.frameDelayMs(30 * minute, ambient = false))
        assertEquals(60_000L, FaceValues.frameDelayMs(null, ambient = false))
        // the dim mode: a minute whatever the age
        assertEquals(60_000L, FaceValues.frameDelayMs(5 * second, ambient = true))
        // a reading from the future is fresh
        assertEquals(1_000L, FaceValues.frameDelayMs(-3 * second, ambient = false))
    }

    @Test
    fun theModelWritesSecondsWhenLitAndMinutesWhenDim() {
        val l = FaceLabels(List(7) { "d" }, List(12) { "m" }, "%1\$d%%", "%1\$d min")
        val snap = snapshot(ageMs = 45 * second)
        assertEquals("45 s", FaceModel.build(snap, zone, l).ageText)
        assertEquals(45 * second, FaceModel.build(snap, zone, l).ageMs)
        assertEquals("0 min", FaceModel.build(snap, zone, l, ambient = true).ageText)
        // red after 10 minutes and "-" after 20 as before
        assertTrue(FaceModel.build(snapshot(ageMs = 11 * minute), zone, l).ageAlarm)
        assertFalse(FaceModel.build(snapshot(ageMs = 21 * minute), zone, l).hasValue)
        assertEquals("1 h", FaceModel.build(snapshot(ageMs = 100 * minute), zone, l).ageText)
    }

    // ------------------------------------------------------------------ active insulin

    @Test
    @Config(qualifiers = "ru")
    fun theActiveInsulinAlwaysWithOneDecimal() {
        val l = labels()
        val ru = Locale.forLanguageTag("ru-RU")
        assertEquals("0,0 ЕД", FaceText.iob(0, l, ru))
        assertEquals("2,3 ЕД", FaceText.iob(23, l, ru))
        assertEquals("12,5 ЕД", FaceText.iob(125, l, ru))
        assertEquals("99,9 ЕД", FaceText.iob(999, l, ru))
        assertEquals("99+ ЕД", FaceText.iob(1000, l, ru))
        assertEquals("99+ ЕД", FaceText.iob(12_345, l, ru))
        assertEquals("— ЕД", FaceText.iob(null, l, ru))
    }

    @Test
    @Config(qualifiers = "en")
    fun theActiveInsulinInEnglish() {
        val l = labels()
        assertEquals("3.0 U", FaceText.iob(30, l, Locale.US))
        assertEquals("— U", FaceText.iob(null, l, Locale.US))
    }

    @Test
    fun freshUnder15MinutesStaleTo30ThenUnknown() {
        assertEquals(IobState.FRESH, FaceValues.iobState(0L))
        assertEquals(IobState.FRESH, FaceValues.iobState(15 * minute - 1))
        assertEquals(IobState.STALE, FaceValues.iobState(15 * minute))
        assertEquals(IobState.STALE, FaceValues.iobState(30 * minute - 1))
        assertEquals(IobState.UNKNOWN, FaceValues.iobState(30 * minute))
        assertEquals(IobState.UNKNOWN, FaceValues.iobState(6 * 60 * minute))
        // a few seconds ahead (delivery) fresh; far ahead (the watch clock set back) unknown
        assertEquals(IobState.FRESH, FaceValues.iobState(-5 * second))
        assertEquals(IobState.UNKNOWN, FaceValues.iobState(-2 * minute))
    }

    @Test
    fun theModelsActiveInsulinRowByItsAge() {
        val l = FaceLabels(List(7) { "d" }, List(12) { "m" }, "%1\$d%%", "%1\$d min")
        Locale.setDefault(Locale.US)
        fun model(iobAgeMs: Long?, tenths: Int = 23) =
            FaceModel.build(snapshot(45 * second, iobAgeMs?.let { FacePumpIob(tenths, now - it) }), zone, l)
        // none: no third row
        assertNull(model(null).iobText)
        assertNull(model(null).iobState)
        assertEquals("2.3 U", model(2 * minute).iobText)
        assertEquals(IobState.FRESH, model(2 * minute).iobState)
        assertEquals("2.3 U", model(20 * minute).iobText)
        assertEquals(IobState.STALE, model(20 * minute).iobState)
        assertEquals("— U", model(31 * minute).iobText)
        assertEquals(IobState.UNKNOWN, model(31 * minute).iobState)
        assertEquals("0.0 U", model(minute, tenths = 0).iobText)
        assertEquals("99+ U", model(minute, tenths = 1000).iobText)
        // the number is never re-computed with time
        assertEquals("2.3 U", model(14 * minute).iobText)
    }

    @Test
    fun theFaceTakesOnlyThePumpsNumberNeverTheJournal() {
        // the face's code reads the active insulin only from our store; upstream's journal computation (on the watch
        // WearJournalIob, on the phone JournalIobAccess) is never a stand-in
        val dir = listOf(File("src/wear/java/tk/glucodata/mod/watch"), File("Common/src/wear/java/tk/glucodata/mod/watch"))
            .first { it.isDirectory }
        val files = dir.resolve("face").listFiles()!!.toList() + dir.resolve("PumpIobStore.kt")
        for (f in files.filter { it.name.endsWith(".kt") }) {
            val code = f.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
            for (name in listOf("WearJournalIob", "JournalIobAccess", "RemoteIobSnapshot", "ApiIobSnapshot",
                "CloneIobSnapshot")) {
                assertFalse("${f.name} uses $name", code.contains(name))
            }
        }
    }

    // ------------------------------------------------------------------ the dim mode reads once a minute

    @Test
    fun aRequestInTheDimModeNeverReads() {
        assertFalse(ModFaceRenderer.requestReads(ambient = true))
        assertTrue(ModFaceRenderer.requestReads(ambient = false))
    }

    @Test
    fun aDimFrameReadsWhatArrivedOnceAMinute() {
        val none = ModFaceRenderer.NO_MINUTE
        val m = 29_000_000L
        // no snapshot yet: read
        assertTrue(ModFaceRenderer.ambientReadDue(false, false, false, none, m))
        // nothing new: no read
        assertFalse(ModFaceRenderer.ambientReadDue(true, false, false, none, m))
        // something arrived: read in this frame
        assertTrue(ModFaceRenderer.ambientReadDue(true, true, false, none, m))
        assertTrue(ModFaceRenderer.ambientReadDue(true, false, true, none, m))
        // an extra dim frame in the same minute: waits for the next minute
        assertFalse(ModFaceRenderer.ambientReadDue(true, true, false, m, m))
        assertTrue(ModFaceRenderer.ambientReadDue(true, true, false, m, m + 1))
    }

    @Test
    fun aReadWhenEnteringTheDimModeDoesNotHoldBackTheNextMinutesRead() {
        // critic 9, L1: the frame that entered the dim mode at :50 read; a value came a moment later; the minute tick
        // 10 s later reads it (by the old 50 s rule it waited for the tick after, up to ~110 s)
        val enterMs = 29_000_000L * 60_000L + 50_000L
        val tickMs = enterMs + 10_000L
        val entered = Math.floorDiv(enterMs, 60_000L)
        assertTrue(ModFaceRenderer.ambientReadDue(true, true, false, entered, Math.floorDiv(tickMs, 60_000L)))
    }

    @Test
    fun anHourOfDimFramesWithThreeSignalsAMinuteIsOneReadAMinute() {
        // the phone's calibration, values and the alert echo each minute: before 20.11 three reads, now one
        var signals = 0L
        var readSignal = 0L
        var lastRead = ModFaceRenderer.NO_MINUTE
        var reads = 0
        var maxBehindMs = 0L
        var arrivedAt = -1L
        for (m in 0 until 60) {
            val minuteStart = m * 60_000L
            // three signals a moment apart, 20 s after the minute tick
            repeat(3) { signals++ }
            if (arrivedAt < 0) arrivedAt = minuteStart + 20_000L
            // the next minute tick's frame
            val frame = minuteStart + 60_000L
            // and an extra dim frame 10 s later in the same minute, after one more signal: no second read
            for (at in listOf(frame, frame + 10_000L)) {
                if (at != frame) signals++
                if (!ModFaceRenderer.ambientReadDue(true, signals > readSignal, false, lastRead, at / 60_000L)) continue
                reads++
                lastRead = at / 60_000L
                readSignal = signals
                maxBehindMs = maxOf(maxBehindMs, at - arrivedAt)
                arrivedAt = -1L
            }
        }
        assertEquals(60, reads)
        assertTrue("behind $maxBehindMs", maxBehindMs <= 60_000L)
    }

    // ------------------------------------------------------------------ helpers

    private fun snapshot(ageMs: Long, iob: FacePumpIob? = null): FaceSnapshot {
        val current = FaceReading(now - ageMs, 6.2f, "6.2", 0.3f, 0xFF33AA55.toInt())
        val history = (0..60).map { FacePoint(now - ageMs - it * minute, 6.2f) }.reversed()
        return FaceSnapshot(now, true, true, 3, current, history, history, 3.9f, 10f, 64, 81, emptyList(), iob)
    }
}
