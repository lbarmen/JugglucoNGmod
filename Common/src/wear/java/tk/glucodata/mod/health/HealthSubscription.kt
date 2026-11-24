package tk.glucodata.mod.health

/**
 * JugglucoNGmod, watch block (part 4b): when the Health Services subscription is made again, and the small rules
 * around it (Glucove's numbers: silent for 15 minutes - its failure of 23.10.2026, when the watch killed the process
 * and the passive deliveries never came back although the subscription was listed; at least every 12 hours). Plain
 * decisions for the tests; the calls are in `tk.glucodata.mod.health.hs` (the `mod` build only).
 */
object HealthSubscription {

    /** Health Services needs Android 11 (API 30); below it nothing is called (upstream's watch minSdk is 26). */
    const val MIN_SDK = 30

    /** No delivery and no subscription for this long: subscribe again (and no attempt more often). */
    const val SILENT_MS = 15 * 60_000L

    /** Subscribe again at least this often (a subscription might be lost without a word). */
    const val REREGISTER_MS = 12 * 60 * 60_000L

    /**
     * No subscription in place and the last attempt failed (Watch 7i; watch log 19.11.2026: after the watch was
     * switched on, heart rate came only with `registered reason=new` about 15 minutes after the boot attempt -
     * Health Services may not answer yet right after boot): after the first failure the next attempt may come this
     * soon, so the next tick (every 5 minutes, at most one a minute) tries again instead of waiting [SILENT_MS].
     */
    const val RETRY_FAILED_MS = 60_000L

    /** The pause after the second failure in a row ([retryGap]). */
    const val RETRY_FAILED_AGAIN_MS = 5 * 60_000L

    /** [HealthState.registerError] when permissions allow heart rate or steps but Health Services offered neither. */
    const val ERROR_NO_TYPES = "NoTypes"

    fun supported(sdk: Int): Boolean = sdk >= MIN_SDK

    /**
     * The pause before the next attempt after [failures] failed attempts in a row with no subscription in place
     * (critic 7k, N2): 1 min after the first (the next tick), 5 min after the second, then [SILENT_MS] - the pause
     * every attempt had before Watch 7i. Each attempt is two Health Services calls of up to 8 s inside the tick's task;
     * a failure that stays (a refused permission, a watch that offers nothing) costs at most four attempts an hour,
     * as before, and a passing one (Health Services not ready after boot) is mended within minutes.
     */
    fun retryGap(failures: Int): Long = when {
        failures <= 1 -> RETRY_FAILED_MS
        failures == 2 -> RETRY_FAILED_AGAIN_MS
        else -> SILENT_MS
    }

    /** The failures in a row after an attempt that ended with [error] (empty - subscribed, or nothing to subscribe). */
    fun failuresAfter(previous: Int, error: String): Int = if (error.isEmpty()) 0 else previous.coerceAtLeast(0) + 1

    /**
     * Keeps the end of one subscription attempt: [error] (empty - subscribed, or nothing allowed) for the status and
     * the failures in a row ([failuresAfter]) that [registerReason] and the boot retry read (critic 7k, N5: the one
     * place both are written, under test).
     */
    fun noteAttempt(state: HealthState, error: String) {
        state.registerFailures = failuresAfter(state.registerFailures, error)
        state.registerError = error
    }

    /**
     * The error of an attempt that found nothing to subscribe: [ERROR_NO_TYPES] when the permissions allow something
     * ([permsAny]) but Health Services offered none of it (not ready yet after boot - tried again), empty when nothing
     * is allowed (nothing to retry).
     */
    fun errorForNoTypes(permsAny: Boolean): String = if (permsAny) ERROR_NO_TYPES else ""

    /**
     * The allowed kinds Health Services did not offer (`hr`, `steps`, joined by `,`; empty - all offered): the
     * subscription is made with the rest, and the watch log names them (critic 7k, N1).
     */
    fun missingTypes(perms: HealthPermissions.Perms, offeredHr: Boolean, offeredSteps: Boolean): String =
        typeNames(hr = perms.hr && !offeredHr, steps = perms.activity && !offeredSteps)

    /**
     * Why to subscribe at [nowMs], or `null` - not now. Nothing without a permission and a subscription ([permsNow]
     * has neither heart rate nor activity; with a subscription in place the change of permissions drops it) or within
     * [SILENT_MS] of the last attempt ([attemptAt]; a subscription in place or one with nothing to subscribe is not
     * retried on every tick) - within [retryGap] of it when none is in place and the last [failures] attempts failed
     * (without a subscription no data comes at all). Then: `new` - none in place ([registered] false); `perms` - the
     * permissions changed since it was made; `stale` - older than [REREGISTER_MS] (or made "in the future": the clock
     * went back); `silent` - no delivery ([lastDataAt]) and no subscription for over [SILENT_MS].
     */
    fun registerReason(
        nowMs: Long,
        permsNow: HealthPermissions.Perms,
        permsAtRegistration: String,
        registered: Boolean,
        registeredAt: Long,
        lastDataAt: Long,
        attemptAt: Long,
        failures: Int = 0,
    ): String? {
        if (!permsNow.any && !registered) return null
        val gap = if (!registered && failures > 0) retryGap(failures) else SILENT_MS
        if (attemptAt > 0 && nowMs - attemptAt in 0 until gap) return null
        return when {
            !registered -> "new"
            permsNow.short() != permsAtRegistration -> "perms"
            nowMs - registeredAt !in 0 until REREGISTER_MS -> "stale"
            nowMs - maxOf(lastDataAt, registeredAt) > SILENT_MS -> "silent"
            else -> null
        }
    }

    /** The subscription memory: `hr,steps`, `hr`, `steps` or empty. */
    fun typeNames(hr: Boolean, steps: Boolean): String = listOfNotNull("hr".takeIf { hr }, "steps".takeIf { steps }).joinToString(",")

    /**
     * The source's part of the status row (the permanent part: a change sends a status at once), never values:
     * `perm=hr1,bg1,act1;reg=hr+steps;regErr=-`.
     */
    fun statusFields(perms: HealthPermissions.Perms, registeredTypes: String, registerError: String): String =
        "perm=${perms.short()};reg=${registeredTypes.replace(',', '+').ifEmpty { "-" }};regErr=${registerError.ifEmpty { "-" }}"

    /** The ordinary time of the watch's boot (Health Services times count from it): now minus the time since boot. */
    fun bootEpochMs(nowMs: Long, elapsedSinceBootMs: Long): Long = nowMs - elapsedSinceBootMs
}
