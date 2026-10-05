package chatlab

import java.time.Instant
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SendMessage(val clientMessageId: String, val text: String)
@Serializable
data class Message(
    val id: String, val clientMessageId: String, val roomId: String, val senderId: String,
    val text: String, val sequence: Long, val createdAt: String, val serverInstanceId: String,
)
@Serializable
data class History(val messages: List<Message>, val serverInstanceId: String, val roomId: String,
    val nextBefore: String?, val endOfHistory: Boolean, val highWatermark: Long)
@Serializable
data class AfterPage(val messages: List<Message>, val serverInstanceId: String, val roomId: String,
    val afterSequence: Long, val nextAfter: Long, val throughSequence: Long, val endOfCatchUp: Boolean)
@Serializable
data class BeforeCursor(val roomId: String, val serverInstanceId: String, val sequence: Long)
@Serializable
data class Event(val type: String, val page: History? = null, val message: Message? = null)
@Serializable
data class ApiError(val code: String, val message: String)
class ChatError(val status: Int, val code: String, override val message: String) : RuntimeException(message)
data class Accepted(val message: Message, val isNew: Boolean)

// One small, process-local store. The lock makes append + publication + snapshot subscription atomic.
class ChatStore(private val members: Map<String, Set<String>> = mapOf("demo" to setOf("alice", "bob"))) {
    val serverInstanceId: String = UUID.randomUUID().toString()
    private val messages = mutableMapOf<String, MutableList<Message>>()
    private val subscriptions = mutableMapOf<String, MutableSet<Channel<Event>>>()

    fun authorize(user: String?, room: String): String {
        if (user !in setOf("alice", "bob")) throw ChatError(401, "TEST_IDENTITY_REQUIRED", "Use local test identity alice or bob")
        if (user !in members[room].orEmpty()) throw ChatError(403, "ROOM_FORBIDDEN", "No access to this room")
        return requireNotNull(user)
    }

    @Synchronized
    fun history(room: String) = messages[room].orEmpty().toList()

    @Synchronized
    fun page(room: String, limit: Int = 20, before: String? = null): History {
        if (limit !in 1..50) throw ChatError(400, "INVALID_LIMIT", "limit must be 1–50")
        val cursor = before?.let { encoded ->
            if (encoded.length > 512) throw ChatError(400, "INVALID_CURSOR", "Invalid before cursor")
            val parsed = runCatching { Json.decodeFromString<BeforeCursor>(String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)) }
                .getOrElse { throw ChatError(400, "INVALID_CURSOR", "Invalid before cursor") }
            if (parsed.roomId != room || parsed.sequence < 1) throw ChatError(400, "CURSOR_SCOPE", "Cursor belongs to another room or has invalid sequence")
            if (parsed.serverInstanceId != serverInstanceId) throw ChatError(409, "CURSOR_EXPIRED", "Server restarted; reconnect for a new page")
            parsed
        }
        val all = messages[room].orEmpty()
        val eligible = all.filter { cursor == null || it.sequence < cursor.sequence }
        val selected = eligible.takeLast(limit)
        val ended = eligible.size <= limit
        val next = if (ended) null else Base64.getUrlEncoder().withoutPadding().encodeToString(
            Json.encodeToString(BeforeCursor.serializer(), BeforeCursor(room, serverInstanceId, selected.first().sequence)).toByteArray(Charsets.UTF_8))
        return History(selected, serverInstanceId, room, next, ended, all.lastOrNull()?.sequence ?: 0)
    }

    @Synchronized
    fun afterPage(room: String, instance: String, after: Long, through: Long?, limit: Int = 20): AfterPage {
        if (limit !in 1..50 || after < 0) throw ChatError(400, "INVALID_AFTER", "Invalid after position or limit")
        if (instance != serverInstanceId) throw ChatError(409, "CURSOR_EXPIRED", "Server restarted; reconnect for a new baseline")
        val all = messages[room].orEmpty()
        val high = all.lastOrNull()?.sequence ?: 0
        val target = through ?: high
        if (target < after || target > high) throw ChatError(400, "INVALID_THROUGH", "through must be between after and the current room high watermark")
        val selected = all.filter { it.sequence > after && it.sequence <= target }.take(limit)
        val next = selected.lastOrNull()?.sequence ?: after
        return AfterPage(selected, serverInstanceId, room, after, next, target, next == target)
    }

    @Synchronized
    fun append(user: String, room: String, request: SendMessage): Accepted {
        authorize(user, room)
        if (!runCatching { UUID.fromString(request.clientMessageId).toString() == request.clientMessageId }.getOrDefault(false))
            throw ChatError(400, "INVALID_ID", "clientMessageId must be a canonical UUID")
        val text = request.text.trim()
        if (text.isEmpty() || text.length > 1000) throw ChatError(400, "INVALID_TEXT", "Text must contain 1–1000 characters")
        val history = messages.getOrPut(room) { mutableListOf() }
        val previous = history.firstOrNull { it.senderId == user && it.clientMessageId == request.clientMessageId }
        if (previous != null) {
            if (previous.text != text) throw ChatError(409, "ID_CONFLICT", "This clientMessageId already has a different text")
            return Accepted(previous, false)
        }
        val message = Message(UUID.randomUUID().toString(), request.clientMessageId, room, user, text,
            (history.lastOrNull()?.sequence ?: 0) + 1, Instant.now().toString(), serverInstanceId)
        history += message
        // A slow consumer is closed instead of silently dropping events. Manual reconnect gets a new snapshot.
        subscriptions[room]?.removeAll { channel ->
            if (channel.trySend(Event("message", message = message)).isFailure) {
                channel.close(IllegalStateException("Slow subscriber; reconnect for history")); true
            } else false
        }
        return Accepted(message, true)
    }

    @Synchronized
    fun subscribe(user: String, room: String): Channel<Event> {
        authorize(user, room)
        val channel = Channel<Event>(64)
        channel.trySend(Event("snapshot", page = page(room))).getOrThrow()
        subscriptions.getOrPut(room) { mutableSetOf() } += channel
        return channel
    }

    @Synchronized
    fun unsubscribe(room: String, channel: Channel<Event>) { subscriptions[room]?.remove(channel); channel.close() }
}
