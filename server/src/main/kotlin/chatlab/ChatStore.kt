package chatlab

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable

@Serializable
data class SendMessage(val clientMessageId: String, val text: String)
@Serializable
data class Message(
    val id: String, val clientMessageId: String, val roomId: String, val senderId: String,
    val text: String, val sequence: Long, val createdAt: String, val serverInstanceId: String,
)
@Serializable
data class History(val messages: List<Message>, val serverInstanceId: String)
@Serializable
data class Event(val type: String, val messages: List<Message>? = null, val message: Message? = null, val serverInstanceId: String? = null)
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
        channel.trySend(Event("snapshot", messages = history(room), serverInstanceId = serverInstanceId)).getOrThrow()
        subscriptions.getOrPut(room) { mutableSetOf() } += channel
        return channel
    }

    @Synchronized
    fun unsubscribe(room: String, channel: Channel<Event>) { subscriptions[room]?.remove(channel); channel.close() }
}
