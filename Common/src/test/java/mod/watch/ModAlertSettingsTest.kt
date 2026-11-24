package mod.watch

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
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
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.mod.watch.ModAlertSettings
import tk.glucodata.mod.watch.ModAlertSettings.Settings
import tk.glucodata.mod.watch.ModWatchMessages

/**
 * JugglucoNGmod, watch block (parts 7b, 7d): `/mod/alerts` - the byte format, what a broken or newer message does, what
 * the phone collects and that what the watch writes is what upstream's AlertRepository and Notify read back (their key
 * names: a red test after merging an upstream release means a key moved - ModAlertSettings needs a look), that the
 * watch never writes an alert's on/off, and when a threshold is set. Runs in both test tasks; the native side is a
 * sink, native thresholds go to [FakeThresholds] (the real native path: ModAlertThresholdsTest). Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ModAlertSettingsTest.TestApplication::class, shadows = [ModAlertSettingsTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModAlertSettingsTest {

    class TestApplication : Applic() {
        override fun onCreate() {}
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    /** Native thresholds in mg/L, as the watch would hold them; counts the writes. */
    class FakeThresholds(val levels: MutableMap<AlertType, Int> = mutableMapOf()) : ModAlertSettings.ThresholdStore {
        var writes = 0
        override fun read(type: AlertType): Int? = levels[type]
        override fun write(type: AlertType, mgPerL: Int) {
            levels[type] = mgPerL
            writes++
        }
    }

    private val PREFS_ONLY = setOf(
        AlertType.MISSED_READING, AlertType.PERSISTENT_HIGH, AlertType.SIGNAL_RESTORED, AlertType.SENSOR_EXPIRY,
        AlertType.FALLING_FAST, AlertType.RISING_FAST,
    )

    private lateinit var prefs: SharedPreferences
    private var unitBefore = 0

    @Before
    fun setUp() {
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        Log.doLog = false
        unitBefore = Applic.unit
        Applic.unit = 0
        // the very file AlertRepository reads (its lazy handle outlives one test's Application)
        prefs = AlertRepository::class.java.getDeclaredMethod("getPrefs").apply { isAccessible = true }
            .invoke(AlertRepository) as SharedPreferences
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        Applic.unit = unitBefore
    }

    private fun sample(kind: Int) = Settings(
        kind = kind,
        soundEnabled = false,
        vibrationEnabled = true,
        soundDelayEnabled = true,
        retryEnabled = true,
        alarmDurationSeconds = 42,
        hapticProfile = HapticProfile.ESCALATING,
        soundVolumePercent = 95,
        soundDelaySeconds = 20,
        retryIntervalMinutes = 2,
        retryCount = 0,
        defaultSnoozeMinutes = 25,
        deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY,
        timeRangeEnabled = true,
        activeStartHour = 22,
        activeStartMinute = 30,
        activeEndHour = 7,
        activeEndMinute = 15,
        thresholdMgPerL = 2000,
    )

    /**
     * One raw entry: the 7b fields, then (unless [base]) the 7d fields, then [extra] zero bytes. Defaults of the 7d
     * fields are "unknown".
     */
    private fun entry(
        kind: Int, flags: Int = 0, duration: Int = 30, haptic: Int = 3, volume: Int = 80,
        delay: Int = 0, interval: Int = 5, count: Int = 3, snooze: Int = 15,
        delivery: Int = 0, startHour: Int = 0xfe, startMinute: Int = 0xfe, endHour: Int = 0xfe, endMinute: Int = 0xfe,
        threshold: Int = 0xffff, base: Boolean = false, extra: Int = 0,
        length: Int = (if (base) ModAlertSettings.BASE_ENTRY_BYTES else ModAlertSettings.ENTRY_BYTES) + extra,
    ): ByteArray {
        val fields = if (base) ModAlertSettings.BASE_ENTRY_BYTES else ModAlertSettings.ENTRY_BYTES
        val buffer = ByteBuffer.allocate(1 + fields + extra)
            .put(length.toByte()).put(kind.toByte()).put(flags.toByte()).put(duration.toByte()).put(haptic.toByte())
            .put(volume.toByte()).putShort(delay.toShort()).putShort(interval.toShort()).putShort(count.toShort())
            .putShort(snooze.toShort())
        if (!base) {
            buffer.put(delivery.toByte()).put(startHour.toByte()).put(startMinute.toByte()).put(endHour.toByte())
                .put(endMinute.toByte()).putShort(threshold.toShort())
        }
        return buffer.array()
    }

    private fun message(vararg entries: ByteArray, version: Int = ModWatchMessages.VERSION, count: Int = entries.size) =
        byteArrayOf(version.toByte(), count.toByte()) + entries.fold(ByteArray(0)) { all, e -> all + e }

    private fun sentAt(ms: Long): ByteArray =
        ByteBuffer.allocate(9).put(ModAlertSettings.TRAILER_SENT_AT.toByte()).putLong(ms).array()

    @Test
    fun everyStandardKindRoundTrips() {
        val list = AlertType.settingsEntries.map { sample(it.id) }
        val bytes = ModAlertSettings.encode(list)
        assertEquals(1 + 1 + list.size * (1 + ModAlertSettings.ENTRY_BYTES), bytes.size)
        assertEquals(ModWatchMessages.VERSION, bytes[0].toInt())
        assertEquals(list, ModAlertSettings.decode(bytes))
        // every pattern and delivery, both ends of the ranges, the fields the phone has not set
        for (profile in HapticProfile.entries) {
            for (delivery in AlertDeliveryMode.entries) {
                val one = listOf(sample(AlertType.HIGH.id).copy(hapticProfile = profile, alarmDurationSeconds = 60,
                    soundVolumePercent = 30, soundDelaySeconds = 300, retryIntervalMinutes = 0, retryCount = 100,
                    defaultSnoozeMinutes = 24 * 60, soundEnabled = true, retryEnabled = false, deliveryMode = delivery,
                    timeRangeEnabled = false, activeStartHour = 0, activeStartMinute = 59,
                    activeEndHour = ModAlertSettings.TIME_UNSET, activeEndMinute = ModAlertSettings.TIME_UNSET,
                    thresholdMgPerL = ModAlertSettings.MAX_THRESHOLD_MG_PER_L))
                assertEquals("$profile $delivery", one, ModAlertSettings.decode(ModAlertSettings.encode(one)))
            }
        }
        val low = listOf(sample(AlertType.LOW.id).copy(thresholdMgPerL = ModAlertSettings.MIN_THRESHOLD_MG_PER_L))
        assertEquals(low, ModAlertSettings.decode(ModAlertSettings.encode(low)))
        assertEquals(emptyList<Settings>(), ModAlertSettings.decode(ModAlertSettings.encode(emptyList())))
    }

    @Test
    fun theSendTimeTravelsAfterTheEntries() {
        val list = listOf(sample(AlertType.LOW.id))
        val bytes = ModAlertSettings.encode(list, 1_700_000_000_123L)
        assertEquals(1 + 1 + (1 + ModAlertSettings.ENTRY_BYTES) + 9, bytes.size)
        assertEquals(ModAlertSettings.Message(list, 1_700_000_000_123L), ModAlertSettings.decodeMessage(bytes))
        // a phone of part 7b: no time; a tail that is not the time is ignored
        assertNull(ModAlertSettings.decodeMessage(ModAlertSettings.encode(list))!!.sentAtMs)
        assertNull(ModAlertSettings.decodeMessage(message(entry(AlertType.LOW.id)) + ByteArray(9))!!.sentAtMs)
        assertNull(ModAlertSettings.decodeMessage(message(entry(AlertType.LOW.id)) + sentAt(5).copyOf(8))!!.sentAtMs)
        assertEquals(5L, ModAlertSettings.decodeMessage(message(entry(AlertType.LOW.id)) + sentAt(5))!!.sentAtMs)
    }

    @Test
    fun aConfigTravelsAsThePhoneHoldsIt() {
        val config = AlertDefaults.defaultConfig(AlertType.LOW, false).copy(
            alarmDurationSeconds = 45, soundEnabled = true, vibrationEnabled = false, hapticProfile = HapticProfile.SOFT,
            soundVolumePercent = 90, soundDelayEnabled = true, soundDelaySeconds = 10, retryEnabled = true,
            retryIntervalMinutes = 0, retryCount = 0, defaultSnoozeMinutes = 20,
            customSoundUri = "content://made-up/melody", flashEnabled = true, overrideDND = false,
            deliveryMode = AlertDeliveryMode.BOTH, timeRangeEnabled = true, activeStartHour = 23, activeStartMinute = 0,
            activeEndHour = 6, activeEndMinute = null, threshold = 65f,
        )
        val expected = Settings(AlertType.LOW.id, true, false, true, true, 45, HapticProfile.SOFT, 90, 10, 0, 0, 20,
            AlertDeliveryMode.BOTH, true, 23, 0, 6, ModAlertSettings.TIME_UNSET, 650)
        assertEquals(expected, ModAlertSettings.fromConfig(config, thresholdMmol = false))
        assertEquals(listOf(expected), ModAlertSettings.decode(ModAlertSettings.encode(listOf(expected))))
        // the same level in mmol/L is the same mg/L
        assertEquals(648, ModAlertSettings.fromConfig(config.copy(threshold = 3.6f), thresholdMmol = true).thresholdMgPerL)
        assertNull(ModAlertSettings.fromConfig(config.copy(threshold = null), thresholdMmol = true).thresholdMgPerL)
    }

    @Test
    fun mgPerLConvertsBothUnits() {
        assertEquals(648, ModAlertSettings.toMgPerL(3.6f, mmol = true))
        assertEquals(650, ModAlertSettings.toMgPerL(65f, mmol = false))
        assertEquals(3.6f, ModAlertSettings.fromMgPerL(648, mmol = true), 0.0001f)
        assertEquals(64.8f, ModAlertSettings.fromMgPerL(648, mmol = false), 0.0001f)
        for (mgPerL in listOf(360, 648, 650, 1999, 3600)) {
            for (mmol in listOf(true, false)) {
                assertEquals(mgPerL, ModAlertSettings.toMgPerL(ModAlertSettings.fromMgPerL(mgPerL, mmol), mmol))
            }
        }
    }

    @Test
    fun thePhoneCollectsWhatUpstreamSaved() {
        val high = AlertRepository.loadConfig(AlertType.HIGH).copy(alarmDurationSeconds = 37, retryEnabled = true,
            retryIntervalMinutes = 10, retryCount = 5, defaultSnoozeMinutes = 45, soundVolumePercent = 60,
            deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY, timeRangeEnabled = true, activeStartHour = 21,
            activeStartMinute = 15, activeEndHour = 8, activeEndMinute = 45)
        AlertRepository.saveConfig(high)
        val collected = ModAlertSettings.collect()
        assertEquals(AlertType.settingsEntries.map { it.id }, collected.map { it.kind })
        val got = collected.single { it.kind == AlertType.HIGH.id }
        assertEquals(37, got.alarmDurationSeconds)
        assertTrue(got.retryEnabled)
        assertEquals(10, got.retryIntervalMinutes)
        assertEquals(5, got.retryCount)
        assertEquals(45, got.defaultSnoozeMinutes)
        assertEquals(60, got.soundVolumePercent)
        assertEquals(AlertDeliveryMode.NOTIFICATION_ONLY, got.deliveryMode)
        assertEquals(true, got.timeRangeEnabled)
        assertEquals(listOf(21, 15, 8, 45), listOf(got.activeStartHour, got.activeStartMinute, got.activeEndHour, got.activeEndMinute))
        // untouched kinds go with upstream's defaults: 5 s, no repeat, alarm, all day; persistent high at 180 mg/dL
        val missed = collected.single { it.kind == AlertType.MISSED_READING.id }
        assertEquals(5, missed.alarmDurationSeconds)
        assertFalse(missed.retryEnabled)
        assertEquals(false, missed.timeRangeEnabled)
        assertEquals(ModAlertSettings.TIME_UNSET, missed.activeStartHour)
        assertNull(missed.thresholdMgPerL)
        assertEquals(1800, collected.single { it.kind == AlertType.PERSISTENT_HIGH.id }.thresholdMgPerL)
    }

    @Test
    fun whatTheWatchWritesIsWhatUpstreamReads() {
        for (type in AlertType.settingsEntries) {
            prefs.edit().clear().commit()
            val enabledBefore = AlertRepository.loadConfig(type).enabled
            val s = sample(type.id)
            val natives = FakeThresholds()
            assertTrue(type.name, ModAlertSettings.apply(prefs, listOf(s), natives) > 0)
            // the watch never writes an alert's on/off
            assertFalse(type.name, prefs.contains("alert_${type.id}_enabled"))
            assertEquals(type.name, enabledBefore, AlertRepository.loadConfig(type).enabled)
            // a prefs-only alert is read from the file once their toggle sync turned it on (their save writes the
            // defaults over ours - the watch then writes ours again, WatchAlertStore)
            if (type in PREFS_ONLY) {
                AlertRepository.saveConfig(AlertRepository.loadConfig(type).copy(enabled = true))
                ModAlertSettings.apply(prefs, listOf(s), natives)
            }
            val c = AlertRepository.loadConfig(type)
            assertEquals(type.name, s.alarmDurationSeconds, c.alarmDurationSeconds)
            assertEquals(type.name, s.soundEnabled, c.soundEnabled)
            assertEquals(type.name, s.vibrationEnabled, c.vibrationEnabled)
            assertEquals(type.name, s.hapticProfile, c.hapticProfile)
            assertEquals(type.name, s.soundVolumePercent, c.soundVolumePercent)
            assertEquals(type.name, s.soundDelayEnabled, c.soundDelayEnabled)
            assertEquals(type.name, s.soundDelaySeconds, c.soundDelaySeconds)
            assertEquals(type.name, s.retryEnabled, c.retryEnabled)
            assertEquals(type.name, s.retryIntervalMinutes, c.retryIntervalMinutes)
            assertEquals(type.name, s.retryCount, c.retryCount)
            assertEquals(type.name, s.defaultSnoozeMinutes, c.defaultSnoozeMinutes)
            assertEquals(type.name, s.deliveryMode, c.deliveryMode)
            assertEquals(type.name, s.timeRangeEnabled, c.timeRangeEnabled)
            assertEquals(type.name, s.activeStartHour, c.activeStartHour)
            assertEquals(type.name, s.activeStartMinute, c.activeStartMinute)
            assertEquals(type.name, s.activeEndHour, c.activeEndHour)
            assertEquals(type.name, s.activeEndMinute, c.activeEndMinute)
            // 22:30-07:15: active at night, not at noon (AlertConfig.isActiveNow's rule)
            assertTrue(type.name, c.isActiveAtMinutes(23 * 60))
            assertFalse(type.name, c.isActiveAtMinutes(12 * 60))
            // Notify reads the duration and the delivery by these literal keys
            assertEquals(type.name, 42, prefs.getInt("alert_${type.id}_alarmDur", 5))
            assertEquals(type.name, "NOTIFICATION_ONLY", prefs.getString("alert_${type.id}_delivery", null))
            // thresholds: native-backed ones through the native side, persistent high in the file (mg/dL here)
            when (type) {
                AlertType.LOW, AlertType.HIGH, AlertType.VERY_LOW, AlertType.VERY_HIGH,
                AlertType.PRE_LOW, AlertType.PRE_HIGH -> assertEquals(type.name, 2000, natives.levels[type])
                AlertType.PERSISTENT_HIGH -> assertEquals(type.name, 200f, c.threshold!!, 0.001f)
                else -> assertNull(type.name, natives.levels[type])
            }
        }
    }

    @Test
    fun theSameSettingsTwiceWriteNothingAndANullFieldKeepsTheWatchValue() {
        val natives = FakeThresholds()
        val s = sample(AlertType.HIGH.id)
        assertTrue(ModAlertSettings.apply(prefs, listOf(s), natives) > 0)
        assertEquals(0, ModAlertSettings.apply(prefs, listOf(s), natives))
        assertEquals(1, natives.writes)
        assertEquals(1, ModAlertSettings.apply(prefs, listOf(s.copy(alarmDurationSeconds = 12)), natives))
        assertEquals(1, ModAlertSettings.apply(prefs, listOf(s.copy(alarmDurationSeconds = null, retryEnabled = false)), natives))
        assertEquals(12, prefs.getInt("alert_${AlertType.HIGH.id}_alarmDur", 0))
        assertFalse(AlertRepository.loadConfig(AlertType.HIGH).retryEnabled)
        // unknown 7d fields (a 7b phone) keep the watch's
        assertEquals(0, ModAlertSettings.apply(prefs, listOf(s.copy(alarmDurationSeconds = 12, retryEnabled = false,
            deliveryMode = null, timeRangeEnabled = null, activeStartHour = null, activeStartMinute = null,
            activeEndHour = null, activeEndMinute = null, thresholdMgPerL = null)), natives))
        assertEquals(1, natives.writes)
    }

    @Test
    fun aNativeThresholdIsSetOnlyWhenItDiffersAndIsInRange() {
        val natives = FakeThresholds(mutableMapOf(AlertType.LOW to 650, AlertType.HIGH to 1620))
        val low = sample(AlertType.LOW.id)
        // the same level: nothing set
        ModAlertSettings.apply(prefs, listOf(low.copy(thresholdMgPerL = 650)), natives)
        assertEquals(0, natives.writes)
        // another level: set once
        ModAlertSettings.apply(prefs, listOf(low.copy(thresholdMgPerL = 700)), natives)
        assertEquals(700, natives.levels[AlertType.LOW])
        assertEquals(1, natives.writes)
        assertEquals(0, ModAlertSettings.apply(prefs, listOf(low.copy(thresholdMgPerL = 700)), natives))
        // out of range or unknown: the watch keeps its own
        for (bad in listOf(ModAlertSettings.MIN_THRESHOLD_MG_PER_L - 1, ModAlertSettings.MAX_THRESHOLD_MG_PER_L + 1, 0, null)) {
            ModAlertSettings.apply(prefs, listOf(low.copy(thresholdMgPerL = bad)), natives)
        }
        assertEquals(700, natives.levels[AlertType.LOW])
        assertEquals(1, natives.writes)
        assertEquals(1620, natives.levels[AlertType.HIGH])
        // a level the watch cannot read is set; one that cannot be set changes nothing else
        val broken = object : ModAlertSettings.ThresholdStore {
            override fun read(type: AlertType): Int? = throw IllegalStateException("made-up")
            override fun write(type: AlertType, mgPerL: Int) = throw IllegalStateException("made-up")
        }
        assertEquals(1, ModAlertSettings.apply(prefs, listOf(low.copy(alarmDurationSeconds = 11)), broken))
        assertEquals(11, prefs.getInt("alert_${AlertType.LOW.id}_alarmDur", 0))
        // the decoder already drops a level out of range
        val decoded = ModAlertSettings.decode(message(
            entry(AlertType.LOW.id, threshold = 359), entry(AlertType.HIGH.id, threshold = 3601),
            entry(AlertType.PRE_LOW.id, threshold = 360),
        ))!!
        assertEquals(listOf(null, null, 360), decoded.map { it.thresholdMgPerL })
        // ... and names their kinds for the watch's log (no level sent is not out of range)
        val named = ModAlertSettings.decodeMessage(message(
            entry(AlertType.LOW.id, threshold = 359), entry(AlertType.HIGH.id, threshold = 3601),
            entry(AlertType.PRE_LOW.id, threshold = 360), entry(AlertType.VERY_LOW.id, threshold = 0xffff),
        ))!!
        assertEquals(listOf(AlertType.LOW.id, AlertType.HIGH.id), named.outOfRangeKinds)
    }

    @Test
    fun thePersistentHighLevelIsWrittenInTheWatchUnitOnlyWhenItDiffers() {
        val kind = AlertType.PERSISTENT_HIGH.id
        val key = "alert_${kind}_threshold"
        val s = sample(kind)
        // the default (180 mg/dL) and no key yet: nothing to write
        ModAlertSettings.apply(prefs, listOf(s.copy(thresholdMgPerL = 1800)), FakeThresholds())
        assertFalse(prefs.contains(key))
        ModAlertSettings.apply(prefs, listOf(s.copy(thresholdMgPerL = 2000)), FakeThresholds())
        assertEquals(200f, prefs.getFloat(key, 0f), 0.001f)
        assertEquals(0, ModAlertSettings.apply(prefs, listOf(s.copy(thresholdMgPerL = 2000)), FakeThresholds()))
        // a watch in mmol/L keeps it in mmol/L, as AlertRepository compares it
        Applic.unit = 1
        ModAlertSettings.apply(prefs, listOf(s.copy(thresholdMgPerL = 1998)), FakeThresholds())
        assertEquals(11.1f, prefs.getFloat(key, 0f), 0.001f)
        assertEquals(0, ModAlertSettings.apply(prefs, listOf(s.copy(thresholdMgPerL = 1998)), FakeThresholds()))
        AlertRepository.saveConfig(AlertRepository.loadConfig(AlertType.PERSISTENT_HIGH).copy(enabled = true))
        ModAlertSettings.apply(prefs, listOf(s.copy(thresholdMgPerL = 1998)), FakeThresholds())
        assertEquals(11.1f, AlertRepository.loadConfig(AlertType.PERSISTENT_HIGH).threshold!!, 0.001f)
    }

    @Test
    fun anActiveHourThePhoneHasNotSetRemovesTheWatchKeyAnUnknownOneKeepsIt() {
        val k = "alert_${AlertType.HIGH.id}_"
        val s = sample(AlertType.HIGH.id)
        ModAlertSettings.apply(prefs, listOf(s), FakeThresholds())
        assertEquals(22, prefs.getInt(k + "startHour", -1))
        ModAlertSettings.apply(prefs, listOf(s.copy(timeRangeEnabled = false, activeStartHour = ModAlertSettings.TIME_UNSET,
            activeStartMinute = null)), FakeThresholds())
        assertFalse(prefs.contains(k + "startHour"))
        assertEquals(30, prefs.getInt(k + "startMin", -1))
        val c = AlertRepository.loadConfig(AlertType.HIGH)
        assertNull(c.activeStartHour)
        assertFalse(c.timeRangeEnabled)
        assertTrue(c.isActiveNow())
        // on the wire: 0xff = not set, another value out of range = unknown
        val got = ModAlertSettings.decode(message(entry(AlertType.HIGH.id, startHour = 0xff, startMinute = 60,
            endHour = 24, endMinute = 59)))!!.single()
        assertEquals(ModAlertSettings.TIME_UNSET, got.activeStartHour)
        assertNull(got.activeStartMinute)
        assertNull(got.activeEndHour)
        assertEquals(59, got.activeEndMinute)
    }

    @Test
    fun aNegativeRepeatCountIsNoRepeatNotWithoutLimit() {
        val odd = sample(AlertType.HIGH.id).copy(retryEnabled = true, retryCount = -1)
        val got = ModAlertSettings.decode(ModAlertSettings.encode(listOf(odd)))!!.single()
        assertFalse(got.retryEnabled)
        assertEquals(1, got.retryCount)
        // too many: clamped to the most the watch accepts
        val many = ModAlertSettings.decode(ModAlertSettings.encode(listOf(odd.copy(retryCount = 500))))!!.single()
        assertTrue(many.retryEnabled)
        assertEquals(ModAlertSettings.MAX_RETRY_COUNT, many.retryCount)
    }

    @Test
    fun brokenOrNewerMessagesAreNull() {
        val good = entry(AlertType.LOW.id)
        assertEquals(1, ModAlertSettings.decode(message(good))?.size)
        assertNull(ModAlertSettings.decode(null))
        assertNull(ModAlertSettings.decode(ByteArray(0)))
        assertNull(ModAlertSettings.decode(byteArrayOf(ModWatchMessages.VERSION.toByte()))) // no count
        assertNull(ModAlertSettings.decode(message(good, version = 0)))
        assertNull(ModAlertSettings.decode(message(good, version = ModWatchMessages.VERSION + 1)))
        assertNull(ModAlertSettings.decode(message(good, count = 2))) // one entry missing
        assertNull(ModAlertSettings.decode(message(good).copyOf(2 + 5))) // entry cut
        assertNull(ModAlertSettings.decode(message(good).copyOf(2 + ModAlertSettings.ENTRY_BYTES))) // its last byte cut
        assertNull(ModAlertSettings.decode(message(entry(AlertType.LOW.id, base = true,
            length = ModAlertSettings.BASE_ENTRY_BYTES - 1))))
        assertNull(ModAlertSettings.decode(message(entry(AlertType.LOW.id, length = 0xff))))
        // a tick sent to this path by mistake
        assertNull(ModAlertSettings.decode(ModWatchMessages.encodeTick(ModWatchMessages.Tick(50, false, 1L, 1))))
    }

    @Test
    fun anEntryOfPart7bHasTheNewFieldsUnknown() {
        val got = ModAlertSettings.decode(message(entry(AlertType.LOW.id, flags = 1 or 16, duration = 20, base = true)))!!
            .single()
        assertEquals(20, got.alarmDurationSeconds)
        assertTrue(got.soundEnabled)
        assertNull(got.deliveryMode)
        assertNull(got.timeRangeEnabled)
        assertNull(got.activeStartHour)
        assertNull(got.activeEndMinute)
        assertNull(got.thresholdMgPerL)
        // and it writes none of them
        ModAlertSettings.apply(prefs, listOf(got), FakeThresholds())
        assertFalse(prefs.contains("alert_${AlertType.LOW.id}_delivery"))
        assertFalse(prefs.contains("alert_${AlertType.LOW.id}_timeRange"))
    }

    @Test
    fun kindsThatAreNotStandardAlertsAreSkipped() {
        val got = ModAlertSettings.decode(message(entry(2), entry(3), entry(AlertType.HIGH.id), entry(15), entry(0xff)))
        assertEquals(listOf(AlertType.HIGH.id), got?.map { it.kind })
    }

    @Test
    fun aFieldOutOfItsRangeIsDroppedTheRestKept() {
        val bad = ModAlertSettings.decode(message(
            entry(AlertType.LOW.id, flags = 1 or 8, duration = 0, haptic = 0, volume = 29, delay = 61, interval = 1441,
                count = 101, snooze = 0, delivery = 4),
            entry(AlertType.HIGH.id, duration = 61, haptic = 5, volume = 101, delay = 61, snooze = 1441),
            entry(AlertType.VERY_LOW.id, delay = 31, interval = 0xffff, count = 0xffff, snooze = 0xffff),
        ))!!
        assertEquals(Settings(AlertType.LOW.id, true, false, false, true, null, null, null, null, null, null, null,
            timeRangeEnabled = false), bad[0])
        val high = bad[1]
        assertNull(high.alarmDurationSeconds)
        assertNull(high.hapticProfile)
        assertNull(high.soundVolumePercent)
        assertEquals(61, high.soundDelaySeconds) // the hypo cap of 60 s is LOW's, not HIGH's
        assertNull(high.defaultSnoozeMinutes)
        assertEquals(5, high.retryIntervalMinutes)
        assertNull(bad[2].soundDelaySeconds) // VERY_LOW: 30 s at most
        assertNull(bad[2].retryIntervalMinutes)
        assertNull(bad[2].retryCount)
        assertNull(bad[2].defaultSnoozeMinutes)
        // and nothing of a dropped field is written on the watch
        prefs.edit().putInt("alert_${AlertType.LOW.id}_alarmDur", 33).commit()
        ModAlertSettings.apply(prefs, bad, FakeThresholds())
        assertEquals(33, prefs.getInt("alert_${AlertType.LOW.id}_alarmDur", 0))
        assertFalse(prefs.contains("alert_${AlertType.LOW.id}_snooze"))
        assertFalse(prefs.contains("alert_${AlertType.LOW.id}_delivery"))
    }

    @Test
    fun aLongerEntryAndUnknownFlagsFromALaterPartAreRead() {
        val got = ModAlertSettings.decode(message(
            entry(AlertType.HIGH.id, flags = 0xe0 or 2, duration = 20, delivery = 2, threshold = 1700, extra = 3),
            entry(AlertType.LOW.id, duration = 50),
        ) + byteArrayOf(7, 7))
        assertEquals(listOf(AlertType.HIGH.id, AlertType.LOW.id), got?.map { it.kind })
        assertEquals(20, got!![0].alarmDurationSeconds)
        assertTrue(got[0].vibrationEnabled)
        assertFalse(got[0].soundEnabled)
        assertEquals(AlertDeliveryMode.SYSTEM_ALARM, got[0].deliveryMode)
        assertEquals(1700, got[0].thresholdMgPerL)
        assertEquals(50, got[1].alarmDurationSeconds)
    }

    @Test
    fun thePhoneSendsUnknownAsDroppedAndConstantlyAsZero() {
        val odd = Settings(AlertType.HIGH.id, true, true, false, true, null, null, null, null, -1, null, 70_000,
            thresholdMgPerL = 70_000)
        val got = ModAlertSettings.decode(ModAlertSettings.encode(listOf(odd)))!!.single()
        assertEquals(0, got.retryIntervalMinutes) // "constantly"
        assertNull(got.alarmDurationSeconds)
        assertNull(got.soundVolumePercent)
        assertNull(got.soundDelaySeconds)
        assertNull(got.retryCount)
        assertNull(got.defaultSnoozeMinutes)
        assertNull(got.deliveryMode)
        assertNull(got.activeStartHour)
        assertNull(got.thresholdMgPerL)
    }

    @Test
    fun aChangeOfACarriedKeyOrOfAnOnOffSendsAtOnce() {
        for (key in listOf("alert_1_alarmDur", "alert_0_soundVolumePercent", "alert_9_retryOn", "alert_14_snooze",
                "alert_1_threshold", "alert_10_threshold", "alert_7_delivery", "alert_5_timeRange", "alert_5_startHour",
                "alert_5_startMin", "alert_5_endHour", "alert_5_endMin", "alert_1_enabled", "alert_12_enabled")) {
            assertTrue(key, ModAlertSettings.triggersSend(key))
        }
        for (key in listOf("alert_1_soundUri", "alert_1_flash", "alert_1_dnd", "alert_2_alarmDur", "alert_2_enabled",
                "alert_1_duration", "notification_dismiss_action", null)) {
            assertFalse(key.toString(), ModAlertSettings.triggersSend(key))
        }
    }
}
