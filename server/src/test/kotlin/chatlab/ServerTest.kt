package chatlab

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import java.util.UUID
import java.net.Socket
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class ServerTest {
    private fun id() = UUID.randomUUID().toString()

    @Test fun httpAndWebSocketRoundTripHistoryAndIdempotency() = testApplication {
        application { chatModule() }
        val client = createClient { install(ContentNegotiation) { json() }; install(WebSockets) }
        client.webSocket("/rooms/demo/events", request = { header("X-Test-User", "bob") }) {
            val snapshot = Json.decodeFromString<Event>((incoming.receive() as Frame.Text).readText())
            assertEquals("snapshot", snapshot.type); assertEquals(emptyList(), snapshot.messages)
            val request = SendMessage(id(), "hello bob")
            val response = client.post("/rooms/demo/messages") { header("X-Test-User", "alice"); contentType(ContentType.Application.Json); setBody(request) }
            assertEquals(HttpStatusCode.Created, response.status)
            val accepted = response.body<Message>()
            assertEquals(snapshot.serverInstanceId, accepted.serverInstanceId)
            val event = withTimeout(3000) { Json.decodeFromString<Event>((incoming.receive() as Frame.Text).readText()) }
            assertEquals(accepted, event.message); assertEquals("alice", event.message?.senderId)
            val replay = client.post("/rooms/demo/messages") { header("X-Test-User", "alice"); contentType(ContentType.Application.Json); setBody(request) }
            assertEquals(HttpStatusCode.OK, replay.status); assertEquals(accepted, replay.body<Message>())
            assertNull(withTimeoutOrNull(150) { incoming.receive() })
            val reply = client.post("/rooms/demo/messages") { header("X-Test-User", "bob"); contentType(ContentType.Application.Json); setBody(SendMessage(id(), "hello alice")) }
            assertEquals(HttpStatusCode.Created, reply.status)
            assertEquals("bob", withTimeout(3000) { Json.decodeFromString<Event>((incoming.receive() as Frame.Text).readText()) }.message?.senderId)
            val history = client.get("/rooms/demo/messages") { header("X-Test-User", "alice") }.body<History>()
            assertEquals(accepted.serverInstanceId, history.serverInstanceId)
            assertEquals(listOf(1L, 2L), history.messages.map { it.sequence })
        }
    }

    @Test fun storeRestartChangesNamespaceWhileRoomSequenceRestarts() {
        val request = SendMessage(id(), "same client intent")
        val old = ChatStore().append("alice", "demo", request).message
        val restarted = ChatStore().append("alice", "demo", request).message
        assertEquals(1, old.sequence); assertEquals(1, restarted.sequence)
        assertNotEquals(old.serverInstanceId, restarted.serverInstanceId)
        assertNotEquals(old.id, restarted.id)
    }

    @Test fun identityAndRoomAccessAreCheckedBeforeHttpAndWebSocketUpgrade() = testApplication {
        // Restricted membership is a test fixture; the runnable demo has alice + bob in its one room.
        application { chatModule(ChatStore(mapOf("demo" to setOf("alice")))) }
        val socketClient = createClient { install(WebSockets) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/rooms/demo/messages").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/rooms/demo/messages") { header("X-Test-User", "mallory") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/rooms/demo/messages") { header("X-Test-User", "bob") }.status)
        for (user in listOf("bob", "mallory")) {
            val failure = runCatching { socketClient.webSocketSession("/rooms/demo/events") { header("X-Test-User", user) } }.exceptionOrNull()
            assertNotNull(failure, "Denied user must not upgrade to WebSocket")
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/rooms/demo/messages") { header("X-Test-User", "bob") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/rooms/other/messages") { header("X-Test-User", "alice") }.status)
    }

    @Test fun realLoopbackWebSocketHandshakeChecksIdentityAndMembership() = runBlocking {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            chatModule(ChatStore(mapOf("demo" to setOf("alice"))))
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            for ((user, status) in listOf("alice" to 101, "bob" to 403, "mallory" to 401)) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 3000
                    val handshake = "GET /rooms/demo/events HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nX-Test-User: $user\r\n\r\n"
                    socket.getOutputStream().write(handshake.toByteArray()); socket.getOutputStream().flush()
                    assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 $status "))
                }
            }
        } finally { server.stop(0, 1000) }
    }

    @Test fun malformedInputAndConflictAreVisibleErrors() = testApplication {
        application { chatModule() }
        val client = createClient { install(ContentNegotiation) { json() } }
        suspend fun post(request: SendMessage) = client.post("/rooms/demo/messages") {
            header("X-Test-User", "alice"); contentType(ContentType.Application.Json); setBody(request)
        }
        assertEquals(HttpStatusCode.BadRequest, post(SendMessage("bad", "hello")).status)
        assertEquals(HttpStatusCode.BadRequest, post(SendMessage(id(), "  ")).status)
        assertEquals(HttpStatusCode.BadRequest, post(SendMessage(id(), "x".repeat(1001))).status)
        val key = id()
        assertEquals(HttpStatusCode.Created, post(SendMessage(key, "first")).status)
        assertEquals(HttpStatusCode.Conflict, post(SendMessage(key, "changed")).status)
        val malformed = client.post("/rooms/demo/messages") { header("X-Test-User", "alice"); contentType(ContentType.Application.Json); setBody("{") }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test fun concurrentAppendsStayUniqueOrderedAndPublicationMatchesHistory() = runBlocking {
        val store = ChatStore()
        val events = store.subscribe("bob", "demo")
        assertEquals("snapshot", events.receive().type)
        coroutineScope { (1..40).map { launch(Dispatchers.Default) { store.append("alice", "demo", SendMessage(id(), "m$it")) } }.joinAll() }
        val history = store.history("demo")
        assertEquals((1L..40L).toList(), history.map { it.sequence })
        assertEquals(40, history.map { it.id }.toSet().size)
        assertEquals(history, (1..40).map { events.receive().message })
        store.unsubscribe("demo", events)
    }

    @Test fun subscriptionSnapshotAndLiveEventsHaveNoGap() = runBlocking {
        val store = ChatStore()
        repeat(10) { store.append("alice", "demo", SendMessage(id(), "before$it")) }
        val channel = store.subscribe("bob", "demo")
        repeat(10) { store.append("alice", "demo", SendMessage(id(), "after$it")) }
        val snapshot = channel.receive().messages!!
        val live = (1..10).map { channel.receive().message!! }
        assertEquals(store.history("demo"), snapshot + live)
        store.unsubscribe("demo", channel)
    }
}
