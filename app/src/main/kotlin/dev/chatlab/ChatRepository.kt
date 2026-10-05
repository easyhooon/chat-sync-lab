package dev.chatlab

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
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

// All incoming transports share this repository and Room merge. HTTP responses never become UI rows.
class ChatRepository(val outbox: OutboxStore, val cache: MessageCacheStore) {
    data class SendOutcome(val status: Int, val message: Message? = null, val error: ApiError? = null)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 8000; connectTimeoutMillis = 5000 }
        engine { config { pingInterval(20, TimeUnit.SECONDS) } }
    }
    suspend fun receive(owner: String, room: String, message: Message) = cache.importMessage(owner, room, message)
    suspend fun latest(owner: String, room: String, page: History) = cache.importLatestPage(owner, room, page)
    suspend fun older(key: HistoryKey, port: Int) {
        val response = client.get("http://127.0.0.1:$port/rooms/${key.roomId}/messages") {
            header("X-Test-User", key.ownerId)
            parameter("limit", 20); parameter("before", requireNotNull(key.nextBefore))
        }
        if (!response.status.isSuccess()) {
            val error = response.body<ApiError>()
            error("${response.status.value} ${error.code}: ${error.message}")
        }
        cache.importOlderPage(key, response.body())
    }
    suspend fun send(entry: OutboxEntry, port: Int): SendOutcome {
        val response = client.post("http://127.0.0.1:$port/rooms/${entry.roomId}/messages") {
            header("X-Test-User", entry.userId); contentType(ContentType.Application.Json)
            setBody(SendMessage(entry.clientMessageId, entry.text))
        }
        if (!response.status.isSuccess()) return SendOutcome(response.status.value, error = response.body())
        val message = response.body<Message>()
        check(message.senderId == entry.userId && message.roomId == entry.roomId &&
            message.clientMessageId == entry.clientMessageId && message.text == entry.text)
        return SendOutcome(response.status.value, message = message)
    }
    suspend fun events(owner: String, room: String, port: Int, consume: suspend (Event) -> Unit) {
        client.webSocket(urlString = "ws://127.0.0.1:$port/rooms/$room/events", request = { header("X-Test-User", owner) }) {
            for (frame in incoming) if (frame is Frame.Text) consume(json.decodeFromString(frame.readText()))
        }
    }
    fun close() = client.close()
}
