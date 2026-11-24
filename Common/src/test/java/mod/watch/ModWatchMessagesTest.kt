package mod.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWatchMessages.PumpIob
import tk.glucodata.mod.watch.ModWatchMessages.SnoozeDone
import tk.glucodata.mod.watch.ModWatchMessages.SnoozeRequest
import tk.glucodata.mod.watch.ModWatchMessages.Tick

/** JugglucoNGmod, watch block: the byte format of our phone-watch messages (runs in both test tasks). Made-up values. */
class ModWatchMessagesTest {

    private val at = 1_800_000_000_000L

    @Test
    fun snoozeRoundTrip() {
        val bytes = ModWatchMessages.encodeSnooze(SnoozeRequest(at))
        assertEquals(1 + 8, bytes.size)
        assertEquals(ModWatchMessages.VERSION, bytes[0].toInt())
        assertEquals(SnoozeRequest(at), ModWatchMessages.decodeSnooze(bytes))
    }

    @Test
    fun snoozeDoneRoundTripForASnoozedKindAndForNoAlert() {
        val snoozed = SnoozeDone(7, at)
        assertEquals(snoozed, ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(snoozed)))
        val none = SnoozeDone(null, at)
        val bytes = ModWatchMessages.encodeSnoozeDone(none)
        assertEquals(1 + 4 + 8, bytes.size)
        assertEquals(none, ModWatchMessages.decodeSnoozeDone(bytes))
        val zero = SnoozeDone(0, at)
        assertEquals(zero, ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(zero)))
    }

    @Test
    fun snoozeDoneRoundTripForLate() {
        val late = SnoozeDone(null, at, late = true)
        val bytes = ModWatchMessages.encodeSnoozeDone(late)
        assertEquals(1 + 4 + 8, bytes.size)
        assertEquals(late, ModWatchMessages.decodeSnoozeDone(bytes))
        // "late" wins over a kind: nothing was snoozed
        val lateWithKind = ModWatchMessages.encodeSnoozeDone(SnoozeDone(1, at, late = true))
        assertEquals(late, ModWatchMessages.decodeSnoozeDone(lateWithKind))
    }

    @Test
    fun onlyStandardAlertKindsCountAsSnoozed() {
        for (type in tk.glucodata.alerts.AlertType.settingsEntries) {
            val done = SnoozeDone(type.id, at)
            assertEquals(type.name, done, ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(done)))
        }
        // legacy-only kinds (2, 3), a kind past the list and a value below "late"
        for (kind in listOf(2, 3, 15, 999, Int.MAX_VALUE, -3, Int.MIN_VALUE)) {
            val bytes = ModWatchMessages.encodeSnoozeDone(SnoozeDone(kind, at))
            assertNull("kind $kind", ModWatchMessages.decodeSnoozeDone(bytes))
        }
    }

    @Test
    fun emptyShortAndNullMessagesAreNull() {
        assertNull(ModWatchMessages.decodeSnooze(null))
        assertNull(ModWatchMessages.decodeSnooze(ByteArray(0)))
        assertNull(ModWatchMessages.decodeSnoozeDone(null))
        assertNull(ModWatchMessages.decodeSnoozeDone(ByteArray(0)))
        val snooze = ModWatchMessages.encodeSnooze(SnoozeRequest(at))
        for (n in 1 until snooze.size) assertNull("$n bytes", ModWatchMessages.decodeSnooze(snooze.copyOf(n)))
        val done = ModWatchMessages.encodeSnoozeDone(SnoozeDone(1, at))
        for (n in 1 until done.size) assertNull("$n bytes", ModWatchMessages.decodeSnoozeDone(done.copyOf(n)))
    }

    @Test
    fun versionZeroAndNewerVersionsAreNull() {
        for (version in listOf(0, ModWatchMessages.VERSION + 1, 0x7f, 0xff)) {
            val snooze = ModWatchMessages.encodeSnooze(SnoozeRequest(at)).also { it[0] = version.toByte() }
            assertNull("version $version", ModWatchMessages.decodeSnooze(snooze))
            val done = ModWatchMessages.encodeSnoozeDone(SnoozeDone(1, at)).also { it[0] = version.toByte() }
            assertNull("version $version", ModWatchMessages.decodeSnoozeDone(done))
        }
    }

    @Test
    fun brokenFieldsAreNull() {
        assertNull(ModWatchMessages.decodeSnooze(ModWatchMessages.encodeSnooze(SnoozeRequest(-5))))
        assertNull(ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(SnoozeDone(-3, at))))
        assertNull(ModWatchMessages.decodeSnoozeDone(ModWatchMessages.encodeSnoozeDone(SnoozeDone(1, -1))))
    }

    @Test
    fun anUnknownTailIsIgnored() {
        val snooze = ModWatchMessages.encodeSnooze(SnoozeRequest(at)) + byteArrayOf(9, 8, 7)
        assertEquals(SnoozeRequest(at), ModWatchMessages.decodeSnooze(snooze))
        val done = ModWatchMessages.encodeSnoozeDone(SnoozeDone(4, at)) + ByteArray(16) { 0x55 }
        assertEquals(SnoozeDone(4, at), ModWatchMessages.decodeSnoozeDone(done))
    }

    @Test
    fun theWireFormatIsBigEndianAfterTheVersionByte() {
        val bytes = ModWatchMessages.encodeSnoozeDone(SnoozeDone(null, 0x0102030405060708L))
        assertArrayEquals(byteArrayOf(1, -1, -1, -1, -1, 1, 2, 3, 4, 5, 6, 7, 8), bytes)
        val late = ModWatchMessages.encodeSnoozeDone(SnoozeDone(null, 0x0102030405060708L, late = true))
        assertArrayEquals(byteArrayOf(1, -1, -1, -1, -2, 1, 2, 3, 4, 5, 6, 7, 8), late)
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = java.util.Random(42)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(24)).also { random.nextBytes(it) }
            ModWatchMessages.decodeSnooze(bytes)
            ModWatchMessages.decodeSnoozeDone(bytes)
            ModWatchMessages.decodeTick(bytes)
        }
    }

    @Test
    fun tickRoundTripForEveryReasonAndBatteryState() {
        val reasons = listOf(
            ModWatchMessages.REASON_CYCLE, ModWatchMessages.REASON_BATTERY,
            ModWatchMessages.REASON_START, ModWatchMessages.REASON_WATCH,
        )
        for (reason in reasons) {
            for (tick in listOf(Tick(57, false, at, reason), Tick(0, true, at, reason), Tick(100, true, at, reason),
                Tick(null, false, at, reason))) {
                val bytes = ModWatchMessages.encodeTick(tick)
                assertEquals(1 + 1 + 1 + 8 + 1, bytes.size)
                assertEquals(ModWatchMessages.VERSION, bytes[0].toInt())
                assertEquals(tick, ModWatchMessages.decodeTick(bytes))
            }
        }
        assertEquals(4, reasons.toSet().size)
    }

    @Test
    fun tickWireFormat() {
        val bytes = ModWatchMessages.encodeTick(Tick(42, true, 0x0102030405060708L, ModWatchMessages.REASON_BATTERY))
        assertArrayEquals(byteArrayOf(1, 42, 1, 1, 2, 3, 4, 5, 6, 7, 8, 2), bytes)
        val unknown = ModWatchMessages.encodeTick(Tick(null, false, 0x0102030405060708L, ModWatchMessages.REASON_CYCLE))
        assertArrayEquals(byteArrayOf(1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 1), unknown)
    }

    @Test
    fun aTickPercentOutOfRangeIsSentAsUnknown() {
        for (percent in listOf(-5, 101, 255, Int.MAX_VALUE)) {
            val bytes = ModWatchMessages.encodeTick(Tick(percent, false, at, ModWatchMessages.REASON_CYCLE))
            assertEquals("percent $percent", Tick(null, false, at, ModWatchMessages.REASON_CYCLE),
                ModWatchMessages.decodeTick(bytes))
        }
    }

    @Test
    fun anUnknownTickReasonIsKeptAsItIs() {
        val bytes = ModWatchMessages.encodeTick(Tick(60, false, at, 200))
        assertEquals(Tick(60, false, at, 200), ModWatchMessages.decodeTick(bytes))
    }

    @Test
    fun brokenShortAndOtherVersionTicksAreNull() {
        assertNull(ModWatchMessages.decodeTick(null))
        assertNull(ModWatchMessages.decodeTick(ByteArray(0)))
        val good = ModWatchMessages.encodeTick(Tick(60, true, at, ModWatchMessages.REASON_CYCLE))
        for (n in 1 until good.size) assertNull("$n bytes", ModWatchMessages.decodeTick(good.copyOf(n)))
        for (version in listOf(0, ModWatchMessages.VERSION + 1, 0xff)) {
            assertNull("version $version", ModWatchMessages.decodeTick(good.copyOf().also { it[0] = version.toByte() }))
        }
        // percent past 100 or below "unknown", charging other than 0/1, a negative time
        for (percent in listOf(101, 127, -2, -128)) {
            assertNull("percent $percent", ModWatchMessages.decodeTick(good.copyOf().also { it[1] = percent.toByte() }))
        }
        for (charging in listOf(2, -1, 0x7f)) {
            assertNull("charging $charging", ModWatchMessages.decodeTick(good.copyOf().also { it[2] = charging.toByte() }))
        }
        assertNull(ModWatchMessages.decodeTick(ModWatchMessages.encodeTick(Tick(60, true, -1, 1))))
    }

    @Test
    fun anUnknownTickTailIsIgnored() {
        val tick = Tick(33, false, at, ModWatchMessages.REASON_START)
        assertEquals(tick, ModWatchMessages.decodeTick(ModWatchMessages.encodeTick(tick) + ByteArray(20) { 0x11 }))
    }

    @Test
    fun tickWithThePumpsActiveInsulinRoundTrip() {
        for (iob in listOf(PumpIob(0, 0), PumpIob(23, 45), PumpIob(125, 720), PumpIob(1000, 1),
            PumpIob(ModWatchMessages.IOB_MAX_TENTHS, ModWatchMessages.IOB_MAX_AGE_SEC))) {
            val tick = Tick(57, false, at, ModWatchMessages.REASON_IOB, iob)
            val bytes = ModWatchMessages.encodeTick(tick)
            assertEquals(1 + 1 + 1 + 8 + 1 + 1 + 4 + 4, bytes.size)
            assertEquals(tick, ModWatchMessages.decodeTick(bytes))
        }
        // out of range on the way out: clamped, never a broken message
        val clamped = ModWatchMessages.decodeTick(ModWatchMessages.encodeTick(Tick(57, false, at, 1, PumpIob(-5, -1))))
        assertEquals(PumpIob(0, 0), clamped!!.iob)
    }

    @Test
    fun tickIobWireFormat() {
        val bytes = ModWatchMessages.encodeTick(
            Tick(42, true, 0x0102030405060708L, ModWatchMessages.REASON_IOB, PumpIob(0x0123, 0x0456)),
        )
        assertArrayEquals(byteArrayOf(1, 42, 1, 1, 2, 3, 4, 5, 6, 7, 8, 5, 0x49, 0, 0, 1, 0x23, 0, 0, 4, 0x56), bytes)
    }

    @Test
    fun anOlderReaderTakesTheFirstFieldsOfATickWithTheActiveInsulin() {
        // an older watch reads the 12 bytes it knows: they are the same as a tick without the field
        val with = ModWatchMessages.encodeTick(Tick(60, true, at, ModWatchMessages.REASON_CYCLE, PumpIob(23, 45)))
        val without = ModWatchMessages.encodeTick(Tick(60, true, at, ModWatchMessages.REASON_CYCLE))
        assertArrayEquals(without, with.copyOf(without.size))
        assertEquals(ModWatchMessages.VERSION, with[0].toInt())
    }

    @Test
    fun aTickOfAnOlderPhoneHasNoActiveInsulin() {
        val old = ModWatchMessages.encodeTick(Tick(60, true, at, ModWatchMessages.REASON_CYCLE))
        assertEquals(12, old.size)
        assertNull(ModWatchMessages.decodeTick(old)!!.iob)
    }

    @Test
    fun aBrokenOrCutActiveInsulinIsNoneAndTheTickStays() {
        val good = ModWatchMessages.encodeTick(Tick(60, true, at, ModWatchMessages.REASON_CYCLE, PumpIob(23, 45)))
        val base = Tick(60, true, at, ModWatchMessages.REASON_CYCLE)
        // cut short anywhere in the field
        for (n in 12 until good.size) assertEquals("$n bytes", base, ModWatchMessages.decodeTick(good.copyOf(n)))
        // another mark, tenths or age out of range
        assertEquals(base, ModWatchMessages.decodeTick(good.copyOf().also { it[12] = 0x11 }))
        val tooMany = good.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(13, ModWatchMessages.IOB_MAX_TENTHS + 1) }
        assertEquals(base, ModWatchMessages.decodeTick(tooMany))
        val negative = good.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(17, -1) }
        assertEquals(base, ModWatchMessages.decodeTick(negative))
    }

    @Test
    fun allPathsAreUnderOurPrefixAndNotUpstreams() {
        val paths = listOf(
            ModWatchMessages.PATH_SNOOZE, ModWatchMessages.PATH_SNOOZE_DONE, ModWatchMessages.PATH_TICK,
            ModWatchMessages.PATH_HEALTH_BATCH_PREFIX,
        )
        for (path in paths) assertTrue(path, path.startsWith(ModWatchMessages.PATH_PREFIX))
        assertEquals(paths.size, paths.toSet().size)
        // upstream's MessageReceiver filters (wear and mobile manifests) list their own prefixes only; none of them is
        // a prefix of ours, so their receiver never gets our messages
        for (theirs in tk.glucodata.WearMessagePath.entries) {
            for (path in paths) assertTrue("$theirs vs $path", !path.startsWith(theirs.wire))
        }
    }
}
