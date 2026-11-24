package tk.glucodata.mod.health

import java.io.File
import tk.glucodata.mod.health.ModHealthBatch.Sample

/**
 * JugglucoNGmod, watch block (part 4): the queue of rows on the watch - files in our own folder of the app storage
 * (no backup), they survive a restart of the process. New rows are appended to `pending.tsv`; before a send it moves
 * whole to `outbox-<time>-<n>.tsv` and appending goes on into a new pending file, so sending and receiving do not get
 * in each other's way. An outbox file is deleted only when the Data Layer accepted all its batches ([done]); a failed
 * send leaves it to go first next time, under new batch numbers (the phone drops rows equal in every field to ones it
 * has; rules in [ModHealthBatch.Sample]). Over [maxBytes] the oldest outbox
 * files are dropped ([trim]).
 */
class SampleBuffer(private val dir: File, private val maxBytes: Long = HealthConfig.BUFFER_MAX_BYTES) {

    private val lock = Any()
    private val pending get() = File(dir, PENDING)

    fun append(samples: List<Sample>) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            dir.mkdirs()
            pending.appendText(samples.joinToString(separator = "") { toLine(it) + "\n" })
        }
    }

    /**
     * Moves what is pending to a new outbox file; returns all outbox files, oldest first. The name never sorts before
     * an existing outbox file (above the newest one even when the clock went back), so [trim] drops the oldest data.
     */
    fun takeOutbox(nowMs: Long): List<File> = synchronized(lock) {
        val current = pending
        if (current.exists() && current.length() > 0) {
            val newest = outboxFiles().lastOrNull()?.name?.removePrefix(OUTBOX)?.substringBefore('-')?.toLongOrNull() ?: -1L
            val stamp = maxOf(nowMs.coerceAtLeast(0), newest + 1).toString().padStart(13, '0')
            var n = 0
            var target = File(dir, "$OUTBOX$stamp-$n.tsv")
            while (target.exists()) target = File(dir, "$OUTBOX$stamp-${++n}.tsv")
            current.renameTo(target)
        }
        outboxFiles()
    }

    fun read(file: File): List<Sample> = synchronized(lock) {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { fromLine(it) }
    }

    /** All batches of [file] were accepted: delete it. */
    fun done(file: File) {
        synchronized(lock) { file.delete() }
    }

    /** Outbox files are left - an earlier send failed. */
    fun hasOutbox(): Boolean = synchronized(lock) { outboxFiles().isNotEmpty() }

    /** Rows waiting: pending and outbox. */
    fun count(): Int = synchronized(lock) {
        (outboxFiles() + pending).sumOf { f -> if (f.exists()) f.useLines { it.count() } else 0 }
    }

    /** The queue is over [maxBytes] (phone away for long): drop the oldest outbox files; returns how many. */
    fun trim(): Int = synchronized(lock) {
        val files = outboxFiles()
        var total = files.sumOf { it.length() } + (if (pending.exists()) pending.length() else 0)
        var dropped = 0
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
            dropped++
        }
        dropped
    }

    private fun outboxFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith(OUTBOX) }?.sortedBy { it.name } ?: emptyList()

    companion object {
        private const val PENDING = "pending.tsv"
        private const val OUTBOX = "outbox-"

        /** A queue line: kind, time, value, extra, tab-separated. */
        fun toLine(s: Sample): String = "${clean(s.kind)}\t${s.timeMs}\t${s.value}\t${clean(s.extra)}"

        /** A row from a queue line; `null` for a broken line. */
        fun fromLine(line: String): Sample? {
            val parts = line.split('\t', limit = 4)
            if (parts.size < 3 || parts[0].isEmpty()) return null
            val time = parts[1].toLongOrNull() ?: return null
            val value = parts[2].toFloatOrNull() ?: return null
            return Sample(parts[0], time, value, parts.getOrElse(3) { "" })
        }

        /** Tabs and line breaks would break the file: they become spaces. */
        fun clean(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
}
