package dev.chatlab

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

// One coordinator shared by foreground WS and background HTTP work. The cursor belongs to Room.
class SyncCoordinator(private val repository: ChatRepository) {
    private val locks = mutableMapOf<Pair<String, String>, Mutex>()
    @Synchronized private fun lock(owner: String, room: String) = locks.getOrPut(owner to room) { Mutex() }
    suspend fun catchUp(owner: String, room: String, instance: String, port: Int,
        progress: (SyncCursor) -> Unit = {}): SyncCursor = lock(owner, room).withLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val before = requireNotNull(repository.cache.syncCursor(owner, room, instance))
            progress(before)
            if (before.contiguousThrough >= before.requestedThrough) return@withLock before
            repository.after(before, port)
            val saved = requireNotNull(repository.cache.syncCursor(owner, room, instance))
            check(saved.contiguousThrough > before.contiguousThrough) { "Catch-up made no contiguous progress" }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    suspend fun refresh(owner: String, room: String, port: Int): SyncCursor {
        val page = repository.latestHttp(owner, room, port)
        repository.latest(owner, room, page)
        return catchUp(owner, room, page.serverInstanceId, port)
    }
}
