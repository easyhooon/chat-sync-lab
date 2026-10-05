package dev.chatlab

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class ChatNotifications(private val context: Context) {
    fun showIfAllowed(owner: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel("chat_messages", "채팅 기록", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, owner.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(owner.hashCode(), Notification.Builder(context, "chat_messages")
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("새 채팅 기록")
            .setContentText("앱을 열어 기록을 확인하세요.").setContentIntent(intent).setAutoCancel(true).build())
        return true
    }
}
