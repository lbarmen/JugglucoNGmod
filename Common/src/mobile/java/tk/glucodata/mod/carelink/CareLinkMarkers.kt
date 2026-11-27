package tk.glucodata.mod.carelink

/** What a pump event is: a bolus (INSULIN), calculator carbs (MEAL), a meter reading (BG_READING), anything else. */
enum class ClKind { BOLUS, MEAL, BG, OTHER }

/**
 * A pump event for the journal and basal writers (stage 4c, contract of the plan). [key] - `type|pump time|n`: its
 * type, its local pump time to the second and n - its number among events of the same type in the same second
 * (usually 0); the keys of the journal rows are built from it. [atMs] - the moment, ms UTC.
 * Bolus: [programmedUnits] / [deliveredUnits] - programmed / delivered (fast and extended parts together),
 * [completed], [bolusType] (FAST - a normal bolus). Calculator carbs: [grams].
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
 * JugglucoNGmod, CareLink (stage 4c): the event types and dose rounding shared by the writers and the Bluetooth
 * bridge. The parsing of the cloud's pump markers that once lived here went with the cloud.
 */
object CareLinkMarkers {

    /** Doses equal if closer than this (the pump step is 0.025-0.1 U). */
    const val EPS = 0.005

    const val TYPE_INSULIN = "INSULIN"
    const val TYPE_MEAL = "MEAL"

    internal fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
