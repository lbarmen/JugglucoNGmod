package tk.glucodata.mod.health

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * JugglucoNGmod, watch block (part 4b): which permissions heart rate and steps need on this watch and in which order
 * the page asks for them (Glucove's order: heart rate with activity, then the background one). Plain decisions on
 * numbers, for the tests; [read] is the one place that asks the system.
 *
 * Which names apply follows the app's effective level - the lower of the watch's Android and our targetSdk: an app
 * targeting below Android 16 (API 36; upstream's watch targets 35) keeps BODY_SENSORS and BODY_SENSORS_BACKGROUND on
 * Android 16 too, the system maps them to the new health permissions.
 */
object HealthPermissions {

    const val HR_LEGACY = "android.permission.BODY_SENSORS"
    const val HR_BACKGROUND_LEGACY = "android.permission.BODY_SENSORS_BACKGROUND"
    const val HR_HEALTH = "android.permission.health.READ_HEART_RATE"
    const val HR_BACKGROUND_HEALTH = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val ACTIVITY = "android.permission.ACTIVITY_RECOGNITION"

    /** The level the permission rules follow: the lower of the watch's [sdk] and the app's [targetSdk]. */
    fun effectiveSdk(sdk: Int, targetSdk: Int): Int = minOf(sdk, targetSdk)

    /** Heart rate: the health permission from Android 16 (API 36), body sensors before. */
    fun hrPermission(level: Int): String = if (level >= 36) HR_HEALTH else HR_LEGACY

    /** Heart rate in the background: from Android 13 (API 33); before that the heart rate permission covers it. */
    fun backgroundPermission(level: Int): String? = when {
        level >= 36 -> HR_BACKGROUND_HEALTH
        level >= 33 -> HR_BACKGROUND_LEGACY
        else -> null
    }

    /** Activity recognition (day steps) is a runtime permission from Android 10 (API 29); granted before. */
    fun activityPermission(level: Int): String? = if (level >= 29) ACTIVITY else null

    /** What is granted: heart rate, heart rate in the background (`true` where it does not exist), steps. */
    data class Perms(val hr: Boolean, val background: Boolean, val activity: Boolean) {
        val any: Boolean get() = hr || activity
        val anyMissing: Boolean get() = !hr || !background || !activity

        /** For the status and the subscription memory: `hr1,bg1,act1`. */
        fun short(): String = "hr${flag(hr)},bg${flag(background)},act${flag(activity)}"

        private fun flag(b: Boolean) = if (b) 1 else 0
    }

    /** [Perms] at [level] with [granted] saying whether one permission is held. */
    fun perms(level: Int, granted: (String) -> Boolean): Perms = Perms(
        hr = granted(hrPermission(level)),
        background = backgroundPermission(level)?.let(granted) ?: true,
        activity = activityPermission(level)?.let(granted) ?: true,
    )

    /** What the page does on "Allow access". */
    sealed interface Step {
        /** Ask for these together (heart rate and activity, the missing ones). */
        data class AskForeground(val permissions: List<String>) : Step
        /** Ask for the background permission (only after the heart rate one is given). */
        data class AskBackground(val permission: String) : Step
        /** Asked once already: Android often shows "allow all the time" only in the app's settings. */
        data object OpenSettings : Step
        /** Everything is given: subscribe. */
        data object Done : Step
    }

    /**
     * The next step at [level] with [granted] and whether the background permission was asked once
     * ([askedBackground]). The background one is never asked before heart rate (Android refuses it then).
     */
    fun nextStep(level: Int, granted: (String) -> Boolean, askedBackground: Boolean): Step {
        val foreground = listOfNotNull(hrPermission(level), activityPermission(level)).filterNot(granted)
        if (foreground.isNotEmpty()) return Step.AskForeground(foreground)
        val background = backgroundPermission(level)
        if (background != null && !granted(background)) {
            return if (askedBackground) Step.OpenSettings else Step.AskBackground(background)
        }
        return Step.Done
    }

    /** The level of this watch and app. */
    fun level(context: Context): Int = effectiveSdk(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)

    /** Whether [permission] is held by our app. */
    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** What is granted now. */
    fun read(context: Context): Perms = perms(level(context)) { granted(context, it) }
}
