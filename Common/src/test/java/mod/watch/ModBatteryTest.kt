package mod.watch

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.watch.ModBattery

/** JugglucoNGmod, watch block: our battery reader (phone battery for /mod/tick, watch battery for the face). Made-up levels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModBatteryTest {

    private fun batteryIntent(level: Int?, scale: Int?, status: Int?) = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
        level?.let { putExtra(BatteryManager.EXTRA_LEVEL, it) }
        scale?.let { putExtra(BatteryManager.EXTRA_SCALE, it) }
        status?.let { putExtra(BatteryManager.EXTRA_STATUS, it) }
    }

    @Test
    fun percentOfLevelAndScale() {
        assertEquals(64, ModBattery.percent(64, 100))
        assertEquals(0, ModBattery.percent(0, 100))
        assertEquals(100, ModBattery.percent(100, 100))
        assertEquals(50, ModBattery.percent(128, 256))
        assertEquals(33, ModBattery.percent(1, 3)) // rounded down, as the system shows it
    }

    @Test
    fun brokenLevelOrScaleIsNull() {
        assertNull(ModBattery.percent(-1, 100))
        assertNull(ModBattery.percent(50, 0))
        assertNull(ModBattery.percent(50, -1))
        assertNull(ModBattery.percent(101, 100))
    }

    @Test
    fun chargingMeansChargingOrFull() {
        assertTrue(ModBattery.isCharging(BatteryManager.BATTERY_STATUS_CHARGING))
        assertTrue(ModBattery.isCharging(BatteryManager.BATTERY_STATUS_FULL))
        assertFalse(ModBattery.isCharging(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertFalse(ModBattery.isCharging(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertFalse(ModBattery.isCharging(BatteryManager.BATTERY_STATUS_UNKNOWN))
        assertFalse(ModBattery.isCharging(-1))
    }

    @Test
    fun fromIntent() {
        assertEquals(ModBattery.Reading(71, true),
            ModBattery.fromIntent(batteryIntent(71, 100, BatteryManager.BATTERY_STATUS_CHARGING)))
        assertEquals(ModBattery.Reading(18, false),
            ModBattery.fromIntent(batteryIntent(18, 100, BatteryManager.BATTERY_STATUS_DISCHARGING)))
        assertEquals(ModBattery.Reading(45, false), ModBattery.fromIntent(batteryIntent(45, 100, null)))
        assertNull(ModBattery.fromIntent(batteryIntent(null, 100, BatteryManager.BATTERY_STATUS_CHARGING)))
        assertNull(ModBattery.fromIntent(batteryIntent(45, null, null)))
        assertNull(ModBattery.fromIntent(null))
    }

    @Test
    fun readTakesTheStickyBroadcast() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(batteryIntent(83, 100, BatteryManager.BATTERY_STATUS_FULL))
        assertEquals(ModBattery.Reading(83, true), ModBattery.read(context))
    }
}
