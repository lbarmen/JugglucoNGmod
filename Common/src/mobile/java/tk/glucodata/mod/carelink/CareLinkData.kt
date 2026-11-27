package tk.glucodata.mod.carelink

import org.json.JSONArray
import org.json.JSONObject

/*
 * JugglucoNGmod: JSON field readers for the stored files (`seen.json`, `pump.json`). The parsing of the CareLink
 * cloud answer that once lived here went with the cloud.
 */

/** A nested object; no field, JSON null or another type - null. */
internal fun JSONObject.clObj(key: String): JSONObject? = if (isNull(key)) null else opt(key) as? JSONObject

/** A nested array; no field, JSON null or another type - null. */
internal fun JSONObject.clArr(key: String): JSONArray? = if (isNull(key)) null else opt(key) as? JSONArray

/**
 * A number field that may come as a number (30.0) or as a string ("5.0"); no field, JSON null, an empty or
 * non-numeric string, NaN or infinity - null. Explicit casts: Android's org.json and JSON-java differ on JSON null.
 */
internal fun JSONObject.clDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val d = when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
    return d?.takeIf { it.isFinite() }
}

/** A flag that may come as a boolean or as a string ("true"/"false", any case); anything else - null. */
internal fun JSONObject.clBool(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
}

/** Objects of an array, skipping anything else. */
internal fun JSONArray?.clObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) (opt(i) as? JSONObject)?.let(out::add)
    return out
}
