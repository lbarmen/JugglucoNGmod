package mod.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.mod.watch.AlertSendMemory
import tk.glucodata.mod.watch.SendStamps
import tk.glucodata.mod.watch.ModAlertSettings.Settings
import tk.glucodata.mod.watch.WatchAlertSettings

/**
 * JugglucoNGmod, watch block (part 7d): when a tick carries the alert settings - changed, new or returning watch, an
 * hour old; not every 5 minutes. Pure rules, no Data Layer. Made-up values.
 */
class AlertSendMemoryTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun settings(seconds: Int) = listOf(Settings(
        kind = 1, soundEnabled = true, vibrationEnabled = true, soundDelayEnabled = false, retryEnabled = false,
        alarmDurationSeconds = seconds, hapticProfile = HapticProfile.STRONG, soundVolumePercent = 100,
        soundDelaySeconds = 0, retryIntervalMinutes = 5, retryCount = 3, defaultSnoozeMinutes = 15, thresholdMgPerL = 1620,
    ))

    @Test
    fun theSameSettingsGoAgainOnlyAfterAnHour() {
        val memory = AlertSendMemory()
        assertTrue(memory.due("w", settings(10), now))
        memory.sent("w", settings(10), now)
        assertFalse(memory.due("w", settings(10), now + 5 * minute))
        assertFalse(memory.due("w", settings(10), now + WatchAlertSettings.RESEND_MS - 1))
        assertTrue(memory.due("w", settings(10), now + WatchAlertSettings.RESEND_MS))
        // a clock set back counts as long ago
        assertTrue(memory.due("w", settings(10), now - minute))
    }

    @Test
    fun changedSettingsGoAtTheNextTick() {
        val memory = AlertSendMemory()
        memory.sent("w", settings(10), now)
        assertTrue(memory.due("w", settings(11), now + 5 * minute))
        assertTrue(memory.due("w", settings(10).map { it.copy(thresholdMgPerL = 1630) }, now + 5 * minute))
    }

    @Test
    fun aFailedSendAMissingWatchOrAReturningOneIsDueAgain() {
        val memory = AlertSendMemory()
        memory.sent("a", settings(10), now)
        memory.sent("b", settings(10), now)
        memory.failed("a")
        assertTrue(memory.due("a", settings(10), now + minute))
        // "b" was not reachable at a tick: forgotten, due when back
        memory.keepOnly(listOf("a"))
        assertTrue(memory.due("b", settings(10), now + minute))
        memory.sent("a", settings(10), now)
        memory.forget()
        assertTrue(memory.due("a", settings(10), now + minute))
        // a watch per watch: one sent, another still due
        memory.sent("a", settings(10), now)
        assertFalse(memory.due("a", settings(10), now + minute))
        assertTrue(memory.due("c", settings(10), now + minute))
    }

    @Test
    fun theSendStampNeverGoesBack() {
        val stamps = SendStamps()
        assertEquals(now, stamps.next(now))
        assertEquals(now + minute, stamps.next(now + minute))
        // the phone clock set back 3 minutes (critic M1 of part 7d): still after the last one, so the watch accepts it
        assertEquals(now + minute + 1, stamps.next(now - 2 * minute))
        assertEquals(now + minute + 2, stamps.next(now + minute))
        // the clock caught up: its own time again
        assertEquals(now + 2 * minute, stamps.next(now + 2 * minute))
    }
}
