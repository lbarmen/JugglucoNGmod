package mod.pumpbt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.runBlocking
import mod.carelink.CareLinkJournalTestApp
import mod.carelink.CareLinkJournalTestEnv
import mod.carelink.CareLinkJournalTestEnv.app
import mod.carelink.CareLinkTestNatives
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.mod.carelink.CareLinkActivity
import tk.glucodata.mod.carelink.CareLinkInsulin
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.ModCareLinkStarter
import tk.glucodata.mod.pumpbt.PumpBtActivity
import tk.glucodata.mod.pumpbt.PumpBtSettingsModel

/**
 * JugglucoNGmod, pump over Bluetooth: the pump window's settings model (in place of the CareLink window's model) -
 * the switches and the threshold are written, the threshold choices are 20/30/60 and a stored 5/10/15 shows as 20 (not
 * rewritten), the insulin type is checked against upstream's list, our channel turned off is shown; the settings row,
 * the shortcut and the notification tap open the pump window where it is declared; the old CareLink window only
 * forwards to it. Upstream's real journal (insulin types) under Robolectric. Made-up numbers only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PumpBtSettingsModelTest {

    private val now = 1_800_000_000_000L
    private lateinit var model: PumpBtSettingsModel

    private val pumpWindow get() = ComponentName(app, PumpBtActivity::class.java)
    private val oldWindow get() = ComponentName(app, CareLinkActivity::class.java)

    /**
     * Without emptying upstream's journal: only the insulin test needs it (and does it itself) - the other tests then
     * also run where Robolectric's SQLite does not (Windows); a journal the model cannot read gives an empty type list.
     */
    @Before
    fun setUp() {
        Applic.app = app
        // loaded while the context lives, as CareLinkJournalTestEnv does (a late class init would break later tests)
        Class.forName("tk.glucodata.Notify", true, Applic::class.java.classLoader)
        app.getSharedPreferences(CareLinkSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        model = PumpBtSettingsModel(app)
    }

    @After
    fun tearDown() {
        CareLinkJournalTestEnv.tearDown()
    }

    private fun load() = runBlocking { model.load() }

    @Test
    fun switchesAndTheWarningThresholdAreWritten() {
        assertTrue(load().writeCarbs)
        assertFalse(load().sendToNightscout)
        assertEquals(30, load().warnAfterMin)
        model.setWriteCarbs(false)
        model.setSendToNightscout(true)
        model.setWarnAfter(20)
        val settings = CareLinkSettings(app)
        assertFalse(settings.writeCarbs)
        assertTrue(settings.sendToNightscout)
        assertEquals(20, settings.warnAfterMin)
        model.setWarnAfter(5)
        assertEquals("5 is no longer offered - ignored", 20, settings.warnAfterMin)
        model.setWarnAfter(45)
        assertEquals("not offered - ignored", 20, settings.warnAfterMin)
        model.setWarnAfter(60)
        assertEquals(60, settings.warnAfterMin)
        val s = load()
        assertFalse(s.writeCarbs)
        assertTrue(s.sendToNightscout)
        assertEquals(60, s.warnAfterMin)
    }

    @Test
    fun thresholdChoicesAre20_30_60AndAnOldShortOneShowsAs20() {
        assertEquals(listOf(20, 30, 60), load().warnChoices)
        val settings = CareLinkSettings(app)
        for (old in listOf(5, 10, 15)) {
            settings.warnAfterMin = old
            assertEquals("stored $old: Bluetooth warns after 20 - shown as 20", 20, load().warnAfterMin)
            assertEquals("the stored value is not rewritten", old, settings.warnAfterMin)
        }
        for (choice in listOf(20, 30, 60)) {
            settings.warnAfterMin = choice
            assertEquals(choice, load().warnAfterMin)
        }
    }

    @Test
    fun insulinTypeIsOneOfTheirsAndALostChoiceShowsTheOneUsed() {
        CareLinkJournalTestEnv.setUp { now }
        val default = runBlocking { CareLinkInsulin.presetId(app) }!!
        // a settings import left an id that is not a pump type - the window shows the type really used
        CareLinkSettings(app).presetId = 999_999L
        var s = load()
        assertTrue(s.insulinChoices.isNotEmpty())
        assertTrue(s.insulinChoices.any { it.id == default })
        assertEquals(default, s.insulinId)
        assertNotNull(s.insulinName)
        // a choice not in the list the window showed is not taken; one in it is written
        model.setInsulin(123_456_789L)
        assertEquals(999_999L, CareLinkSettings(app).presetId)
        model.setInsulin(default)
        assertEquals(default, CareLinkSettings(app).presetId)
        s = load()
        assertEquals(default, s.insulinId)
    }

    @Test
    fun ourChannelTurnedOffIsShown() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(load().channelOff)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CareLinkNotifier.CHANNEL, "Pump", NotificationManager.IMPORTANCE_NONE),
        )
        val s = load()
        assertTrue(s.notificationsAllowed)
        assertTrue(s.channelOff)
    }

    @Test
    fun settingsRowShortcutAndNotificationTapOpenThePumpWindowOnlyWhereItIsDeclared() {
        // upstream's builds: no window - the row is not drawn, no shortcut, a tap opens the app (taken out of
        // Robolectric's package manager first, in case its test manifest has it)
        shadowOf(app.packageManager).removeActivity(pumpWindow)
        assertNull(PumpBtActivity.intentIfDeclared(app))
        val fallback = CareLinkNotifier.tapIntent(app)
        assertTrue(fallback == null || fallback.component?.className != PumpBtActivity::class.java.name)
        // the `mod` build declares it
        shadowOf(app.packageManager).addActivityIfNotPresent(pumpWindow)
        assertNotNull(PumpBtActivity.intentIfDeclared(app))
        assertEquals(PumpBtActivity::class.java.name, CareLinkNotifier.tapIntent(app)!!.component!!.className)
        ModCareLinkStarter.installShortcut(app)
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(app).single { it.id == "mod-carelink" }
        assertEquals("the kept shortcut id opens the pump window", PumpBtActivity::class.java.name,
            shortcut.intent.component!!.className)
    }

    @Test
    fun theOldCareLinkWindowOnlyForwardsToThePumpWindow() {
        shadowOf(app.packageManager).addActivityIfNotPresent(oldWindow)
        shadowOf(app.packageManager).addActivityIfNotPresent(pumpWindow)
        val old = Robolectric.buildActivity(CareLinkActivity::class.java).create().get()
        assertEquals(PumpBtActivity::class.java.name, shadowOf(old).nextStartedActivity.component!!.className)
        assertTrue("no screen of its own", old.isFinishing)
        // a build without the pump window: nothing opened, still closed
        shadowOf(app.packageManager).removeActivity(pumpWindow)
        val alone = Robolectric.buildActivity(CareLinkActivity::class.java).create().get()
        assertNull(shadowOf(alone).nextStartedActivity)
        assertTrue(alone.isFinishing)
    }
}
