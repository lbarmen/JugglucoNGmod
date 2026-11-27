package tk.glucodata.mod.pumpbt

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Where the "Mobile …" name goes in the pairing advertisement. */
internal enum class PumpBtNameVariant {
    /** Medtronic manufacturer data 0x01F9 `00 + "Mobile NNNNNN" + 00` (Android codes of OpenMinimed, Pebble). */
    A,

    /**
     * The phone's Bluetooth name, changed to "Mobile NNNNNN" during the pairing (OpenMinimed description, Python). Only
     * a fallback of the wizard's pairing when the pump does not see the phone with A ([PumpBtTrial.tryOtherName]); the
     * name comes back right after the pairing, and the variant goes back to A (series brief, finding 11).
     */
    B,
}

/**
 * JugglucoNGmod, pump over Bluetooth: the small state of the link kept between runs - the 6 digits of the advertised
 * name (the pump knows the phone by them: new digits mean a new pairing), the name variant, "paired once", the pump's
 * address, the accepted warnings of the pairing wizard, the phone's own Bluetooth name while variant B has changed it, the reference time of the window's history
 * lines, the detailed journal switch (off by default) and what the source's notifications already told
 * ([PumpBtAlertMemory]). No key material, no session, no history: the history memory and its number are
 * [PumpBtHistoryStore]'s.
 *
 * A file, not a preference (critic B1, C1): upstream's settings import clears or rewrites every shared_prefs file, and
 * without its digits the pump would no longer know the phone. `noBackupFilesDir/mod-pumpbt/link.json`, written whole
 * through a temporary file and a rename - only when a value changes, off the caller's thread (the link sets values on
 * the main thread; critic B2a, N-9), except the phone's own name, which is on disk before the call returns. Once, when
 * the file is not there yet, the values are taken from the old preferences `mod_pumpbt` (same key names, left in place
 * for an older build) and the preferences get the mark "moved"; an unreadable file is kept beside as `link.json.broken`
 * and the preferences are read again only when the mark is not there (an import may have written foreign values into
 * them; critic B2a, N-8). One instance per process ([PumpBtSource.store]); thread-safe.
 */
internal class PumpBtStore internal constructor(private val file: File, private val legacy: SharedPreferences?) {

    constructor(context: Context) : this(
        File(File(context.applicationContext.noBackupFilesDir, DIR), FILE),
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE),
    )

    private val lock = Any()
    private val fileLock = Any()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ModPumpBtLinkState").apply { isDaemon = true } }

    /** Version of [values] (under [lock]) and the version on disk (under [fileLock]): an older text never overwrites a newer one. */
    private var version = 0L
    private var written = 0L

    private val values: JSONObject = load()

    /** The 6 digits of "Mobile NNNNNN", made once. */
    val nameDigits: String
        get() = synchronized(lock) {
            values.optString(K_DIGITS, "").takeIf { it.length == 6 && it.all(Char::isDigit) }
                ?: "%06d".format((0..999_999).random()).also { put(K_DIGITS, it) }
        }

    val advertisedName: String get() = "Mobile $nameDigits"

    var variant: PumpBtNameVariant
        get() = synchronized(lock) {
            runCatching { PumpBtNameVariant.valueOf(values.optString(K_VARIANT, "A")) }.getOrDefault(PumpBtNameVariant.A)
        }
        set(v) = synchronized(lock) { put(K_VARIANT, v.name) }

    var paired: Boolean
        get() = synchronized(lock) { values.optBoolean(K_PAIRED, false) }
        set(v) = synchronized(lock) { put(K_PAIRED, v) }

    var pumpAddress: String
        get() = synchronized(lock) { values.optString(K_ADDRESS, "") }
        set(v) = synchronized(lock) { put(K_ADDRESS, v) }

    /**
     * The person accepted the warnings before the first pairing (series brief, decision 14). In this file, not a
     * preference: upstream's settings import would clear it (critic B1, C1).
     */
    var warningsAccepted: Boolean
        get() = synchronized(lock) { values.optBoolean(K_WARNINGS, false) }
        set(v) = synchronized(lock) { put(K_WARNINGS, v) }

    /** Detailed link journal (serial number, values, decrypted answers, history records); off by default. */
    var verbose: Boolean
        get() = synchronized(lock) { values.optBoolean(K_VERBOSE, false) }
        set(v) = synchronized(lock) { put(K_VERBOSE, v) }

    /** The link journal file holds detailed lines: set with the detailed journal, cleared when the file is emptied or made anew. */
    var journalHasDetail: Boolean
        get() = synchronized(lock) { values.optBoolean(K_JOURNAL_DETAIL, false) }
        set(v) = synchronized(lock) { put(K_JOURNAL_DETAIL, v) }

    /** Latest pump reference time seen in the history, for the window's history lines: record number and time (epoch ms). */
    var historyReference: Pair<Long, Long>?
        get() = synchronized(lock) {
            if (values.has(K_REF_SEQ) && values.has(K_REF_MS)) values.optLong(K_REF_SEQ) to values.optLong(K_REF_MS) else null
        }
        set(v) = synchronized(lock) {
            if (v == historyReference) return@synchronized
            if (v == null) {
                values.remove(K_REF_SEQ)
                values.remove(K_REF_MS)
            } else {
                values.put(K_REF_SEQ, v.first)
                values.put(K_REF_MS, v.second)
            }
            write()
        }

    /**
     * The phone's Bluetooth name before variant B changed it; null while it is not changed. On disk (synced) before
     * the setter returns: the process may die right after the change. An empty name is never saved (it could not be
     * given back).
     */
    var originalAdapterName: String?
        get() = synchronized(lock) { values.optString(K_ORIGINAL_NAME, "").takeIf { it.isNotEmpty() } }
        set(v) = synchronized(lock) {
            if (v.isNullOrEmpty()) values.remove(K_ORIGINAL_NAME) else values.put(K_ORIGINAL_NAME, v)
            version++
            writeNow(values.toString(), version)
        }

    /** What the source's notifications already told (once per type, per bolus, the clock drift once a day, the lost bond). */
    var alerts: PumpBtAlertMemory
        get() = synchronized(lock) {
            val o = values.optJSONObject(K_ALERTS) ?: return@synchronized PumpBtAlertMemory()
            try {
                PumpBtAlertMemory(
                    unknownTypes = ints(o.optJSONArray("unknown")),
                    unparsedTypes = ints(o.optJSONArray("unparsed")),
                    noResultKeys = o.optJSONArray("noResult")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                    driftAtMs = if (o.has("driftAt")) o.getLong("driftAt") else null,
                    bondLostTold = o.optBoolean("bondLostTold", false),
                )
            } catch (t: Throwable) {
                PumpBtAlertMemory()
            }
        }
        set(v) = synchronized(lock) {
            put(
                K_ALERTS,
                JSONObject().apply {
                    put("unknown", JSONArray(v.unknownTypes.sorted()))
                    put("unparsed", JSONArray(v.unparsedTypes.sorted()))
                    put("noResult", JSONArray(v.noResultKeys.sorted()))
                    v.driftAtMs?.let { put("driftAt", it) }
                    put("bondLostTold", v.bondLostTold)
                },
            )
        }

    private fun ints(a: JSONArray?): Set<Int> = if (a == null) emptySet() else (0 until a.length()).map { a.getInt(it) }.toSet()

    /** Under [lock]: the value, written only when it changed. */
    private fun put(key: String, value: Any) {
        val old = values.opt(key)
        val same = if (value is JSONObject) old is JSONObject && old.toString() == value.toString() else old == value
        if (same) return
        values.put(key, value)
        write()
    }

    /** Under [lock]: the current values to the file, off this thread, in order. */
    private fun write() {
        version++
        val text = values.toString()
        val ver = version
        try {
            writer.execute { writeNow(text, ver) }
        } catch (t: Throwable) {
            writeNow(text, ver)
        }
    }

    /** Waits for the writes handed off so far (tests; a new instance on the same file). */
    internal fun awaitWrites() {
        try {
            writer.submit {}.get()
        } catch (_: Throwable) {
        }
    }

    /**
     * The file; missing - the old preferences once (then the mark "moved" there); unreadable - kept beside, and the old
     * preferences only when they were never moved.
     */
    private fun load(): JSONObject {
        if (file.isFile) {
            try {
                return JSONObject(file.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                PumpBtLog.w("link state not read (${t.javaClass.simpleName})")
                try {
                    file.copyTo(File(file.parentFile, "$FILE$BROKEN"), overwrite = true)
                } catch (c: Throwable) {
                    PumpBtLog.w("broken link state not kept (${c.javaClass.simpleName})")
                }
            }
        }
        val o = JSONObject()
        try {
            val p = legacy
            if (p != null && p.getBoolean(K_MOVED, false)) {
                PumpBtLog.w("link state begins again: the old preferences were moved before")
            } else if (p != null) {
                p.getString(K_DIGITS, null)?.let { o.put(K_DIGITS, it) }
                p.getString(K_VARIANT, null)?.let { o.put(K_VARIANT, it) }
                if (p.contains(K_PAIRED)) o.put(K_PAIRED, p.getBoolean(K_PAIRED, false))
                p.getString(K_ADDRESS, null)?.let { o.put(K_ADDRESS, it) }
                p.getString(K_ORIGINAL_NAME, null)?.takeIf { it.isNotEmpty() }?.let { o.put(K_ORIGINAL_NAME, it) }
                if (p.contains(K_REF_SEQ) && p.contains(K_REF_MS)) {
                    o.put(K_REF_SEQ, p.getLong(K_REF_SEQ, 0))
                    o.put(K_REF_MS, p.getLong(K_REF_MS, 0))
                }
                writeNow(o.toString(), 0)
                if (file.isFile) p.edit().putBoolean(K_MOVED, true).commit()
                return o
            }
        } catch (t: Throwable) {
            PumpBtLog.w("old link preferences not read (${t.javaClass.simpleName})")
        }
        writeNow(o.toString(), 0)
        return o
    }

    /** .tmp + sync + rename, unless a newer version is on disk already; a failure is logged (the next change writes again). */
    private fun writeNow(text: String, ver: Long) = synchronized(fileLock) {
        if (ver < written) return@synchronized
        try {
            val dir = file.parentFile ?: return@synchronized
            dir.mkdirs()
            val tmp = File(dir, "$FILE.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    PumpBtLog.w("link state not moved into place")
                    return@synchronized
                }
            }
            written = ver
        } catch (t: Throwable) {
            PumpBtLog.w("link state not saved (${t.javaClass.simpleName})")
        }
    }

    internal companion object {
        const val DIR = "mod-pumpbt"
        const val FILE = "link.json"
        const val BROKEN = ".broken"
        const val LEGACY_PREFS = "mod_pumpbt"

        // the names of the old preferences, kept as they were (series brief, decision 10)
        const val K_DIGITS = "name_digits"
        const val K_VARIANT = "name_variant"
        const val K_PAIRED = "paired"
        const val K_ADDRESS = "pump_address"
        const val K_REF_SEQ = "history_ref_seq"
        const val K_REF_MS = "history_ref_ms"
        const val K_ORIGINAL_NAME = "original_adapter_name"
        const val K_VERBOSE = "verbose"
        const val K_ALERTS = "alerts"
        const val K_JOURNAL_DETAIL = "journal_has_detail"
        const val K_WARNINGS = "warnings_accepted"

        /** In the old preferences: their values were moved to the file. */
        const val K_MOVED = "moved_to_file"

        /**
         * A pump's address is kept - read only, nothing made or written (critic of the joints 2, N-5: the starter asks it
         * at every process start, also of people who never used the pump). The file when it is there (unreadable - true:
         * someone used the pump, the store sorts it out), else the old preferences unless they were moved.
         */
        fun addressKnown(context: Context): Boolean {
            val app = context.applicationContext
            return addressKnown(File(File(app.noBackupFilesDir, DIR), FILE), app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE))
        }

        fun addressKnown(file: File, legacy: SharedPreferences?): Boolean {
            if (file.isFile) {
                return try {
                    JSONObject(file.readText(Charsets.UTF_8)).optString(K_ADDRESS, "").isNotBlank()
                } catch (_: Throwable) {
                    true
                }
            }
            return try {
                legacy != null && !legacy.getBoolean(K_MOVED, false) && !legacy.getString(K_ADDRESS, null).isNullOrBlank()
            } catch (_: Throwable) {
                false
            }
        }
    }
}
