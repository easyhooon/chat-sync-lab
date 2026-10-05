package chatlab

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.test.*

class PaginationTest {
    private fun append(store: ChatStore, count: Int) = repeat(count) {
        store.append("bob", "demo", SendMessage(UUID.randomUUID().toString(), "fixture-$it"))
    }
    @Test fun exclusiveCursorTraversesAllRowsWhileNewAppendsDoNotShiftOlderPages() {
        val store = ChatStore(); append(store, 85)
        var page = store.page("demo", 20)
        assertEquals((66L..85L).toList(), page.messages.map { it.sequence })
        val originalCursor = page.nextBefore
        append(store, 5)
        assertEquals((46L..65L).toList(), store.page("demo", 20, originalCursor).messages.map { it.sequence })
        val collected = page.messages.toMutableList()
        while (!page.endOfHistory) {
            val oldBoundary = page.messages.first().sequence
            page = store.page("demo", 20, page.nextBefore)
            assertTrue(page.messages.all { it.sequence < oldBoundary })
            assertTrue(page.messages.size <= 20)
            collected += page.messages
        }
        assertNull(page.nextBefore)
        assertEquals((1L..85L).toList(), collected.map { it.sequence }.sorted())
        assertEquals(85, collected.map { it.id }.toSet().size)
        assertTrue(ChatStore().page("demo").endOfHistory)
    }
    @Test fun boundedSubscriptionAndPastPagesCoverConcurrentPublicationWithoutGap() = runBlocking {
        repeat(10) {
            val store = ChatStore(); append(store, 40)
            val writer = launch(Dispatchers.Default) { append(store, 50) }
            val channel = store.subscribe("alice", "demo")
            var page = requireNotNull(channel.receive().page)
            assertEquals(20, page.messages.size)
            val atSubscription = page.highWatermark
            val received = page.messages.toMutableList()
            while (!page.endOfHistory) { page = store.page("demo", 20, page.nextBefore); received += page.messages }
            writer.join()
            repeat((90 - atSubscription).toInt()) { received += requireNotNull(withTimeout(3000) { channel.receive() }.message) }
            assertEquals(store.history("demo"), received.distinctBy { it.id }.sortedBy { it.sequence })
            store.unsubscribe("demo", channel)
        }
    }
    @Test fun httpChecksLimitCursorRoomServerRunAndAccessBeforeCursorParsing() = testApplication {
        val store = ChatStore(mapOf("demo" to setOf("alice", "bob"), "other" to setOf("bob")))
        append(store, 45)
        application { chatModule(store) }
        val api = createClient { install(ContentNegotiation) { json() } }
        suspend fun get(path: String, user: String? = "alice") = api.get(path) { if (user != null) header("X-Test-User", user) }
        val latest = get("/rooms/demo/messages?limit=20").body<History>()
        assertEquals((26L..45L).toList(), latest.messages.map { it.sequence })
        val older = get("/rooms/demo/messages?before=${latest.nextBefore}&limit=20").body<History>()
        assertEquals((6L..25L).toList(), older.messages.map { it.sequence })
        val last = get("/rooms/demo/messages?before=${older.nextBefore}&limit=20").body<History>()
        assertEquals((1L..5L).toList(), last.messages.map { it.sequence }); assertTrue(last.endOfHistory)
        for (limit in listOf("0", "51", "bad")) assertEquals(HttpStatusCode.BadRequest, get("/rooms/demo/messages?limit=$limit").status)
        assertEquals(HttpStatusCode.BadRequest, get("/rooms/demo/messages?before=bad").status)
        assertEquals(HttpStatusCode.BadRequest, get("/rooms/other/messages?before=${latest.nextBefore}", "bob").status)
        assertEquals(HttpStatusCode.Forbidden, get("/rooms/other/messages?before=bad").status)
        assertEquals(HttpStatusCode.Unauthorized, get("/rooms/demo/messages?before=bad", null).status)
        val foreignCursor = ChatStore().also { append(it, 21) }.page("demo").nextBefore
        assertEquals(HttpStatusCode.Conflict, get("/rooms/demo/messages?before=$foreignCursor").status)
    }
}
