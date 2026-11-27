package tk.glucodata.mod.carelink

import org.json.JSONObject

/**
 * JugglucoNGmod: reading of the stored JSON files (`seen.json`, `pump.json`, the pump snapshot in the state). The
 * cloud sign-in rules that once lived here went with the CareLink cloud; the name stays for the callers.
 * JSON is read with has/isNull/opt and explicit casts: org.json on the phone (Android) and in plain JVM tests
 * (JSON-java) differ on JSON null.
 */
object CareLinkConfig {

    /** A JSON object of the top level; null text or not an object - null. */
    fun parse(text: String?): JSONObject? {
        if (text == null) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }
}

/** A string or number field as text; no field or JSON null - null (the same on Android's org.json and JSON-java). */
internal fun JSONObject.clStr(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> null
    }
}

/** A whole number field (a number or a numeric string); no field, JSON null or not a number - null. */
internal fun JSONObject.clLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Int -> v.toLong()
        is Long -> v
        is Number -> v.toDouble().takeIf { it.isFinite() }?.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }
}

internal fun JSONObject.clInt(key: String): Int? =
    clLong(key)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
