package chatlab

import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class CatchUpTest {
    private fun append(store: ChatStore, count: Int) = repeat(count) { store.append("bob", "demo", SendMessage(UUID.randomUUID().toString(), "gap-$it")) }
    @Test fun boundedAfterPagesCoverFixedTargetWhileNewLiveAppendsContinue() = runBlocking {
        val store = ChatStore(); append(store, 85)
        val subscribed = store.subscribe("alice", "demo")
        val baseline = subscribed.receive().page!!
        append(store, 123)
        val target = 205L
        var after = baseline.highWatermark
        val rows = mutableListOf<Message>()
        val sizes = mutableListOf<Int>()
        while (after < target) {
            val page = store.afterPage("demo", store.serverInstanceId, after, target, 20)
            assertEquals(after, page.afterSequence); assertEquals(target, page.throughSequence)
            assertEquals(page.nextAfter == target, page.endOfCatchUp)
            assertTrue(page.messages.size in 1..20)
            rows += page.messages; sizes += page.messages.size; after = page.nextAfter
            append(store, 1) // The fixed recovery fence does not slide with new appends.
        }
        assertEquals((86L..205L).toList(), rows.map { it.sequence })
        assertEquals(listOf(20,20,20,20,20,20), sizes)
        assertTrue(store.afterPage("demo", store.serverInstanceId, target, target).endOfCatchUp)
        store.unsubscribe("demo", subscribed)
    }
    @Test fun afterHttpRequiresScopeAndDirectionAndRejectsExpiredRunOrFutureTarget() = testApplication {
        val store = ChatStore(); append(store, 120)
        application { chatModule(store) }
        val api = createClient { install(ContentNegotiation) { json() } }
        suspend fun get(query: String, room: String = "demo", owner: String? = "alice") = api.get("/rooms/$room/messages?$query") {
            if (owner != null) header("X-Test-User", owner)
        }
        val suffix = "serverInstanceId=${store.serverInstanceId}"
        assertEquals((21L..40L).toList(), get("after=20&through=100&$suffix").body<AfterPage>().messages.map { it.sequence })
        for (q in listOf("after=-1&$suffix", "after=bad&$suffix", "after=20", "after=20&through=121&$suffix", "after=20&through=19&$suffix", "after=20&through=bad&$suffix", "after=20&limit=51&$suffix", "after=20&before=bad&$suffix"))
            assertEquals(HttpStatusCode.BadRequest, get(q).status, q)
        assertEquals(HttpStatusCode.Conflict, get("after=20&serverInstanceId=old-run").status)
        assertEquals(HttpStatusCode.Unauthorized, get("after=bad", owner = null).status)
        assertEquals(HttpStatusCode.Forbidden, get("after=bad", room = "other").status)
    }
    @Test fun fidBodyHasOneExplicitTargetAndRequiresSdkRegistrationReceipt() {
        val registered = ConfirmedFidRegistration("alice", "test-project", "synthetic-fid", 1)
        val encoded = Json.parseToJsonElement(Json.encodeToString(FcmSendRequest.serializer(), fidCatchUpRequest(registered, "demo", "run-A", 120))).jsonObject["message"]!!.jsonObject
        assertEquals("synthetic-fid", encoded["fid"]!!.jsonPrimitive.content)
        assertFalse("token" in encoded); assertFalse("topic" in encoded)
        assertEquals("120", encoded["data"]!!.jsonObject["throughSequence"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { fidCatchUpRequest(registered.copy(sdkRegisteredAtMillis = 0), "demo", "run-A", 120) }
        assertFalse(registered.toString().contains("synthetic-fid"))
        assertFalse(fidCatchUpRequest(registered,"demo","run-A",120).toString().contains("synthetic-fid"))
    }
}
