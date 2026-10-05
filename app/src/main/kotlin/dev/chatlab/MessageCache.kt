package dev.chatlab

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cache_sessions", primaryKeys = ["ownerId", "roomId", "serverInstanceId"])
data class CacheSession(val ownerId: String, val roomId: String, val serverInstanceId: String, val ordinal: Long)

@Entity(tableName = "cached_messages", primaryKeys = ["ownerId", "roomId", "serverInstanceId", "serverId"],
    indices = [Index(value = ["ownerId", "roomId", "serverInstanceId", "senderId", "clientMessageId"], unique = true),
        Index(value = ["ownerId", "roomId", "serverInstanceId", "sequence"], unique = true)])
data class CachedMessage(val ownerId: String, val roomId: String, val serverInstanceId: String, val serverId: String,
    val clientMessageId: String, val senderId: String, val text: String, val sequence: Long, val createdAt: String,
    val stableKey: String) {
    fun message() = Message(serverId, clientMessageId, roomId, senderId, text, sequence, createdAt, serverInstanceId)
}

@Dao
abstract class MessageDao {
    @Insert abstract suspend fun insert(message: CachedMessage)
    @Insert abstract suspend fun insertSession(session: CacheSession)
    @Query("SELECT * FROM cache_sessions WHERE ownerId = :owner AND roomId = :room AND serverInstanceId = :instance")
    abstract suspend fun session(owner: String, room: String, instance: String): CacheSession?
    @Query("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM cache_sessions WHERE ownerId = :owner AND roomId = :room")
    abstract suspend fun nextOrdinal(owner: String, room: String): Long
    @Query("SELECT * FROM cached_messages WHERE ownerId = :owner AND roomId = :room AND serverInstanceId = :instance AND serverId = :id")
    abstract suspend fun find(owner: String, room: String, instance: String, id: String): CachedMessage?

    // One observed statement: a commit cannot expose both the pending row and its accepted replacement.
    @Query("""
        SELECT clientMessageId, senderId, text, serverId, sequence, status, roomId, serverInstanceId, stableKey
        FROM (
            SELECT m.clientMessageId, m.senderId, m.text, m.serverId, m.sequence, 'SENT' AS status,
                m.roomId, m.serverInstanceId, m.stableKey, 0 AS pendingSort, s.ordinal AS sessionSort,
                m.sequence AS messageSort, 0 AS localSort
            FROM cached_messages m JOIN cache_sessions s
                ON m.ownerId = s.ownerId AND m.roomId = s.roomId AND m.serverInstanceId = s.serverInstanceId
            WHERE m.ownerId = :owner AND m.roomId = :room
            UNION ALL
            SELECT o.clientMessageId, o.userId AS senderId, o.text, o.serverId, o.sequence, o.status,
                o.roomId, o.serverInstanceId, 'outbox:' || o.userId || ':' || o.roomId || ':' || o.clientMessageId AS stableKey,
                1 AS pendingSort, 0 AS sessionSort, 0 AS messageSort, o.createdAtMillis AS localSort
            FROM outbox o WHERE o.userId = :owner AND o.roomId = :room AND o.status != 'SENT'
        ) ORDER BY pendingSort, sessionSort, messageSort, localSort, clientMessageId
    """)
    abstract fun observe(owner: String, room: String): Flow<List<MessageRow>>
}

class MessageCacheStore(private val database: OutboxDatabase, private val outbox: OutboxStore) {
    private val dao = database.messages()
    fun observe(owner: String, room: String) = dao.observe(owner, room)

    suspend fun importSnapshot(owner: String, room: String, instance: String, messages: List<Message>) {
        require(instance.isNotBlank())
        require(messages.all { it.roomId == room && it.serverInstanceId == instance }) { "Snapshot scope mismatch" }
        database.withTransaction {
            ensureSession(owner, room, instance) // Empty snapshots still record a new server instance; no deletion.
            messages.forEach { importInTransaction(owner, room, it) }
        }
    }

    suspend fun importMessage(owner: String, room: String, message: Message) {
        require(message.roomId == room) { "Message room mismatch" }
        require(message.serverInstanceId.isNotBlank() && message.sequence > 0)
        database.withTransaction {
            ensureSession(owner, room, message.serverInstanceId)
            importInTransaction(owner, room, message)
        }
    }

    private suspend fun ensureSession(owner: String, room: String, instance: String) {
        if (dao.session(owner, room, instance) == null)
            dao.insertSession(CacheSession(owner, room, instance, dao.nextOrdinal(owner, room)))
    }

    private suspend fun importInTransaction(owner: String, room: String, message: Message) {
        require(message.sequence > 0 && message.id.isNotBlank() && message.clientMessageId.isNotBlank())
        val previous = dao.find(owner, room, message.serverInstanceId, message.id)
        if (previous != null) {
            check(previous.message() == message) { "Immutable cached message conflict" }
        } else {
            val intent = database.outbox().find(owner, room, message.clientMessageId)
            val matchesIntent = message.senderId == owner && intent?.text == message.text &&
                (intent.serverId == null || intent.serverId == message.id) &&
                (intent.serverInstanceId == null || intent.serverInstanceId == message.serverInstanceId)
            val key = if (matchesIntent) "outbox:$owner:$room:${message.clientMessageId}"
                else "server:$owner:$room:${message.serverInstanceId}:${message.id}"
            dao.insert(CachedMessage(owner, room, message.serverInstanceId, message.id, message.clientMessageId,
                message.senderId, message.text, message.sequence, message.createdAt, key))
        }
        // Same transaction as cache insert. A late HTTP timeout cannot revert this receipt.
        outbox.accept(owner, room, message)
    }
}
