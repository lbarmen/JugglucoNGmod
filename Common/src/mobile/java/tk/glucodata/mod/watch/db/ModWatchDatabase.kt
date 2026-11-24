package tk.glucodata.mod.watch.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.watch.ModWatchIntake

/**
 * JugglucoNGmod, watch block (part 5): heart rate and steps from the watch on the phone - our own Room database, a
 * file of its own (`mod-watch.db`), never a table in upstream's database (their migrations are their files). Version
 * 1, no migrations; `exportSchema = false`, or Room would write the schema into upstream's `Common/schemas/`.
 *
 * Rules for later versions (critic 5, N-5-3): the version only goes up, by one, with a hand-written `Migration` that
 * keeps every row (add columns or tables; never drop or rename) - upstream's test gate forbids Room's
 * destructive fallback, and there is no schema file for auto-migrations. Rolling a phone back to an
 * older candidate after a version went up makes Room refuse to open the file: [tk.glucodata.mod.watch.ModWatchStore.db]
 * returns `null`, batches wait in the Data Layer and the check screen says the data could not be opened. Then: install
 * the newer candidate again (the rows are all there), or delete only `mod-watch.db` and take what the watch still
 * holds - upstream's data stays untouched either way. Try the roll-back on the emulator before a version goes up.
 */
@Database(entities = [ModWatchSample::class, ModWatchBatchSeen::class], version = 1, exportSchema = false)
abstract class ModWatchDatabase : RoomDatabase() {
    abstract fun dao(): ModWatchDao

    companion object {
        const val FILE = "mod-watch.db"

        fun open(context: Context): ModWatchDatabase =
            Room.databaseBuilder(context.applicationContext, ModWatchDatabase::class.java, FILE).build()
    }
}

/**
 * One row from the watch as it came ([ModHealthBatch.Sample]): kind, watch time, value, extra; the batch number and
 * when the phone took it. One row per kind and time; a late heart rate row of the same kind ([ModHealthBatch.HR_KINDS]:
 * `hr1`, and `hr5` of an older watch app - a kind is a string, so a new kind needs no migration) is merged into it, and
 * [parts] remembers every row merged in (value and extra, [ModWatchIntake.signature]), so a repeat of any of them is
 * known.
 */
@Entity(tableName = "sample", indices = [Index(value = ["kind", "at"], unique = true)])
data class ModWatchSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    /** Watch time, ms of the epoch ([ModHealthBatch.Sample.timeMs]). */
    val at: Long,
    val value: Double,
    val extra: String,
    /** The batch the row came in last. */
    val batchSeq: Long,
    /** When the phone stored it, ms of the epoch. */
    val receivedAt: Long,
    /** Signatures of the rows this one is made of, separated by [ModWatchIntake.PART_SEPARATOR]. */
    val parts: String = "",
) {
    fun sample(): ModHealthBatch.Sample = ModHealthBatch.Sample(kind, at, value.toFloat(), extra)
}

/** A batch already taken: its number and send time (the number starts again from 1 after a reinstall on the watch). */
@Entity(tableName = "batch", primaryKeys = ["seq", "sentAt"])
data class ModWatchBatchSeen(val seq: Long, val sentAt: Long, val receivedAt: Long, val rows: Int)

/** What storing one batch did. */
data class ModWatchStored(val repeat: Boolean, val inserted: Int, val merged: Int, val dropped: Int)

/** Blocking calls: always on our own thread ([tk.glucodata.mod.watch.ModWatchStore]), never the main one. */
@Dao
abstract class ModWatchDao {

    @Query("SELECT COUNT(*) FROM batch WHERE seq = :seq AND sentAt = :sentAt")
    abstract fun batchCount(seq: Long, sentAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertBatch(batch: ModWatchBatchSeen): Long

    @Query("SELECT * FROM sample WHERE kind = :kind AND at = :at LIMIT 1")
    abstract fun find(kind: String, at: Long): ModWatchSample?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insert(row: ModWatchSample): Long

    @Update
    abstract fun update(row: ModWatchSample): Int

    /** The newest row of [kind]. */
    @Query("SELECT * FROM sample WHERE kind = :kind ORDER BY at DESC LIMIT 1")
    abstract fun latest(kind: String): ModWatchSample?

    /** The newest row of [kind] with its time in [from, to). */
    @Query("SELECT * FROM sample WHERE kind = :kind AND at >= :from AND at < :to ORDER BY at DESC LIMIT 1")
    abstract fun latestBetween(kind: String, from: Long, to: Long): ModWatchSample?

    /** When the last batch was stored; `null` - never. */
    @Query("SELECT MAX(receivedAt) FROM batch")
    abstract fun lastReceivedAt(): Long?

    @Query("SELECT COUNT(*) FROM sample")
    abstract fun count(): Int

    /**
     * One batch whole, in one transaction (the listener and the start-up drain may both hold the same item): a batch
     * already taken is dropped before its rows are read; otherwise each known row is inserted, merged or dropped by
     * [ModWatchIntake.decide], and the batch is marked as taken.
     */
    @Transaction
    open fun store(batch: ModHealthBatch, receivedAt: Long): ModWatchStored {
        if (batchCount(batch.seq, batch.sentAtMs) > 0) return ModWatchStored(repeat = true, 0, 0, 0)
        var inserted = 0
        var merged = 0
        var dropped = 0
        for (sample in batch.knownSamples()) {
            when (val action = ModWatchIntake.decide(find(sample.kind, sample.timeMs), sample, batch.seq, receivedAt)) {
                is ModWatchIntake.Action.Insert -> if (insert(action.row) != -1L) inserted++ else dropped++
                is ModWatchIntake.Action.Replace -> if (update(action.row) > 0) merged++ else dropped++
                ModWatchIntake.Action.Drop -> dropped++
            }
        }
        insertBatch(ModWatchBatchSeen(batch.seq, batch.sentAtMs, receivedAt, batch.samples.size))
        return ModWatchStored(repeat = false, inserted, merged, dropped)
    }
}
