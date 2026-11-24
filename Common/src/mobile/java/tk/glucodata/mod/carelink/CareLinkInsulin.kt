package tk.glucodata.mod.carelink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository

/**
 * JugglucoNGmod, CareLink (stage 4d): the insulin type of pump boluses - one of upstream's journal types with their
 * action curves (plan decision 2). The one chosen in the CareLink window ([CareLinkSettings.presetId]), else
 * upstream's default for calculation: the first by `sortOrder` of the types not archived, counted in IOB and used for
 * calculation (upstream has no ready "default type" function). A chosen type not counted in IOB (a long-acting
 * insulin) or archived since is not taken: a pump gives rapid insulin only (critic 4d, N6).
 */
object CareLinkInsulin {

    /** The type for new pump boluses; null - upstream has none (their journal then keeps the dose without a curve). */
    suspend fun presetId(context: Context): Long? = withContext(Dispatchers.IO) {
        val repo = JournalRepository()
        repo.ensureDefaultInsulinPresets()
        choose(repo.getInsulinPresetsSnapshot(), CareLinkSettings(context).presetId)
    }

    /** The types the CareLink window offers (4e): counted in IOB, not archived, in upstream's order. */
    suspend fun choices(context: Context): List<JournalInsulinPreset> = withContext(Dispatchers.IO) {
        val repo = JournalRepository()
        repo.ensureDefaultInsulinPresets()
        repo.getInsulinPresetsSnapshot().filter { it.countsTowardIob && !it.isArchived }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }

    internal fun choose(presets: List<JournalInsulinPreset>, chosen: Long?): Long? =
        chosen?.let { id -> presets.firstOrNull { it.id == id && it.countsTowardIob && !it.isArchived } }?.id
            ?: defaultOf(presets)?.id

    /** The default for calculation by the plan's rule (upstream has no such function to call). */
    internal fun defaultOf(presets: List<JournalInsulinPreset>): JournalInsulinPreset? =
        presets.filter { !it.isArchived && it.countsTowardIob && it.useForCalculation }
            .minWithOrNull(compareBy({ it.sortOrder }, { it.id }))
}
