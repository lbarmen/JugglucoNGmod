package mod.aod

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.aod.ModAodWindow

/** JugglucoNGmod phone AOD: when the overlay window is put up again (only while dozing, at most once a minute). */
class ModAodWindowTest {

    @Test
    fun onlySamsungPhones() {
        assertTrue(ModAodWindow.applies("samsung"))
        assertTrue(ModAodWindow.applies("SAMSUNG"))
        assertFalse(ModAodWindow.applies("Google"))
        assertFalse(ModAodWindow.applies("Xiaomi"))
        assertFalse(ModAodWindow.applies(null))
    }

    @Test
    fun onlyTheDozingStatesCount() {
        assertTrue(ModAodWindow.dozing(Display.STATE_DOZE))
        assertTrue(ModAodWindow.dozing(Display.STATE_DOZE_SUSPEND))
        assertFalse(ModAodWindow.dozing(Display.STATE_ON))
        assertFalse(ModAodWindow.dozing(Display.STATE_OFF))
        assertFalse(ModAodWindow.dozing(Display.STATE_UNKNOWN))
    }

    @Test
    fun atMostOnceAMinuteAndNeverOnTheLitScreen() {
        val suspended = Display.STATE_DOZE_SUSPEND
        val t = 1_000_000L
        assertTrue(ModAodWindow.due(suspended, t, 0L))
        assertFalse(ModAodWindow.due(suspended, t + 30_000L, t))
        assertFalse(ModAodWindow.due(suspended, t + ModAodWindow.MIN_INTERVAL_MS - 1, t))
        assertTrue(ModAodWindow.due(suspended, t + ModAodWindow.MIN_INTERVAL_MS, t))
        assertFalse(ModAodWindow.due(Display.STATE_ON, t + 120_000L, t))
        assertFalse(ModAodWindow.due(Display.STATE_ON, t, 0L))
    }
}
