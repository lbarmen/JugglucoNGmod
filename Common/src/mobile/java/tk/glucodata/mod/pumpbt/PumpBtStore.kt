package tk.glucodata.mod.pumpbt

import android.content.Context

/** Where the "Mobile …" name goes in the pairing advertisement. */
internal enum class PumpBtNameVariant {
    /** Medtronic manufacturer data 0x01F9 `00 + "Mobile NNNNNN" + 00` (Android codes of OpenMinimed, Pebble). */
    A,

    /** The phone's Bluetooth name, changed to "Mobile NNNNNN" during the trial (OpenMinimed description, Python). */
    B,
}

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the small state the trial keeps between runs - the 6 digits of the
 * advertised name, the name variant, "paired once", the pump's address, the history reference time and the phone's
 * own Bluetooth name while variant B has changed it. The number of the last history record read is not kept here:
 * every start reads the last 50 records again. No key material, no session.
 */
internal class PumpBtStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("mod_pumpbt", Context.MODE_PRIVATE)

    /** The 6 digits of "Mobile NNNNNN", made once. */
    val nameDigits: String
        get() = prefs.getString(K_DIGITS, null)?.takeIf { it.length == 6 && it.all(Char::isDigit) }
            ?: "%06d".format((0..999_999).random()).also { prefs.edit().putString(K_DIGITS, it).apply() }

    val advertisedName: String get() = "Mobile $nameDigits"

    var variant: PumpBtNameVariant
        get() = runCatching { PumpBtNameVariant.valueOf(prefs.getString(K_VARIANT, null) ?: "A") }.getOrDefault(PumpBtNameVariant.A)
        set(v) = prefs.edit().putString(K_VARIANT, v.name).apply()

    var paired: Boolean
        get() = prefs.getBoolean(K_PAIRED, false)
        set(v) = prefs.edit().putBoolean(K_PAIRED, v).apply()

    var pumpAddress: String
        get() = prefs.getString(K_ADDRESS, "") ?: ""
        set(v) = prefs.edit().putString(K_ADDRESS, v).apply()

    /** Latest pump reference time seen in the history: its record number and the time it gave (epoch ms). */
    var historyReference: Pair<Long, Long>?
        get() = if (prefs.contains(K_REF_SEQ) && prefs.contains(K_REF_MS)) prefs.getLong(K_REF_SEQ, 0) to prefs.getLong(K_REF_MS, 0) else null
        set(v) = prefs.edit().apply {
            if (v == null) {
                remove(K_REF_SEQ)
                remove(K_REF_MS)
            } else {
                putLong(K_REF_SEQ, v.first)
                putLong(K_REF_MS, v.second)
            }
        }.apply()

    /**
     * The phone's Bluetooth name before variant B changed it; null while it is not changed. Saved before the change.
     * An empty name is never saved (it could not be given back): it removes the entry.
     */
    var originalAdapterName: String?
        get() = prefs.getString(K_ORIGINAL_NAME, null)?.takeIf { it.isNotEmpty() }
        set(v) {
            // commit: it must be on disk before the name changes, the process may die right after
            prefs.edit().apply { if (v.isNullOrEmpty()) remove(K_ORIGINAL_NAME) else putString(K_ORIGINAL_NAME, v) }.commit()
        }

    private companion object {
        const val K_DIGITS = "name_digits"
        const val K_VARIANT = "name_variant"
        const val K_PAIRED = "paired"
        const val K_ADDRESS = "pump_address"
        const val K_REF_SEQ = "history_ref_seq"
        const val K_REF_MS = "history_ref_ms"
        const val K_ORIGINAL_NAME = "original_adapter_name"
    }
}
