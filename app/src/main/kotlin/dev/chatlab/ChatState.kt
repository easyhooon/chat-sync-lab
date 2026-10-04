package dev.chatlab

import kotlinx.serialization.Serializable

@Serializable
data class Message(val id: String, val clientMessageId: String, val roomId: String, val senderId: String,
    val text: String, val sequence: Long, val createdAt: String)
@Serializable data class SendMessage(val clientMessageId: String, val text: String)
@Serializable data class Event(val type: String, val messages: List<Message>? = null, val message: Message? = null)
@Serializable data class ApiError(val code: String, val message: String)
enum class SendStatus { SENDING, SENT, FAILED, UNKNOWN }
enum class LabMode(val port: Int, val label: String) {
    DIRECT(8080, ""), BOTH_LOST(8081, "HTTP + WS 수락 알림 유실 실험"), HTTP_LOST(8082, "HTTP만 유실 · WS 수락 확인 실험");
}
data class MessageRow(val clientMessageId: String, val senderId: String, val text: String,
    val serverId: String? = null, val sequence: Long? = null, val status: SendStatus = SendStatus.SENDING)
data class UiError(val message: String, val clientMessageId: String? = null)
data class ChatState(val user: String = "alice", val connection: String = "연결 끊김", val connected: Boolean = false,
    val messages: List<MessageRow> = emptyList(), val error: UiError? = null, val labMode: LabMode = LabMode.DIRECT)

fun mergeMessage(rows: List<MessageRow>, message: Message): List<MessageRow> {
    val accepted = MessageRow(message.clientMessageId, message.senderId, message.text, message.id, message.sequence, SendStatus.SENT)
    return (rows.filterNot { it.serverId == message.id || (it.senderId == message.senderId && it.clientMessageId == message.clientMessageId) } + accepted)
        .sortedWith(compareBy<MessageRow> { it.sequence ?: Long.MAX_VALUE }.thenBy { it.clientMessageId })
}
fun mergeSnapshot(rows: List<MessageRow>, snapshot: List<Message>): List<MessageRow> {
    val unresolved = rows.filter { it.serverId == null }
    return snapshot.fold(unresolved, ::mergeMessage)
}
// A late failed POST must not undo a success already observed through the WebSocket echo.
fun markUnconfirmed(rows: List<MessageRow>, id: String, status: SendStatus): List<MessageRow> =
    rows.map { if (it.clientMessageId == id && it.serverId == null) it.copy(status = status) else it }

fun acceptMessage(state: ChatState, message: Message): ChatState = state.copy(
    messages = mergeMessage(state.messages, message),
    error = state.error?.takeUnless { message.senderId == state.user && it.clientMessageId == message.clientMessageId },
)
fun recordSendError(state: ChatState, id: String, status: SendStatus, reason: String): ChatState {
    if (state.messages.none { it.senderId == state.user && it.clientMessageId == id && it.serverId == null }) return state
    return state.copy(messages = markUnconfirmed(state.messages, id, status), error = UiError(reason, id))
}

fun retryRequest(state: ChatState, id: String): SendMessage? {
    if (!state.connected) return null
    val row = state.messages.firstOrNull { it.senderId == state.user && it.clientMessageId == id
        && it.status == SendStatus.UNKNOWN && it.serverId == null } ?: return null
    return SendMessage(row.clientMessageId, row.text)
}
