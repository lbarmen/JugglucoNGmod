package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.health.SampleBuffer

/** JugglucoNGmod, watch block (part 4): the queue files and their lines (ported from Glucove). Made-up values. */
class SampleBufferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun hr(t: Long) = Sample(ModHealthBatch.KIND_HR5, t, 70f, "n=3;min=68;max=72")

    @Test
    fun appendThenTakeOutboxMovesEverything() {
        val buffer = SampleBuffer(tmp.newFolder("q"))
        buffer.append(listOf(hr(1), hr(2)))
        buffer.append(listOf(hr(3)))
        assertEquals(3, buffer.count())
        val files = buffer.takeOutbox(nowMs = 1_000)
        assertEquals(1, files.size)
        assertEquals(listOf(hr(1), hr(2), hr(3)), buffer.read(files[0]))
        assertEquals(3, buffer.count())
        assertTrue(buffer.hasOutbox())
    }

    @Test
    fun appendDuringSendGoesToNewPendingAndSurvivesDone() {
        val buffer = SampleBuffer(tmp.newFolder("q"))
        buffer.append(listOf(hr(1)))
        val files = buffer.takeOutbox(nowMs = 1_000)
        buffer.append(listOf(hr(2)))
        buffer.done(files[0])
        assertEquals(1, buffer.count())
        val next = buffer.takeOutbox(nowMs = 2_000)
        assertEquals(listOf(hr(2)), next.flatMap { buffer.read(it) })
    }

    @Test
    fun failedSendKeepsOutboxForNextTimeInOrder() {
        val buffer = SampleBuffer(tmp.newFolder("q"))
        buffer.append(listOf(hr(1)))
        buffer.takeOutbox(nowMs = 1_000) // the send failed - done() not called
        buffer.append(listOf(hr(2)))
        val files = buffer.takeOutbox(nowMs = 2_000)
        assertEquals(2, files.size)
        assertEquals(listOf(hr(1), hr(2)), files.flatMap { buffer.read(it) })
    }

    @Test
    fun sameMillisecondGetsSeparateFiles() {
        val buffer = SampleBuffer(tmp.newFolder("q"))
        buffer.append(listOf(hr(1)))
        buffer.takeOutbox(nowMs = 5_000)
        buffer.append(listOf(hr(2)))
        val files = buffer.takeOutbox(nowMs = 5_000)
        assertEquals(2, files.size)
        assertEquals(listOf(hr(1), hr(2)), files.flatMap { buffer.read(it) })
    }

    @Test
    fun nothingPendingGivesNoOutbox() {
        val buffer = SampleBuffer(tmp.newFolder("q"))
        assertTrue(buffer.takeOutbox(nowMs = 1).isEmpty())
        assertEquals(0, buffer.count())
        assertFalse(buffer.hasOutbox())
    }

    @Test
    fun trimDropsOldestOutboxFilesOverLimit() {
        val dir = tmp.newFolder("q")
        val buffer = SampleBuffer(dir, maxBytes = 80)
        buffer.append(listOf(hr(1), hr(2)))
        val first = buffer.takeOutbox(nowMs = 1_000).single()
        buffer.append(listOf(hr(3), hr(4)))
        buffer.takeOutbox(nowMs = 2_000)
        assertEquals(1, buffer.trim())
        assertFalse(first.exists())
        assertEquals(listOf(hr(3), hr(4)), buffer.takeOutbox(nowMs = 3_000).flatMap { buffer.read(it) })
    }

    @Test
    fun clockSetBackStillQueuesTheNewFileLastAndTrimDropsTheOldOne() {
        val buffer = SampleBuffer(tmp.newFolder("q"), maxBytes = 80)
        buffer.append(listOf(hr(1), hr(2)))
        val first = buffer.takeOutbox(nowMs = 1_800_000_000_000L).single()
        buffer.append(listOf(hr(3), hr(4)))
        val files = buffer.takeOutbox(nowMs = 1_800_000_000_000L - 3_600_000L) // the clock went back an hour
        assertEquals(listOf(hr(1), hr(2), hr(3), hr(4)), files.flatMap { buffer.read(it) })
        assertEquals(1, buffer.trim())
        assertFalse(first.exists())
    }

    @Test
    fun lineRoundTrip() {
        val samples = listOf(
            Sample(ModHealthBatch.KIND_HR5, 1_800_000_000_000L, 72.25f, "n=5;min=70;max=75"),
            Sample(ModHealthBatch.KIND_STEPS_DAY, 1_800_000_060_000L, 1234f, "1799964000000"),
            Sample(ModHealthBatch.KIND_BATTERY, 1_800_000_060_000L, 55f),
        )
        for (s in samples) assertEquals(s, SampleBuffer.fromLine(SampleBuffer.toLine(s)))
    }

    @Test
    fun tabsAndNewlinesInExtraBecomeSpaces() {
        val s = Sample(ModHealthBatch.KIND_STATUS, 1L, 0f, "a\tb\nc\rd")
        assertEquals("a b c d", SampleBuffer.fromLine(SampleBuffer.toLine(s))!!.extra)
    }

    @Test
    fun brokenLinesAreSkipped() {
        assertNull(SampleBuffer.fromLine(""))
        assertNull(SampleBuffer.fromLine("hr5\t123"))
        assertNull(SampleBuffer.fromLine("hr5\tweek\t72.0\t"))
        assertNull(SampleBuffer.fromLine("hr5\t123\tmany\t"))
        assertNull(SampleBuffer.fromLine("\t123\t72.0\t"))
    }
}
