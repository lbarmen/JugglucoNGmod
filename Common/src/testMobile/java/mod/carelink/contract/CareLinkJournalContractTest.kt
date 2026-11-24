package mod.carelink.contract

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.carelink.CareLinkJournalTestApp
import mod.carelink.CareLinkJournalTestEnv
import mod.carelink.CareLinkTestNatives
import tk.glucodata.RemoteIobSnapshot
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.journal.JournalTreatmentTransfer
import tk.glucodata.data.journal.JournalTreatmentUploader
import tk.glucodata.data.journal.LibreviewJournalTransfer
import tk.glucodata.data.journal.isCloneJournalExportSource
import tk.glucodata.data.journal.isExternalJournalMirrorSource
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.carelink.CareLinkInsulin

/**
 * JugglucoNGmod, CareLink 4d: what our pump writers rely on in upstream's journal (plan, "Technique", contract
 * tests). A red test here after merging an upstream release means their API moved - the writers need a look, not
 * this test a change. Made-up values only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkJournalContractTest {

    private val now = 1_800_000_000_000L
    private val dao get() = CareLinkJournalTestEnv.dao

    @Before
    fun setUp() {
        CareLinkJournalTestEnv.setUp { now }
        ApiIobSnapshot.clear()
    }

    @After
    fun tearDown() {
        CareLinkJournalTestEnv.tearDown()
        ApiIobSnapshot.clear()
    }

    private fun input(key: String, amount: Float, source: JournalEntrySource = JournalEntrySource.HEALTH_CONNECT) =
        JournalEntryInput(
            timestamp = now - 3_600_000L, type = JournalEntryType.INSULIN, title = "made-up", amount = amount,
            source = source, sourceRecordId = key,
        )

    @Test
    fun repeatedUpsertWithTheSameKeyIsOneRow() = runBlocking {
        val repo = JournalRepository()
        repo.upsertEntry(input("carelink:test:1:insulin", 1.0f))
        repo.upsertEntry(input("carelink:test:1:insulin", 2.0f))
        val rows = dao.getEntries()
        assertEquals(1, rows.size)
        assertEquals(2.0f, rows.single().amount!!, 1e-6f)
        assertNotNull(dao.getEntryBySourceRecordId("carelink:test:1:insulin"))
        assertTrue(repo.retagSourceRecordId(rows.single().id, "carelink:test:2:insulin"))
        assertEquals(rows.single().id, dao.getEntryBySourceRecordId("carelink:test:2:insulin")!!.id)
    }

    @Test
    fun healthConnectIsSentApiIsAMirrorEverywhere() {
        val hc = JournalEntrySource.HEALTH_CONNECT
        val api = JournalEntrySource.API
        assertFalse(isExternalJournalMirrorSource(hc))
        assertFalse(JournalTreatmentUploader.isExternalMirrorSource(hc.storageValue))
        assertFalse(LibreviewJournalTransfer.isExternalMirrorSource(hc.storageValue))
        assertTrue(isExternalJournalMirrorSource(api))
        assertTrue(JournalTreatmentUploader.isExternalMirrorSource(api.storageValue))
        assertTrue(LibreviewJournalTransfer.isExternalMirrorSource(api.storageValue))
        // Clone export: Health Connect rows go to Clone peers, API rows do not
        assertTrue(isCloneJournalExportSource(hc.storageValue))
        assertFalse(isCloneJournalExportSource(api.storageValue))
    }

    @Test
    fun aRowKeepsItsMirrorSourceOnUpdate() = runBlocking {
        val repo = JournalRepository()
        repo.upsertEntry(input("carelink:test:3:insulin", 1.0f, JournalEntrySource.API))
        repo.upsertEntry(input("carelink:test:3:insulin", 1.5f, JournalEntrySource.HEALTH_CONNECT))
        assertEquals(JournalEntrySource.API.storageValue, dao.getEntries().single().source)
    }

    private fun entity(type: JournalEntryType, amount: Float, duration: Int? = null) = JournalEntryEntity(
        id = 7L, timestamp = now - 3_600_000L, sensorSerial = null, entryType = type.storageValue, title = "made-up",
        note = null, amount = amount, glucoseValueMgDl = null, durationMinutes = duration, intensity = null,
        insulinPresetId = null, source = JournalEntrySource.HEALTH_CONNECT.storageValue,
        sourceRecordId = "carelink:test:7", createdAt = now, updatedAt = now,
    )

    @Test
    fun treatmentJsonForInsulinCarbsAndTempBasal() {
        val ins = JournalTreatmentTransfer.buildTreatmentJson(entity(JournalEntryType.INSULIN, 2.0f), "jng-j-7", null, null, false)!!
        assertEquals("Correction Bolus", ins.getString("eventType"))
        assertEquals(2.0, ins.getDouble("insulin"), 1e-6)
        val carbs = JournalTreatmentTransfer.buildTreatmentJson(entity(JournalEntryType.CARBS, 45f), "jng-j-7", null, null, false)!!
        assertEquals("Meal Bolus", carbs.getString("eventType"))
        assertEquals(45.0, carbs.getDouble("carbs"), 1e-6)
        val basal = JournalTreatmentTransfer.buildTreatmentJson(
            entity(JournalEntryType.TEMP_BASAL, 0.8f, 30), "jng-j-7", null, null, false,
        )!!
        assertEquals("Temp Basal", basal.getString("eventType"))
        assertEquals(0.8, basal.getDouble("absolute"), 1e-6)
        assertEquals("minutes", 30, basal.getInt("duration"))
        val stopped = JournalTreatmentTransfer.buildTreatmentJson(
            entity(JournalEntryType.TEMP_BASAL, 0f, 30), "jng-j-7", null, null, false,
        )!!
        assertEquals("a stop is absolute 0", 0.0, stopped.getDouble("absolute"), 1e-9)
    }

    @Test
    fun tempBasalIsSendable() {
        // the hook carelink-tempbasal: without it the pump basal never reaches Nightscout
        val m = JournalTreatmentUploader::class.java.getDeclaredMethod("isSendableType", String::class.java)
        m.isAccessible = true
        assertEquals(true, m.invoke(JournalTreatmentUploader, JournalEntryType.TEMP_BASAL.storageValue))
        assertEquals(true, m.invoke(JournalTreatmentUploader, JournalEntryType.NOTE.storageValue))
        assertEquals(true, m.invoke(JournalTreatmentUploader, JournalEntryType.INSULIN.storageValue))
    }

    @Test
    fun deleteEntryQueuesTheNightscoutDeletionOfOurRowOnly() = runBlocking {
        val repo = JournalRepository()
        repo.upsertEntry(input("carelink:test:hc:insulin", 1.0f, JournalEntrySource.HEALTH_CONNECT))
        repo.upsertEntry(input("carelink:test:api:insulin", 1.0f, JournalEntrySource.API))
        val hc = dao.getEntryBySourceRecordId("carelink:test:hc:insulin")!!
        val api = dao.getEntryBySourceRecordId("carelink:test:api:insulin")!!
        dao.markEntryUploadedToNightscout(hc.id, "made-up-remote-hc", now)
        dao.markEntryUploadedToNightscout(api.id, "made-up-remote-api", now)
        repo.deleteEntry(hc.id)
        repo.deleteEntry(api.id)
        assertEquals(listOf(hc.id), dao.getPendingNightscoutDeletes().map { it.entryId })
        assertTrue(dao.getEntries().isEmpty())
    }

    @Test
    fun insulinTypesAndTheDefaultForCalculation() = runBlocking {
        val repo = JournalRepository()
        repo.ensureDefaultInsulinPresets()
        val presets = repo.getInsulinPresetsSnapshot()
        assertTrue("upstream's built-in types", presets.size >= 10)
        val default = CareLinkInsulin.defaultOf(presets)
        assertNotNull(default)
        assertTrue(default!!.countsTowardIob && default.useForCalculation && !default.isArchived)
        val long = presets.first { !it.countsTowardIob }
        assertEquals("a long-acting type is not taken", default.id, CareLinkInsulin.choose(presets, long.id))
        // a chosen rapid type other than the default is taken (made up beside theirs: their other built-in rapid types
        // are archived on a fresh install)
        val other = default.copy(id = 999_998L, sortOrder = default.sortOrder + 1)
        assertEquals(other.id, CareLinkInsulin.choose(presets + other, other.id))
        // critic 4d, N6: a chosen type archived since is not taken
        assertEquals(default.id, CareLinkInsulin.choose(presets + other.copy(isArchived = true), other.id))
        presets.firstOrNull { it.countsTowardIob && it.isArchived }?.let { archived ->
            assertEquals(default.id, CareLinkInsulin.choose(presets, archived.id))
        }
        assertEquals(default.id, CareLinkInsulin.choose(presets, null))
        assertEquals(default.id, CareLinkInsulin.choose(presets, 999_999L))
    }

    @Test
    fun apiIobSnapshotIsFreshFor30MinAndAnOlderOneIsRefused() {
        assertTrue(ApiIobSnapshot.update(ApiIobSnapshot.RemoteIob(1.5f, Float.NaN, Float.NaN, now - 29 * 60_000L)))
        assertNotNull(ApiIobSnapshot.fresh(now))
        assertEquals(null, ApiIobSnapshot.fresh(now + 2 * 60_000L))
        assertFalse(ApiIobSnapshot.update(ApiIobSnapshot.RemoteIob(9f, Float.NaN, Float.NaN, now - 40 * 60_000L)))
        assertEquals(1.5f, ApiIobSnapshot.fresh(now)!!.iobUnits, 1e-6f)
    }

    @Test
    fun remoteSnapshotPrecedenceCloneNightscoutApi() {
        fun v(src: RemoteIobSnapshot.Source) = RemoteIobSnapshot.Values(1f, Float.NaN, Float.NaN, timestampMillis = now, source = src)
        val clone = v(RemoteIobSnapshot.Source.CLONE)
        val ns = v(RemoteIobSnapshot.Source.NIGHTSCOUT)
        val api = v(RemoteIobSnapshot.Source.API)
        assertEquals(clone, RemoteIobSnapshot.select(true, true, clone, ns, api))
        assertEquals("Nightscout follower over our pump number", ns, RemoteIobSnapshot.select(false, false, clone, ns, api))
        assertEquals(api, RemoteIobSnapshot.select(false, false, null, null, api))
        assertEquals(api, RemoteIobSnapshot.select(true, true, null, null, api))
    }
}
