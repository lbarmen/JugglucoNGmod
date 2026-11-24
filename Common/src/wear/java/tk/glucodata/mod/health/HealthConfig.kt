package tk.glucodata.mod.health

/**
 * JugglucoNGmod, watch block (part 4): the numbers of heart rate and steps collection on the watch. Heart rate goes as
 * 1-minute means (Watch 7i, the owner's decision of 19.11.2026; before it 5-minute means, as Glucove ran them). A
 * delivery of Health Services sends by itself when no batch went for [SEND_AFTER_DATA_GAP_MS]; the phone's
 * `/mod/tick` (every 5 minutes) stays as the fallback and the retry.
 */
object HealthConfig {
    /** Heart rate goes as one mean per window of this length (aligned to the clock: :00, :01, :02 ...). */
    const val HR_WINDOW_MS = 60_000L

    /** A window closes this long after its end after "deliver now" (the data up to now is in)... */
    const val HR_GRACE_REFRESH_MS = 5_000L

    /**
     * ...and, on a normal delivery, a window the delivery did not cover (its end after the delivery's newest reading)
     * this long after its end. Health Services hands its readings over in batches every 1.5-3.5 minutes (watch log
     * 19.11.2026), each up to about its delivery time, so a window ended before a batch's newest reading closes with
     * that batch ([HrAggregator.close]); this wait only matters when the readings stop (watch off the wrist) and must
     * stay longer than the lag of a batch behind its newest reading. Kept at 2 minutes: the tick closes such a window
     * within 5 minutes anyway, and a window closed too early only costs a late second row the phone merges.
     */
    const val HR_GRACE_DATA_MS = 120_000L

    /** How long to wait for data after asking the source to deliver what it collected. */
    const val FLUSH_WAIT_MS = 1_500L

    /**
     * A delivery sends a batch by itself only when the last send or attempt is at least this old (at most one send a
     * minute; a delivery comes every 1.5-3.5 minutes). It runs on [HealthCore.executor] in the delivery's wake-up, no
     * WorkManager task (no expedited quota is used).
     */
    const val SEND_AFTER_DATA_GAP_MS = 60_000L

    /** The status line goes at least this often (and at once when its permanent part changes). */
    const val STATUS_PERIOD_MS = 30 * 60_000L

    /** Queue limit while the phone is away (several weeks of means and step counts); the oldest files go first. */
    const val BUFFER_MAX_BYTES = 20L * 1024 * 1024

    /** One Data Layer put waits at most this long for the local accept. */
    const val PUT_TIMEOUT_MS = 10_000L

    /** One hand-over sends files for at most this long; the rest goes next time (a long-away phone, many files). */
    const val SEND_BUDGET_MS = 20_000L
}
