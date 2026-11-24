package mod.watch.face

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.watch.face.FaceLabels
import tk.glucodata.mod.watch.face.FaceText
import tk.glucodata.mod.watch.face.FaceValues

/** JugglucoNGmod, watch block (part 3a): the face's date, clock, battery and age texts in English and Russian. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FaceTextTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val tuesday = LocalDateTime.of(2026, 11, 17, 14, 35).atZone(zone).toInstant().toEpochMilli()
    private val earlyMorning = LocalDateTime.of(2026, 5, 4, 0, 7).atZone(zone).toInstant().toEpochMilli()

    private fun labels() = FaceLabels.from(ApplicationProvider.getApplicationContext())

    @Test
    @Config(qualifiers = "en")
    fun englishDateBatteryAndAge() {
        val labels = labels()
        assertEquals("Tue 17 Nov", FaceText.date(tuesday, zone, labels))
        assertEquals("Mon 4 May", FaceText.date(earlyMorning, zone, labels))
        // the face's bottom row (part 7e): day and short month, no weekday
        assertEquals("17 Nov", FaceText.dayAndMonth(tuesday, zone, labels))
        assertEquals("4 May", FaceText.dayAndMonth(earlyMorning, zone, labels))
        assertEquals("64%", FaceText.battery(64, labels))
        assertEquals("2 min", FaceText.age(2, labels))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianDateBatteryAndAge() {
        val labels = labels()
        assertEquals("Вт 17 ноя", FaceText.date(tuesday, zone, labels))
        assertEquals("Пн 4 мая", FaceText.date(earlyMorning, zone, labels))
        assertEquals("17 ноя", FaceText.dayAndMonth(tuesday, zone, labels))
        assertEquals("4 мая", FaceText.dayAndMonth(earlyMorning, zone, labels))
        assertEquals("64 %", FaceText.battery(64, labels))
        assertEquals("2 мин", FaceText.age(2, labels))
    }

    @Test
    @Config(qualifiers = "en")
    fun theBatteryRowInEnglish() {
        val l = labels()
        assertEquals("phone 64%   watch 81%", FaceText.batteries(FaceText.battery(64, l), FaceText.battery(81, l), l))
        assertEquals("phone —   watch 81%", FaceText.batteries(FaceText.battery(null, l), FaceText.battery(81, l), l))
    }

    @Test
    @Config(qualifiers = "ru")
    fun theBatteryRowInRussian() {
        val l = labels()
        assertEquals("телефон 64 %   часы 81 %",
            FaceText.batteries(FaceText.battery(64, l), FaceText.battery(81, l), l))
    }

    @Test
    fun unknownOrBrokenBatteryIsADash() {
        val labels = labels()
        assertEquals(FaceValues.NONE, FaceText.battery(null, labels))
        assertEquals(FaceValues.NONE, FaceText.battery(101, labels))
        assertEquals(FaceValues.NONE, FaceText.battery(-1, labels))
        assertNull(FaceText.age(null, labels))
    }

    @Test
    fun theClockIn24And12HourModes() {
        assertEquals(FaceText.Clock("14:35", null), FaceText.clock(tuesday, zone, hour24 = true))
        assertEquals(FaceText.Clock("2:35", "pm"), FaceText.clock(tuesday, zone, hour24 = false))
        assertEquals(FaceText.Clock("00:07", null), FaceText.clock(earlyMorning, zone, hour24 = true))
        assertEquals(FaceText.Clock("12:07", "am"), FaceText.clock(earlyMorning, zone, hour24 = false))
    }

    @Test
    fun theDateFollowsTheZone() {
        // 14:35 in Moscow is 06:35 in New York, the same day; 23:30 in Moscow is 05:30 next day in Tokyo
        assertEquals("Tue 17 Nov", FaceText.date(tuesday, ZoneId.of("America/New_York"), labels()))
        val lateEvening = LocalDateTime.of(2026, 11, 17, 23, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Wed 18 Nov", FaceText.date(lateEvening, ZoneId.of("Asia/Tokyo"), labels()))
        assertEquals("18 Nov", FaceText.dayAndMonth(lateEvening, ZoneId.of("Asia/Tokyo"), labels()))
    }
}
