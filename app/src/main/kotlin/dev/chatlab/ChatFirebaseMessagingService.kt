package dev.chatlab

import android.annotation.SuppressLint
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

// Android lint still checks the legacy onNewToken hook. Messaging 25.1.3 uses onRegistered in FID mode.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class ChatFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        if (!BuildConfig.CHAT_FCM_ENABLED) return
        val application = application as ChatApplication
        try {
            application.fcmBinding.recordSdkRegistration(requireNotNull(FirebaseApp.getInstance().options.projectId), installationId)
            Log.i("ChatLab", "FCM SDK registration confirmed; identifier kept private")
            // App-server upload/send approval is a separate step; no outbound sender is wired here.
        } catch (_: Exception) { Log.w("ChatLab", "FCM registration binding unavailable") }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.CHAT_FCM_ENABLED) return
        val app = application as ChatApplication
        try {
            val owner = requireNotNull(app.fcmBinding.confirmed()?.ownerId)
            val payload = parseFcmHint(message.data)
            runBlocking { withTimeout(2000) { app.localPushAdapter.receive(owner, payload) } }
            Log.i("ChatLab", "FCM_HINT_RECORDED owner=$owner through=${payload.throughSequence}")
            // Only durable short ingestion runs in this callback. HTTP sync runs in WorkManager.
            app.pushScheduler.enqueue(owner, payload.roomId).result.get(3, TimeUnit.SECONDS)
            app.chatNotifications.showIfAllowed(owner)
        } catch (_: Exception) { Log.w("ChatLab", "FCM hint rejected or deferred; foreground sync remains available") }
    }
    override fun onDeletedMessages() {
        if (!BuildConfig.CHAT_FCM_ENABLED) return
        val app = application as ChatApplication
        app.fcmBinding.confirmed()?.ownerId?.let { app.pushScheduler.enqueue(it, "demo") }
    }
}
