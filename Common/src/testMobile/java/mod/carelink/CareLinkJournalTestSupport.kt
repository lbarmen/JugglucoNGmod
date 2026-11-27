package mod.carelink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalDao
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.mod.carelink.CareLinkAuth
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkBasalWriter
import tk.glucodata.mod.carelink.CareLinkSeenStore
import tk.glucodata.mod.carelink.CareLinkSettings

/**
 * JugglucoNGmod, CareLink 4d tests: upstream's journal needs `Applic.app` (the repository takes its database through
 * it) and a working `Natives` (a journal write wakes their backup) - as upstream's own calibration tests: an `Applic`
 * that does nothing on create, and every native call answered by a stub.
 */
class CareLinkJournalTestApp : Applic() {
    override fun onCreate() {}
}

@Implements(value = Natives::class, callThroughByDefault = false)
class CareLinkTestNatives {
    companion object {
        @JvmStatic
        @Implementation
        fun __staticInitializer__() {
        }
    }
}

/** One journal for the tests of a class: the database is one for the whole test JVM - emptied before each test. */
internal object CareLinkJournalTestEnv {

    val app: Applic get() = RuntimeEnvironment.getApplication() as Applic

    val dao: JournalDao get() = HistoryDatabase.getInstance(app).journalDao()

    fun setUp(now: () -> Long) {
        Applic.app = app
        // A journal write schedules upstream's refresh 400 ms later (OutboundApiJournalSnapshot), which loads their
        // Notify. Fired between two tests, the application has no base context: Notify's class init fails for the rest
        // of the test JVM, and their later tests go red (MOD sync 44164852207: 88). Loaded here, while the context lives.
        Class.forName("tk.glucodata.Notify", true, Applic::class.java.classLoader)
        CareLinkAuth.resetForTest()
        CareLinkClock.now = now
        CareLinkBasalWriter.zone = { java.time.ZoneOffset.UTC }
        runBlocking {
            withContext(Dispatchers.IO) { HistoryDatabase.getInstance(app).clearAllTables() }
        }
        CareLinkSeenStore.clear(app)
        app.getSharedPreferences(CareLinkSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun tearDown() {
        CareLinkAuth.resetForTest()
        CareLinkBasalWriter.zone = { java.time.ZoneId.systemDefault() }
    }

    fun rows(): List<JournalEntryEntity> = runBlocking { dao.getEntries() }

    fun rows(type: String): List<JournalEntryEntity> = rows().filter { it.entryType == type }
}
