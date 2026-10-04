package chatlab

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class AckLossProxyTest {
    @Test fun bothNotificationsLostThenSameIdRetryReturnsOriginalMessage() = runBlocking { exercise(AckLossMode.BOTH) }
    @Test fun lostHttpStillHasWebSocketAcceptanceAndReplayDoesNotDuplicate() = runBlocking { exercise(AckLossMode.HTTP_ONLY) }

    private suspend fun exercise(mode: AckLossMode) {
        val store = ChatStore()
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { chatModule(store) }.start(wait = false)
        var stopProxy: (() -> Unit)? = null
        var closeClient: (() -> Unit)? = null
        try {
            val port = server.engine.resolvedConnectors().single().port
            val proxy = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ackLossProxyModule(mode, port, responseDelayMillis = 6000) }.start(wait = false)
            stopProxy = { proxy.stop(0, 1000) }
            val proxyPort = proxy.engine.resolvedConnectors().single().port
            val client = HttpClient(CIO) {
                install(ContentNegotiation) { json() }; install(WebSockets); install(HttpTimeout) { requestTimeoutMillis = 5000 }
            }
            closeClient = { client.close() }
            client.webSocket(urlString = "ws://127.0.0.1:$proxyPort/rooms/demo/events", request = { header("X-Test-User", "alice") }) {
                assertEquals("snapshot", Json.decodeFromString<Event>((incoming.receive() as Frame.Text).readText()).type)
                val request = SendMessage(UUID.randomUUID().toString(), "accepted before timeout")
                val firstFailure = runCatching {
                    client.post("http://127.0.0.1:$proxyPort/rooms/demo/messages") {
                        header("X-Test-User", "alice"); contentType(ContentType.Application.Json); setBody(request)
                        timeout { requestTimeoutMillis = 1500 }
                    }
                }.exceptionOrNull()
                assertIs<HttpRequestTimeoutException>(firstFailure)
                val accepted = store.history("demo").single()
                assertEquals(request.clientMessageId, accepted.clientMessageId)
                if (mode == AckLossMode.BOTH) assertNull(withTimeoutOrNull(150) { incoming.receive() })
                else assertEquals(accepted, withTimeout(3000) { Json.decodeFromString<Event>((incoming.receive() as Frame.Text).readText()) }.message)
                val retry = client.post("http://127.0.0.1:$proxyPort/rooms/demo/messages") {
                    header("X-Test-User", "alice"); contentType(ContentType.Application.Json); setBody(request)
                }
                assertEquals(HttpStatusCode.OK, retry.status)
                assertEquals(accepted, retry.body<Message>())
                assertEquals(listOf(accepted), store.history("demo"))
                assertNull(withTimeoutOrNull(150) { incoming.receive() }, "Replay must not publish a second event")
                // Sender is part of the idempotency key: Bob's same UUID is an independent message.
                val bob = client.post("http://127.0.0.1:$proxyPort/rooms/demo/messages") {
                    header("X-Test-User", "bob"); contentType(ContentType.Application.Json)
                    setBody(SendMessage(request.clientMessageId, "Bob with the same client ID"))
                }.body<Message>()
                assertEquals("bob", bob.senderId)
                val bobEvent = withTimeout(3000) { Json.decodeFromString<Event>((incoming.receive() as Frame.Text).readText()) }
                assertEquals(bob, bobEvent.message)
                assertEquals(2, store.history("demo").size)
            }
        } finally { closeClient?.invoke(); stopProxy?.invoke(); server.stop(0, 1000) }
    }
}
