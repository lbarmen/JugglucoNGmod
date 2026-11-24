package tk.glucodata.mod.watch

/**
 * JugglucoNGmod, watch block (part 7o): at most [count] runs in any [windowMs] - the pace of our reactions to new data
 * (the face's read, the early check of the alerts). A normal minute brings up to three signals a moment apart (the
 * phone's calibration, then the values, then the echo of upstream's alert path handing the new reading on): they go
 * through at once. History catching up brings dozens: the first [count] go through, the rest wait until the oldest run
 * leaves the window. That is more than the old "one read, then a pause of 20 s" allowed (one read plus one at the end
 * of the pause, 3 a minute): up to [count] x 3 a minute - 15 for the face's budget of 5, 9 for the alerts' 3 (critic
 * 7o, N1); bounded, and only while history catches up. Keeps only the last [count] run times; thread-safe.
 */
internal class SignalBudget(val count: Int = COUNT, val windowMs: Long = WINDOW_MS) {
    init {
        require(count > 0 && windowMs >= 0L)
    }

    private val runs = LongArray(count) { NEVER }
    private var next = 0

    /** How long to wait at [nowMs] before the next run (0 - at once). */
    @Synchronized
    fun waitMs(nowMs: Long): Long = waitFor(runs, nowMs, windowMs)

    /** The times of the last runs, oldest first not guaranteed ([NEVER] - no run). */
    @Synchronized
    fun lastRuns(): LongArray = runs.copyOf()

    /** A run at [nowMs] (the clock of [waitMs]). */
    @Synchronized
    fun record(nowMs: Long) {
        runs[next] = nowMs
        next = (next + 1) % count
    }

    companion object {
        const val COUNT = 3
        const val WINDOW_MS = 20_000L

        /** "No run yet": far enough back to be out of any window, close enough to never overflow. */
        const val NEVER = Long.MIN_VALUE / 2

        /**
         * The wait before the next run at [nowMs], given [lastRuns] - the times of the last runs, as many as the budget
         * allows: none of them is older than [windowMs] - wait until the oldest is; otherwise at once.
         */
        internal fun waitFor(lastRuns: LongArray, nowMs: Long, windowMs: Long): Long =
            ((lastRuns.minOrNull() ?: NEVER) + windowMs - nowMs).coerceAtLeast(0L)
    }
}
