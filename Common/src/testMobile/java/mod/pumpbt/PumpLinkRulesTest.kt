package mod.pumpbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.PumpBtBlocker
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtProgress
import tk.glucodata.mod.pumpbt.PumpBtReadiness
import tk.glucodata.mod.pumpbt.PumpBtReturn
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.PumpBtUiState
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules.ServerEvent
import tk.glucodata.mod.pumpbt.core.HistoryReadStatus
import tk.glucodata.mod.pumpbt.core.PumpUuid
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import tk.glucodata.mod.pumpbt.core.ReadOnlyPolicy
import tk.glucodata.mod.pumpbt.core.StatusReaderOpcode
import tk.glucodata.mod.pumpbt.core.bytesOf
import java.util.UUID

/** JugglucoNGmod, pump over Bluetooth (trial): the link's decisions without an Android device. */
class PumpLinkRulesTest {

    @Test
    fun onlyDeviceInformationReadsAndSakeWritesMakeThePump() {
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.CONNECTED))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.DISCONNECTED))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.READ_OTHER))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.WRITE_OTHER))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.WRITE_OTHER_DESCRIPTOR))
        assertTrue(PumpLinkRules.countsAsPump(ServerEvent.READ_DEVICE_INFO))
        assertTrue(PumpLinkRules.countsAsPump(ServerEvent.WRITE_SAKE_PORT))
        assertTrue(PumpLinkRules.countsAsPump(ServerEvent.WRITE_SAKE_CCCD))
    }

    @Test
    fun serverEventsAreClassifiedByUuid() {
        val dis = PumpUuid.parse(PumpUuid.APP_DEVICE_INFO_SERVICE)
        val port = PumpUuid.parse(PumpUuid.SAKE_PORT)
        val cccd = PumpUuid.parse(PumpUuid.CCCD)
        assertEquals(ServerEvent.READ_DEVICE_INFO, PumpLinkRules.classifyRead(dis))
        // the SIG device information service (a sensor's own) is not ours
        assertEquals(ServerEvent.READ_OTHER, PumpLinkRules.classifyRead(PumpUuid.parse(PumpUuid.DEVICE_INFO_SERVICE)))
        assertEquals(ServerEvent.READ_OTHER, PumpLinkRules.classifyRead(null))
        assertEquals(ServerEvent.WRITE_SAKE_PORT, PumpLinkRules.classifyWrite(port))
        assertEquals(ServerEvent.WRITE_OTHER, PumpLinkRules.classifyWrite(UUID.randomUUID()))
        assertEquals(ServerEvent.WRITE_SAKE_CCCD, PumpLinkRules.classifyDescriptorWrite(cccd, port))
        assertEquals(ServerEvent.WRITE_OTHER_DESCRIPTOR, PumpLinkRules.classifyDescriptorWrite(cccd, dis))
        assertEquals(ServerEvent.WRITE_OTHER_DESCRIPTOR, PumpLinkRules.classifyDescriptorWrite(UUID.randomUUID(), port))
    }

    @Test
    fun clientAttachesOnlyToAPumpOnOurServer() {
        assertEquals(PumpLinkRules.Attach.NO_PUMP, PumpLinkRules.clientAttach(pumpKnown = false, serverConnected = true))
        assertEquals(PumpLinkRules.Attach.NO_PUMP, PumpLinkRules.clientAttach(pumpKnown = false, serverConnected = false))
        assertEquals(PumpLinkRules.Attach.NOT_CONNECTED, PumpLinkRules.clientAttach(pumpKnown = true, serverConnected = false))
        assertEquals(PumpLinkRules.Attach.ALLOWED, PumpLinkRules.clientAttach(pumpKnown = true, serverConnected = true))
        assertTrue(PumpLinkRules.Attach.NOT_CONNECTED.text.isNotBlank())
        assertFalse(PumpLinkRules.keepClientConnection(serverConnected = false))
        assertTrue(PumpLinkRules.keepClientConnection(serverConnected = true))
    }

    @Test
    fun advertisingStopsWithThePump() {
        assertNull(PumpLinkRules.advertising(active = false, pumpPresent = false, pairMode = true))
        assertNull(PumpLinkRules.advertising(active = true, pumpPresent = true, pairMode = true))
        assertEquals(PumpLinkRules.Advertising.PAIR, PumpLinkRules.advertising(active = true, pumpPresent = false, pairMode = true))
        assertEquals(PumpLinkRules.Advertising.RECONNECT, PumpLinkRules.advertising(active = true, pumpPresent = false, pairMode = false))
    }

    @Test
    fun pairingAdvertisementFits() {
        val name = "Mobile 012345"
        assertArrayEquals(byteArrayOf(0) + name.toByteArray() + byteArrayOf(0), PumpLinkRules.manufacturerData(name))
        assertTrue(PumpLinkRules.pairAdvertisementBytes(PumpBtNameVariant.A, name) <= 31)
        assertTrue(PumpLinkRules.pairAdvertisementBytes(PumpBtNameVariant.B, name) <= 31)
    }

    @Test
    fun historyPagesFromTheOldestRecordWithoutCap() {
        // no number yet: from the oldest record on the pump
        assertEquals(900L, PumpLinkRules.historyStart(from = null, oldest = 900, newest = 1234))
        val pages = PumpLinkRules.historyPages(900, 1234)
        assertEquals(listOf(900L..999L, 1000L..1099L, 1100L..1199L, 1200L..1234L), pages.map { it.from..it.to })
        // no cap of 200: a long gap is read to the newest, page by page, without overlaps or holes
        val long = PumpLinkRules.historyPages(0, 4_999)
        assertEquals(50, long.size)
        assertEquals(5_000L, long.sumOf { it.to - it.from + 1 })
        assertTrue(long.zipWithNext().all { (a, b) -> b.from == a.to + 1 })
        assertTrue(long.all { it.to - it.from + 1 <= PumpLinkRules.HISTORY_PAGE })
        assertEquals(listOf(7L..7L), PumpLinkRules.historyPages(7, 7).map { it.from..it.to })
        assertTrue(PumpLinkRules.historyPages(8, 7).isEmpty())
    }

    @Test
    fun historyFromOlderThanTheOldestRecordStartsAtTheOldest() {
        assertEquals(900L, PumpLinkRules.historyStart(from = 100, oldest = 900, newest = 1234))
        assertEquals(1000L, PumpLinkRules.historyStart(from = 1000, oldest = 900, newest = 1234))
        // no answer to "first record": the number given is used as it is
        assertEquals(100L, PumpLinkRules.historyStart(from = 100, oldest = null, newest = 1234))
    }

    @Test
    fun newestBelowHistoryFromReadsNothing() {
        // nothing new
        assertNull(PumpLinkRules.historyStart(from = 1235, oldest = 900, newest = 1234))
        // the pump's counter is below the number (another pump): nothing read, the memory sees newestOnPump
        assertNull(PumpLinkRules.historyStart(from = 5000, oldest = 900, newest = 1234))
        // no newest record
        assertNull(PumpLinkRules.historyStart(from = 10, oldest = null, newest = null))
        assertEquals(1234L, PumpLinkRules.historyStart(from = 1234, oldest = 900, newest = 1234))
        // neither a number nor the oldest record: the last 3000 numbers (not below 0)
        assertEquals(0L, PumpLinkRules.historyStart(from = null, oldest = null, newest = 1234))
        assertEquals(7_000L, PumpLinkRules.historyStart(from = null, oldest = null, newest = 10_000))
    }

    @Test
    fun historyDoesNotDependOnFirstRecord() {
        val ok = PumpLinkRules.EndAnswer.SUCCESS
        val bad = PumpLinkRules.EndAnswer.FAILED
        val none = PumpLinkRules.EndAnswer.NO_RECORDS
        val plan = PumpLinkRules::historyPlan
        // both answered
        assertEquals(PumpLinkRules.HistoryPlan.Read(900, 1234, 900, 1234, fallback = false), plan(null, ok, 900, ok, 1234))
        assertEquals(PumpLinkRules.HistoryPlan.Read(1000, 1234, 900, 1234, fallback = false), plan(1000, ok, 900, ok, 1234))
        // "first record" refused, failed, timed out or "no records": oldest unknown, the read goes on
        for (first in listOf(bad, none)) {
            assertEquals(PumpLinkRules.HistoryPlan.Read(1000, 1234, null, 1234, fallback = false), plan(1000, first, 900, ok, 1234))
            assertEquals(PumpLinkRules.HistoryPlan.Read(0, 1234, null, 1234, fallback = true), plan(null, first, null, ok, 1234))
        }
        assertEquals(PumpLinkRules.HistoryPlan.Read(7_000, 10_000, null, 10_000, fallback = true), plan(null, bad, null, ok, 10_000))
        // a number seen during "first record" counts only with its success answer
        assertEquals(PumpLinkRules.HistoryPlan.Read(7_000, 10_000, null, 10_000, fallback = true), plan(null, bad, 9_990, ok, 10_000))
        // oldest above newest (a late record): not trusted
        assertEquals(PumpLinkRules.HistoryPlan.Read(1000, 1234, null, 1234, fallback = false), plan(1000, ok, 5000, ok, 1234))
        // only "last record" decides failure
        assertEquals(PumpLinkRules.HistoryPlan.Failed(900), plan(1000, ok, 900, bad, 1234))
        assertEquals(PumpLinkRules.HistoryPlan.Failed(900), plan(1000, ok, 900, ok, null))
        assertNull(PumpLinkRules.HistoryPlan.Failed(900).newest)
        // no records at all, nothing new
        assertEquals(PumpLinkRules.HistoryPlan.NothingToRead(null, null), plan(1000, bad, null, none, null))
        assertEquals(PumpLinkRules.HistoryPlan.NothingToRead(900, 1234), plan(1235, ok, 900, ok, 1234))
    }

    @Test
    fun lateFirstRecordAnswerIsNotTakenForTheNewest() {
        val ok = PumpLinkRules.EndAnswer.SUCCESS
        val bad = PumpLinkRules.EndAnswer.FAILED
        val plan = PumpLinkRules::historyPlan
        // "first record" went wrong and the "newest" is below the memory's number: failed, no newestOnPump
        val late = plan(1000, bad, null, ok, 900)
        assertEquals(PumpLinkRules.HistoryPlan.Failed(null), late)
        assertNull(late.newest)
        val outcome = PumpLinkRules.historyOutcome(PumpLinkRules.HistoryEnding.FAILED, null, null, late.oldest, late.newest, emptyList())
        assertEquals(HistoryReadStatus.FAILED, outcome.status)
        assertNull(outcome.newestOnPump)
        // just nothing new (newest = number - 1) is not that case
        assertEquals(PumpLinkRules.HistoryPlan.NothingToRead(null, 999), plan(1000, bad, null, ok, 999))
        // with a successful "first record" a smaller newest is given out (the memory decides "new pump")
        assertEquals(PumpLinkRules.HistoryPlan.NothingToRead(10, 900), plan(1000, ok, 10, ok, 900))
        // the pause before "last record" after a timeout sends nothing
        assertNull(PumpLinkRules.LinkOp.HistoryQuiet.write())
        assertTrue(PumpLinkRules.LinkOp.HistoryQuiet.isHistory)
        assertEquals(5_000L, PumpLinkRules.FIRST_RECORD_QUIET_MS)
    }

    @Test
    fun fallbackReadStartsAtTheOldestRecordThatCame() {
        // a page error before any record came: "no records"; after a record: a real failure
        assertTrue(PumpLinkRules.fallbackPageErrorIsEmpty(fallback = true, receivedAny = false))
        assertFalse(PumpLinkRules.fallbackPageErrorIsEmpty(fallback = true, receivedAny = true))
        assertFalse(PumpLinkRules.fallbackPageErrorIsEmpty(fallback = false, receivedAny = false))
        // read 7000..10000, the pump has 9200..10000: start and oldest = 9200, no false holes below
        val r = PumpLinkRules.fallbackOutcome(PumpLinkRules.HistoryEnding.DONE, 7_000, 10_000, 10_000, (9_200L..10_000L).toList())
        assertEquals(HistoryReadStatus.COMPLETE, r.status)
        assertEquals(9_200L, r.requestedFrom)
        assertEquals(9_200L, r.oldestOnPump)
        assertEquals(10_000L, r.contiguousTo)
        assertTrue(r.missing.isEmpty())
        // a real hole above the oldest stays a hole
        val g = PumpLinkRules.fallbackOutcome(PumpLinkRules.HistoryEnding.DONE, 7_000, 10_000, 10_000, (9_200L..9_499L) + (9_501L..10_000L))
        assertEquals(HistoryReadStatus.GAP, g.status)
        assertEquals(9_499L, g.contiguousTo)
        assertEquals(listOf(9_500L..9_500L), g.missing)
        // nothing came although the pump has records: failed
        val none = PumpLinkRules.fallbackOutcome(PumpLinkRules.HistoryEnding.DONE, 7_000, 10_000, 10_000, emptyList())
        assertEquals(HistoryReadStatus.FAILED, none.status)
        assertNull(none.contiguousTo)
        assertNull(none.oldestOnPump)
    }

    @Test
    fun pairingGetsTwoMinutesFromIdentificationToSake() {
        assertEquals(120_000L, PumpLinkRules.identifyToSakeMs(pairMode = true))
        assertEquals(30_000L, PumpLinkRules.identifyToSakeMs(pairMode = false))
    }

    @Test
    fun handshakeTimeLimitExtendsOnlyTwiceWhilePairing() {
        assertTrue(PumpLinkRules.handshakeExtend(bonding = true, extensions = 0))
        assertTrue(PumpLinkRules.handshakeExtend(bonding = true, extensions = 1))
        assertFalse(PumpLinkRules.handshakeExtend(bonding = true, extensions = 2))
        assertFalse(PumpLinkRules.handshakeExtend(bonding = false, extensions = 0))
    }

    @Test
    fun journalShowsAddressesAndThePhoneNameOnlyWhenDetailed() {
        val mac = "C4:AB:12:34:56:78"
        val tag = PumpLinkRules.deviceTag(mac, verbose = false)
        assertFalse(tag.contains("56:78") || tag.contains("C4:AB"))
        assertEquals(tag, PumpLinkRules.deviceTag(mac.lowercase(), verbose = false))
        assertTrue(tag != PumpLinkRules.deviceTag("C4:AB:12:34:56:79", verbose = false))
        assertEquals(mac, PumpLinkRules.deviceTag(mac, verbose = true))
        assertEquals("?", PumpLinkRules.deviceTag(null, verbose = false))
        val line = PumpLinkRules.hideQuoted("phone Bluetooth name given back: \"Galaxy S24 of Somebody\" (accepted)")
        assertFalse(line.contains("Galaxy"))
        assertTrue(line.endsWith("(accepted)"))
    }

    @Test
    fun historyOutcomeIsHonest() {
        val outcome = PumpLinkRules::historyOutcome
        // all came
        val all = outcome(PumpLinkRules.HistoryEnding.DONE, 100, 399, 50, 399, (100L..399L).toList())
        assertEquals(HistoryReadStatus.COMPLETE, all.status)
        assertEquals(399L, all.contiguousTo)
        assertTrue(all.missing.isEmpty())
        assertEquals(50L, all.oldestOnPump)
        assertEquals(399L, all.newestOnPump)
        assertEquals(100L, all.requestedFrom)
        assertEquals(399L, all.requestedTo)

        // a hole in the middle: the number stops before it, the rest still comes
        val gap = outcome(PumpLinkRules.HistoryEnding.DONE, 100, 399, 50, 399, (100L..149L) + (151L..399L))
        assertEquals(HistoryReadStatus.GAP, gap.status)
        assertEquals(149L, gap.contiguousTo)
        assertEquals(listOf(150L..150L), gap.missing)

        // timed out in the middle of the second page: the unread part is missing too
        val late = outcome(PumpLinkRules.HistoryEnding.TIMED_OUT, 100, 399, 50, 399, (100L..249L).toList())
        assertEquals(HistoryReadStatus.TIMED_OUT, late.status)
        assertEquals(249L, late.contiguousTo)
        assertEquals(listOf(250L..399L), late.missing)

        // the link broke before the first record: nothing contiguous
        val broken = outcome(PumpLinkRules.HistoryEnding.FAILED, 100, 399, 50, 399, (120L..130L).toList())
        assertEquals(HistoryReadStatus.FAILED, broken.status)
        assertNull(broken.contiguousTo)
        assertEquals(listOf(100L..119L, 131L..399L), broken.missing)

        // records outside the range and repeats do not count
        val noisy = outcome(PumpLinkRules.HistoryEnding.DONE, 10, 12, 0, 12, listOf(9L, 10L, 10L, 11L, 12L, 13L))
        assertEquals(HistoryReadStatus.COMPLETE, noisy.status)
        assertEquals(12L, noisy.contiguousTo)

        // nothing to ask (no records on the pump, nothing new): complete without records
        val nothing = outcome(PumpLinkRules.HistoryEnding.DONE, null, null, 900, 1234, emptyList())
        assertEquals(HistoryReadStatus.COMPLETE, nothing.status)
        assertNull(nothing.requestedFrom)
        assertNull(nothing.contiguousTo)
        assertEquals(1234L, nothing.newestOnPump)
        val empty = outcome(PumpLinkRules.HistoryEnding.DONE, null, null, null, null, emptyList())
        assertEquals(HistoryReadStatus.COMPLETE, empty.status)
        assertEquals(HistoryReadStatus.FAILED, outcome(PumpLinkRules.HistoryEnding.FAILED, null, null, null, null, emptyList()).status)
    }

    @Test
    fun cycleWritesOnlyWhatTheReadOnlyListAllows() {
        // every write a background cycle can send: the fixed part in all variants and history pages
        val ops = buildList<PumpLinkRules.LinkOp> {
            for (tir in listOf(true, false)) for (features in listOf(true, false)) for (history in listOf(true, false)) {
                addAll(PumpLinkRules.cycleOps(tir, features, history))
            }
            addAll(PumpLinkRules.historyPages(0, 1_234))
            addAll(PumpLinkRules.historyPages(4_294_967_000L, 4_294_967_295L))
        }
        assertTrue(ops.isNotEmpty())
        for (op in ops) {
            val w = op.write()
            if (w == null) {
                assertTrue("$op is a read", op is PumpLinkRules.LinkOp.Read)
                val read = op as PumpLinkRules.LinkOp.Read
                assertTrue("$op on the read list", ReadOnlyPolicy.mayRead(read.characteristic))
            } else {
                val decision = ReadOnlyPolicy.check(w)
                assertTrue("$op: $decision", decision.isAllowed)
                assertFalse(w.characteristic == PumpUuid.normalize(PumpUuid.IDD_COMMAND_CP))
            }
        }
        // time in range is left out once the pump said it does not support it
        assertFalse(
            PumpLinkRules.cycleOps(timeInRange = false, features = true, history = true)
                .any { it == PumpLinkRules.LinkOp.StatusReader(StatusReaderOpcode.GET_TIME_IN_RANGE) },
        )
        // history: "first" and "last" before the pages
        assertEquals(
            listOf(PumpLinkRules.LinkOp.HistoryFirst, PumpLinkRules.LinkOp.HistoryLast),
            PumpLinkRules.cycleOps(timeInRange = true, features = false, history = true).filter { it.isHistory },
        )
        assertTrue(PumpLinkRules.cycleOps(timeInRange = true, features = false, history = false).none { it.isHistory })
    }

    @Test
    fun anotherDeviceOnOurServerGetsNothing() {
        val serves = PumpLinkRules::serves
        // the pump (or the device that may become it) gets the device information and the SAKE port
        assertTrue(serves(ServerEvent.READ_DEVICE_INFO, PumpLinkRules.Claim.TAKE))
        assertTrue(serves(ServerEvent.WRITE_SAKE_PORT, PumpLinkRules.Claim.SAME))
        assertTrue(serves(ServerEvent.WRITE_SAKE_CCCD, PumpLinkRules.Claim.SAME))
        // another device while the pump is there: refused
        val other = PumpLinkRules.claim(known = "A", knownBySake = true, knownLinked = true, device = "B", bySake = true)
        assertEquals(PumpLinkRules.Claim.IGNORE, other)
        assertFalse(serves(ServerEvent.READ_DEVICE_INFO, other))
        assertFalse(serves(ServerEvent.WRITE_SAKE_PORT, other))
        assertFalse(serves(ServerEvent.WRITE_SAKE_CCCD, other))
        // anything that is not the pump's request: refused whoever asks
        for (claim in PumpLinkRules.Claim.entries) {
            assertFalse(serves(ServerEvent.READ_OTHER, claim))
            assertFalse(serves(ServerEvent.WRITE_OTHER, claim))
            assertFalse(serves(ServerEvent.WRITE_OTHER_DESCRIPTOR, claim))
            assertFalse(serves(ServerEvent.CONNECTED, claim))
        }
    }

    @Test
    fun failedPairingIsRecognised() {
        // bond "bonding -> none" of a device on our server or of the pump
        assertTrue(PumpLinkRules.pairingFailedByBond(wasBonding = true, nowNone = true, ourDevice = true))
        assertFalse(PumpLinkRules.pairingFailedByBond(wasBonding = true, nowNone = true, ourDevice = false))
        assertFalse(PumpLinkRules.pairingFailedByBond(wasBonding = false, nowNone = true, ourDevice = true))
        assertFalse(PumpLinkRules.pairingFailedByBond(wasBonding = true, nowNone = false, ourDevice = true))
        // left within 5 s without counting as the pump, while advertising for pairing (27.11: after 0.9 s)
        assertTrue(PumpLinkRules.pairingFailedByDrop(pairMode = true, identified = false, connectedForMs = 900))
        assertFalse(PumpLinkRules.pairingFailedByDrop(pairMode = true, identified = false, connectedForMs = 5_000))
        assertFalse(PumpLinkRules.pairingFailedByDrop(pairMode = true, identified = true, connectedForMs = 900))
        assertFalse(PumpLinkRules.pairingFailedByDrop(pairMode = false, identified = false, connectedForMs = 900))
        assertFalse(PumpLinkRules.pairingFailedByDrop(pairMode = true, identified = false, connectedForMs = null))
    }

    @Test
    fun advertisingPauseGrowsToAMinuteAndNeverStops() {
        val delays = (0..20).map { PumpLinkRules.recoverDelayMs(it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L), delays.take(7))
        assertTrue(delays.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(delays.all { it in 1_000L..PumpLinkRules.RECOVER_MAX_MS })
        assertEquals(60_000L, PumpLinkRules.recoverDelayMs(Int.MAX_VALUE))
        assertEquals(30_000L, PumpLinkRules.HANDSHAKE_TIMEOUT_MS)
    }

    @Test
    fun wakeHoldIsShortAndCapped() {
        assertEquals(30_000L, PumpLinkRules.wakeHoldMs(0))
        assertEquals(30_000L, PumpLinkRules.wakeHoldMs(100_000))
        assertEquals(10_000L, PumpLinkRules.wakeHoldMs(170_000))
        assertEquals(0L, PumpLinkRules.wakeHoldMs(180_000))
        assertEquals(0L, PumpLinkRules.wakeHoldMs(600_000))
    }

    @Test
    fun statusReaderAnswersEndOnlyTheirOwnRequest() {
        // answer opcodes map to their requests
        assertEquals(0x03F3, PumpLinkRules.statusReaderAnswerFor(bytesOf(0xFC, 0x03, 0x00, 0x00)))
        assertEquals(0x0365, PumpLinkRules.statusReaderAnswerFor(bytesOf(0x6A, 0x03, 0x00)))
        // "response code" answer: the request inside (740G: time in range not supported)
        val unsupported = bytesOf(0x03, 0x03, 0x01, 0x04, 0x70)
        assertEquals(0x0401, PumpLinkRules.statusReaderAnswerFor(unsupported))
        assertEquals(StatusReaderOpcode.GET_TIME_IN_RANGE, PumpLinkRules.statusReaderUnsupported(unsupported))
        assertNull(PumpLinkRules.statusReaderUnsupported(bytesOf(0x03, 0x03, 0x01, 0x04, 0x0F)))
        assertNull(PumpLinkRules.statusReaderUnsupported(bytesOf(0x02, 0x04, 0x00)))
        // unknown or short
        assertNull(PumpLinkRules.statusReaderAnswerFor(bytesOf(0x34, 0x12)))
        assertNull(PumpLinkRules.statusReaderAnswerFor(bytesOf(0x03)))
        assertNull(PumpLinkRules.statusReaderAnswerFor(bytesOf(0x03, 0x03, 0x01)))
    }

    @Test
    fun racpAnswerCodes() {
        assertEquals(PumpLinkRules.RACP_SUCCESS, PumpLinkRules.racpCode(bytesOf(0x0F, 0x0F, 0x33, 0xF0)))
        assertEquals(PumpLinkRules.RACP_NO_RECORDS, PumpLinkRules.racpCode(bytesOf(0x0F, 0x0F, 0x33, 0x06)))
        assertNull(PumpLinkRules.racpCode(bytesOf(0x0F, 0x0F, 0x33)))
        assertNull(PumpLinkRules.racpCode(bytesOf(0x01, 0x0F, 0x33, 0xF0)))
        // the request opcode inside the answer is checked
        assertEquals(PumpLinkRules.RACP_SUCCESS, PumpLinkRules.racpCode(bytesOf(0x0F, 0x0F, 0x33, 0xF0), request = 0x33))
        assertNull(PumpLinkRules.racpCode(bytesOf(0x0F, 0x0F, 0x5A, 0xF0), request = 0x33))
    }

    @Test
    fun pumpStringsWithoutNulBytesAndBlanks() {
        assertEquals("NG1234567H", PumpLinkRules.pumpString("NG1234567H\u0000".toByteArray()))
        assertEquals("6.28.3", PumpLinkRules.pumpString(" 6.28.3 \u0000\u0000".toByteArray()))
        assertEquals("", PumpLinkRules.pumpString(byteArrayOf(0, 0)))
    }

    @Test
    fun journalCountsRecordTypesWithoutValues() {
        val records = listOf(
            RawHistoryRecord(1, 0xF00E, 0, ByteArray(8)),
            RawHistoryRecord(2, 0x0069, 10, ByteArray(13) { 7 }),
            RawHistoryRecord(3, 0x0069, 20, ByteArray(13) { 9 }),
        )
        val line = PumpLinkRules.typeCounts(records)
        assertEquals("0x0069×2, 0xF00E×1", line)
        assertEquals("", PumpLinkRules.typeCounts(emptyList()))
    }

    @Test
    fun afterACommandTimeoutTheNextOneWaitsForAndroid() {
        assertTrue(PumpLinkRules.gattRetry(50, sinceTimeoutMs = 1_000))
        assertTrue(PumpLinkRules.gattRetry(0, sinceTimeoutMs = 29_999))
        assertFalse(PumpLinkRules.gattRetry(0, sinceTimeoutMs = 30_000))
    }

    @Test
    fun bondShownAsPairing() {
        assertTrue(PumpLinkRules.bondCountsAsPairing("A", pump = "A", onOurServer = false, pairMode = false))
        assertFalse(PumpLinkRules.bondCountsAsPairing("B", pump = "A", onOurServer = true, pairMode = true))
        assertTrue(PumpLinkRules.bondCountsAsPairing("B", pump = null, onOurServer = true, pairMode = true))
        assertFalse(PumpLinkRules.bondCountsAsPairing("B", pump = null, onOurServer = false, pairMode = true))
        assertFalse(PumpLinkRules.bondCountsAsPairing("B", pump = null, onOurServer = true, pairMode = false))
    }

    @Test
    fun sakeWriterOverridesADeviceThatOnlyReadDeviceInformation() {
        val claim = PumpLinkRules::claim
        assertEquals(PumpLinkRules.Claim.TAKE, claim(null, false, false, "A", false))
        assertEquals(PumpLinkRules.Claim.SAME, claim("A", false, true, "A", true))
        // another device known only from reading 0x0900 and still connected: a SAKE write takes over, a read does not
        assertEquals(PumpLinkRules.Claim.TAKE, claim("A", false, true, "B", true))
        assertEquals(PumpLinkRules.Claim.IGNORE, claim("A", false, true, "B", false))
        // the known device wrote to SAKE itself: it stays while connected
        assertEquals(PumpLinkRules.Claim.IGNORE, claim("A", true, true, "B", true))
        // the known device left our server: anyone may take over
        assertEquals(PumpLinkRules.Claim.TAKE, claim("A", true, false, "B", false))
    }

    @Test
    fun disconnectBeforeThePumpIsIdentifiedAdvertisesAgain() {
        val drop = PumpLinkRules::serverDisconnect
        assertEquals(PumpLinkRules.Drop.ADVERTISE_AGAIN, drop(true, false, false, false))
        assertEquals(PumpLinkRules.Drop.NOTHING, drop(false, false, false, false))
        // the pump's own address
        assertEquals(PumpLinkRules.Drop.PUMP_LOST, drop(true, true, true, true))
        // another address (identity vs random) and the pump does not look connected: check again first
        assertEquals(PumpLinkRules.Drop.RECHECK, drop(true, true, false, false))
        // another device (e.g. the Sibionics sensor) left, the pump is still there
        assertEquals(PumpLinkRules.Drop.NOTHING, drop(true, true, false, true))
        // stopped: a known pump is still cleaned up
        assertEquals(PumpLinkRules.Drop.PUMP_LOST, drop(false, true, true, false))
    }

    @Test
    fun pumpCountsAsGoneOnlyWhenTheRecheckAgrees() {
        assertTrue(PumpLinkRules.recheckLost(samePump = true, stillLinked = false))
        // back on our server after a second (a false "not connected" before): not lost
        assertFalse(PumpLinkRules.recheckLost(samePump = true, stillLinked = true))
        // meanwhile another device became the pump or the pump was handled already
        assertFalse(PumpLinkRules.recheckLost(samePump = false, stillLinked = false))
        assertEquals(1_000L, PumpLinkRules.RECHECK_MS)
    }

    @Test
    fun stageGoesBackOnlyWhenThePairingFailedOrItsDeviceLeft() {
        val back = PumpLinkRules::stageAfterDrop
        // another device left: the stage stays (BONDED can still turn "pairing" into "pump connected")
        assertNull(back(PumpBtStage.PAIRING, true, false, false))
        assertNull(back(PumpBtStage.PUMP_CONNECTED, false, false, false))
        // pairing failed (BOND_NONE after BONDING)
        assertEquals(PumpBtStage.ADVERTISING_PAIR, back(PumpBtStage.PAIRING, true, true, false))
        // the device whose pairing is shown left
        assertEquals(PumpBtStage.WAITING, back(PumpBtStage.PUMP_CONNECTED, false, false, true))
        // "connection lost", errors and advertising stay
        assertNull(back(PumpBtStage.LOST, false, true, true))
        assertNull(back(PumpBtStage.ERROR, true, true, false))
        assertNull(back(PumpBtStage.ADVERTISING_PAIR, true, true, false))
        assertEquals(500L, PumpLinkRules.ADVERTISE_AGAIN_DELAY_MS)
    }

    @Test
    fun gattCommandRetriedTenTimesThenDropped() {
        assertTrue(PumpLinkRules.gattRetry(0))
        assertTrue(PumpLinkRules.gattRetry(9))
        assertFalse(PumpLinkRules.gattRetry(10))
        assertEquals(10, (0 until 100).takeWhile { PumpLinkRules.gattRetry(it) }.size)
        assertEquals(500L, PumpLinkRules.GATT_RETRY_MS)
    }

    @Test
    fun notificationFailuresLoggedFirstAndEvery20th() {
        assertEquals(listOf(1, 20, 40, 60), (0..60).filter { PumpLinkRules.logNotifyFailure(it) })
    }

    @Test
    fun onlyPumpLikeBondedNamesAreWritten() {
        assertTrue(PumpLinkRules.pumpLikeName("Pump 123456"))
        assertTrue(PumpLinkRules.pumpLikeName("MiniMed 740G"))
        assertTrue(PumpLinkRules.pumpLikeName("Medtronic"))
        assertFalse(PumpLinkRules.pumpLikeName("Galaxy Watch5 Pro"))
        assertFalse(PumpLinkRules.pumpLikeName(null))
    }

    @Test
    fun bondLineHasOneFormat() {
        assertEquals(
            "bond state of device-1a2b: bonded → none, reason 9",
            PumpLinkRules.bondLine("device-1a2b", PumpLinkRules.BOND_BONDED, PumpLinkRules.BOND_NONE, 9),
        )
        assertEquals(
            "bond state of ?: unknown → bonding, reason -1",
            PumpLinkRules.bondLine("?", -1, PumpLinkRules.BOND_BONDING, -1),
        )
        // the platform's values
        assertEquals(android.bluetooth.BluetoothDevice.BOND_NONE, PumpLinkRules.BOND_NONE)
        assertEquals(android.bluetooth.BluetoothDevice.BOND_BONDING, PumpLinkRules.BOND_BONDING)
        assertEquals(android.bluetooth.BluetoothDevice.BOND_BONDED, PumpLinkRules.BOND_BONDED)
    }

    @Test
    fun sourceWritesTheBondLineOnlyForOurPumpWhileTheLinkIsNotRunning() {
        val pump = "C4:AB:12:34:56:78"
        val tag = PumpLinkRules.deviceTag(pump, verbose = false)
        val line = PumpLinkRules.bondLine(tag, PumpLinkRules.BOND_BONDED, PumpLinkRules.BOND_NONE, 9)
        assertEquals(line, PumpLinkRules.sourceBondLine(false, ours = true, device = tag, before = PumpLinkRules.BOND_BONDED, now = PumpLinkRules.BOND_NONE, reason = 9))
        // a running link writes the line itself: no line twice
        assertNull(PumpLinkRules.sourceBondLine(true, ours = true, device = tag, before = PumpLinkRules.BOND_BONDED, now = PumpLinkRules.BOND_NONE, reason = 9))
        // another device (a watch, headphones)
        assertNull(PumpLinkRules.sourceBondLine(false, ours = false, device = tag, before = PumpLinkRules.BOND_BONDED, now = PumpLinkRules.BOND_NONE, reason = 9))

        assertTrue(PumpLinkRules.ourPumpBond(pump.lowercase(), null, pump))
        assertTrue(PumpLinkRules.ourPumpBond("00:11:22:33:44:55", "Pump 123456", pump))
        assertTrue(PumpLinkRules.ourPumpBond(null, null, pump))
        assertFalse(PumpLinkRules.ourPumpBond("00:11:22:33:44:55", "Galaxy Watch5 Pro", pump))
        assertFalse(PumpLinkRules.ourPumpBond("00:11:22:33:44:55", null, null))
    }

    @Test
    fun failedPairingBeforeThePumpIsIdentifiedAdvertisesAgain() {
        assertEquals(PumpLinkRules.Drop.ADVERTISE_AGAIN, PumpLinkRules.bondFailed(running = true, pumpKnown = false, wasBonding = true, nowNone = true))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = true, pumpKnown = true, wasBonding = true, nowNone = true))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = true, pumpKnown = false, wasBonding = false, nowNone = true))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = true, pumpKnown = false, wasBonding = true, nowNone = false))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = false, pumpKnown = false, wasBonding = true, nowNone = true))
    }

    @Test
    fun dataReadOnlyWithTheSerialNumber() {
        assertEquals(PumpBtStage.DATA_READ, PumpLinkRules.cycleDoneStage(serialRead = true))
        assertEquals(PumpBtStage.READING, PumpLinkRules.cycleDoneStage(serialRead = false))
    }

    @Test
    fun furthestStageOnlyMovesForward() {
        var f: PumpBtStage? = null
        for (s in listOf(PumpBtStage.ADVERTISING_PAIR, PumpBtStage.PAIRING, PumpBtStage.PUMP_CONNECTED, PumpBtStage.HANDSHAKE)) {
            f = PumpBtProgress.furthest(f, s)
        }
        assertEquals(PumpBtStage.HANDSHAKE, f)
        // an error, a lost link or going back to advertising do not move it back
        f = PumpBtProgress.furthest(f, PumpBtStage.ERROR)
        f = PumpBtProgress.furthest(f, PumpBtStage.LOST)
        f = PumpBtProgress.furthest(f, PumpBtStage.WAITING)
        assertEquals(PumpBtStage.HANDSHAKE, f)
        assertEquals(PumpBtStage.DATA_READ, PumpBtProgress.furthest(f, PumpBtStage.DATA_READ))
        assertNull(PumpBtProgress.furthest(null, PumpBtStage.STOPPED))
    }

    @Test
    fun lastThreeReturnsNewestFirst() {
        var returns = emptyList<PumpBtReturn>()
        for (i in 1..5L) returns = PumpBtProgress.addReturn(returns, PumpBtReturn(lostAtMs = i * 1000, afterMs = i))
        assertEquals(listOf(5L, 4L, 3L), returns.map { it.afterMs })
    }

    @Test
    fun startNeedsBluetoothAdvertisingAndKey() {
        assertEquals(
            listOf(PumpBtBlocker.NO_BLUETOOTH, PumpBtBlocker.NO_PERMISSION, PumpBtBlocker.NO_KEY),
            PumpBtReadiness.blockers(hasBluetooth = false, on = false, permissions = false, advertising = null, key = false),
        )
        assertEquals(listOf(PumpBtBlocker.BLUETOOTH_OFF), PumpBtReadiness.blockers(true, false, true, null, true))
        assertEquals(listOf(PumpBtBlocker.NO_ADVERTISING), PumpBtReadiness.blockers(true, true, true, false, true))
        assertTrue(PumpBtReadiness.blockers(true, true, true, true, true).isEmpty())

        val ready = PumpBtUiState(hasBluetooth = true, bluetoothOn = true, permissionsGranted = true, advertisingSupported = true, keyCrc = "01020304")
        assertTrue(ready.canStart)
        // only the permissions missing: the start asks for them
        assertTrue(ready.copy(permissionsGranted = false).canStart)
        assertFalse(ready.copy(keyCrc = null).canStart)
        assertFalse(ready.copy(advertisingSupported = false).canStart)
        assertFalse(ready.copy(link = PumpBtLinkState(running = true)).canStart)
    }
}
