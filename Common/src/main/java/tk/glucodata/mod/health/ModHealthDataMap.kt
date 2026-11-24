package tk.glucodata.mod.health

import com.google.android.gms.wearable.DataMap

/**
 * JugglucoNGmod, watch block (part 4): [ModHealthBatch] in a Data Layer `DataMap` and back - the thin adapter between
 * the plain format and Google Play services (the watch writes, the phone reads, part 5). All checks are in
 * [ModHealthBatch.decode].
 */
object ModHealthDataMap {

    fun write(batch: ModHealthBatch, map: DataMap) {
        map.putInt(ModHealthBatch.KEY_VERSION, batch.version)
        map.putLong(ModHealthBatch.KEY_SEQ, batch.seq)
        map.putLong(ModHealthBatch.KEY_SENT_AT, batch.sentAtMs)
        map.putStringArray(ModHealthBatch.KEY_KIND, batch.kinds())
        map.putLongArray(ModHealthBatch.KEY_TIME, batch.times())
        map.putFloatArray(ModHealthBatch.KEY_VALUE, batch.values())
        map.putStringArray(ModHealthBatch.KEY_EXTRA, batch.extras())
    }

    fun read(map: DataMap): ModHealthBatch.Decoded = ModHealthBatch.decode(
        version = map.getInt(ModHealthBatch.KEY_VERSION, 0),
        seq = map.getLong(ModHealthBatch.KEY_SEQ, 0L),
        sentAtMs = map.getLong(ModHealthBatch.KEY_SENT_AT, -1L),
        kinds = map.getStringArray(ModHealthBatch.KEY_KIND),
        times = map.getLongArray(ModHealthBatch.KEY_TIME),
        values = map.getFloatArray(ModHealthBatch.KEY_VALUE),
        extras = map.getStringArray(ModHealthBatch.KEY_EXTRA),
    )
}
