package tk.glucodata.mod.pumpbt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the trial journal, `filesDir/mod-pumpbt/journal.txt`, one line per event
 * with the phone's time (`HH:mm:ss.SSS`). At most [MAX_BYTES]: the oldest part is cut off. The screen shows the last
 * [TAIL] lines. Holds stages, Bluetooth events, handshake steps (number and length only), every write towards the pump
 * with the gate's decision, decrypted answers of the pump and errors. Never key material: no static or session keys,
 * permit or handshake payload - callers pass only lengths and CRCs of those. File writes run on one background thread.
 */
internal object PumpBtJournal {

    const val MAX_BYTES = 2L * 1024 * 1024
    const val KEEP_BYTES = 1024 * 1024
    const val TAIL = 50

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ModPumpBtJournal").apply { isDaemon = true } }
    private val lock = Any()
    private val _tail = MutableStateFlow<List<String>>(emptyList())
    val tail: StateFlow<List<String>> = _tail.asStateFlow()

    @Volatile
    private var file: File? = null

    /** Opens the journal of this process (idempotent) and loads its last lines for the screen. */
    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val f = File(File(context.applicationContext.filesDir, "mod-pumpbt"), "journal.txt")
            file = f
            writer.execute {
                val last = try {
                    if (f.exists()) lastLines(f, TAIL) else emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
                synchronized(lock) { _tail.value = (last + _tail.value).takeLast(TAIL) }
            }
        }
    }

    fun add(message: String) {
        val line = format(System.currentTimeMillis(), message)
        synchronized(lock) { _tail.value = (_tail.value + line).takeLast(TAIL) }
        val f = file ?: return
        writer.execute {
            try {
                appendCapped(f, line, MAX_BYTES, KEEP_BYTES)
            } catch (t: Throwable) {
                PumpBtLog.w("journal not written (${t.javaClass.simpleName})")
            }
        }
    }

    /** Empties the journal and deletes its shared copies in [exportsDir]. */
    fun clear(exportsDir: File?) {
        synchronized(lock) { _tail.value = emptyList() }
        val f = file
        writer.execute {
            try {
                f?.delete()
            } catch (_: Throwable) {
            }
            if (exportsDir != null) deleteCopies(exportsDir)
        }
    }

    /**
     * A copy of the whole journal in [dir] (after the pending writes); the older copies there are deleted first.
     * Null when there is none. Blocking.
     */
    fun copyTo(dir: File, name: String): File? {
        val f = file ?: return null
        return try {
            writer.submit(Callable {
                deleteCopies(dir)
                if (!f.exists()) return@Callable null
                dir.mkdirs()
                val out = File(dir, name)
                f.copyTo(out, overwrite = true)
                out
            }).get(10, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            PumpBtLog.w("journal not copied (${t.javaClass.simpleName})")
            null
        }
    }

    /** Deletes the journal copies (`mod-pumpbt-journal-*.txt`) in [dir], nothing else; returns how many. */
    fun deleteCopies(dir: File): Int {
        val copies = dir.listFiles { f -> f.isFile && f.name.startsWith(COPY_PREFIX) && f.name.endsWith(".txt") } ?: return 0
        return copies.count { runCatching { it.delete() }.getOrDefault(false) }
    }

    const val COPY_PREFIX = "mod-pumpbt-journal-"

    fun format(atMs: Long, message: String): String =
        timeFormat.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())) + " " + message.replace('\n', ' ')

    /** The phone's date and time `yyyy-MM-dd HH:mm:ss` (journal lines carry only the time of day). */
    fun fullDate(atMs: Long = System.currentTimeMillis()): String =
        dateFormat.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

    /** Appends one line; past [max] bytes keeps only about the last [keep] bytes, from a line start. */
    fun appendCapped(f: File, line: String, max: Long, keep: Int) {
        f.parentFile?.mkdirs()
        f.appendText(line + "\n", Charsets.UTF_8)
        if (f.length() <= max) return
        val bytes = RandomAccessFile(f, "r").use { raf ->
            val start = (raf.length() - keep).coerceAtLeast(0L)
            val buf = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            buf
        }
        val firstLine = bytes.indexOf('\n'.code.toByte()).let { if (it < 0) 0 else it + 1 }
        f.writeBytes(bytes.copyOfRange(firstLine, bytes.size))
    }

    private fun lastLines(f: File, n: Int): List<String> {
        val bytes = RandomAccessFile(f, "r").use { raf ->
            val start = (raf.length() - 64 * 1024).coerceAtLeast(0L)
            val buf = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            buf
        }
        return String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }.takeLast(n)
    }
}
