package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tk.glucodata.mod.pumpbt.PumpBtJournal
import java.io.File

/** JugglucoNGmod, pump over Bluetooth (trial): journal lines and the size cap. */
class PumpBtJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun lineHasTimeWithMilliseconds() {
        val line = PumpBtJournal.format(0L, "stage: reading\nsecond line")
        assertTrue(line, Regex("""\d\d:\d\d:\d\d\.\d\d\d stage: reading second line""").matches(line))
    }

    @Test
    fun fullDateHasYearToSeconds() {
        assertTrue(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d""").matches(PumpBtJournal.fullDate(0L)))
    }

    @Test
    fun onlyJournalCopiesAreDeleted() {
        val dir = tmp.newFolder("exports")
        File(dir, "mod-pumpbt-journal-20261126-101010.txt").writeText("a")
        File(dir, "mod-pumpbt-journal-20261126-111111.txt").writeText("b")
        File(dir, "upstream-export.txt").writeText("c")
        File(dir, "mod-pumpbt-journal-notes.csv").writeText("d")
        assertEquals(2, PumpBtJournal.deleteCopies(dir))
        assertEquals(setOf("upstream-export.txt", "mod-pumpbt-journal-notes.csv"), dir.list()!!.toSet())
        assertEquals(0, PumpBtJournal.deleteCopies(File(tmp.root, "missing")))
    }

    @Test
    fun oldPartIsCutPastTheCap() {
        val f = File(tmp.root, "mod-pumpbt/journal.txt")
        repeat(500) { PumpBtJournal.appendCapped(f, "line %04d ".format(it) + "x".repeat(40), max = 10_000, keep = 5_000) }
        assertTrue(f.length() <= 10_000)
        val lines = f.readLines()
        assertTrue(lines.first(), lines.first().startsWith("line "))
        assertEquals("line 0499 " + "x".repeat(40), lines.last())
        assertTrue(lines.all { it.startsWith("line ") && it.length == 50 })
    }
}
