package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.HealthStatus
import tk.glucodata.mod.watch.ModBattery

/** JugglucoNGmod, watch block (part 4): when the status and battery rows go and what the status says. Made-up values. */
class HealthStatusTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun statusGoesFirstTimeOnChangeAndEveryThirtyMinutes() {
        val stable = HealthStatus.stablePart("1.3.2-mod0", 34, "perm=hr,act;reg=1")
        assertTrue(HealthStatus.isDue(now, 0, stable, ""))
        assertFalse(HealthStatus.isDue(now + 29 * minute, now, stable, stable))
        assertTrue(HealthStatus.isDue(now + 30 * minute, now, stable, stable))
        val changed = HealthStatus.stablePart("1.3.2-mod0", 34, "perm=hr;reg=0")
        assertTrue(HealthStatus.isDue(now + minute, now, changed, stable))
        assertTrue(HealthStatus.isDue(now - minute, now, stable, stable)) // the clock went back
    }

    @Test
    fun statusLineHasAgesNotTimes() {
        val stable = HealthStatus.stablePart("1.3.2-mod0", 34, "")
        assertEquals("app=1.3.2-mod0;sdk=34;src=none", stable)
        val line = HealthStatus.line(stable, now, now - 7 * minute - 30_000, 0, 12, 3, "")
        assertEquals("app=1.3.2-mod0;sdk=34;src=none;data=7;sent=-;queue=12;late=3;err=-", line)
        assertEquals("0", HealthStatus.ageMin(now, now + minute)) // a time ahead is 0, not negative
    }

    @Test
    fun statusIsOneLine() {
        val stable = HealthStatus.stablePart("v", 30, "a=1\tb=2\nc=3")
        assertFalse(stable.contains('\t') || stable.contains('\n'))
    }

    @Test
    fun batteryRowOnlyOnChange() {
        assertTrue(HealthStatus.batteryChanged(ModBattery.Reading(55, false), -1, false))
        assertFalse(HealthStatus.batteryChanged(ModBattery.Reading(55, false), 55, false))
        assertTrue(HealthStatus.batteryChanged(ModBattery.Reading(55, true), 55, false))
        assertTrue(HealthStatus.batteryChanged(ModBattery.Reading(54, false), 55, false))
        assertFalse(HealthStatus.batteryChanged(null, 55, false))
    }
}
