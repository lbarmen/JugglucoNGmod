package tk.glucodata.mod.carelink

import org.json.JSONArray
import org.json.JSONObject

/**
 * JugglucoNGmod, CareLink (stage 4c): the `display/message` answer - `{"metadata": {...}, "patientData": {...}}`
 * (older versions put the pump fields at the root). Moved from Glucove's CareLinkData: only the validity check and the
 * pump data time; nothing of the answer is ever written to disk or to a log (no scrub, no summary).
 */
object CareLinkData {

    /** The pump part of the answer: `patientData`, or the root itself for older answers. */
    fun patient(root: JSONObject): JSONObject = root.clObj("patientData") ?: root

    /**
     * Looks like pump data: at least the markers list or the time of the pump data. An answer with neither (an empty
     * object, an error page in JSON) is not a poll result.
     */
    fun looksValid(root: JSONObject): Boolean {
        val p = patient(root)
        return p.clArr("markers") != null || p.clLong("lastMedicalDeviceDataUpdateServerTime") != null
    }

    /**
     * When the pump data reached the cloud (`lastMedicalDeviceDataUpdateServerTime`, ms UTC) - the measure of
     * freshness, not the time of the answer: the cloud answers long after the phone with MiniMed Mobile stopped
     * uploading. No field, not a number or not positive - null.
     */
    fun pumpDataAt(root: JSONObject): Long? =
        patient(root).clLong("lastMedicalDeviceDataUpdateServerTime")?.takeIf { it > 0 }
}

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
