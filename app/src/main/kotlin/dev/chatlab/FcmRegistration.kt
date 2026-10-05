package dev.chatlab

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

// This receipt is written only by the SDK's onRegistered callback, never FirebaseInstallations.getId().
data class RegisteredInstallation(val ownerId: String, val projectId: String, val fid: String, val registeredAtMillis: Long) {
    override fun toString() = "RegisteredInstallation(ownerId=$ownerId, projectId=$projectId, fid=<redacted>)"
}
class FcmBindingStore(context: Context, preferencesName: String = "chat-fcm-binding") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    fun boundAccount(): String? = preferences.getString("owner", null)
    fun bindForRegistration(owner: String) {
        require(owner in setOf("alice", "bob"))
        check(preferences.edit().putString("owner", owner).remove("fid").remove("registeredAt").commit())
    }
    fun recordSdkRegistration(project: String, fid: String): RegisteredInstallation {
        val owner = requireNotNull(boundAccount())
        require(project.isNotBlank() && fid.isNotBlank())
        val now = System.currentTimeMillis()
        check(preferences.edit().putString("project", project).putString("fid", fid).putLong("registeredAt", now).commit())
        return RegisteredInstallation(owner, project, fid, now)
    }
    fun confirmed(): RegisteredInstallation? {
        val fid = preferences.getString("fid", null) ?: return null
        val project = preferences.getString("project", null) ?: return null
        val owner = boundAccount() ?: return null
        val at = preferences.getLong("registeredAt", 0)
        return if (at > 0) RegisteredInstallation(owner, project, fid, at) else null
    }
}
class FcmRegistrationController(private val context: Context, private val binding: FcmBindingStore) {
    fun registerForApprovedLocalTest(owner: String) {
        check(BuildConfig.DEBUG && BuildConfig.CHAT_FCM_ENABLED) { "FCM local opt-in is disabled" }
        val options = requireNotNull(FirebaseOptions.fromResource(context)) { "Approved Firebase configuration missing" }
        binding.bindForRegistration(owner)
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) FirebaseApp.initializeApp(context, options)
        FirebaseMessaging.getInstance().register().addOnCompleteListener { task ->
            // Task success triggers onRegistered separately; never declare receipt success here.
            if (!task.isSuccessful) android.util.Log.w("ChatLab", "FCM registration failed; receipt remains unconfirmed")
        }
    }
}
fun parseFcmHint(data: Map<String, String>): PushEnvelope {
    require(data["kind"] == "catch_up" && data.size <= 8)
    val recipient = requireNotNull(data["recipientId"])
    val room = requireNotNull(data["roomId"])
    val run = requireNotNull(data["serverInstanceId"])
    val through = requireNotNull(data["throughSequence"]?.toLongOrNull())
    require(recipient.length <= 64 && room.length <= 64 && run.length in 1..128 && through > 0)
    return PushEnvelope(recipient, room, run, through)
}
