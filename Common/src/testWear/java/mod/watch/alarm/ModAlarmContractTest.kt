package mod.watch.alarm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JugglucoNGmod, watch block (part 8): what our alarm screen takes from upstream's code, read from the sources - a
 * merge of a JugglucoNG release that changes it turns this red (mod/SYNC.md says what to compare then).
 *
 * - The timing of the swap: they register their screen in `Specific.registerBridges`, the first statement of
 *   `Applic.onCreate` (our provider's claim runs right after it).
 * - Their watch `AlarmActivity` still does exactly the steps ours copies, with the same four lifecycle methods - a new
 *   step or method there is new behaviour our screen does not have yet.
 * - Our manifest entry is declared as theirs (private, over the lock, screen on, one instance, theme), and our
 *   starter is private.
 */
class ModAlarmContractTest {

    private fun source(path: String): String =
        listOf(File(path), File("Common/$path")).first { it.isFile }.readText()

    private fun activityBlock(manifest: String, name: String): String =
        Regex("""<activity\s+android:name="${Regex.escape(name)}"[\s\S]*?/>""").find(manifest)?.value.orEmpty()

    private fun attr(block: String, name: String): String? =
        Regex("""android:$name="([^"]*)"""").find(block)?.groupValues?.get(1)

    @Test
    fun theyRegisterTheirScreenFirstThingInApplicOnCreate() {
        val specific = source("src/wear/java/tk/glucodata/Specific.java")
        val bridges = specific.substringAfter("static void registerBridges()")
        assertTrue(bridges.contains("tk.glucodata.ui.AlarmActivityAccess.register(tk.glucodata.ui.WearAlarmActivityHost.INSTANCE);"))
        val applic = source("src/main/java/tk/glucodata/Applic.java")
        val onCreate = applic.substringAfter("public void onCreate() {").substringBefore("initproc();")
        assertTrue("registerBridges in Applic.onCreate", onCreate.contains("Specific.registerBridges();"))
        val host = source("src/wear/java/tk/glucodata/ui/WearAlarmActivityHost.kt")
        assertTrue(host.contains("Intent(context, AlarmActivity::class.java)"))
    }

    @Test
    fun theirWatchAlarmScreenStillDoesWhatOursCopies() {
        val theirs = source("src/wear/java/tk/glucodata/ui/AlarmActivity.kt")
        val steps = listOf(
            "WindowCompat.setDecorFitsSystemWindows(window, false)",
            "Notify.setAlarmUiVisible(true)",
            "Notify.setAlarmUiVisible(false)",
            "AlarmActivityAccess.removeObserver(this)",
            "AlarmActivityAccess.observeAcknowledgement(this, intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)) { finish() }",
            "if (intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID) == null)",
            "AlarmActivityAccess.isAcknowledged(intent)",
            "Notify.cancelQueuedAlarmActivityLaunch(",
            "CurrentDisplaySource.resolveCurrent()",
            "AlertRepository.loadConfig(it).defaultSnoozeMinutes",
            "DEFAULT_SNOOZE_MINUTES = 15",
            "AlarmActionReceiver.ACTION_SNOOZE",
            "AlarmActionReceiver.ACTION_DISMISS",
            "putExtra(AlarmActionReceiver.EXTRA_ALERT_TYPE_ID, alertTypeId)",
            "putExtra(AlarmActionReceiver.EXTRA_SNOOZE_MINUTES, snoozeMinutes)",
            "sendBroadcast(actionIntent)",
            "window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)",
            "window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)",
            "setShowWhenLocked(true)",
            "setTurnScreenOn(true)",
            "keyguardManager?.requestDismissKeyguard(this, null)",
            "Regex(\"\\\\d+(?:[.,]\\\\d+)?\")",
        )
        steps.forEach { assertTrue("their AlarmActivity: $it", theirs.contains(it)) }
        val overrides = Regex("""override fun (\w+)""").findAll(theirs).map { it.groupValues[1] }.toList()
        assertEquals(listOf("onCreate", "onNewIntent", "onStart", "onStop"), overrides)
    }

    @Test
    fun ourManifestEntryIsDeclaredAsTheirs() {
        val ours = activityBlock(source("src/wearMod/AndroidManifest.xml"), "tk.glucodata.mod.watch.alarm.ModAlarmActivity")
        assertTrue("our alarm activity declared", ours.isNotEmpty())
        val theirWear = activityBlock(source("src/wear/AndroidManifest.xml"), ".ui.AlarmActivity")
        val theirMain = activityBlock(source("src/main/AndroidManifest.xml"), ".ui.AlarmActivity")
        assertTrue(theirWear.isNotEmpty() && theirMain.isNotEmpty())
        // the watch build merges both of their entries, the watch one first
        for (name in listOf("exported", "taskAffinity", "excludeFromRecents", "autoRemoveFromRecents", "showOnLockScreen",
            "showWhenLocked", "turnScreenOn", "launchMode", "theme")) {
            val theirs = attr(theirWear, name) ?: attr(theirMain, name)
            assertEquals(name, theirs, attr(ours, name))
        }
        assertEquals("false", attr(ours, "exported"))
        assertEquals("true", attr(ours, "showWhenLocked"))
        assertEquals("true", attr(ours, "turnScreenOn"))
        val provider = Regex("""<provider\s+android:name="tk\.glucodata\.mod\.watch\.alarm\.ModAlarmStarter"[\s\S]*?/>""")
            .find(source("src/wearMod/AndroidManifest.xml"))?.value.orEmpty()
        assertEquals("false", attr(provider, "exported"))
        assertTrue(provider.contains("\${applicationId}.modalarmstarter"))
    }
}
