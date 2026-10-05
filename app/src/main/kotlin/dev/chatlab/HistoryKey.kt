package dev.chatlab

import androidx.room.*

@Entity(tableName = "history_keys", primaryKeys = ["ownerId", "roomId", "serverInstanceId"])
data class HistoryKey(val ownerId: String, val roomId: String, val serverInstanceId: String,
    val nextBefore: String?, val oldestSequence: Long, val highWatermark: Long, val endReached: Boolean)

@Dao
interface HistoryKeyDao {
    @Query("SELECT * FROM history_keys WHERE ownerId=:owner AND roomId=:room AND serverInstanceId=:instance")
    suspend fun find(owner: String, room: String, instance: String): HistoryKey?
    @Upsert suspend fun save(key: HistoryKey)
}
