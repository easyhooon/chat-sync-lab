package dev.chatlab

import androidx.room.*
import androidx.paging.PagingSource
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

private const val MESSAGE_PROJECTION = """
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
        )
"""

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
    @Query(MESSAGE_PROJECTION + " ORDER BY pendingSort, sessionSort, messageSort, localSort, clientMessageId")
    abstract fun observe(owner: String, room: String): Flow<List<MessageRow>>
    @Query(MESSAGE_PROJECTION + " ORDER BY pendingSort DESC, sessionSort DESC, messageSort DESC, localSort DESC, clientMessageId DESC")
    abstract fun pagingSource(owner: String, room: String): PagingSource<Int, MessageRow>
    @Query("SELECT COUNT(*) FROM cached_messages WHERE ownerId=:owner AND roomId=:room AND serverInstanceId=:instance AND sequence>:after AND sequence<=:through")
    abstract suspend fun countRange(owner: String, room: String, instance: String, after: Long, through: Long): Long
    @Query("SELECT sequence FROM cached_messages WHERE ownerId=:owner AND roomId=:room AND serverInstanceId=:instance AND sequence>:after ORDER BY sequence LIMIT 100")
    abstract suspend fun sequenceWindow(owner: String, room: String, instance: String, after: Long): List<Long>
    @Query("SELECT COALESCE(MIN(sequence), 0) FROM cached_messages WHERE ownerId=:owner AND roomId=:room AND serverInstanceId=:instance")
    abstract suspend fun minSequence(owner: String, room: String, instance: String): Long
    @Query("SELECT COALESCE(MAX(sequence), 0) FROM cached_messages WHERE ownerId=:owner AND roomId=:room AND serverInstanceId=:instance")
    abstract suspend fun maxSequence(owner: String, room: String, instance: String): Long

}

class MessageCacheStore(private val database: OutboxDatabase, private val outbox: OutboxStore) {
    private val dao = database.messages()
    fun observe(owner: String, room: String) = dao.observe(owner, room)
    fun pagingSource(owner: String, room: String) = dao.pagingSource(owner, room)
    suspend fun historyKey(owner: String, room: String, instance: String) = database.historyKeys().find(owner, room, instance)

    suspend fun syncCursor(owner: String, room: String, instance: String) = database.sync().cursor(owner, room, instance)

    private fun validatePage(room: String, page: History) {
        require(page.roomId == room && page.serverInstanceId.isNotBlank())
        require(page.endOfHistory == (page.nextBefore == null))
        require(page.messages.all { it.roomId == room && it.serverInstanceId == page.serverInstanceId })
        require(page.messages.zipWithNext().all { (a, b) -> b.sequence == a.sequence + 1 })
        require(page.highWatermark >= (page.messages.lastOrNull()?.sequence ?: 0))
        require(page.messages.isNotEmpty() || page.endOfHistory)
        require(!page.endOfHistory || page.messages.isEmpty() || page.messages.first().sequence == 1L)
    }

    suspend fun importLatestPage(owner: String, room: String, page: History) {
        validatePage(room, page)
        require(page.highWatermark == (page.messages.lastOrNull()?.sequence ?: 0L))
        database.withTransaction {
            ensureSession(owner, room, page.serverInstanceId)
            initializeSync(owner, room, page)
            page.messages.forEach { importInTransaction(owner, room, it) }
            extendHighWatermark(owner, room, page.serverInstanceId)
            val previous = historyKey(owner, room, page.serverInstanceId)
            val first = page.messages.firstOrNull()?.sequence ?: 0
            val fresh = HistoryKey(owner, room, page.serverInstanceId, page.nextBefore, first, page.highWatermark, page.endOfHistory)
            val merged = when {
                previous == null || previous.oldestSequence == 0L -> fresh
                first == 0L -> error("A nonempty server run cannot become empty")
                first > previous.highWatermark + 1 -> fresh // A disconnected gap: restart the past traversal at the latest tail.
                page.highWatermark < previous.oldestSequence - 1 -> previous // Delayed obsolete latest response.
                else -> previous.copy(
                    oldestSequence = minOf(first, previous.oldestSequence),
                    highWatermark = maxOf(page.highWatermark, previous.highWatermark),
                    nextBefore = if (first < previous.oldestSequence) page.nextBefore else previous.nextBefore,
                    endReached = if (first < previous.oldestSequence) page.endOfHistory else previous.endReached,
                )
            }
            database.historyKeys().save(merged)
            requestInTransaction(owner, room, page.serverInstanceId, page.highWatermark)
            advanceSync(owner, room, page.serverInstanceId)
        }
    }

    suspend fun importOlderPage(request: HistoryKey, page: History) {
        validatePage(request.roomId, page)
        require(page.serverInstanceId == request.serverInstanceId)
        require(!request.endReached && request.nextBefore != null)
        require(page.messages.all { it.sequence < request.oldestSequence })
        require(page.messages.isNotEmpty() || request.oldestSequence == 1L)
        require(page.messages.isEmpty() || page.messages.last().sequence == request.oldestSequence - 1) {
            "Older page must end immediately before the requested boundary"
        }
        database.withTransaction {
            ensureSession(request.ownerId, request.roomId, page.serverInstanceId)
            page.messages.forEach { importInTransaction(request.ownerId, request.roomId, it) }
            advanceSync(request.ownerId, request.roomId, request.serverInstanceId)
            val current = historyKey(request.ownerId, request.roomId, request.serverInstanceId)
            // Late/repeated responses may add valid rows, but cannot move a newer cursor backwards.
            if (current?.nextBefore == request.nextBefore && current.oldestSequence == request.oldestSequence) {
                database.historyKeys().save(current.copy(nextBefore = page.nextBefore, endReached = page.endOfHistory,
                    oldestSequence = page.messages.firstOrNull()?.sequence ?: current.oldestSequence))
            }
        }
    }

    private suspend fun extendHighWatermark(owner: String, room: String, instance: String) {
        val key = historyKey(owner, room, instance) ?: return
        val highest = dao.maxSequence(owner, room, instance)
        if (highest > key.highWatermark && dao.countRange(owner, room, instance, key.highWatermark, highest) == highest - key.highWatermark)
            database.historyKeys().save(key.copy(highWatermark = highest))
    }

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
            extendHighWatermark(owner, room, message.serverInstanceId)
            requestInTransaction(owner, room, message.serverInstanceId, message.sequence)
            advanceSync(owner, room, message.serverInstanceId)
        }
    }

    private suspend fun initializeSync(owner: String, room: String, page: History) {
        if (syncCursor(owner, room, page.serverInstanceId) != null) return
        val knownRun = historyKey(owner, room, page.serverInstanceId) != null
        // For v3 cached runs use the oldest observed prefix, before importing the new latest tail.
        val minimum = if (knownRun) dao.minSequence(owner, room, page.serverInstanceId) else page.messages.firstOrNull()?.sequence ?: 0
        val base = if (minimum > 0) minimum - 1 else 0
        val hint = database.sync().hint(owner, room, page.serverInstanceId)?.throughSequence ?: 0
        database.sync().save(SyncCursor(owner, room, page.serverInstanceId, base, base, maxOf(page.highWatermark, hint)))
    }

    suspend fun requestCatchUp(owner: String, room: String, instance: String, through: Long) {
        require(owner in setOf("alice", "bob") && room == "demo" && instance.isNotBlank() && through > 0)
        database.withTransaction { requestInTransaction(owner, room, instance, through); advanceSync(owner, room, instance) }
    }

    private suspend fun requestInTransaction(owner: String, room: String, instance: String, through: Long) {
        val previous = database.sync().hint(owner, room, instance)
        database.sync().saveHint(SyncHint(owner, room, instance, maxOf(previous?.throughSequence ?: 0, through)))
    }

    private suspend fun advanceSync(owner: String, room: String, instance: String) {
        val key = syncCursor(owner, room, instance) ?: return // Push cannot establish a bootstrap baseline.
        var contiguous = key.contiguousThrough
        while (true) {
            val window = dao.sequenceWindow(owner, room, instance, contiguous)
            var complete = true
            for (sequence in window) {
                if (sequence != contiguous + 1) { complete = false; break }
                contiguous = sequence
            }
            if (!complete || window.size < 100) break
        }
        val requested = maxOf(key.requestedThrough, database.sync().hint(owner, room, instance)?.throughSequence ?: 0)
        database.sync().save(key.copy(contiguousThrough = contiguous, requestedThrough = maxOf(requested, contiguous)))
    }

    suspend fun importAfterPage(request: SyncCursor, page: AfterPage) {
        require(page.roomId == request.roomId && page.serverInstanceId == request.serverInstanceId)
        require(page.afterSequence == request.contiguousThrough && page.throughSequence == request.requestedThrough)
        require(page.messages.size <= 20 && page.nextAfter in page.afterSequence..page.throughSequence)
        require(page.endOfCatchUp == (page.nextAfter == page.throughSequence))
        require(page.messages.all { it.roomId == request.roomId && it.serverInstanceId == request.serverInstanceId })
        require(page.nextAfter - page.afterSequence == page.messages.size.toLong())
        require(page.messages.withIndex().all { (index, message) -> message.sequence == page.afterSequence + index + 1 })
        require(page.nextAfter == (page.messages.lastOrNull()?.sequence ?: page.afterSequence))
        require(page.nextAfter > page.afterSequence || page.endOfCatchUp)
        database.withTransaction {
            requireNotNull(syncCursor(request.ownerId, request.roomId, request.serverInstanceId))
            page.messages.forEach { importInTransaction(request.ownerId, request.roomId, it) }
            requestInTransaction(request.ownerId, request.roomId, request.serverInstanceId, page.throughSequence)
            advanceSync(request.ownerId, request.roomId, request.serverInstanceId)
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
