package dev.chatlab

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "outbox", primaryKeys = ["userId", "roomId", "clientMessageId"])
data class OutboxEntry(
    val userId: String,
    val roomId: String,
    val clientMessageId: String,
    val text: String,
    val status: SendStatus = SendStatus.SENDING,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val serverId: String? = null,
    val sequence: Long? = null,
    val serverInstanceId: String? = null,
) {
    fun row() = MessageRow(clientMessageId, userId, text, serverId, sequence, status, roomId, serverInstanceId,
        "outbox:$userId:$roomId:$clientMessageId")
}

class OutboxConverters {
    @TypeConverter fun encode(status: SendStatus): String = status.name
    @TypeConverter fun decode(status: String): SendStatus = SendStatus.valueOf(status)
}

@Dao
abstract class OutboxDao {
    @Insert abstract suspend fun insert(entry: OutboxEntry)

    @Query("SELECT * FROM outbox WHERE userId = :user AND roomId = :room ORDER BY createdAtMillis, clientMessageId")
    abstract fun observe(user: String, room: String): Flow<List<OutboxEntry>>

    @Query("SELECT * FROM outbox WHERE userId = :user AND roomId = :room ORDER BY createdAtMillis, clientMessageId")
    abstract suspend fun load(user: String, room: String): List<OutboxEntry>

    @Query("SELECT * FROM outbox WHERE userId = :user AND roomId = :room AND clientMessageId = :id")
    abstract suspend fun find(user: String, room: String, id: String): OutboxEntry?

    @Query("UPDATE outbox SET status = 'UNKNOWN' WHERE status = 'SENDING' AND serverId IS NULL")
    abstract suspend fun recoverInterrupted(): Int

    @Query("UPDATE outbox SET status = 'SENDING' WHERE userId = :user AND roomId = :room AND clientMessageId = :id AND status = 'UNKNOWN' AND serverId IS NULL")
    protected abstract suspend fun claim(user: String, room: String, id: String): Int

    @Transaction
    open suspend fun claimRetry(user: String, room: String, id: String): OutboxEntry? {
        if (claim(user, room, id) != 1) return null
        return requireNotNull(find(user, room, id))
    }

    @Query("UPDATE outbox SET status = :status WHERE userId = :user AND roomId = :room AND clientMessageId = :id AND status = 'SENDING' AND serverId IS NULL")
    abstract suspend fun unconfirmed(user: String, room: String, id: String, status: SendStatus): Int

    @Query("UPDATE outbox SET status = 'SENT', serverId = :serverId, sequence = :sequence, serverInstanceId = :instance WHERE userId = :user AND roomId = :room AND clientMessageId = :id AND text = :text AND (serverId IS NULL OR serverId = :serverId) AND (serverInstanceId IS NULL OR serverInstanceId = :instance)")
    abstract suspend fun accept(user: String, room: String, id: String, text: String, serverId: String, sequence: Long, instance: String): Int
}

@Database(entities = [OutboxEntry::class, CachedMessage::class, CacheSession::class, HistoryKey::class, SyncCursor::class, SyncHint::class], version = 4,
    exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)])
@TypeConverters(OutboxConverters::class)
abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun messages(): MessageDao
    abstract fun historyKeys(): HistoryKeyDao
    abstract fun sync(): SyncDao

    companion object {
        fun open(context: Context) = Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, "chat-outbox.db").build()
    }
}

// One instance per app process. Recovery is never repeated on account changes or reconnects.
class OutboxStore(private val database: OutboxDatabase) {
    private val startup = Mutex()
    private var initialized = false
    private val dao = database.outbox()

    suspend fun initialize(): Int = startup.withLock {
        if (initialized) return@withLock 0
        val recovered = dao.recoverInterrupted()
        initialized = true
        recovered
    }

    fun observe(user: String, room: String) = dao.observe(user, room)
    suspend fun load(user: String, room: String) = dao.load(user, room)
    suspend fun enqueue(entry: OutboxEntry) = dao.insert(entry)
    suspend fun claimRetry(user: String, room: String, id: String) = dao.claimRetry(user, room, id)

    suspend fun unconfirmed(user: String, room: String, id: String, status: SendStatus): Int {
        require(status == SendStatus.UNKNOWN || status == SendStatus.FAILED)
        return dao.unconfirmed(user, room, id, status)
    }

    suspend fun accept(user: String, room: String, message: Message): Int {
        if (message.senderId != user || message.roomId != room) return 0
        return dao.accept(user, room, message.clientMessageId, message.text, message.id, message.sequence, message.serverInstanceId)
    }

    suspend fun acceptSnapshot(user: String, room: String, messages: List<Message>) {
        database.withTransaction { messages.forEach { accept(user, room, it) } }
    }
}

class ChatApplication : Application() {
    private val database by lazy { OutboxDatabase.open(this) }
    val outbox by lazy { OutboxStore(database) }
    val messages by lazy { MessageCacheStore(database, outbox) }
    val repository by lazy { ChatRepository(outbox, messages) }
    val foregroundSession by lazy { ForegroundChatSession(repository) }
    val fcmBinding by lazy { FcmBindingStore(this) }
    val fcmRegistration by lazy { FcmRegistrationController(this, fcmBinding) }
    val pushScheduler by lazy { PushSyncScheduler(this) }
    val chatNotifications by lazy { ChatNotifications(this) }
    val localPushAdapter by lazy { LocalPushAdapter(repository) }
    override fun onCreate() {
        super.onCreate()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = foregroundSession.onForeground()
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = foregroundSession.onBackground()
        })
    }
}
