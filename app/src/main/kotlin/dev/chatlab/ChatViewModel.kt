package dev.chatlab

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

class ChatViewModel(private val outbox: OutboxStore, private val labMode: LabMode = LabMode.DIRECT) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 8000; connectTimeoutMillis = 5000 }
        engine { config { pingInterval(20, TimeUnit.SECONDS) } }
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
            if (old.user == user) old.copy(connection = "로컬 기록 복원 중", error = null, outboxReady = false)
            else ChatState(user = user, roomId = room, connection = "로컬 기록 복원 중", labMode = labMode)
        }
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val recovered = outbox.initialize()
                val stored = outbox.load(user, room)
                updateFor(token) { it.copy(messages = mergeOutbox(it.messages, stored.map(OutboxEntry::row)), outboxReady = true, connection = "연결 중") }
                Log.i("ChatLab", "OUTBOX_RESTORED user=$user room=$room rows=${stored.size} recoveredSending=$recovered")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e("ChatLab", "Outbox restore failed", error)
                connectionLost(token, "로컬 기록 복원 실패. 전송을 시작하지 않았습니다. 다시 연결해 확인하세요.")
                return@launch
            }
            coroutineScope {
                launch {
                    try {
                        outbox.observe(user, room).collect { stored ->
                            updateFor(token) { it.copy(messages = mergeOutbox(it.messages, stored.map(OutboxEntry::row))) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Log.e("ChatLab", "Outbox observation failed", error)
                        updateFor(token) { it.copy(connected = false, connection = "연결 끊김", outboxReady = false,
                            error = UiError("로컬 기록 갱신을 확인하지 못했습니다. 다시 연결하세요.")) }
                        this@coroutineScope.cancel("Outbox observation failed")
                    }
                }
                try {
                    client.webSocket(urlString = "ws://127.0.0.1:${labMode.port}/rooms/$room/events", request = { header("X-Test-User", user) }) {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val event = json.decodeFromString<Event>(frame.readText())
                            if (token != generation) continue
                            when (event.type) {
                                "snapshot" -> {
                                    val messages = requireNotNull(event.messages).filter { it.roomId == room }
                                    var persistenceError: UiError? = null
                                    try { outbox.acceptSnapshot(user, room, messages) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) {
                                        Log.e("ChatLab", "Snapshot receipt save failed", error)
                                        persistenceError = UiError("서버 기록은 받았지만 로컬 수락 기록 저장에 실패했습니다. 다시 연결해 확인하세요.")
                                    }
                                    updateFor(token) { it.copy(connected = true, connection = "연결됨", error = persistenceError,
                                        messages = mergeSnapshot(it.messages, messages)) }
                                }
                                "message" -> {
                                    val message = requireNotNull(event.message)
                                    if (message.roomId != room) continue
                                    acceptKnown(user, room, message, token)
                                    Log.i("ChatLab", "WS receive sender=${message.senderId} sequence=${message.sequence} id=${message.id} clientId=${message.clientMessageId} text=${message.text}")
                                }
                            }
                        }
                    }
                    connectionLost(token, "실시간 연결이 종료됐습니다. 다시 연결해 기록을 확인하세요.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.w("ChatLab", "WebSocket failed", error)
                    connectionLost(token, "서버 연결 실패. 서버 실행과 adb reverse를 확인하고 다시 연결하세요.")
                }
            }
        }
    }

    fun disconnect() {
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
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
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
        var persistenceError: UiError? = null
        try { outbox.accept(user, room, message) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("ChatLab", "Acceptance receipt save failed", error)
            persistenceError = UiError("서버 수락은 확인했지만 로컬 기록 저장에 실패했습니다. 다시 연결해 확인하세요.")
        }
        updateFor(token) { acceptMessage(it, message).let { accepted -> if (persistenceError != null) accepted.copy(error = persistenceError) else accepted } }
    }

    private suspend fun persistUnconfirmed(entry: OutboxEntry, status: SendStatus, token: Int, reason: String) {
        try {
            val changed = outbox.unconfirmed(entry.userId, entry.roomId, entry.clientMessageId, status)
            if (changed == 1) updateFor(token) { recordSendError(it, entry.clientMessageId, status, reason) }
            val stored = outbox.load(entry.userId, entry.roomId).firstOrNull { it.clientMessageId == entry.clientMessageId }
            Log.i("ChatLab", "HTTP timeout/error user=${entry.userId} room=${entry.roomId} clientId=${entry.clientMessageId} resultingStatus=${stored?.status}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e("ChatLab", "Outbox outcome save failed", error)
            updateFor(token) { it.copy(error = UiError("전송 결과의 로컬 저장에 실패했습니다. 다시 연결해 서버 기록을 확인하세요.")) }
        }
    }

    private suspend fun cleanupCancelled(entry: OutboxEntry) = withContext(NonCancellable) {
        // Graceful ViewModel cancellation can leave the process alive. A hard process kill uses startup recovery instead.
        try { outbox.unconfirmed(entry.userId, entry.roomId, entry.clientMessageId, SendStatus.UNKNOWN) }
        catch (error: Exception) { Log.e("ChatLab", "Cancelled request outcome save failed", error) }
    }

    private suspend fun submit(entry: OutboxEntry, token: Int, retry: Boolean) {
        val id = entry.clientMessageId
        Log.i("ChatLab", "HTTP attempt sender=${entry.userId} room=${entry.roomId} clientId=$id retry=$retry text=${entry.text}")
        try {
            val response = client.post("http://127.0.0.1:${labMode.port}/rooms/${entry.roomId}/messages") {
                header("X-Test-User", entry.userId); contentType(ContentType.Application.Json); setBody(SendMessage(id, entry.text))
            }
            if (response.status.isSuccess()) {
                val message = response.body<Message>()
                check(message.senderId == entry.userId && message.roomId == entry.roomId && message.clientMessageId == id && message.text == entry.text)
                acceptKnown(entry.userId, entry.roomId, message, token)
                Log.i("ChatLab", "HTTP accepted sender=${entry.userId} sequence=${message.sequence} id=${message.id} clientId=$id status=${response.status.value}")
            } else {
                val apiError = response.body<ApiError>()
                val status = if (response.status.value in 400..499) SendStatus.FAILED else SendStatus.UNKNOWN
                persistUnconfirmed(entry, status, token, apiError.message)
            }
        } catch (cancelled: CancellationException) { cleanupCancelled(entry); throw cancelled }
        catch (error: Exception) {
            Log.w("ChatLab", "HTTP outcome unknown", error)
            persistUnconfirmed(entry, SendStatus.UNKNOWN, token, "전송 결과 미확인. 같은 ID로 재시도하거나 다시 연결해 기록을 확인하세요.")
        }
    }

    override fun onCleared() { disconnect(); client.close() }
}
