package dev.chatlab

import kotlinx.serialization.Serializable

@Serializable
data class PushEnvelope(val recipientId: String, val roomId: String, val serverInstanceId: String,
    val throughSequence: Long, val message: Message? = null)
data class CatchUpRequest(val ownerId: String, val roomId: String, val serverInstanceId: String, val throughSequence: Long)
sealed interface PushOutcome {
    data object Recorded : PushOutcome
    data class NeedsCatchUp(val request: CatchUpRequest) : PushOutcome
}

// Shared bounded ingestion adapter for tests and an explicitly configured FCM service.
// A future authenticated delivery boundary must supply boundAccount independently of the payload.
class LocalPushAdapter(private val repository: ChatRepository) {
    suspend fun receive(boundAccount: String, payload: PushEnvelope): PushOutcome {
        require(boundAccount in setOf("alice", "bob") && payload.recipientId == boundAccount) { "Push account mismatch" }
        require(payload.roomId == "demo" && payload.serverInstanceId.isNotBlank() && payload.throughSequence > 0) { "Invalid push scope" }
        val message = payload.message
        if (message == null) {
            repository.cache.requestCatchUp(boundAccount, payload.roomId, payload.serverInstanceId, payload.throughSequence)
            return PushOutcome.NeedsCatchUp(
                CatchUpRequest(boundAccount, payload.roomId, payload.serverInstanceId, payload.throughSequence))
        }
        require(message.roomId == payload.roomId && message.serverInstanceId == payload.serverInstanceId && message.sequence == payload.throughSequence)
        repository.receive(boundAccount, payload.roomId, message)
        // Recording one payload does not prove that delayed/dropped messages before it were recovered.
        return PushOutcome.Recorded
    }
}
