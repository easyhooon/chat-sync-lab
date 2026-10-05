package dev.chatlab

import androidx.room.*

@Entity(tableName = "sync_cursors", primaryKeys = ["ownerId", "roomId", "serverInstanceId"])
data class SyncCursor(val ownerId: String, val roomId: String, val serverInstanceId: String,
    val baseSequence: Long, val contiguousThrough: Long, val requestedThrough: Long)
@Entity(tableName = "sync_hints", primaryKeys = ["ownerId", "roomId", "serverInstanceId"])
data class SyncHint(val ownerId: String, val roomId: String, val serverInstanceId: String, val throughSequence: Long)
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_cursors WHERE ownerId=:owner AND roomId=:room AND serverInstanceId=:instance")
    suspend fun cursor(owner: String, room: String, instance: String): SyncCursor?
    @Upsert suspend fun save(cursor: SyncCursor)
    @Query("SELECT * FROM sync_hints WHERE ownerId=:owner AND roomId=:room AND serverInstanceId=:instance")
    suspend fun hint(owner: String, room: String, instance: String): SyncHint?
    @Upsert suspend fun saveHint(hint: SyncHint)
}
