package mod.watch.face

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceGraph
import tk.glucodata.mod.watch.face.FaceLayout
import tk.glucodata.mod.watch.SignalBudget
import tk.glucodata.mod.watch.face.FacePoint
import tk.glucodata.mod.watch.face.FaceReading
import tk.glucodata.mod.watch.face.FaceStyle
import tk.glucodata.mod.watch.face.ModFaceRenderer
import tk.glucodata.mod.watch.face.ReadPause

/**
 * JugglucoNGmod, watch block (part 3c2): the graph's hour lines (count and place for 1 / 3 / 6 h, across midnight,
 * 12 / 24 h labels, labels kept off the round edge and off each other), the trace cut at the target lines, the face's
 * read pacing and the editor's action in the manifest. Made-up times and values.
 */
class FaceHoursAndRangeTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun at(h: Int, m: Int, day: Int = 17) =
        LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ hour marks

    @Test
    fun oneThreeAndSixLinesForTheWindowsInOrder() {
        val now = at(10, 8)
        for (hours in FaceGraph.HOURS) {
            val marks = FaceGraph.hourMarks(now - hours * hour, now, zone, hour24 = true)
            assertEquals("window $hours h", hours, marks.size)
            assertEquals((10 - hours + 1..10).map { it.toString() }, marks.map { it.label })
            assertEquals((10 - hours + 1..10).map { at(it, 0) }, marks.map { it.timeMs })
        }
    }

    @Test
    fun aWindowStartingOnAFullHourHasNoLineAtItsLeftEdgeButOneAtItsRight() {
        val now = at(10, 0)
        val marks = FaceGraph.hourMarks(now - 3 * hour, now, zone, hour24 = true)
        assertEquals(listOf("8", "9", "10"), marks.map { it.label })
        assertEquals(now, marks.last().timeMs)
    }

    @Test
    fun acrossMidnightThe24And12HourLabels() {
        val now = at(1, 30, day = 18)
        val from = now - 6 * hour
        assertEquals(listOf("20", "21", "22", "23", "0", "1"),
            FaceGraph.hourMarks(from, now, zone, hour24 = true).map { it.label })
        assertEquals(listOf("8", "9", "10", "11", "12", "1"),
            FaceGraph.hourMarks(from, now, zone, hour24 = false).map { it.label })
        // the midnight line is at local midnight, not at a UTC hour
        assertEquals(at(0, 0, day = 18), FaceGraph.hourMarks(from, now, zone, hour24 = true)[4].timeMs)
    }

    @Test
    fun aZoneWithHalfHourOffsetGetsLocalFullHours() {
        val india = ZoneId.of("Asia/Kolkata")
        val now = LocalDateTime.of(2026, 11, 17, 10, 8).atZone(india).toInstant().toEpochMilli()
        val marks = FaceGraph.hourMarks(now - hour, now, india, hour24 = true)
        assertEquals(1, marks.size)
        assertEquals(LocalDateTime.of(2026, 11, 17, 10, 0).atZone(india).toInstant().toEpochMilli(), marks[0].timeMs)
    }

    @Test
    fun anEmptyOrBackwardsWindowHasNoLines() {
        val now = at(10, 8)
        assertTrue(FaceGraph.hourMarks(now, now, zone, true).isEmpty())
        assertTrue(FaceGraph.hourMarks(now, now - hour, zone, true).isEmpty())
    }

    // ------------------------------------------------------------------ hour lines on the face

    @Test
    fun theLinesSitAtTheirTimeInsideThePlotAndTheLabelsFit() {
        for (size in listOf(384, 450)) {
            val layout = FaceLayout.compute(size, size)
            val plotLeft = layout.graph.left
            val plotRight = layout.graph.right - 0.1f * size
            val now = at(10, 8)
            for (hours in FaceGraph.HOURS) {
                val plot = FaceGraph.plot(emptyList(), now, hours, 3.9f, 10f, isMmol = true)
                val marks = FaceGraph.hourMarks(plot.fromMs, plot.toMs, zone, hour24 = true)
                val lines = FaceLayout.hourLines(plot, marks, plotLeft, plotRight)
                assertEquals(hours, lines.size)
                lines.zip(marks).forEach { (line, mark) ->
                    val expected = plotLeft + (mark.timeMs - plot.fromMs).toFloat() / (hours * hour) * (plotRight - plotLeft)
                    assertEquals(expected, line.x, 0.01f)
                    assertTrue(line.x > plotLeft && line.x <= plotRight)
                }
                // the last full hour, 10:00, is 8 minutes before the right edge
                assertEquals(plotRight - 8f / (hours * 60) * (plotRight - plotLeft), lines.last().x, 0.01f)
                val widths = lines.map { FaceLayout.estimatedWidth(it.label, layout.hours.textSize) }
                val placed = FaceLayout.placeHourLabels(layout.hours, lines, widths, 0.01f * size)
                assertTrue("every label fits on $size for $hours h: $placed", placed.all { it != null })
                placed.forEachIndexed { i, left ->
                    assertEquals(lines[i].x - widths[i] / 2f, left!!, 0.01f)
                    assertTrue(left >= layout.hours.left && left + widths[i] <= layout.hours.right)
                }
            }
            // the hour labels' row is between the graph and the status row (part 7c)
            assertTrue(layout.graph.bottom <= layout.hours.top && layout.hours.bottom <= layout.status.top)
        }
    }

    @Test
    fun aLabelPastTheRoundEdgeOrOnItsNeighbourIsLeftOutButItsLineStays() {
        val row = FaceLayout.Row(100f, 300f, 300f, 315f, 12f)
        val lines = listOf(
            FaceLayout.HourLine(102f, "8"),   // half of it past the left edge
            FaceLayout.HourLine(150f, "9"),
            FaceLayout.HourLine(155f, "10"),  // on top of "9"
            FaceLayout.HourLine(200f, "11"),
            FaceLayout.HourLine(299f, "12"),  // past the right edge
        )
        val placed = FaceLayout.placeHourLabels(row, lines, List(lines.size) { 14f }, 2f)
        assertEquals(listOf(null, 143f, null, 193f, null), placed)
    }

    @Test
    fun theDimModeKeepsTheLinesAndTheLabelsFainter() {
        val normal = FaceStyle.of(ambient = false)
        val dim = FaceStyle.of(ambient = true)
        // part 7l, the owner glances at the dim mode all day: the labels there too, dimmed
        assertNotNull(normal.hourLabel)
        assertTrue((dim.hourLabel!! and 0xFF) < (normal.hourLabel!! and 0xFF))
        assertTrue((dim.hourLine and 0xFF) < (normal.hourLine and 0xFF))
        assertTrue(dim.hourLineWidth < normal.hourLineWidth)
        // fainter than the target lines, so the range stays the main thing
        assertTrue((normal.hourLine and 0xFF) < (normal.rangeLine and 0xFF))
    }

    // ------------------------------------------------------------------ the trace cut at the target lines

    private fun p(min: Int, value: Float) = FacePoint(at(9, 0) + min * minute, value)

    @Test
    fun aStepInsideTheRangeIsOnePieceColouredByWhereItArrives() {
        val pieces = FaceGraph.splitByRange(p(0, 5f), p(5, 6f), 3.9f, 10f)
        assertEquals(1, pieces.size)
        assertEquals(FaceGraph.Zone.IN, pieces[0].zone)
        assertEquals(6f, pieces[0].colorValue, 0f)
    }

    @Test
    fun aStepCrossingTheHighLineIsCutThere() {
        val a = p(0, 9f)
        val b = p(10, 12f)
        val pieces = FaceGraph.splitByRange(a, b, 3.9f, 10f)
        assertEquals(listOf(FaceGraph.Zone.IN, FaceGraph.Zone.ABOVE), pieces.map { it.zone })
        val cut = pieces[0].to
        assertEquals(10f, cut.value, 0f)
        // a third of the way up: 9 -> 10 of 9 -> 12
        assertEquals(a.timeMs + 10 * minute / 3, cut.timeMs)
        assertTrue(pieces[1].from === cut)
        assertEquals(9f, pieces[0].colorValue, 0f)
        assertEquals(12f, pieces[1].colorValue, 0f)
        assertTrue(pieces[0].from === a && pieces[1].to === b)
    }

    @Test
    fun aStepFallingBelowTheLowLineIsCutThere() {
        val pieces = FaceGraph.splitByRange(p(0, 4.5f), p(5, 3.5f), 3.9f, 10f)
        assertEquals(listOf(FaceGraph.Zone.IN, FaceGraph.Zone.BELOW), pieces.map { it.zone })
        assertEquals(3.9f, pieces[0].to.value, 0f)
        assertEquals(3.5f, pieces[1].colorValue, 0f)
    }

    @Test
    fun aStepFromBelowToAboveGivesThreePiecesInOrder() {
        val pieces = FaceGraph.splitByRange(p(0, 3f), p(10, 11f), 3.9f, 10f)
        assertEquals(listOf(FaceGraph.Zone.BELOW, FaceGraph.Zone.IN, FaceGraph.Zone.ABOVE), pieces.map { it.zone })
        assertEquals(listOf(3f, 6.95f, 11f), pieces.map { it.colorValue })
        assertEquals(listOf(3.9f, 10f), listOf(pieces[0].to.value, pieces[1].to.value))
        assertTrue(pieces[0].to.timeMs < pieces[1].to.timeMs)
        // and down again, mirrored
        val down = FaceGraph.splitByRange(p(0, 11f), p(10, 3f), 3.9f, 10f)
        assertEquals(listOf(FaceGraph.Zone.ABOVE, FaceGraph.Zone.IN, FaceGraph.Zone.BELOW), down.map { it.zone })
    }

    @Test
    fun aStepOutsideOrEndingOnALineIsNotCut() {
        val above = FaceGraph.splitByRange(p(0, 11f), p(5, 13f), 3.9f, 10f)
        assertEquals(listOf(FaceGraph.Zone.ABOVE), above.map { it.zone })
        // arriving exactly on the high line from above: still the colour of above
        val onLine = FaceGraph.splitByRange(p(0, 12f), p(5, 10f), 3.9f, 10f)
        assertEquals(1, onLine.size)
        assertEquals(FaceGraph.Zone.ABOVE, onLine[0].zone)
        assertEquals(12f, onLine[0].colorValue, 0f)
        // a reading exactly on the line is in the range
        assertEquals(FaceGraph.Zone.IN, FaceGraph.zoneOf(10f, 3.9f, 10f))
        // mg/dL works the same
        assertEquals(2, FaceGraph.splitByRange(p(0, 150f), p(5, 200f), 70f, 180f).size)
    }

    // ------------------------------------------------------------------ read pacing (critic 3c, N1/N2/N7)

    // part 7o: a budget of reads instead of "read, then a pause of 20 s"

    /** The renderer's pacing on one clock: requests at [signalsMs]; returns the times of the reads. */
    private fun simulateReads(signalsMs: List<Long>, visible: Boolean = true): List<Long> {
        val budget = SignalBudget(ModFaceRenderer.READS_PER_WINDOW, ModFaceRenderer.READ_WINDOW_MS)
        val reads = mutableListOf<Long>()
        var pausedUntil: Long? = null
        var dirty = false
        fun read(at: Long) {
            budget.record(at)
            reads += at
            dirty = false
        }
        fun request(at: Long) {
            dirty = true
            val wait = ModFaceRenderer.readWait(visible, budget.lastRuns(), at) ?: return
            if (wait <= 0L) read(at) else if (pausedUntil == null) pausedUntil = at + wait
        }
        val queue = signalsMs.sorted().toMutableList()
        while (queue.isNotEmpty() || pausedUntil != null) {
            val pause = pausedUntil
            if (pause != null && (queue.isEmpty() || pause <= queue.first())) {
                pausedUntil = null
                if (dirty) request(pause)
            } else {
                request(queue.removeAt(0))
            }
        }
        return reads
    }

    /** One minute's signals at [t]: the calibration, the values a moment later, the echo of upstream's alert path. */
    private fun minute(t: Long) = listOf(t, t + 400L, t + 410L)

    @Test
    fun aNormalMinuteReadsTheValuesAtOnce() {
        val t = 1_000_000L
        // all three read at once, the new value with no pause
        assertEquals(minute(t), simulateReads(minute(t)))
        // minute after minute the same
        val minutes = (0 until 10).flatMap { minute(t + it * 60_000L) }
        assertEquals(minutes, simulateReads(minutes))
        // critic 7o, S1: two wrist raises in the 20 s before the data still leave room for the values
        val raised = listOf(t - 15_000L, t - 5_000L) + minute(t)
        assertEquals(raised, simulateReads(raised))
    }

    @Test
    fun historyCatchingUpKeepsToTheBudget() {
        val t = 1_000_000L
        val budget = ModFaceRenderer.READS_PER_WINDOW
        val signals = (0 until 60).map { t + it * 100L } // 60 pieces in 6 s
        val reads = simulateReads(signals)
        // never more than the budget in any window
        for (r in reads) {
            assertTrue(reads.count { it >= r && it < r + ModFaceRenderer.READ_WINDOW_MS } <= budget)
        }
        // the first ones at once, the last piece read once the oldest read leaves the window
        assertEquals((0 until budget).map { t + it * 100L }, reads.take(budget))
        assertEquals(t + ModFaceRenderer.READ_WINDOW_MS, reads.last())
        assertEquals(budget + 1, reads.size)
    }

    @Test
    fun aReadWaitsUntilTheOldestLeavesTheWindow() {
        val t = 1_000_000L
        val n = ModFaceRenderer.READS_PER_WINDOW
        assertEquals(5, n)
        val full = LongArray(n) { t + it * 1_000L }
        assertEquals(ModFaceRenderer.READ_WINDOW_MS - 5_000L, ModFaceRenderer.readWait(true, full, t + 5_000L))
        assertEquals(0L, ModFaceRenderer.readWait(true, full, t + ModFaceRenderer.READ_WINDOW_MS))
        assertEquals(0L, ModFaceRenderer.readWait(true, full, t + 10 * ModFaceRenderer.READ_WINDOW_MS))
        // one read short of the budget in the window: the next at once
        val short = LongArray(n) { if (it == 0) SignalBudget.NEVER else t + it * 1_000L }
        assertEquals(0L, ModFaceRenderer.readWait(true, short, t + 6_000L))
        // never read yet: at once
        assertEquals(0L, ModFaceRenderer.readWait(true, LongArray(n) { SignalBudget.NEVER }, t))
    }

    @Test
    fun anInvisibleFaceReadsNothing() {
        val t = 1_000_000L
        val n = ModFaceRenderer.READS_PER_WINDOW
        assertNull(ModFaceRenderer.readWait(visible = false, LongArray(n) { SignalBudget.NEVER }, t))
        assertNull(ModFaceRenderer.readWait(false, LongArray(n) { t }, t + 999_999L))
        assertTrue(simulateReads(minute(t), visible = false).isEmpty())
    }

    @Test
    fun aLastingReadFailureIsJournalledOnceAMinute() {
        val t = 1_000_000L
        assertTrue(ModFaceRenderer.shouldLogFailure(SignalBudget.NEVER, t))
        assertFalse(ModFaceRenderer.shouldLogFailure(t, t + ModFaceRenderer.FAILURE_LOG_MS - 1L))
        assertTrue(ModFaceRenderer.shouldLogFailure(t, t + ModFaceRenderer.FAILURE_LOG_MS))
    }

    @Test
    fun theJournalAgeIsInWholeSecondsWithoutTheValue() {
        val reading = FaceReading(1_000_000L, 7.2f, "7,2", 0f, 0)
        assertEquals("12 s", ModFaceRenderer.ageText(reading, 1_012_999L))
        assertEquals("none", ModFaceRenderer.ageText(null, 1_012_999L))
    }

    @Test
    fun aRequestFromTheEndOfAPauseSetsTheNextPause() {
        // critic 5, N-5-9 (the fix of critic 3c, N1): the job clears itself before its action, or this second pause
        // would be refused as "one is waiting" and the request lost
        val thread = Executors.newSingleThreadExecutor()
        val dispatcher = thread.asCoroutineDispatcher()
        try {
            val pause = ReadPause(CoroutineScope(dispatcher))
            val runs = AtomicInteger()
            val ignored = AtomicInteger()
            val second = CountDownLatch(1)
            runBlocking(dispatcher) {
                pause.arm(20L) {
                    runs.incrementAndGet()
                    pause.arm(20L) {
                        runs.incrementAndGet()
                        second.countDown()
                    }
                }
                // one pause at a time: a request while it waits sets nothing new
                assertTrue(pause.waiting)
                pause.arm(1L) { ignored.incrementAndGet() }
            }
            assertTrue("the second pause never ran", second.await(5, TimeUnit.SECONDS))
            assertEquals(2, runs.get())
            assertEquals(0, ignored.get())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun theStaleReadOfAFrameNeverBreaksThePause() {
        assertTrue(ModFaceRenderer.STALE_MS >= ModFaceRenderer.READ_WINDOW_MS)
        assertTrue(ModFaceRenderer.STALE_MS < ModFaceRenderer.FRAME_MS)
        // the dim mode reads again only every few frames (part 7c, critic 7, N8)
        assertEquals(ModFaceRenderer.STALE_MS, ModFaceRenderer.staleAfter(false))
        assertEquals(ModFaceRenderer.AMBIENT_STALE_MS, ModFaceRenderer.staleAfter(true))
        assertTrue(ModFaceRenderer.AMBIENT_STALE_MS >= 5 * ModFaceRenderer.FRAME_MS)
    }

    @Test
    fun theSystemIconOfOurWatchBuildIsAFaintDotOfUpstreamsSize() {
        // part 7c, the owner's word: Wear OS draws upstream's `novalue` (their ongoing activity's icon) at the bottom
        // of every face; our watch build overrides it with a faint dot of the same box, the phone keeps theirs
        fun file(path: String) = listOf(File("src/$path"), File("Common/src/$path")).first { it.isFile }.readText()
        val ours = file("wearMod/res/drawable-anydpi-v24/novalue.xml")
        val theirs = file("main/res/drawable-anydpi-v24/novalue.xml")
        for (attr in listOf("width", "height", "viewportWidth", "viewportHeight")) {
            val pattern = Regex("android:$attr=\"([^\"]+)\"")
            assertEquals(attr, pattern.find(theirs)?.groupValues?.get(1), pattern.find(ours)?.groupValues?.get(1))
        }
        val colors = Regex("android:fillColor=\"#([0-9A-Fa-f]{8})\"").findAll(ours).map { it.groupValues[1] }.toList()
        assertTrue(colors.isNotEmpty())
        colors.forEach { assertTrue("alpha of #$it", it.substring(0, 2).toInt(16) <= 0x50) }
        assertFalse(ours.contains("android:tint"))
    }

    @Test
    fun theFaceHasNoEditorActionAndTheEditorIsAnAppEntry() {
        val manifest = listOf(File("src/wearMod/AndroidManifest.xml"), File("Common/src/wearMod/AndroidManifest.xml"))
            .first { it.isFile }.readText()
        val face = Regex("""<service\s+android:name="tk\.glucodata\.mod\.watch\.face\.ModWatchFaceService"[\s\S]*?</service>""")
            .find(manifest)?.value.orEmpty()
        assertTrue(face, face.contains("android.service.wallpaper.WallpaperService"))
        // Wear OS leaves a face whose wearableConfigurationAction is not upstream's WATCH_FACE_EDITOR out of the
        // picker (watch, 18.11.2026), and upstream's action would open their WatchFaceConfigActivity: none at all
        assertFalse(face, face.contains("wearableConfigurationAction"))
        val editor = Regex("""<activity\s+android:name="tk\.glucodata\.mod\.watch\.face\.FaceSettingsActivity"[\s\S]*?</activity>""")
            .find(manifest)?.value.orEmpty()
        assertTrue(editor, editor.contains("android.intent.category.LAUNCHER"))
        assertFalse(editor, editor.contains("WEARABLE_CONFIGURATION") || editor.contains("WATCH_FACE_EDITOR"))
    }
}
