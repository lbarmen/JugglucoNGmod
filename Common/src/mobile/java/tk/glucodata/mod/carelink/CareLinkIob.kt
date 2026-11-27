package tk.glucodata.mod.carelink

import tk.glucodata.JournalSnapshotAccess
import tk.glucodata.drivers.api.ApiIobSnapshot

/**
 * JugglucoNGmod, CareLink (stage 4d): the pump's active insulin for upstream's IoB plate, notification and widgets
 * through their remote snapshot `ApiIobSnapshot` (fresh 30 min; eIOB and COB NaN - theirs stay their own computation).
 * It does not go into the forecast or to Nightscout (upstream's choice for remote snapshots). Plan decision 9: the
 * time is the pump's `activeInsulin` time (none - the data time), not later than now; a time more than 5 min ahead is
 * dropped - such a snapshot would stay "fresh" and refuse every real one after it.
 */
object CareLinkIob {

    /** The snapshot we put there last: [clear] takes away only our own. */
    @Volatile
    private var published: ApiIobSnapshot.RemoteIob? = null

    /** [pumpNow] - the snapshot of this answer; null - the empty answer: nothing to publish. Not suspend. */
    fun publish(pumpNow: PumpNow?) {
        val p = pumpNow ?: return
        val units = p.iobUnits?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val now = CareLinkClock.now()
        val at = p.iobAtMs ?: p.dataAtMs
        if (at > now + CareLinkPumpNow.IOB_FUTURE_MS) return
        val remote = ApiIobSnapshot.RemoteIob(units.toFloat(), Float.NaN, Float.NaN, minOf(at, now))
        if (ApiIobSnapshot.update(remote)) {
            published = remote
            JournalSnapshotAccess.invalidateBroadcastIobCache()
        }
    }

    /**
     * Sign-out and "sign in again": the pump's number leaves the plate now, not after 30 min (critic of the joints,
     * N2). Only while the slot still holds our snapshot: the same slot is upstream's API follower source, a newer
     * number of theirs stays.
     */
    fun clear() {
        val ours = published ?: return
        published = null
        if (ApiIobSnapshot.fresh(CareLinkClock.now()) === ours) {
            ApiIobSnapshot.clear()
            JournalSnapshotAccess.invalidateBroadcastIobCache()
        }
    }
}
