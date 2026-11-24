package mod.carelink

import android.app.Application
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.mod.carelink.ModCareLinkStarter

/**
 * JugglucoNGmod, CareLink 4a: the starter lives in Common/src/mobile/java (seen by upstream's debug tests) and must
 * never throw on process start. A plain Application: the starter needs none of upstream's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModCareLinkStarterTest {

    @Test
    fun onCreateReturnsTrueAndThePostedStepDoesNotThrow() {
        val starter = Robolectric.setupContentProvider(ModCareLinkStarter::class.java, "mod.carelink.test")
        assertTrue(starter.onCreate())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun providerAnswersNothing() {
        val starter = Robolectric.setupContentProvider(ModCareLinkStarter::class.java, "mod.carelink.test")
        val uri = android.net.Uri.parse("content://mod.carelink.test/x")
        assertNull(starter.query(uri, null, null, null, null))
        assertNull(starter.getType(uri))
        assertNull(starter.insert(uri, null))
        assertEquals(0, starter.delete(uri, null, null))
        assertEquals(0, starter.update(uri, null, null, null))
    }
}
