package chatlab

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

// Local Bob peer for one-emulator demo: wait for a new Alice message, then reply over HTTP.
fun main() = runBlocking {
    val client = HttpClient(CIO) { install(ContentNegotiation) { json() }; install(WebSockets) }
    try {
        withTimeout(120_000) {
            client.webSocket(urlString = "ws://127.0.0.1:8080/rooms/demo/events", request = { header("X-Test-User", "bob") }) {
                val first = Json.decodeFromString<Event>((incoming.receive() as Frame.Text).readText())
                check(first.type == "snapshot")
                println("BOB_READY snapshotCount=${first.page!!.messages.size}")
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val event = Json.decodeFromString<Event>(frame.readText())
                    val message = event.message ?: continue
                    println("BOB_WS_RECEIVED sender=${message.senderId} sequence=${message.sequence} id=${message.id} text=${message.text}")
                    if (message.senderId != "alice") continue
                    val response = client.post("http://127.0.0.1:8080/rooms/demo/messages") {
                        header("X-Test-User", "bob"); contentType(ContentType.Application.Json)
                        setBody(SendMessage(UUID.randomUUID().toString(), "Bob reply: ${message.text.take(100)}"))
                    }
                    check(response.status == HttpStatusCode.Created)
                    val reply = response.body<Message>()
                    println("BOB_HTTP_ACCEPTED sequence=${reply.sequence} id=${reply.id}")
                    var echo: Message? = null
                    withTimeout(5000) {
                        while (echo?.id != reply.id) {
                            val next = incoming.receive()
                            if (next is Frame.Text) echo = Json.decodeFromString<Event>(next.readText()).message
                        }
                    }
                    check(echo == reply)
                    val history = client.get("http://127.0.0.1:8080/rooms/demo/messages") { header("X-Test-User", "bob") }.body<History>()
                    check(history.messages.containsAll(listOf(message, reply)))
                    println("ROUND_TRIP_PASS historyCount=${history.messages.size}")
                    break
                }
            }
        }
    } finally { client.close() }
}
