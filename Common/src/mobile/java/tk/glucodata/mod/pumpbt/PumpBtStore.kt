package tk.glucodata.mod.pumpbt

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Where the "Mobile …" name goes in the pairing advertisement. */
internal enum class PumpBtNameVariant {
    /** Medtronic manufacturer data 0x01F9 `00 + "Mobile NNNNNN" + 00` (Android codes of OpenMinimed, Pebble). */
    A,

    /** The phone's Bluetooth name, changed to "Mobile NNNNNN" during the trial (OpenMinimed description, Python). */
    B,
}

/**
 * JugglucoNGmod, pump over Bluetooth: the small state of the link kept between runs - the 6 digits of the advertised
 * name (the pump knows the phone by them: new digits mean a new pairing), the name variant, "paired once", the pump's
 * address, the phone's own Bluetooth name while variant B has changed it, the reference time of the window's history
 * lines, the detailed journal switch (off by default) and what the source's notifications already told
 * ([PumpBtAlertMemory]). No key material, no session, no history: the history memory
 * and its number are [PumpBtHistoryStore]'s.
 *
 * A file, not a preference (critic B1, C1): upstream's settings import clears or rewrites every shared_prefs file, and
 * without its digits the pump would no longer know the phone. `noBackupFilesDir/mod-pumpbt/link.json`, written whole
 * through a temporary file and a rename on every change. Once, when the file is not there yet (or cannot be read), the
 * values are taken from the old preferences `mod_pumpbt` (same key names, left in place for an older build); after that
 * the preferences are never read again. One instance per process ([PumpBtSource.store]); thread-safe.
 */
internal class PumpBtStore internal constructor(private val file: File, private val legacy: SharedPreferences?) {

    constructor(context: Context) : this(
        File(File(context.applicationContext.noBackupFilesDir, DIR), FILE),
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE),
    )

    private val lock = Any()
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

    /** Detailed link journal (serial number, values, decrypted answers, history records); off by default. */
    var verbose: Boolean
        get() = synchronized(lock) { values.optBoolean(K_VERBOSE, false) }
        set(v) = synchronized(lock) { put(K_VERBOSE, v) }

    /** Latest pump reference time seen in the history, for the window's history lines: record number and time (epoch ms). */
    var historyReference: Pair<Long, Long>?
        get() = synchronized(lock) {
            if (values.has(K_REF_SEQ) && values.has(K_REF_MS)) values.optLong(K_REF_SEQ) to values.optLong(K_REF_MS) else null
        }
        set(v) = synchronized(lock) {
            if (v == null) {
                values.remove(K_REF_SEQ)
                values.remove(K_REF_MS)
                write()
            } else {
                values.put(K_REF_SEQ, v.first)
                values.put(K_REF_MS, v.second)
                write()
            }
        }

    /**
     * The phone's Bluetooth name before variant B changed it; null while it is not changed. Saved (on disk, synced)
     * before the change: the process may die right after. An empty name is never saved (it could not be given back).
     */
    var originalAdapterName: String?
        get() = synchronized(lock) { values.optString(K_ORIGINAL_NAME, "").takeIf { it.isNotEmpty() } }
        set(v) = synchronized(lock) {
            if (v.isNullOrEmpty()) values.remove(K_ORIGINAL_NAME) else values.put(K_ORIGINAL_NAME, v)
            write()
        }

    /** What the source's notifications already told (once per type, per bolus; the clock drift once a day). */
    var alerts: PumpBtAlertMemory
        get() = synchronized(lock) {
            val o = values.optJSONObject(K_ALERTS) ?: return@synchronized PumpBtAlertMemory()
            try {
                PumpBtAlertMemory(
                    unknownTypes = ints(o.optJSONArray("unknown")),
                    unparsedTypes = ints(o.optJSONArray("unparsed")),
                    noResultKeys = o.optJSONArray("noResult")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                    driftAtMs = if (o.has("driftAt")) o.getLong("driftAt") else null,
                    driftArmed = o.optBoolean("driftArmed", true),
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
                    put("driftArmed", v.driftArmed)
                    put("bondLostTold", v.bondLostTold)
                },
            )
        }

    private fun ints(a: JSONArray?): Set<Int> = if (a == null) emptySet() else (0 until a.length()).map { a.getInt(it) }.toSet()

    private fun put(key: String, value: Any) {
        values.put(key, value)
        write()
    }

    /** The file, or (missing or unreadable) the old preferences once - then written to the file at once. */
    private fun load(): JSONObject {
        if (file.isFile) {
            try {
                return JSONObject(file.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                PumpBtLog.w("link state not read (${t.javaClass.simpleName}), taken from the old preferences")
            }
        }
        val o = JSONObject()
        try {
            val p = legacy
            if (p != null) {
                p.getString(K_DIGITS, null)?.let { o.put(K_DIGITS, it) }
                p.getString(K_VARIANT, null)?.let { o.put(K_VARIANT, it) }
                if (p.contains(K_PAIRED)) o.put(K_PAIRED, p.getBoolean(K_PAIRED, false))
                p.getString(K_ADDRESS, null)?.let { o.put(K_ADDRESS, it) }
                p.getString(K_ORIGINAL_NAME, null)?.takeIf { it.isNotEmpty() }?.let { o.put(K_ORIGINAL_NAME, it) }
                if (p.contains(K_REF_SEQ) && p.contains(K_REF_MS)) {
                    o.put(K_REF_SEQ, p.getLong(K_REF_SEQ, 0))
                    o.put(K_REF_MS, p.getLong(K_REF_MS, 0))
                }
            }
        } catch (t: Throwable) {
            PumpBtLog.w("old link preferences not read (${t.javaClass.simpleName})")
        }
        writeTo(o)
        return o
    }

    private fun write() = writeTo(values)

    /** .tmp + sync + rename; a failure is logged (the values stay in memory and go with the next change). */
    private fun writeTo(o: JSONObject) {
        try {
            val dir = file.parentFile ?: return
            dir.mkdirs()
            val tmp = File(dir, "$FILE.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(o.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) PumpBtLog.w("link state not moved into place")
            }
        } catch (t: Throwable) {
            PumpBtLog.w("link state not saved (${t.javaClass.simpleName})")
        }
    }

    internal companion object {
        const val DIR = "mod-pumpbt"
        const val FILE = "link.json"
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
    }
}
