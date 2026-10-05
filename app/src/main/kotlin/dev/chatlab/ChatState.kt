package dev.chatlab

import kotlinx.serialization.Serializable

@Serializable
data class Message(val id: String, val clientMessageId: String, val roomId: String, val senderId: String,
    val text: String, val sequence: Long, val createdAt: String, val serverInstanceId: String)
@Serializable data class History(val messages: List<Message>, val serverInstanceId: String, val roomId: String,
    val nextBefore: String?, val endOfHistory: Boolean, val highWatermark: Long)
@Serializable data class SendMessage(val clientMessageId: String, val text: String)
@Serializable data class Event(val type: String, val page: History? = null, val message: Message? = null)
@Serializable data class ApiError(val code: String, val message: String)
enum class SendStatus { SENDING, SENT, FAILED, UNKNOWN }
enum class LabMode(val port: Int, val label: String) {
    DIRECT(8080, ""), BOTH_LOST(8081, "HTTP + WS 수락 알림 유실 실험"), HTTP_LOST(8082, "HTTP만 유실 · WS 수락 확인 실험"), PAGE_FAILURE(8081, "과거 페이지 실패·재시도 실험");
}
data class MessageRow(val clientMessageId: String, val senderId: String, val text: String,
    val serverId: String? = null, val sequence: Long? = null, val status: SendStatus = SendStatus.SENDING, val roomId: String = "demo",
    val serverInstanceId: String? = null, val stableKey: String = "outbox:$senderId:$roomId:$clientMessageId")
data class UiError(val message: String, val clientMessageId: String? = null)
data class ChatState(val user: String = "alice", val connection: String = "연결 끊김", val connected: Boolean = false,
    val sendRows: List<MessageRow> = emptyList(), val error: UiError? = null, val labMode: LabMode = LabMode.DIRECT,
    val roomId: String = "demo", val outboxReady: Boolean = false, val queueing: Boolean = false, val lastQueuedId: String? = null, val historyInstance: String? = null, val loadingOlder: Boolean = false,
    val olderError: String? = null, val historyEnd: Boolean = false, val catchUpRequired: Boolean = false)

// Message rows come only from Room. Keep transient errors independent of the stored list.
fun withOutboxRows(state: ChatState, rows: List<MessageRow>): ChatState = state.copy(
    sendRows = rows,
    error = state.error?.takeUnless { error -> error.clientMessageId != null && rows.any {
        it.senderId == state.user && it.roomId == state.roomId && it.clientMessageId == error.clientMessageId && it.status == SendStatus.SENT
    } },
)
fun withSendError(state: ChatState, id: String, reason: String): ChatState {
    if (state.sendRows.any { it.senderId == state.user && it.roomId == state.roomId && it.clientMessageId == id && it.status == SendStatus.SENT }) return state
    return state.copy(error = UiError(reason, id))
}

fun retryRequest(state: ChatState, id: String): SendMessage? {
    if (!state.connected) return null
    val row = state.sendRows.firstOrNull { it.senderId == state.user && it.clientMessageId == id
        && it.roomId == state.roomId && it.status == SendStatus.UNKNOWN && it.serverId == null } ?: return null
    return SendMessage(row.clientMessageId, row.text)
}
