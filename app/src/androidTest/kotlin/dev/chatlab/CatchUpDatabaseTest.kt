package dev.chatlab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatchUpDatabaseTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun db() = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
    private fun message(n: Long, run: String = "run-A") = Message("server-$n", "client-$n", "demo", "bob", "body-$n", n, "2026-10-05T00:00:00Z", run)
    private fun latest(first: Long, last: Long, run: String = "run-A") = History((first..last).map { message(it, run) }, run, "demo", if (first==1L) null else "before-$first", first==1L, last)
    private fun page(request: SyncCursor, last: Long) = AfterPage((request.contiguousThrough+1..last).map { message(it, request.serverInstanceId) }, request.serverInstanceId, request.roomId,
        request.contiguousThrough, last, request.requestedThrough, last==request.requestedThrough)
    private suspend fun rows(cache: MessageCacheStore, owner: String = "alice") = cache.observe(owner, "demo").first()
    private fun http(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { handler(it) }) { install(ContentNegotiation) { json() } }

    @Test fun earlyHighPushCannotEstablishBaselineOrSkipGapsInKnownRun() = runBlocking {
        val db=db(); val cache=MessageCacheStore(db,OutboxStore(db))
        try {
            cache.importMessage("alice","demo",message(200))
            assertNull(cache.syncCursor("alice","demo","run-A"))
            cache.importLatestPage("alice","demo",latest(61,80))
            assertEquals(SyncCursor("alice","demo","run-A",60,80,200),cache.syncCursor("alice","demo","run-A"))
            cache.importLatestPage("alice","demo",latest(181,200))
            assertEquals(80L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
            cache.importMessage("alice","demo",message(82))
            assertEquals(80L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
            cache.importMessage("alice","demo",message(81))
            assertEquals(82L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
        } finally { db.close() }
    }
    @Test fun coordinatorSerializesOverlapsAndFillsMultiplePagesWithLiveAndLateDuplicates() = runBlocking {
        val db=db(); val cache=MessageCacheStore(db,OutboxStore(db)); val requests=mutableListOf<Long>()
        val client=http { request ->
            val after=request.url.parameters["after"]!!.toLong(); val target=request.url.parameters["through"]!!.toLong()
            requests+=after
            if (requests.size==1) cache.importMessage("alice","demo",message(201)) // Live above a hole arrives first.
            val body=page(SyncCursor("alice","demo","run-A",60,after,target), minOf(after+20,target))
            respond(Json.encodeToString(AfterPage.serializer(),body), headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        val repository=ChatRepository(OutboxStore(db),cache,client)
        try {
            cache.importLatestPage("alice","demo",latest(61,80)); cache.importLatestPage("alice","demo",latest(181,200))
            val request=cache.syncCursor("alice","demo","run-A")!!
            val beforeKey=cache.historyKey("alice","demo","run-A")!!
            coroutineScope { (1..10).map { async { repository.sync.catchUp("alice","demo","run-A",8080) } }.awaitAll() }
            assertEquals(listOf(80L,100L,120L,140L,160L),requests)
            assertEquals((61L..201L).toList(),rows(cache).map{it.sequence})
            val complete=cache.syncCursor("alice","demo","run-A")!!
            assertEquals(201L,complete.contiguousThrough)
            cache.importAfterPage(request,page(request,100))
            cache.importMessage("alice","demo",message(199))
            assertEquals(complete,cache.syncCursor("alice","demo","run-A"))
            val afterKey=cache.historyKey("alice","demo","run-A")!!
            assertEquals(beforeKey.nextBefore,afterKey.nextBefore) // after never consumes before.
            assertEquals(beforeKey.oldestSequence,afterKey.oldestSequence)
            assertEquals(beforeKey.endReached,afterKey.endReached)
            assertEquals(201L,afterKey.highWatermark) // A separate live import extends the tail.
        } finally { repository.close(); db.close() }
    }
    @Test fun interruptedCommittedPageReopensAndResumesWithoutSkipping() = runBlocking {
        val name="catch-up-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,OutboxDatabase::class.java,name).build()
        var cache=MessageCacheStore(db,OutboxStore(db)); val requests=mutableListOf<Long>(); var fail=true
        fun client()=http { request ->
            val after=request.url.parameters["after"]!!.toLong(); requests+=after
            if (after==40L && fail) error("Injected interruption after one committed page")
            val target=request.url.parameters["through"]!!.toLong()
            respond(Json.encodeToString(AfterPage.serializer(),page(SyncCursor("alice","demo","run-A",0,after,target),minOf(after+20,target))), headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        var repository=ChatRepository(OutboxStore(db),cache,client())
        try {
            cache.importLatestPage("alice","demo",latest(1,20)); cache.importLatestPage("alice","demo",latest(121,140))
            assertTrue(runCatching { repository.sync.catchUp("alice","demo","run-A",8080) }.isFailure)
            assertEquals(40L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
            repository.close();db.close()
            db=Room.databaseBuilder(context,OutboxDatabase::class.java,name).build();cache=MessageCacheStore(db,OutboxStore(db));fail=false
            repository=ChatRepository(OutboxStore(db),cache,client())
            repository.sync.catchUp("alice","demo","run-A",8080)
            assertEquals(listOf(20L,40L,40L,60L,80L,100L),requests)
            assertEquals((1L..140L).toList(),rows(cache).map{it.sequence})
            assertEquals(140L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
        } finally { repository.close();db.close();context.deleteDatabase(name) }
    }
    @Test fun coroutineCancellationBeforeAndAfterPageCommitKeepsOriginalScopeAndReceipt() = runBlocking {
        val db=db();val outbox=OutboxStore(db);val cache=MessageCacheStore(db,outbox)
        val requests=mutableListOf<Long>();val firstStarted=CompletableDeferred<Unit>();val secondStarted=CompletableDeferred<Unit>()
        val gate=CompletableDeferred<Unit>();var first=true;var second=true
        val client=http { request ->
            val after=request.url.parameters["after"]!!.toLong();val target=request.url.parameters["through"]!!.toLong();requests+=after
            if(after==20L && first){first=false;firstStarted.complete(Unit);gate.await()}
            if(after==40L && second){second=false;secondStarted.complete(Unit);gate.await()}
            val body=page(SyncCursor("alice","demo","run-A",0,after,target),minOf(after+20,target)).let { p ->
                p.copy(messages=p.messages.map { if(it.sequence==21L)it.copy(senderId="alice") else it })
            }
            respond(Json.encodeToString(AfterPage.serializer(),body),headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        val repository=ChatRepository(outbox,cache,client)
        try {
            cache.importLatestPage("alice","demo",latest(1,20));cache.importLatestPage("alice","demo",latest(61,80))
            outbox.enqueue(OutboxEntry("alice","demo","client-21","body-21",SendStatus.UNKNOWN))
            val firstJob=launch {repository.sync.catchUp("alice","demo","run-A",8080)}
            withTimeout(5000){firstStarted.await()};firstJob.cancelAndJoin()
            assertEquals(20L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
            assertEquals(SendStatus.UNKNOWN,outbox.load("alice","demo").single().status)
            val secondJob=launch {repository.sync.catchUp("alice","demo","run-A",8080)}
            withTimeout(5000){secondStarted.await()};secondJob.cancelAndJoin()
            assertEquals(40L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
            assertEquals(SendStatus.SENT,outbox.load("alice","demo").single().status)
            assertEquals(0,outbox.unconfirmed("alice","demo","client-21",SendStatus.UNKNOWN))
            cache.importLatestPage("bob","demo",latest(61,80))
            repository.sync.catchUp("alice","demo","run-A",8080)
            assertEquals(listOf(20L,20L,40L,40L),requests)
            assertEquals(80L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
            assertEquals(SyncCursor("bob","demo","run-A",60,80,80),cache.syncCursor("bob","demo","run-A"))
            assertEquals("outbox:alice:demo:client-21",rows(cache).single{it.sequence==21L}.stableKey)
        } finally {repository.close();db.close()}
    }
    @Test fun conflictingOrGappedPageRollsBackReceiptAndCursor() = runBlocking {
        val db=db();val outbox=OutboxStore(db);val cache=MessageCacheStore(db,outbox)
        try {
            cache.importLatestPage("alice","demo",latest(1,20));cache.importLatestPage("alice","demo",latest(61,80))
            cache.importMessage("alice","demo",message(25).copy(text="different old body"))
            outbox.enqueue(OutboxEntry("alice","demo","client-21","body-21",SendStatus.UNKNOWN))
            val request=cache.syncCursor("alice","demo","run-A")!!
            val valid=page(request,40).let { it.copy(messages=it.messages.map { m -> if(m.sequence==21L) m.copy(senderId="alice") else m }) }
            assertTrue(runCatching { cache.importAfterPage(request,valid.copy(messages=valid.messages.filter { it.sequence!=22L })) }.isFailure)
            assertTrue(runCatching { cache.importAfterPage(request,valid) }.isFailure)
            assertEquals(request,cache.syncCursor("alice","demo","run-A"))
            assertEquals(SendStatus.UNKNOWN,outbox.load("alice","demo").single().status)
            assertEquals(41,rows(cache).count{it.serverId!=null})
        } finally { db.close() }
    }
    @Test fun serverRunAccountAndLateOriginalResponseRemainSeparate() = runBlocking {
        val db=db();val cache=MessageCacheStore(db,OutboxStore(db))
        try {
            cache.importLatestPage("alice","demo",latest(1,20));cache.importLatestPage("alice","demo",latest(41,60))
            val original=cache.syncCursor("alice","demo","run-A")!!
            cache.importLatestPage("alice","demo",latest(1,3,"run-B"));cache.importLatestPage("bob","demo",latest(41,60))
            cache.importAfterPage(original,page(original,40))
            assertEquals(60L,cache.syncCursor("alice","demo","run-A")!!.contiguousThrough)
            assertEquals(SyncCursor("alice","demo","run-B",0,3,3),cache.syncCursor("alice","demo","run-B"))
            assertEquals(SyncCursor("bob","demo","run-A",40,60,60),cache.syncCursor("bob","demo","run-A"))
            assertEquals(20,rows(cache,"bob").size)
            assertTrue(runCatching{cache.importAfterPage(original,page(original,40).copy(serverInstanceId="run-B"))}.isFailure)
        } finally { db.close() }
    }
    @Test fun versionThreeMigrationUsesOldObservedPrefixBeforeNewBootstrap() = runBlocking {
        val name="catch-up-v3-${UUID.randomUUID()}.db"
        val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("dev.chatlab.OutboxDatabase/3.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{ old ->
                val entities=schema.getJSONArray("entities")
                for(index in 0 until entities.length()){
                    val entity=entities.getJSONObject(index);val table=entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=entity.optJSONArray("indices")
                    if(indices!=null)for(i in 0 until indices.length())old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup=schema.getJSONArray("setupQueries");for(index in 0 until setup.length())old.execSQL(setup.getString(index))
                old.execSQL("INSERT INTO cache_sessions VALUES ('alice','demo','run-A',1)")
                for(n in 1..20)old.execSQL("INSERT INTO cached_messages VALUES ('alice','demo','run-A',?,?, 'bob',?,?, 'old time',?)",arrayOf<Any>("server-$n","client-$n","body-$n",n,"old-$n"))
                old.execSQL("INSERT INTO history_keys VALUES ('alice','demo','run-A',NULL,1,20,1)");old.version=3
            }
            val db=Room.databaseBuilder(context,OutboxDatabase::class.java,name).build()
            try {
                val cache=MessageCacheStore(db,OutboxStore(db))
                assertNull(cache.syncCursor("alice","demo","run-A"))
                cache.importLatestPage("alice","demo",latest(161,180))
                assertEquals(SyncCursor("alice","demo","run-A",0,20,180),cache.syncCursor("alice","demo","run-A"))
                assertEquals(40,rows(cache).size)
                assertEquals("old-1",rows(cache).first().stableKey)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun durablePushHintsAndDeniedNotificationDoNotSkipSync() = runBlocking {
        val db=db();val cache=MessageCacheStore(db,OutboxStore(db));val repository=ChatRepository(OutboxStore(db),cache)
        try {
            cache.importLatestPage("alice","demo",latest(1,20))
            val hint=PushEnvelope("alice","demo","run-A",120)
            LocalPushAdapter(repository).receive("alice",hint)
            assertEquals(SyncCursor("alice","demo","run-A",0,20,120),cache.syncCursor("alice","demo","run-A"))
            assertEquals(120L,db.sync().hint("alice","demo","run-A")!!.throughSequence)
            assertTrue(runCatching{LocalPushAdapter(repository).receive("bob",hint)}.isFailure)
            assertNull(db.sync().hint("bob","demo","run-A"))
            val wasGranted=context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
            fun permission(command: String) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm $command dev.chatlab android.permission.POST_NOTIFICATIONS").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
            }
            if(wasGranted)permission("revoke")
            try {
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
                assertFalse(ChatNotifications(context).showIfAllowed("alice"))
            } finally { if(wasGranted)permission("grant") }
            assertEquals(20,rows(cache).size)
        } finally { repository.close();db.close() }
    }
}
