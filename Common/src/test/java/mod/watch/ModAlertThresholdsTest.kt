package mod.watch

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.mod.watch.ModAlertSettings
import tk.glucodata.mod.watch.ModAlertSettings.Settings

/**
 * JugglucoNGmod, watch block (part 7d): the watch sets a native threshold upstream's own way - read through their
 * AlertRepository.loadConfig, set through their Natives.setAlertConfig (the real Java body runs; only the JNI calls
 * below it are stood in for by [NativeLevels], which keeps levels as upstream's C++ does: mg/L, converted with the
 * device unit). On/off and the other levels stay as they were; the same level sets nothing. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ModAlertThresholdsTest.TestApplication::class, shadows = [ModAlertThresholdsTest.NativeLevels::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModAlertThresholdsTest {

    class TestApplication : Applic() {
        override fun onCreate() {}
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeLevels {
        companion object {
            var unit = 1
            val level = IntArray(9)
            val on = BooleanArray(9)
            var setterCalls = 0

            private fun toMgPerL(value: Float) = (value * if (unit == 1) 180f else 10f).roundToInt()
            private fun fromMgPerL(mgPerL: Int) = mgPerL / if (unit == 1) 180f else 10f

            @Implementation @JvmStatic fun getunit(): Int = unit
            @Implementation @JvmStatic fun alarmlow(): Float = fromMgPerL(level[0])
            @Implementation @JvmStatic fun alarmhigh(): Float = fromMgPerL(level[1])
            @Implementation @JvmStatic fun alarmverylow(): Float = fromMgPerL(level[5])
            @Implementation @JvmStatic fun alarmveryhigh(): Float = fromMgPerL(level[6])
            @Implementation @JvmStatic fun alarmprelow(): Float = fromMgPerL(level[7])
            @Implementation @JvmStatic fun alarmprehigh(): Float = fromMgPerL(level[8])
            @Implementation @JvmStatic fun hasalarmlow(): Boolean = on[0]
            @Implementation @JvmStatic fun hasalarmhigh(): Boolean = on[1]
            @Implementation @JvmStatic fun hasalarmloss(): Boolean = on[4]
            @Implementation @JvmStatic fun hasalarmverylow(): Boolean = on[5]
            @Implementation @JvmStatic fun hasalarmveryhigh(): Boolean = on[6]
            @Implementation @JvmStatic fun hasalarmprelow(): Boolean = on[7]
            @Implementation @JvmStatic fun hasalarmprehigh(): Boolean = on[8]

            /** javasettings.cpp setalarms: roundf(tomgperL(x)). */
            @Implementation @JvmStatic
            fun setalarms(alow: Float, ahigh: Float, lowalarm: Boolean, highalarm: Boolean, available: Boolean, loss: Boolean) {
                setterCalls++
                level[0] = toMgPerL(alow); level[1] = toMgPerL(ahigh)
                on[0] = lowalarm; on[1] = highalarm; on[2] = available; on[4] = loss
            }

            @Implementation @JvmStatic
            fun setAdvancedAlarms(
                verylow: Float, veryhigh: Float, verylowalarm: Boolean, veryhighalarm: Boolean,
                prelowalarm: Boolean, prehighalarm: Boolean, prelow: Float, prehigh: Float,
            ) {
                setterCalls++
                level[5] = toMgPerL(verylow); level[6] = toMgPerL(veryhigh)
                level[7] = toMgPerL(prelow); level[8] = toMgPerL(prehigh)
                on[5] = verylowalarm; on[6] = veryhighalarm; on[7] = prelowalarm; on[8] = prehighalarm
            }

            /** Upstream's own Java body (Natives.setAlertConfig), calling the stand-ins above. */
            @Implementation @JvmStatic
            fun setAlertConfig(type: Int, threshold: Float, enabled: Boolean) {
                Shadow.directlyOn<Any?, Natives>(
                    Natives::class.java, "setAlertConfig",
                    ClassParameter.from(Int::class.javaPrimitiveType, type),
                    ClassParameter.from(Float::class.javaPrimitiveType, threshold),
                    ClassParameter.from(Boolean::class.javaPrimitiveType, enabled),
                )
            }
        }
    }

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        Log.doLog = false
        prefs = AlertRepository::class.java.getDeclaredMethod("getPrefs").apply { isAccessible = true }
            .invoke(AlertRepository) as SharedPreferences
        // their one-time threshold migration counts as done: it is theirs, and none of these levels is a legacy default
        prefs.edit().clear().putBoolean("alert_threshold_defaults_v3", true).commit()
        NativeLevels.unit = 1
        // low 3.4, high 10.5, very low 3.0, very high 13.0, forecast 4.2 / 9.5 mmol/L
        intArrayOf(612, 1890, 0, 0, 0, 540, 2340, 756, 1710).copyInto(NativeLevels.level)
        booleanArrayOf(true, false, false, false, true, true, false, false, true).copyInto(NativeLevels.on)
        // their once-per-process cleanup of the hidden legacy alert goes through setAlertConfig too - before counting
        AlertRepository.loadConfig(AlertType.LOW)
        NativeLevels.setterCalls = 0
    }

    private fun settings(kind: Int, mgPerL: Int) = Settings(
        kind = kind, soundEnabled = true, vibrationEnabled = true, soundDelayEnabled = false, retryEnabled = false,
        alarmDurationSeconds = 10, hapticProfile = HapticProfile.STRONG, soundVolumePercent = 100, soundDelaySeconds = 0,
        retryIntervalMinutes = 5, retryCount = 3, defaultSnoozeMinutes = 15, thresholdMgPerL = mgPerL,
    )

    @Test
    fun upstreamReadsTheLevelsInMgPerL() {
        val read = ModAlertSettings.UpstreamThresholds
        assertEquals(612, read.read(AlertType.LOW))
        assertEquals(1890, read.read(AlertType.HIGH))
        assertEquals(540, read.read(AlertType.VERY_LOW))
        assertEquals(2340, read.read(AlertType.VERY_HIGH))
        assertEquals(756, read.read(AlertType.PRE_LOW))
        assertEquals(1710, read.read(AlertType.PRE_HIGH))
        assertEquals(null, read.read(AlertType.LOSS))
    }

    @Test
    fun aLevelIsSetUpstreamsWayTheRestKept() {
        val onBefore = NativeLevels.on.copyOf()
        ModAlertSettings.apply(prefs, listOf(settings(AlertType.LOW.id, 702), settings(AlertType.PRE_HIGH.id, 1800)))
        assertEquals(702, NativeLevels.level[0])
        assertEquals(1800, NativeLevels.level[8])
        // what their AlertRepository now reads: 3.9 mmol/L and 10.0 mmol/L
        assertEquals(3.9f, AlertRepository.loadConfig(AlertType.LOW).threshold!!, 0.001f)
        assertEquals(10.0f, AlertRepository.loadConfig(AlertType.PRE_HIGH).threshold!!, 0.001f)
        // every other level and every on/off as before (their wrapper always keeps "value available" off)
        assertEquals(listOf(1890, 540, 2340, 756), listOf(1, 5, 6, 7).map { NativeLevels.level[it] })
        for (i in listOf(0, 1, 4, 5, 6, 7, 8)) assertEquals("on/off $i", onBefore[i], NativeLevels.on[i])
        assertFalse(NativeLevels.on[2])
    }

    @Test
    fun theSameLevelSetsNothing() {
        ModAlertSettings.apply(prefs, listOf(settings(AlertType.HIGH.id, 1890), settings(AlertType.VERY_LOW.id, 540)))
        assertEquals(0, NativeLevels.setterCalls)
        ModAlertSettings.apply(prefs, listOf(settings(AlertType.HIGH.id, 1900)))
        assertEquals(1, NativeLevels.setterCalls)
        ModAlertSettings.apply(prefs, listOf(settings(AlertType.HIGH.id, 1900)))
        assertEquals(1, NativeLevels.setterCalls)
    }

    @Test
    fun aWatchInMgPerDlGetsTheSameLevel() {
        NativeLevels.unit = 2
        ModAlertSettings.apply(prefs, listOf(settings(AlertType.LOW.id, 650)))
        assertEquals(650, NativeLevels.level[0])
        assertEquals(65f, AlertRepository.loadConfig(AlertType.LOW).threshold!!, 0.001f)
        assertTrue(NativeLevels.on[0])
    }

    @Test
    fun aLevelOutOfRangeSetsNothing() {
        ModAlertSettings.apply(prefs, listOf(settings(AlertType.LOW.id, 100), settings(AlertType.HIGH.id, 5000)))
        assertEquals(0, NativeLevels.setterCalls)
        assertEquals(612, NativeLevels.level[0])
        assertEquals(1890, NativeLevels.level[1])
    }
}
