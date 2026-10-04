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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

class ChatViewModel : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 8000; connectTimeoutMillis = 5000 }
        engine { config { pingInterval(20, TimeUnit.SECONDS) } }
    }
    private val mutableState = MutableStateFlow(ChatState())
    val state = mutableState.asStateFlow()
    private var sessionJob: Job? = null
    @Volatile private var generation = 0

    private fun updateFor(token: Int, transform: (ChatState) -> ChatState) {
        mutableState.update { if (token == generation) transform(it) else it }
    }

    fun connect(user: String = state.value.user) {
        disconnect()
        val token = generation
        mutableState.update { old ->
            if (old.user == user) old.copy(connection = "연결 중", error = null)
            else ChatState(user = user, connection = "연결 중")
        }
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                client.webSocket(urlString = "ws://127.0.0.1:8080/rooms/demo/events", request = { header("X-Test-User", user) }) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val event = json.decodeFromString<Event>(frame.readText())
                        if (token != generation) continue
                        updateFor(token) { current ->
                            when (event.type) {
                                "snapshot" -> current.copy(connected = true, connection = "연결됨", error = null,
                                    messages = mergeSnapshot(current.messages, requireNotNull(event.messages)))
                                "message" -> acceptMessage(current, requireNotNull(event.message))
                                else -> current
                            }
                        }
                        event.message?.let { Log.i("ChatLab", "WS receive sender=${it.senderId} sequence=${it.sequence} id=${it.id} text=${it.text}") }
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

    fun disconnect() {
        generation++
        sessionJob?.cancel()
        sessionJob = null
        mutableState.update { current -> current.copy(connected = false, connection = "연결 끊김",
            messages = current.messages.map { if (it.status == SendStatus.SENDING) it.copy(status = SendStatus.UNKNOWN) else it }) }
    }

    private fun connectionLost(token: Int, reason: String) {
        updateFor(token) { current -> current.copy(connected = false, connection = "연결 끊김", error = UiError(reason)) }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (!state.value.connected || trimmed.isEmpty() || trimmed.length > 1000) return
        val user = state.value.user
        val token = generation
        val id = UUID.randomUUID().toString()
        mutableState.update { it.copy(messages = it.messages + MessageRow(id, user, trimmed), error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = client.post("http://127.0.0.1:8080/rooms/demo/messages") {
                    header("X-Test-User", user); contentType(ContentType.Application.Json); setBody(SendMessage(id, trimmed))
                }
                if (token != generation) return@launch
                if (response.status.isSuccess()) {
                    val message = response.body<Message>()
                    updateFor(token) { acceptMessage(it, message) }
                    Log.i("ChatLab", "HTTP accepted sender=$user sequence=${message.sequence} id=${message.id}")
                } else {
                    val apiError = response.body<ApiError>()
                    val status = if (response.status.value in 400..499) SendStatus.FAILED else SendStatus.UNKNOWN
                    updateFor(token) { recordSendError(it, id, status, apiError.message) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("ChatLab", "HTTP outcome unknown", error)
                updateFor(token) { recordSendError(it, id, SendStatus.UNKNOWN,
                    "전송 결과를 확인하지 못했습니다. 다시 연결하면 서버 기록과 대조합니다.") }
            }
        }
    }

    override fun onCleared() { disconnect(); client.close() }
}
