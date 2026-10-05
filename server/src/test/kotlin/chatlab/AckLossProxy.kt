package chatlab

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

// Test source set only. No control route, fault headers, or production server changes.
enum class AckLossMode(val port: Int) { BOTH(8081), HTTP_ONLY(8082), PAGE_FAILURE(8081), CATCH_UP_FAILURE(8081) }

fun main(args: Array<String>) {
    val mode = when (args.singleOrNull()) {
        "both" -> AckLossMode.BOTH
        "http-only" -> AckLossMode.HTTP_ONLY
        "page-failure" -> AckLossMode.PAGE_FAILURE
        "catch-up" -> AckLossMode.CATCH_UP_FAILURE
        else -> error("Explicit opt-in required: -Pscenario=both, http-only, page-failure, or catch-up")
    }
    println("ACK_LOSS_PROXY_MODE mode=$mode listen=127.0.0.1:${mode.port} upstream=127.0.0.1:8080")
    embeddedServer(Netty, host = "127.0.0.1", port = mode.port) { ackLossProxyModule(mode) }.start(wait = true)
}

fun Application.ackLossProxyModule(mode: AckLossMode, upstreamPort: Int = 8080, responseDelayMillis: Long = 60_000) {
    val upstream = HttpClient(CIO) { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }; install(io.ktor.client.plugins.websocket.WebSockets) }
    monitor.subscribe(ApplicationStopped) { upstream.close() }
    val faultedId = AtomicReference<String?>(null)
    val faultedPage = java.util.concurrent.atomic.AtomicBoolean(false)
    val afterRequests = java.util.concurrent.atomic.AtomicInteger(0)
    val auth = ChatStore()
    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
    install(StatusPages) { exception<ChatError> { call, cause -> call.respond(HttpStatusCode.fromValue(cause.status), ApiError(cause.code, cause.message)) } }
    install(io.ktor.server.websocket.WebSockets) { pingPeriod = 10.seconds; timeout = 10.seconds }
    val access = createRouteScopedPlugin("ProxyTestIdentity") {
        onCall { call -> auth.authorize(call.request.header("X-Test-User"), "demo") }
    }
    routing {
        route("/rooms/demo") {
            install(access)
            get("/messages") {
                val before = call.request.queryParameters["before"]
                val after = call.request.queryParameters["after"]
                if (mode == AckLossMode.CATCH_UP_FAILURE && after != null) {
                    val number = afterRequests.incrementAndGet()
                    println("PROXY_AFTER_REQUEST number=$number after=$after through=${call.request.queryParameters["through"]}")
                    delay(1500)
                    if (number == 2) {
                        println("PROXY_AFTER_FAILED after=$after")
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("TEST_CATCH_UP_FAILURE", "Local test: resume from the committed after cursor"))
                        return@get
                    }
                }
                if (mode == AckLossMode.PAGE_FAILURE && before != null) {
                    println("PROXY_PAGE_REQUEST before=$before")
                    if (faultedPage.compareAndSet(false, true)) {
                        println("PROXY_PAGE_FAILED before=$before")
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("TEST_PAGE_FAILURE", "Local test: retry the same before cursor"))
                        return@get
                    }
                    delay(1500) // A live WS message can arrive while an older HTTP page is in flight.
                }
                val response = upstream.get("http://127.0.0.1:$upstreamPort/rooms/demo/messages") { header("X-Test-User", call.request.header("X-Test-User")!!)
                    url { parameters.appendAll(call.request.queryParameters) } }
                if (response.status.isSuccess()) {
                    if (after != null) call.respond(response.status, response.body<AfterPage>())
                    else call.respond(response.status, response.body<History>())
                }
                else call.respond(response.status, response.body<ApiError>())
            }
            post("/messages") {
                val user = call.request.header("X-Test-User")!!
                val request = call.receive<SendMessage>()
                // Arm BEFORE forwarding: an upstream WS echo can beat the upstream HTTP response.
                val inject = mode in setOf(AckLossMode.BOTH, AckLossMode.HTTP_ONLY) && user == "alice" && faultedId.compareAndSet(null, request.clientMessageId)
                val response = upstream.post("http://127.0.0.1:$upstreamPort/rooms/demo/messages") {
                    header("X-Test-User", user); contentType(ContentType.Application.Json); setBody(request)
                }
                if (!response.status.isSuccess()) {
                    if (inject) faultedId.compareAndSet(request.clientMessageId, null)
                    call.respond(response.status, response.body<ApiError>())
                    return@post
                }
                val accepted = response.body<Message>()
                println("PROXY_UPSTREAM_ACCEPTED mode=$mode status=${response.status.value} clientId=${accepted.clientMessageId} id=${accepted.id} sequence=${accepted.sequence}")
                if (inject) {
                    println("PROXY_HOLD_HTTP_AFTER_ACCEPT clientId=${accepted.clientMessageId}")
                    delay(responseDelayMillis) // The upstream append has already completed; app times out at 8 seconds.
                }
                call.respond(response.status, accepted)
            }
            webSocket("/events") {
                val downstream = this
                val user = call.request.header("X-Test-User")!!
                val forwarder = launch {
                    try {
                        upstream.webSocket(urlString = "ws://127.0.0.1:$upstreamPort/rooms/demo/events", request = { header("X-Test-User", user) }) {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val payload = frame.readText()
                                val event = Json.decodeFromString<Event>(payload)
                                if (mode == AckLossMode.BOTH && user == "alice" && event.message?.senderId == "alice"
                                    && event.message.clientMessageId == faultedId.get() && event.type == "message") {
                                    println("PROXY_HIDE_WS clientId=${event.message.clientMessageId}")
                                } else downstream.send(Frame.Text(payload))
                            }
                        }
                    } finally { downstream.close(CloseReason(CloseReason.Codes.NORMAL, "Upstream ended")) }
                }
                try {
                    for (frame in incoming) if (frame is Frame.Text || frame is Frame.Binary)
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Send messages using HTTP POST"))
                } finally { forwarder.cancelAndJoin() }
            }
        }
    }
}
