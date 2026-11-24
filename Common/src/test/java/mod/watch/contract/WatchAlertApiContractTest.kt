package mod.watch.contract

import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertActions
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.AlarmActivityAccess

/**
 * JugglucoNGmod, watch block: what the snooze button relies on in upstream's alert code (plan, "Technique", contract
 * tests). A rename alone already breaks the compile; these tests hold the meaning - the overloads, `null` as "a
 * standard alert", return types, which kinds count, that only the presented alert is "current" and how an episode is
 * opened, dismissed and reset. A red test here
 * after merging an upstream release means their API moved: OngoingAlert needs a look, not this test a change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WatchAlertApiContractTest.TestApplication::class,
    shadows = [WatchAlertApiContractTest.NotifyInit::class, WatchAlertApiContractTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WatchAlertApiContractTest {

    class TestApplication : Applic() {
        override fun onCreate() {}
    }

    /** Only Notify's static setup is skipped (it needs the native side); its alert queries run for real. */
    @Implements(Notify::class)
    class NotifyInit {
        companion object {
            @JvmStatic @Implementation fun __staticInitializer__() {}
        }
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    @Before
    fun setUp() {
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        Applic.app.getSharedPreferences("tk.glucodata.snooze", 0).edit().clear().commit()
        AlertType.entries.forEach {
            AlertStateTracker.resetState(it)
            AlertStateTracker.consumeManualTestAction(it)
        }
        Log.doLog = false
    }

    @Test
    fun handleTakesKindCustomIdAndOptionalMinutesAndIsStatic() {
        val cls = AlertActions::class.java
        val full = cls.getMethod("handle", Int::class.javaPrimitiveType, String::class.java, Integer::class.java)
        assertTrue(Modifier.isStatic(full.modifiers))
        assertEquals(Void.TYPE, full.returnType)
        // @JvmOverloads: the minutes have a default ("stop" / the restricted-mode snooze)
        val short = cls.getMethod("handle", Int::class.javaPrimitiveType, String::class.java)
        assertTrue(Modifier.isStatic(short.modifiers))
    }

    @Test
    fun defaultSnoozeMinutesIsAnIntPerKindWithNullForAStandardAlert() {
        val m = AlertActions::class.java.getMethod("defaultSnoozeMinutes", Int::class.javaPrimitiveType, String::class.java)
        assertTrue(Modifier.isStatic(m.modifiers))
        assertEquals(Int::class.javaPrimitiveType, m.returnType)
        for (type in AlertType.settingsEntries) {
            val minutes = AlertActions.defaultSnoozeMinutes(type.id, null)
            assertTrue("${type.name}: $minutes", minutes in 1..24 * 60)
        }
        // an unknown kind and a custom alert fall back to 15 minutes
        assertEquals(15, AlertActions.defaultSnoozeMinutes(-1, null))
        assertEquals(15, AlertActions.defaultSnoozeMinutes(AlertType.HIGH.id, "made-up-custom"))
    }

    @Test
    fun isCurrentStandardAlertIsAStaticBooleanTrueOnlyForThePresentedKind() {
        val m = Notify::class.java.getMethod("isCurrentStandardAlert", Int::class.javaPrimitiveType)
        assertTrue(Modifier.isStatic(m.modifiers))
        assertEquals(java.lang.Boolean.TYPE, m.returnType)
        // their private "presented" mark; renamed - read Notify.isCurrentStandardAlert again and fix this line
        val presented = Notify::class.java.getDeclaredField("presentedStandardAlertKind").apply { isAccessible = true }
        try {
            presented.setInt(null, -1)
            for (type in AlertType.entries) assertFalse(type.name, Notify.isCurrentStandardAlert(type.id))
            presented.setInt(null, AlertType.LOW.id)
            assertTrue(Notify.isCurrentStandardAlert(AlertType.LOW.id))
            for (type in AlertType.entries.filter { it != AlertType.LOW }) {
                assertFalse(type.name, Notify.isCurrentStandardAlert(type.id))
            }
            assertFalse(Notify.isCurrentStandardAlert(-1))
        } finally {
            presented.setInt(null, -1)
        }
    }

    @Test
    fun anEpisodeLastsFromItsFiringToItsResetAndADismissalIsSeparate() {
        for (name in listOf("isEpisodeActive", "isDismissed")) {
            val m = AlertStateTracker::class.java.getMethod(name, AlertType::class.java)
            assertEquals(name, java.lang.Boolean.TYPE, m.returnType)
        }
        val type = AlertType.HIGH
        assertFalse(AlertStateTracker.isEpisodeActive(type))
        AlertStateTracker.onAlertTriggered(type)
        assertTrue(AlertStateTracker.isEpisodeActive(type))
        assertFalse(AlertStateTracker.isDismissed(type))
        AlertStateTracker.onAlertDismissed(type)
        assertTrue(AlertStateTracker.isEpisodeActive(type))
        assertTrue(AlertStateTracker.isDismissed(type))
        AlertStateTracker.resetState(type)
        assertFalse(AlertStateTracker.isEpisodeActive(type))
        assertFalse(AlertStateTracker.isDismissed(type))
    }

    @Test
    fun theNativeSoundFlagIsAStaticBoolean() {
        // LOSS has no episode and a sound may outlive one; OngoingAlert reads "a sound is on" from here
        val m = Natives::class.java.getMethod("getisalarm")
        assertTrue(Modifier.isStatic(m.modifiers))
        assertEquals(java.lang.Boolean.TYPE, m.returnType)
    }

    @Test
    fun resolveAlertKindIsTheSoundingKindBeforeThePresentedOne() {
        // OngoingAlert tells "this kind's own sound is on" from here (the amount reminder sounds as AMOUNT and leaves
        // the presented mark alone). Their private fields; renamed - read Notify.resolveAlertKind and playringhier
        // (it sets the kind together with the native flag, runstopalarm clears both) again and fix these lines.
        val m = Notify::class.java.getMethod("resolveAlertKind", Int::class.javaPrimitiveType)
        assertTrue(Modifier.isStatic(m.modifiers))
        assertEquals(Int::class.javaPrimitiveType, m.returnType)
        fun field(name: String) = Notify::class.java.getDeclaredField(name).apply { isAccessible = true }
        val lock = field("retrySessionLock")
        val session = field("activeRetrySession")
        val sounding = field("lastalarm")
        val presented = field("presentedStandardAlertKind")
        assertEquals(Int::class.javaPrimitiveType, sounding.type)
        assertTrue(Modifier.isStatic(sounding.modifiers))
        try {
            lock.set(null, Any()) // their static setup is skipped here, and with it the lock
            session.set(null, null)
            sounding.setInt(null, -1)
            presented.setInt(null, -1)
            assertEquals(-1, Notify.resolveAlertKind(-1))
            assertEquals(AlertType.HIGH.id, Notify.resolveAlertKind(AlertType.HIGH.id))
            presented.setInt(null, AlertType.LOW.id)
            assertEquals(AlertType.LOW.id, Notify.resolveAlertKind(-1))
            sounding.setInt(null, AlertType.AMOUNT.id)
            assertEquals(AlertType.AMOUNT.id, Notify.resolveAlertKind(-1))
            sounding.setInt(null, AlertType.LOW.id)
            assertEquals(AlertType.LOW.id, Notify.resolveAlertKind(-1))
        } finally {
            sounding.setInt(null, -1)
            presented.setInt(null, -1)
        }
    }

    @Test
    fun acknowledgeTakesAKindOnTheObjectAndClosesOnlyThatKindsScreen() {
        val m = AlarmActivityAccess::class.java.getMethod("acknowledge", Int::class.javaPrimitiveType)
        assertEquals(Void.TYPE, m.returnType)
        assertNotNull(AlarmActivityAccess::class.java.getField("INSTANCE"))
        val owner = Any()
        val closed = mutableListOf<Int>()
        AlarmActivityAccess.observeAcknowledgement(owner, AlertType.HIGH.id) { closed += AlertType.HIGH.id }
        try {
            AlarmActivityAccess.acknowledge(AlertType.LOW.id)
            assertTrue(closed.isEmpty())
            AlarmActivityAccess.acknowledge(AlertType.HIGH.id)
            assertEquals(listOf(AlertType.HIGH.id), closed)
        } finally {
            AlarmActivityAccess.removeObserver(owner)
        }
    }

    @Test
    fun settingsEntriesIsACompanionGetterWithTheStandardKindsOnly() {
        val getter = AlertType.Companion::class.java.getMethod("getSettingsEntries")
        assertFalse(Modifier.isStatic(getter.modifiers))
        assertTrue(List::class.java.isAssignableFrom(getter.returnType))
        val entries = AlertType.settingsEntries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertFalse(entries.contains(AlertType.AVAILABLE))
        assertFalse(entries.contains(AlertType.AMOUNT))
        for (type in listOf(AlertType.LOW, AlertType.HIGH, AlertType.VERY_LOW, AlertType.VERY_HIGH, AlertType.LOSS)) {
            assertTrue(type.name, entries.contains(type))
        }
    }
}
