package mod.watch.face

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Modifier
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.CurrentGlucoseSource
import tk.glucodata.GlucoseDelta
import tk.glucodata.GlucosePoint
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.NotificationHistorySource
import tk.glucodata.ui.WearPrediction
import tk.glucodata.mod.watch.face.FaceLabels
import tk.glucodata.mod.watch.face.FaceModel
import tk.glucodata.mod.watch.face.FaceSnapshot
import tk.glucodata.mod.watch.face.FaceSource
import tk.glucodata.mod.watch.face.FaceSources
import tk.glucodata.mod.watch.face.FaceValues

/**
 * JugglucoNGmod, watch block (part 3a): what our face source takes from upstream (plan, "Technique", contract tests).
 * A rename already breaks the compile; these tests hold the meaning: the static entry points, and that a reading
 * 15 minutes old comes back from `CurrentDisplaySource.resolveCurrent` with our age limit (their default of 330 s
 * drops it - the reason the face does not use `GlucoseComplicationData.currentReading()`). Only the live store and
 * the history store are stood in for; the resolver between them is upstream's own. A red test after merging an
 * upstream release means their API moved: ModFaceSource needs a look, not this test a change. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FaceSourceContractTest.TestApplication::class,
    shadows = [FaceSourceContractTest.LiveStore::class, FaceSourceContractTest.HistoryStore::class,
        FaceSourceContractTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class FaceSourceContractTest {

    class TestApplication : Applic() {
        override fun onCreate() {}
    }

    /** Upstream's live store: one reading of [ageMs], returned only when the caller's age limit allows it. */
    @Implements(CurrentGlucoseSource::class)
    class LiveStore {
        companion object {
            var ageMs = 15 * 60_000L
            var present = true
            val askedMaxAges = mutableListOf<Long>()

            @JvmStatic @Implementation
            fun getFresh(maxAgeMillis: Long, preferredSensorId: String?): CurrentGlucoseSource.Snapshot? {
                askedMaxAges += maxAgeMillis
                if (!present || ageMs > maxAgeMillis) return null
                return CurrentGlucoseSource.Snapshot(System.currentTimeMillis() - ageMs, "7.2", 7.2f, Float.NaN,
                    rate = 0.5f, sensorId = SENSOR, sensorGen = 0, index = 0, source = "contract")
            }
        }
    }

    /** Upstream's history store: [points] (empty by default, so only the live store can give the value). */
    @Implements(NotificationHistorySource::class)
    class HistoryStore {
        companion object {
            var points: List<GlucosePoint> = emptyList()

            @JvmStatic @Implementation
            fun getDisplayHistory(startTimeMs: Long, isMmol: Boolean, sensorSerial: String?): List<GlucosePoint> =
                points
        }
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    /** A stand-in for the local trial source (the real one lives outside this repository). */
    class FakeTrial : FaceSource {
        override fun snapshot(context: Context, nowMs: Long, graphHours: Int): FaceSnapshot =
            FaceSnapshot(nowMs, true, true, graphHours, null, emptyList(), emptyList(), 3.9f, 10f, null, null)
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<TestApplication>()
        Applic.app = context as TestApplication
        LiveStore.ageMs = 15 * 60_000L
        LiveStore.present = true
        LiveStore.askedMaxAges.clear()
        HistoryStore.points = emptyList()
    }

    @Test
    fun aLiveValueBehindANewerSyncedReadingGivesWayToIt() {
        LiveStore.ageMs = 15 * 60_000L + 30_000L
        val now = System.currentTimeMillis()
        // synced history up to 2 minutes ago, every 5 minutes, a little below the stale live 7.2
        val newest = now - 2 * 60_000L
        HistoryStore.points = (0..6).map { GlucosePoint(newest - it * 5 * 60_000L, 6.1f, 6.1f) }.reversed()
        val snapshot = FaceSources.find("tk.glucodata.mod.watch.trial.NoSuchClass").snapshot(context, now, 3)
        assertNotNull(snapshot.current)
        assertEquals(newest, snapshot.current!!.timeMs)
        assertEquals(6.1f, snapshot.current!!.value, 0.001f)
        val model = FaceModel.build(snapshot, ZoneId.of("UTC"), FaceLabels.from(context))
        assertTrue(model.hasValue)
        assertEquals(2, model.ageMinutes)
    }

    @Test
    fun aFifteenMinuteOldReadingComesBackWithOurAgeLimit() {
        val snapshot = CurrentDisplaySource.resolveCurrent(FaceValues.SOURCE_MAX_AGE_MS, SENSOR)
        assertNotNull(snapshot)
        assertEquals(7.2f, snapshot!!.primaryValue, 0.001f)
        assertTrue(System.currentTimeMillis() - snapshot.timeMillis in 15 * 60_000L until 16 * 60_000L)
        assertEquals(listOf(FaceValues.SOURCE_MAX_AGE_MS), LiveStore.askedMaxAges)
    }

    @Test
    fun upstreamsDefaultAgeLimitWouldDropIt() {
        assertNull(CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout, SENSOR))
    }

    @Test
    fun ourSourceShowsTheFifteenMinuteOldReadingWithARedAge() {
        val source = FaceSources.find("tk.glucodata.mod.watch.trial.NoSuchClass")
        // half a minute over 15, so the whole-minute age is 15 although the store's clock runs a little after `now`
        LiveStore.ageMs = 15 * 60_000L + 30_000L
        val now = System.currentTimeMillis()
        val snapshot = source.snapshot(context, now, 3)
        assertNotNull(snapshot.current)
        assertTrue(LiveStore.askedMaxAges.all { it == FaceValues.SOURCE_MAX_AGE_MS })
        val model = FaceModel.build(snapshot, ZoneId.of("UTC"), FaceLabels.from(context))
        assertTrue(model.hasValue)
        assertTrue(model.ageAlarm)
        assertEquals(15, model.ageMinutes)
        // range lines from upstream's defaults while the native side has none
        assertTrue(snapshot.low > 0f && snapshot.high > snapshot.low)
    }

    @Test
    fun ourSourceWithoutAnyReadingGivesNoValue() {
        LiveStore.present = false
        val snapshot = FaceSources.find("tk.glucodata.mod.watch.trial.NoSuchClass")
            .snapshot(context, System.currentTimeMillis(), 6)
        assertNull(snapshot.current)
        assertTrue(snapshot.history.isEmpty())
        assertEquals(6, snapshot.graphHours)
    }

    @Test
    fun theTrialSourceIsUsedOnlyWhenItsClassExists() {
        val real = FaceSources.find("tk.glucodata.mod.watch.trial.NoSuchClass")
        assertSame(real, FaceSources.find("java.lang.Object"))
        assertTrue(FaceSources.find(FakeTrial::class.java.name) is FakeTrial)
        // builds from GitHub have no trial class
        assertSame(real, FaceSources.get())
    }

    @Test
    fun upstreamEntryPointsAreStaticWithTheseParameters() {
        val resolve = CurrentDisplaySource::class.java.getMethod("resolveCurrent", Long::class.javaPrimitiveType,
            String::class.java)
        assertTrue(Modifier.isStatic(resolve.modifiers))
        assertEquals(CurrentDisplaySource.Snapshot::class.java, resolve.returnType)
        val history = NotificationHistorySource::class.java.getMethod("getDisplayHistory", Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java)
        assertTrue(Modifier.isStatic(history.modifiers))
        assertEquals(List::class.java, history.returnType)
        val viewMode = CurrentDisplaySource::class.java.getMethod("resolveViewModeForSensor", String::class.java)
        assertTrue(Modifier.isStatic(viewMode.modifiers))
        // the change: upstream's 5-minute "Δ" and its walk-back gap
        assertEquals(5, GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
        assertEquals(270_000L, GlucoseDelta.minGapMillis(FaceValues.DELTA_INTERVAL_MINUTES))
        // part 7a: the watch forecast (their chart's call); the arrow's 30-minute projection is gone (part 7l)
        val forecast = WearPrediction::class.java.getMethod("forecast", List::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        assertEquals(List::class.java, forecast.returnType)
        assertEquals(Boolean::class.javaPrimitiveType, WearPrediction::class.java.getMethod("isEnabled").returnType)
    }

    companion object {
        const val SENSOR = "contract-sensor"
    }
}
