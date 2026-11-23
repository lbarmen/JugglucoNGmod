package tk.glucodata.mod.carelink

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What a pump marker is: a bolus (INSULIN), calculator carbs (MEAL), a meter reading (BG_READING), anything else. */
enum class ClKind { BOLUS, MEAL, BG, OTHER }

/**
 * A pump event from `patientData.markers[]` (stage 4c, contract of the plan). [key] - `type|pump time|n`: the cloud
 * gives a marker no id, so the key is its type, its local pump time to the second and n - its number among markers of
 * the same type in the same second (usually 0). [atMs] - the moment, ms UTC (see [CareLinkMarkers.parse]).
 * Bolus: [programmedUnits] / [deliveredUnits] - sums of the `programmed*Amount` / `delivered*Amount` fields (fast and
 * extended parts), [completed], [bolusType] (FAST - a normal bolus). Calculator carbs: [grams].
 */
data class ClEvent(
    val kind: ClKind,
    val key: String,
    val atMs: Long,
    val programmedUnits: Double? = null,
    val deliveredUnits: Double? = null,
    val completed: Boolean? = null,
    val bolusType: String? = null,
    val grams: Double? = null,
) {
    /** The dose to record: while the bolus runs (`completed=false`) - programmed, then - delivered. */
    val dose: Double?
        get() = if (completed == false) programmedUnits ?: deliveredUnits else deliveredUnits ?: programmedUnits

    /** Finished with less delivered than programmed (stopped by hand, occlusion). */
    val interrupted: Boolean
        get() = completed == true && programmedUnits != null && deliveredUnits != null &&
            deliveredUnits < programmedUnits - CareLinkMarkers.EPS

    /** Square or dual wave (not FAST): rare; recorded as one dose with a note (plan, decision 4). */
    val extended: Boolean get() = bolusType != null && bolusType != BOLUS_FAST

    companion object {
        const val BOLUS_FAST = "FAST"
    }
}

/**
 * JugglucoNGmod, CareLink (stage 4c): pump markers of a `display/message` answer into [ClEvent]s. Moved from
 * Glucove's CareLinkMarkers on org.json; alarms and notifications of the pump are not taken (plan, decision 10).
 *
 * Marker time is the pump's local time with no zone ("2026-01-10T08:00:03"): the moment comes from the offset of
 * `metadata.clientDateTime` ("...+03:00"); with no offset - from the phone zone. Doses and grams come both as numbers
 * and as strings, `completed` too. A marker without a type or a readable time is skipped.
 */
object CareLinkMarkers {

    /** Doses equal if closer than this (the pump step is 0.025-0.1 U). */
    const val EPS = 0.005

    const val TYPE_INSULIN = "INSULIN"
    const val TYPE_MEAL = "MEAL"
    const val TYPE_BG = "BG_READING"

    /**
     * Events of [root] by time (then by key). [phoneZone] - the zone used when the answer carries no pump offset
     * (`metadata.clientDateTime`); the pump's own offset always wins.
     */
    fun parse(root: JSONObject, phoneZone: ZoneId = ZoneId.systemDefault()): List<ClEvent> {
        val offset = offsetOf(root)
        val sameSecond = HashMap<String, Int>()
        val out = ArrayList<ClEvent>()
        for (m in CareLinkData.patient(root).clArr("markers").clObjects()) {
            val type = m.clStr("type")?.takeIf { it.isNotBlank() } ?: continue
            val ts = m.clStr("timestamp")?.take(19) ?: continue
            val at = localMs(ts, offset, phoneZone) ?: continue
            // n - in the order of the answer, among markers of this type in this second
            val base = "$type|$ts"
            val n = sameSecond.getOrDefault(base, 0)
            sameSecond[base] = n + 1
            out += event(type, "$base|$n", at, m.clObj("data")?.clObj("dataValues") ?: JSONObject())
        }
        return out.sortedWith(compareBy({ it.atMs }, { it.key }))
    }

    private fun event(type: String, key: String, at: Long, v: JSONObject): ClEvent = when (type) {
        TYPE_INSULIN -> ClEvent(
            ClKind.BOLUS, key, at,
            programmedUnits = sum(v, "programmed"),
            deliveredUnits = sum(v, "delivered"),
            completed = v.clBool("completed"),
            bolusType = v.clStr("bolusType")?.takeIf { it.isNotBlank() },
        )
        TYPE_MEAL -> ClEvent(ClKind.MEAL, key, at, grams = v.clDouble("amount")?.takeIf { it >= 0.0 })
        TYPE_BG -> ClEvent(ClKind.BG, key, at)
        else -> ClEvent(ClKind.OTHER, key, at)
    }

    /** Sum of the `<prefix>...Amount` fields (fast and extended parts); no readable field - null. */
    private fun sum(v: JSONObject, prefix: String): Double? {
        var total = 0.0
        var any = false
        val names = v.keys()
        while (names.hasNext()) {
            val k = names.next()
            if (!k.startsWith(prefix) || !k.endsWith("Amount")) continue
            val d = v.clDouble(k) ?: continue
            total += d
            any = true
        }
        return if (any) round3(total) else null
    }

    /**
     * The pump zone offset from `metadata.clientDateTime` ("2026-01-10T12:00:00.123+03:00"); none or unreadable - null.
     * `Z` / `+00:00` counts as none (critic 4c, N7): a server-made UTC stamp is not the pump's zone, the phone zone is
     * the safer guess (the same for a person who does live at +00:00).
     */
    fun offsetOf(root: JSONObject): ZoneOffset? {
        val s = root.clObj("metadata")?.clStr("clientDateTime") ?: return null
        return try {
            OffsetDateTime.parse(s.trim()).offset.takeIf { it != ZoneOffset.UTC }
        } catch (_: Exception) {
            null
        }
    }

    /** Local pump time "YYYY-MM-DDTHH:MM:SS" -> ms UTC: by [offset], none - by [zone]; unreadable - null. */
    fun localMs(s: String, offset: ZoneOffset?, zone: ZoneId): Long? = try {
        val local = LocalDateTime.parse(s.trim().take(19))
        (if (offset != null) local.toInstant(offset) else local.atZone(zone).toInstant()).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    internal fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
