package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.HealthPermissions
import tk.glucodata.mod.health.HealthPermissions.Step

/** JugglucoNGmod, watch block (part 4b): which permissions apply on which Android, and the page's order of asking. */
class HealthPermissionsTest {

    private val legacyHr = HealthPermissions.HR_LEGACY
    private val legacyBg = HealthPermissions.HR_BACKGROUND_LEGACY
    private val activity = HealthPermissions.ACTIVITY

    @Test
    fun permissionNamesFollowTheEffectiveLevel() {
        assertEquals(legacyHr, HealthPermissions.hrPermission(34))
        assertEquals(legacyBg, HealthPermissions.backgroundPermission(34))
        assertEquals(activity, HealthPermissions.activityPermission(34))
        assertEquals(HealthPermissions.HR_HEALTH, HealthPermissions.hrPermission(36))
        assertEquals(HealthPermissions.HR_BACKGROUND_HEALTH, HealthPermissions.backgroundPermission(36))
        assertNull(HealthPermissions.backgroundPermission(32)) // Wear OS 3: no separate background permission
        assertNull(HealthPermissions.activityPermission(28))
        // Upstream's watch targets 35: on Android 16 the legacy names stay (the system maps them).
        assertEquals(35, HealthPermissions.effectiveSdk(sdk = 36, targetSdk = 35))
        assertEquals(34, HealthPermissions.effectiveSdk(sdk = 34, targetSdk = 35))
        assertEquals(legacyBg, HealthPermissions.backgroundPermission(HealthPermissions.effectiveSdk(36, 35)))
    }

    @Test
    fun backgroundPermissionNameHasNoSpace() {
        assertEquals("android.permission.BODY_SENSORS_BACKGROUND", legacyBg) // upstream's manifest has a trailing space
    }

    @Test
    fun permsCountAMissingPermissionKindAsGranted() {
        val none = HealthPermissions.perms(32) { false }
        assertTrue(none.background) // nothing to grant below API 33
        assertFalse(none.any)
        val hrOnly = HealthPermissions.perms(34) { it == legacyHr }
        assertEquals("hr1,bg0,act0", hrOnly.short())
        assertTrue(hrOnly.any)
        assertTrue(hrOnly.anyMissing)
        assertFalse(HealthPermissions.perms(34) { true }.anyMissing)
    }

    @Test
    fun pageAsksHeartRateWithActivityFirstThenBackgroundThenSettings() {
        val granted = mutableSetOf<String>()
        assertEquals(Step.AskForeground(listOf(legacyHr, activity)), HealthPermissions.nextStep(34, granted::contains, false))
        granted += legacyHr
        assertEquals(Step.AskForeground(listOf(activity)), HealthPermissions.nextStep(34, granted::contains, false))
        granted += activity
        assertEquals(Step.AskBackground(legacyBg), HealthPermissions.nextStep(34, granted::contains, false))
        assertEquals(Step.OpenSettings, HealthPermissions.nextStep(34, granted::contains, askedBackground = true))
        granted += legacyBg
        assertEquals(Step.Done, HealthPermissions.nextStep(34, granted::contains, true))
    }

    @Test
    fun backgroundIsNeverAskedBeforeHeartRate() {
        val onlyActivity = setOf(activity)
        assertEquals(Step.AskForeground(listOf(legacyHr)), HealthPermissions.nextStep(34, onlyActivity::contains, true))
        // Wear OS 3 (API 30-32): heart rate and activity are all there is.
        assertEquals(Step.Done, HealthPermissions.nextStep(30, setOf(legacyHr, activity)::contains, false))
        // Android 16 with targetSdk 36: the health names.
        assertEquals(
            Step.AskForeground(listOf(HealthPermissions.HR_HEALTH, activity)),
            HealthPermissions.nextStep(36, emptySet<String>()::contains, false),
        )
    }
}
