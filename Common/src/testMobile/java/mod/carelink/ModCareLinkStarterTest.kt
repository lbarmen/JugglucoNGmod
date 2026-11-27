package mod.carelink

import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.mod.carelink.CareLinkCloudCleanup
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.ModCareLinkStarter
import java.io.File

/**
 * JugglucoNGmod, CareLink 4a, part D1: the starter lives in Common/src/mobile/java (seen by upstream's debug tests) and
 * must never throw on process start; after the main loop it runs the cloud cleanup off the main thread. A plain
 * Application: the starter needs none of upstream's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModCareLinkStarterTest {

    private val startBackground0 = ModCareLinkStarter.startBackground
    private val cancelWork0 = CareLinkCloudCleanup.cancelWork
    private val deleteKey0 = CareLinkCloudCleanup.deleteKey
    private val clearCookies0 = CareLinkCloudCleanup.clearCookies

    @After
    fun tearDown() {
        ModCareLinkStarter.startBackground = startBackground0
        CareLinkCloudCleanup.cancelWork = cancelWork0
        CareLinkCloudCleanup.deleteKey = deleteKey0
        CareLinkCloudCleanup.clearCookies = clearCookies0
    }

    @Test
    fun onCreateReturnsTrueAndThePostedStepDoesNotThrow() {
        val started = mutableListOf<Runnable>()
        ModCareLinkStarter.startBackground = { started += it }
        val starter = Robolectric.setupContentProvider(ModCareLinkStarter::class.java, "mod.carelink.test")
        assertTrue(starter.onCreate())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(started.isNotEmpty())
    }

    @Test
    fun theCleanupRunsOffTheMainLoopAfterIt() {
        val app = RuntimeEnvironment.getApplication()
        val session = File(File(app.noBackupFilesDir, CareLinkStateStore.DIR), "session.bin")
        session.parentFile!!.mkdirs()
        session.writeText("x")
        val started = mutableListOf<Runnable>()
        ModCareLinkStarter.startBackground = { started += it }
        CareLinkCloudCleanup.cancelWork = { _, _ -> }
        CareLinkCloudCleanup.deleteKey = { false }
        CareLinkCloudCleanup.clearCookies = { }

        Robolectric.setupContentProvider(ModCareLinkStarter::class.java, "mod.carelink.test")
        assertTrue("nothing before the main loop", session.exists())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("not on the main thread", session.exists())
        started.forEach { it.run() }

        assertFalse(session.exists())
        assertTrue(File(session.parentFile, CareLinkCloudCleanup.MARK_FILE).readLines().contains("done"))
    }

    @Test
    fun providerAnswersNothing() {
        ModCareLinkStarter.startBackground = { }
        val starter = Robolectric.setupContentProvider(ModCareLinkStarter::class.java, "mod.carelink.test")
        val uri = android.net.Uri.parse("content://mod.carelink.test/x")
        assertNull(starter.query(uri, null, null, null, null))
        assertNull(starter.getType(uri))
        assertNull(starter.insert(uri, null))
        assertEquals(0, starter.delete(uri, null, null))
        assertEquals(0, starter.update(uri, null, null, null))
    }
}
