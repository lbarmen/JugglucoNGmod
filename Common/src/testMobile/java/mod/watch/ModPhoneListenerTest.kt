package mod.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.mod.watch.ModPhoneListener
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.OngoingAlert
import tk.glucodata.mod.watch.ModWatchMessages.SnoozeDone
import tk.glucodata.mod.watch.ModWatchMessages.SnoozeRequest

/** JugglucoNGmod, watch block: the phone's answer to the watch's snooze button. Made-up times only. */
class ModPhoneListenerTest {

    private val pressedAt = 1_800_000_000_000L
    private val now = 1_800_000_001_000L
    private val request = ModWatchMessages.encodeSnooze(SnoozeRequest(pressedAt))

    @Test
    fun aSnoozedAlertIsAnsweredWithItsKind() {
        var calls = 0
        val reply = ModPhoneListener.answerSnooze(request, now) { calls++; 5 }
        assertEquals(1, calls)
        assertEquals(SnoozeDone(5, now), ModWatchMessages.decodeSnoozeDone(reply))
    }

    @Test
    fun noAlertIsAnsweredWithNoAlert() {
        val reply = ModPhoneListener.answerSnooze(request, now) { null }
        assertEquals(SnoozeDone(null, now), ModWatchMessages.decodeSnoozeDone(reply))
    }

    @Test
    fun aLateMainThreadIsAnsweredLateNotNoAlert() {
        val reply = ModPhoneListener.answerSnooze(request, now) { OngoingAlert.LATE }
        assertEquals(SnoozeDone(null, now, late = true), ModWatchMessages.decodeSnoozeDone(reply))
    }

    @Test
    fun aStalePressSnoozesNothingAndIsNotAnswered() {
        val max = ModPhoneListener.MAX_PRESS_AGE_MS
        var calls = 0
        // held back by Play services, or the watch clock far ahead
        for (shift in listOf(max + 1, -(max + 1), 60 * 60_000L)) {
            assertNull("shift $shift", ModPhoneListener.answerSnooze(request, pressedAt + shift) { calls++; 5 })
        }
        assertEquals(0, calls)
        // within the window either way: answered
        for (shift in listOf(max, -max, 0L)) {
            assertEquals(SnoozeDone(5, pressedAt + shift),
                ModWatchMessages.decodeSnoozeDone(ModPhoneListener.answerSnooze(request, pressedAt + shift) { 5 }))
        }
    }

    @Test
    fun aBadMessageSnoozesNothingAndIsNotAnswered() {
        var calls = 0
        for (bad in listOf(null, ByteArray(0), byteArrayOf(1, 2, 3), request.copyOf().also { it[0] = 2 })) {
            assertNull(ModPhoneListener.answerSnooze(bad, now) { calls++; 1 })
        }
        assertEquals(0, calls)
    }
}
