package chatlab

import kotlinx.serialization.Serializable

// Prepared HTTP v1 body only. No credentials, initialization or external delivery is performed.
@Serializable data class FcmSendRequest(val message: FidMessage)
@Serializable data class FidMessage(val fid: String, val data: Map<String, String>) {
    override fun toString() = "FidMessage(fid=<redacted>, dataKeys=${data.keys})"
}
data class ConfirmedFidRegistration(val ownerId: String, val projectId: String, val fid: String, val sdkRegisteredAtMillis: Long) {
    override fun toString() = "ConfirmedFidRegistration(ownerId=$ownerId, projectId=$projectId, fid=<redacted>)"
}
fun fidCatchUpRequest(registration: ConfirmedFidRegistration, room: String, run: String, through: Long): FcmSendRequest {
    require(registration.sdkRegisteredAtMillis > 0 && registration.fid.isNotBlank() && registration.projectId.isNotBlank())
    require(registration.ownerId in setOf("alice", "bob") && room == "demo" && run.isNotBlank() && through > 0)
    return FcmSendRequest(FidMessage(registration.fid, mapOf("kind" to "catch_up", "recipientId" to registration.ownerId,
        "roomId" to room, "serverInstanceId" to run, "throughSequence" to through.toString())))
}
