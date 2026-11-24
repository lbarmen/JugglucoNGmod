package mod.watch

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.OngoingNotification
import tk.glucodata.OngoingNotificationAccess
import tk.glucodata.WearOngoingActivity
import tk.glucodata.mod.watch.NoOngoingActivity

/**
 * JugglucoNGmod, watch block (part 7j): ours takes upstream's ongoing-activity slot, and their foreground notification
 * goes through it unchanged - ongoing, without an ongoing activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoOngoingActivityTest {

    @After
    fun restoreUpstream() {
        OngoingNotificationAccess.register(WearOngoingActivity)
    }

    @Test
    fun oursReplacesUpstreamsOnceAndKeepsTheSlot() {
        OngoingNotificationAccess.register(WearOngoingActivity)
        assertTrue(NoOngoingActivity.claim())
        assertSame(NoOngoingActivity, OngoingNotificationAccess.get())
        // already ours: nothing to do
        assertFalse(NoOngoingActivity.claim())
        // upstream registered again (a second registerBridges): the next claim takes it back
        OngoingNotificationAccess.register(WearOngoingActivity)
        assertTrue(NoOngoingActivity.claim())
        assertSame(NoOngoingActivity, OngoingNotificationAccess.get())
    }

    @Test
    fun aStatusUpdateGoesOnToTheDisplacedOne() {
        // critic 7l-7m, M1: their ongoing activity may still be attached (a start the claim did not precede)
        var updates = 0
        val theirs = object : OngoingNotification {
            override fun attach(context: Context, notification: Notification, notificationId: Int) = notification
            override fun updateStatus(context: Context?, notificationId: Int) {
                updates++
            }
        }
        OngoingNotificationAccess.register(theirs)
        assertTrue(NoOngoingActivity.claim())
        NoOngoingActivity.updateStatus(ApplicationProvider.getApplicationContext(), 7)
        assertTrue(updates == 1)
    }

    @Test
    fun theNotificationGoesUnchangedAndOngoing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = NotificationCompat.Builder(context, "test").setContentTitle("t").build()
        val out = NoOngoingActivity.attach(context, notification, 1)
        assertSame(notification, out)
        assertTrue(out.flags and Notification.FLAG_ONGOING_EVENT != 0)
        // no ongoing activity extras were added
        assertFalse(out.extras.keySet().any { it.contains("ongoing", ignoreCase = true) })
        NoOngoingActivity.updateStatus(context, 1)
    }
}
