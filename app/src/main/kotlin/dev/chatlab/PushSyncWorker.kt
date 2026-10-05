package dev.chatlab

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class PushSyncScheduler(private val context: Context) {
    fun enqueue(owner: String, room: String): Operation {
        require(owner in setOf("alice", "bob") && room == "demo")
        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setInputData(workDataOf("owner" to owner, "room" to room))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        // A hint arriving at a running worker's final boundary must still have a follow-up task.
        return WorkManager.getInstance(context).enqueueUniqueWork("chat-sync:$owner:$room", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
class PushSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("owner") ?: return Result.failure()
        val room = inputData.getString("room") ?: return Result.failure()
        if (owner !in setOf("alice", "bob") || room != "demo") return Result.failure()
        val app = applicationContext as ChatApplication
        // The local test binding is independent of whichever account the UI currently displays.
        if (app.fcmBinding.confirmed()?.ownerId != owner) return Result.failure()
        return try {
            app.repository.sync.refresh(owner, room, 8080) // No background WebSocket.
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
