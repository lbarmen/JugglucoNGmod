package tk.glucodata.mod.carelink

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * What became of a journal row our writer made: still ours, changed or deleted by the person, removed by us (a
 * cancelled bolus), or its key moved to a new one (a running bolus whose marker moved; the row now lives under the new
 * key). Anything but LIVE is never written again - an older answer bringing the old key back must not make a second
 * row (critic 4d, B1).
 */
enum class SeenState { LIVE, EDITED, DELETED, REMOVED, MOVED }

/**
 * One journal row of ours (stage 4d, plan decision 5). [kind] - the journal type (`insulin`, `carbs`); [atMs] - the
 * pump event time (the 48 h window counts from it); [rowId] - the journal row (null - never written, e.g. a bolus
 * cancelled before its first poll); [writtenAt] - the row's `updatedAt` read back after our last write (upstream's
 * upsert and retag stamp their own clock, so a later `updatedAt` means the person changed it); [source] - the source the
 * row was made with (kept on every update). [dose], [completed], [note] - the bolus as last written; [grams] - carbs;
 * [carbsKey] - the calculator carbs written with this bolus.
 */
data class SeenRecord(
    val kind: String,
    val atMs: Long,
    val rowId: Long? = null,
    val writtenAt: Long? = null,
    val source: String? = null,
    val dose: Double? = null,
    val completed: Boolean? = null,
    val note: String? = null,
    val grams: Double? = null,
    val carbsKey: String? = null,
    val state: SeenState = SeenState.LIVE,
)

/**
 * The basal segment still running: journal key, start, rate (U/h), the end written into the row and the data time of
 * the last snapshot that confirmed it.
 */
data class BasalOpen(val key: String, val startMs: Long, val rate: Double, val endMs: Long, val lastDataAtMs: Long)

/**
 * The "pump stopped" note still waiting for its end: key, start, row and its `updatedAt` after our write, [lastAtMs] -
 * the data time of the last snapshot that still saw the pump stopped (the end when the data broke off: sign-out, "sign
 * in again", a gap; critic of the joints, C2), [text] - the note as we wrote it (null in files of 4d builds).
 */
data class StopOpen(
    val key: String,
    val startMs: Long,
    val rowId: Long?,
    val writtenAt: Long?,
    val lastAtMs: Long = startMs,
    val text: String? = null,
)

/**
 * All the writers remember: rows by journal key, the last normal basal rate and [lastNormalAtMs] - the data time it is
 * known for (carried along while the data runs without a gap; critic 4d, C3), the open segment and stop note.
 */
class SeenData(
    val records: MutableMap<String, SeenRecord> = LinkedHashMap(),
    var lastNormalRate: Double? = null,
    var lastNormalAtMs: Long? = null,
    var basalOpen: BasalOpen? = null,
    var stopOpen: StopOpen? = null,
)

/**
 * JugglucoNGmod, CareLink (stage 4d): what our writers put into upstream's journal, so a row the person changed or
 * deleted is never written over or brought back (as Glucove's `clSeen`). The file
 * `noBackupFilesDir/mod-carelink/seen.json`: not in upstream's settings backup and not moved to another phone without
 * the journal rows it speaks of. Rows of events older than [WINDOW_MS] are forgotten (the cloud answer covers 24 h).
 * Numbers and keys only (the key is the pump event type and its local time). Written through a temporary file and a
 * rename; a broken file is read as empty - the writer then adopts its rows by key (see [CareLinkJournalWriter]).
 * Used only by the writers, inside the poll's one-at-a-time lock.
 */
object CareLinkSeenStore {

    const val WINDOW_MS = 48 * 3_600_000L
    const val FILE = "seen.json"

    private fun file(context: Context): File =
        File(File(context.applicationContext.noBackupFilesDir, CareLinkStateStore.DIR), FILE)

    fun load(context: Context, nowMs: Long): SeenData {
        val f = file(context)
        if (!f.isFile) return SeenData()
        return try {
            fromJson(CareLinkConfig.parse(f.readText(Charsets.UTF_8)) ?: return SeenData(), nowMs)
        } catch (t: Throwable) {
            CareLinkLog.w("seen file not read (${t.javaClass.simpleName})")
            SeenData()
        }
    }

    fun save(context: Context, data: SeenData, nowMs: Long) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(toJson(data, nowMs).toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("seen file not moved into place")
        }
    }

    /** Forgets everything (tests). */
    internal fun clear(context: Context) {
        file(context).delete()
    }

    internal fun toJson(d: SeenData, nowMs: Long): JSONObject {
        val rows = JSONObject()
        for ((k, r) in d.records) {
            if (r.atMs < nowMs - WINDOW_MS) continue
            rows.put(k, JSONObject().apply {
                put("kind", r.kind)
                put("at", r.atMs)
                putOpt("row", r.rowId)
                putOpt("written", r.writtenAt)
                putOpt("source", r.source)
                putOpt("dose", r.dose)
                putOpt("completed", r.completed)
                putOpt("note", r.note)
                putOpt("grams", r.grams)
                putOpt("carbs", r.carbsKey)
                put("state", r.state.name)
            })
        }
        return JSONObject().apply {
            put("v", 1)
            put("rows", rows)
            putOpt("lastNormalRate", d.lastNormalRate)
            putOpt("lastNormalAt", d.lastNormalAtMs)
            d.basalOpen?.let { b ->
                put("basalOpen", JSONObject().put("key", b.key).put("start", b.startMs).put("rate", b.rate)
                    .put("end", b.endMs).put("lastData", b.lastDataAtMs))
            }
            d.stopOpen?.let { s ->
                put("stopOpen", JSONObject().put("key", s.key).put("start", s.startMs).putOpt("row", s.rowId)
                    .putOpt("written", s.writtenAt).put("last", s.lastAtMs).putOpt("text", s.text))
            }
        }
    }

    internal fun fromJson(o: JSONObject, nowMs: Long): SeenData {
        val d = SeenData()
        o.clObj("rows")?.let { rows ->
            val names = rows.keys()
            while (names.hasNext()) {
                val k = names.next()
                val r = rows.clObj(k) ?: continue
                val at = r.clLong("at") ?: continue
                if (at < nowMs - WINDOW_MS) continue
                d.records[k] = SeenRecord(
                    kind = r.clStr("kind") ?: continue,
                    atMs = at,
                    rowId = r.clLong("row"),
                    writtenAt = r.clLong("written"),
                    source = r.clStr("source"),
                    dose = r.clDouble("dose"),
                    completed = r.clBool("completed"),
                    note = r.clStr("note"),
                    grams = r.clDouble("grams"),
                    carbsKey = r.clStr("carbs"),
                    state = r.clStr("state")?.let { s -> SeenState.values().firstOrNull { it.name == s } } ?: SeenState.LIVE,
                )
            }
        }
        d.lastNormalRate = o.clDouble("lastNormalRate")
        d.lastNormalAtMs = o.clLong("lastNormalAt")
        d.basalOpen = o.clObj("basalOpen")?.let { b ->
            val key = b.clStr("key")
            val start = b.clLong("start")
            val rate = b.clDouble("rate")
            val end = b.clLong("end")
            val last = b.clLong("lastData")
            if (key == null || start == null || rate == null || end == null || last == null) null
            else BasalOpen(key, start, rate, end, last)
        }
        d.stopOpen = o.clObj("stopOpen")?.let { s ->
            val key = s.clStr("key")
            val start = s.clLong("start")
            if (key == null || start == null) null
            else StopOpen(key, start, s.clLong("row"), s.clLong("written"), s.clLong("last") ?: start, s.clStr("text"))
        }
        return d
    }
}
