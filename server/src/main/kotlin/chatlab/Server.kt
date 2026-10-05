package chatlab

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, host = "127.0.0.1", port = 8080) { chatModule() }.start(wait = true)
}

fun Application.chatModule(store: ChatStore = ChatStore()) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<ChatError> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.status), ApiError(cause.code, cause.message)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_JSON", "Expected clientMessageId and text JSON")) }
    }
    install(WebSockets) { pingPeriod = 15.seconds; timeout = 15.seconds; maxFrameSize = 8192 }
    // Runs before the WebSocket upgrade, so denied clients receive an HTTP error, never a room event.
    val roomAccess = createRouteScopedPlugin("TestIdentityAndRoomAccess") {
        onCall { call -> store.authorize(call.request.header("X-Test-User"), call.parameters["roomId"].orEmpty()) }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        route("/rooms/{roomId}") {
            install(roomAccess)
            get("/messages") { call.respond(History(store.history(call.parameters["roomId"]!!), store.serverInstanceId)) }
            post("/messages") {
                val accepted = store.append(call.request.header("X-Test-User")!!, call.parameters["roomId"]!!, call.receive<SendMessage>())
                call.respond(if (accepted.isNew) HttpStatusCode.Created else HttpStatusCode.OK, accepted.message)
            }
            webSocket("/events") {
                val room = call.parameters["roomId"]!!
                val events = store.subscribe(call.request.header("X-Test-User")!!, room)
                val sender = launch {
                    try { for (event in events) send(Frame.Text(Json.encodeToString(event))) }
                    finally { close(CloseReason(CloseReason.Codes.NORMAL, "Event stream ended")) }
                }
                try {
                    for (frame in incoming) {
                        // v1 only sends messages over HTTP; avoid implying WS input was accepted.
                        if (frame is Frame.Text || frame is Frame.Binary) close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Send messages using HTTP POST"))
                    }
                } finally { store.unsubscribe(room, events); sender.cancelAndJoin() }
            }
        }
    }
}
