package dev.chatlab

import android.util.Log
import androidx.paging.cachedIn
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// Application-owned foreground session. Process lifecycle, not Activity/ViewModel disposal, owns the socket.
class ForegroundChatSession(private val repository: ChatRepository) {
    private val outbox = repository.outbox
    private val messages = repository.cache
    private class CacheWriteFailure(cause: Exception) : Exception(cause)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var labMode = LabMode.DIRECT
    @Volatile private var foreground = false
    val isForeground: Boolean get() = foreground
    val sessionGeneration: Int get() = generation
    private val pagers = mutableMapOf<Pair<String, String>, Flow<androidx.paging.PagingData<MessageRow>>>()
    @Synchronized
    fun pagedMessages(user: String, room: String) = pagers.getOrPut(user to room) {
        androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 20, initialLoadSize = 20,
            prefetchDistance = 5, enablePlaceholders = false)) { messages.pagingSource(user, room) }.flow.cachedIn(scope)
    }
    fun configureMode(mode: LabMode) {
        if (mode == labMode) return
        labMode = mode
        mutableState.update { it.copy(labMode = mode) }
        if (foreground) connect()
    }
    fun onForeground() {
        if (foreground) return
        foreground = true
        Log.i("ChatLab", "PROCESS_FOREGROUND")
        connect()
    }
    fun onBackground() {
        if (!foreground) return
        foreground = false
        disconnect()
        mutableState.update { it.copy(connection = "백그라운드", catchUpRequired = true) }
        Log.i("ChatLab", "PROCESS_BACKGROUND socketStopped generation=$generation")
    }
    private val mutableState = MutableStateFlow(ChatState(labMode = labMode))
    val state = mutableState.asStateFlow()
    private var sessionJob: Job? = null
    @Volatile private var generation = 0

    private fun updateFor(token: Int, transform: (ChatState) -> ChatState) {
        mutableState.update { if (token == generation) transform(it) else it }
    }

    fun connect(user: String = state.value.user) {
        disconnect()
        val token = generation
        val room = state.value.roomId
        mutableState.update { old ->
            if (old.user == user) old.copy(connection = "로컬 기록 복원 중", error = null, outboxReady = false, historyInstance = null, loadingOlder = false, olderError = null, historyEnd = false)
            else ChatState(user = user, roomId = room, connection = "로컬 기록 복원 중", labMode = labMode)
        }
        if (!foreground) return
        sessionJob = scope.launch {
            try {
                val recovered = outbox.initialize()
                Log.i("ChatLab", "OUTBOX_RECOVERED user=$user room=$room recoveredSending=$recovered")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e("ChatLab", "Outbox restore failed", error)
                connectionLost(token, "로컬 기록 복원 실패. 전송을 시작하지 않았습니다. 다시 연결해 확인하세요.")
                return@launch
            }
            coroutineScope {
                val databaseReady = CompletableDeferred<Unit>()
                launch {
                    try {
                        outbox.observe(user, room).collect { stored ->
                            val rows = stored.map(OutboxEntry::row)
                            updateFor(token) { withOutboxRows(it, rows).copy(outboxReady = true) }
                            if (!databaseReady.isCompleted) {
                                Log.i("ChatLab", "OUTBOX_RESTORED user=$user room=$room rows=${rows.size}")
                                updateFor(token) { it.copy(connection = "연결 중") }
                                databaseReady.complete(Unit)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Log.e("ChatLab", "Message observation failed", error)
                        updateFor(token) { it.copy(connected = false, connection = "연결 끊김", outboxReady = false,
                            error = UiError("로컬 기록 갱신을 확인하지 못했습니다. 다시 연결하세요.")) }
                        this@coroutineScope.cancel("Message observation failed")
                    }
                }
                databaseReady.await()
                try {
                    Log.i("ChatLab", "WS_START user=$user room=$room generation=$token")
                    repository.events(user, room, labMode.port) { event ->
                        if (token == generation && foreground) when (event.type) {
                            "snapshot" -> {
                                val page = requireNotNull(event.page)
                                repository.latest(user, room, page)
                                val key = requireNotNull(messages.historyKey(user, room, page.serverInstanceId))
                                updateFor(token) { it.copy(connected = true, connection = "연결됨",
                                    historyInstance = page.serverInstanceId, historyEnd = key.endReached) }
                                Log.i("ChatLab", "BOOTSTRAP_CACHED user=$user rows=${page.messages.size} highWatermark=${page.highWatermark} end=${key.endReached}")
                            }
                            "message" -> {
                                val message = requireNotNull(event.message)
                                acceptKnown(user, room, message, token)
                                Log.i("ChatLab", "WS receive sender=${message.senderId} sequence=${message.sequence} id=${message.id} clientId=${message.clientMessageId} text=${message.text}")
                            }
                        }
                    }
                    connectionLost(token, "실시간 연결이 종료됐습니다. 다시 연결해 기록을 확인하세요.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.w("ChatLab", "WebSocket failed", error)
                    connectionLost(token, "실시간 연결/기록 저장 실패. 저장된 기록을 표시합니다. 서버와 adb reverse를 확인하고 다시 연결하세요.")
                }
            }
        }
    }

    private fun disconnect() {
        generation++
        sessionJob?.cancel()
        sessionJob = null
        // An HTTP job in this process may still finish. Only process startup recovers abandoned SENDING.
        mutableState.update { it.copy(connected = false, connection = "연결 끊김", queueing = false) }
    }

    private fun connectionLost(token: Int, reason: String) {
        updateFor(token) { it.copy(connected = false, connection = "연결 끊김", error = UiError(reason)) }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val current = state.value
        if (!current.connected || !current.outboxReady || current.queueing || trimmed.isEmpty() || trimmed.length > 1000) return
        val token = generation
        val entry = OutboxEntry(current.user, current.roomId, UUID.randomUUID().toString(), trimmed)
        mutableState.update { it.copy(queueing = true) }
        scope.launch {
            try {
                outbox.enqueue(entry) // Commit first. No network request exists if this throws.
            } catch (cancelled: CancellationException) { cleanupCancelled(entry); throw cancelled }
            catch (error: Exception) {
                Log.e("ChatLab", "Outbox enqueue failed; no POST", error)
                updateFor(token) { it.copy(queueing = false, error = UiError("로컬 저장 실패. 전송하지 않았습니다. 입력을 유지했습니다.")) }
                return@launch
            }
            Log.i("ChatLab", "OUTBOX_COMMITTED user=${entry.userId} room=${entry.roomId} clientId=${entry.clientMessageId} text=${entry.text}")
            updateFor(token) { it.copy(queueing = false, lastQueuedId = entry.clientMessageId, error = null) }
            if (token != generation) {
                persistUnconfirmed(entry, SendStatus.UNKNOWN, token, "전송 시작 전에 연결이 변경됐습니다. 같은 ID로 재시도하세요.")
                return@launch
            }
            submit(entry, token, retry = false)
        }
    }

    fun retry(id: String) {
        val current = state.value
        if (!current.outboxReady || retryRequest(current, id) == null) return
        val token = generation
        scope.launch {
            val entry = try { outbox.claimRetry(current.user, current.roomId, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e("ChatLab", "Outbox retry claim failed; no POST", error)
                updateFor(token) { it.copy(error = UiError("로컬 재시도 준비 실패. 전송하지 않았습니다.", id)) }
                return@launch
            } ?: return@launch
            updateFor(token) { it.copy(error = it.error?.takeUnless { error -> error.clientMessageId == id }) }
            if (token != generation) {
                persistUnconfirmed(entry, SendStatus.UNKNOWN, token, "재시도 시작 전에 연결이 변경됐습니다.")
                return@launch
            }
            submit(entry, token, retry = true)
        }
    }

    private suspend fun acceptKnown(user: String, room: String, message: Message, token: Int) {
        try { repository.receive(user, room, message) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("ChatLab", "Accepted message cache save failed", error)
            updateFor(token) { it.copy(error = UiError("서버 수락은 받았지만 로컬 기록 저장에 실패했습니다. 다시 연결해 확인하세요.")) }
            throw CacheWriteFailure(error)
        }
    }

    private suspend fun persistUnconfirmed(entry: OutboxEntry, status: SendStatus, token: Int, reason: String) {
        try {
            val changed = outbox.unconfirmed(entry.userId, entry.roomId, entry.clientMessageId, status)
            if (changed == 1) updateFor(token) { withSendError(it, entry.clientMessageId, reason) }
            val stored = outbox.load(entry.userId, entry.roomId).firstOrNull { it.clientMessageId == entry.clientMessageId }
            Log.i("ChatLab", "HTTP timeout/error user=${entry.userId} room=${entry.roomId} clientId=${entry.clientMessageId} resultingStatus=${stored?.status}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("ChatLab", "Outbox outcome save failed", error)
            updateFor(token) { it.copy(error = UiError("전송 결과의 로컬 저장에 실패했습니다. 다시 연결해 서버 기록을 확인하세요.")) }
        }
    }

    private suspend fun cleanupCancelled(entry: OutboxEntry) = withContext(NonCancellable) {
        // Graceful session shutdown may cancel an HTTP job. A hard process kill uses startup recovery instead.
        try { outbox.unconfirmed(entry.userId, entry.roomId, entry.clientMessageId, SendStatus.UNKNOWN) }
        catch (error: Exception) { Log.e("ChatLab", "Cancelled request outcome save failed", error) }
    }

    private suspend fun submit(entry: OutboxEntry, token: Int, retry: Boolean) {
        val id = entry.clientMessageId
        Log.i("ChatLab", "HTTP attempt sender=${entry.userId} room=${entry.roomId} clientId=$id retry=$retry text=${entry.text}")
        try {
            val response = repository.send(entry, labMode.port)
            if (response.message != null) {
                val message = response.message
                acceptKnown(entry.userId, entry.roomId, message, token)
                Log.i("ChatLab", "HTTP accepted sender=${entry.userId} sequence=${message.sequence} id=${message.id} clientId=$id status=${response.status}")
            } else {
                val apiError = requireNotNull(response.error)
                val status = if (response.status in 400..499) SendStatus.FAILED else SendStatus.UNKNOWN
                persistUnconfirmed(entry, status, token, apiError.message)
            }
        } catch (cancelled: CancellationException) { cleanupCancelled(entry); throw cancelled }
        catch (error: CacheWriteFailure) {
            persistUnconfirmed(entry, SendStatus.UNKNOWN, token, "서버 수락은 받았지만 로컬 기록 저장에 실패했습니다. 다시 연결해 확인하세요.")
        }
        catch (error: Exception) {
            Log.w("ChatLab", "HTTP outcome unknown", error)
            persistUnconfirmed(entry, SendStatus.UNKNOWN, token, "전송 결과 미확인. 같은 ID로 재시도하거나 다시 연결해 기록을 확인하세요.")
        }
    }

    fun loadOlder() {
        val current = state.value
        val instance = current.historyInstance ?: return
        if (!current.connected || current.loadingOlder || current.historyEnd) return
        val token = generation
        mutableState.update { it.copy(loadingOlder = true, olderError = null) }
        scope.launch {
            try {
                val key = requireNotNull(messages.historyKey(current.user, current.roomId, instance))
                if (!key.endReached) {
                    Log.i("ChatLab", "OLDER_REQUEST user=${key.ownerId} oldest=${key.oldestSequence} cursor=${key.nextBefore}")
                    repository.older(key, labMode.port)
                }
                val saved = requireNotNull(messages.historyKey(current.user, current.roomId, instance))
                updateFor(token) { it.copy(loadingOlder = false, historyEnd = saved.endReached) }
                Log.i("ChatLab", "OLDER_CACHED user=${saved.ownerId} oldest=${saved.oldestSequence} end=${saved.endReached}")
            } catch (cancelled: CancellationException) {
                updateFor(token) { it.copy(loadingOlder = false) }; throw cancelled
            } catch (error: Exception) {
                Log.w("ChatLab", "Older page failed; cursor preserved", error)
                updateFor(token) { it.copy(loadingOlder = false, olderError = "과거 조회 실패. 저장된 기록을 유지합니다. 다시 시도하세요.") }
            }
        }
    }
    suspend fun close() { disconnect(); scope.coroutineContext[Job]?.cancelAndJoin(); repository.close() }
}
