package dev.chatlab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.paging.*
import androidx.paging.testing.asSnapshot
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class PagingDatabaseTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun db() = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
    private fun message(n: Long, run: String = "run-A") = Message("server-$n", "client-$n", "demo", "bob", "body-$n", n, "2026-10-05T00:00:00Z", run)
    private fun page(first: Long, last: Long, high: Long = last, run: String = "run-A") =
        History((first..last).map { message(it, run) }, run, "demo", if (first == 1L) null else "cursor-$run-$first", first == 1L, high)
    private suspend fun rows(cache: MessageCacheStore, owner: String = "alice") = cache.observe(owner, "demo").first()

    @Test fun versionTwoMigrationPreservesExistingCacheOutboxAndStableKeys() = runBlocking {
        val name = "paging-v2-${UUID.randomUUID()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("dev.chatlab.OutboxDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
                old.execSQL("INSERT INTO cache_sessions VALUES ('alice','demo','run-A',1)")
                old.execSQL("INSERT INTO cached_messages VALUES ('alice','demo','run-A','server-1','client-1','bob','old body',1,'old time','old-stable-key')")
                old.execSQL("INSERT INTO outbox VALUES ('alice','demo','pending','old unknown','UNKNOWN',10,NULL,NULL,NULL)")
                old.version = 2
            }
            val db = Room.databaseBuilder(context, OutboxDatabase::class.java, name).build()
            try {
                val cache = MessageCacheStore(db, OutboxStore(db))
                assertEquals(listOf("old body", "old unknown"), rows(cache).map { it.text })
                assertEquals("old-stable-key", rows(cache).first().stableKey)
                assertEquals(SendStatus.UNKNOWN, rows(cache).last().status)
                assertNull(cache.historyKey("alice", "demo", "run-A"))
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun olderPagesLiveAndDuplicatePushShareRowsWithoutMissingOrReordering() = runBlocking {
        val db = db()
        val repository = ChatRepository(OutboxStore(db), MessageCacheStore(db, OutboxStore(db)))
        try {
            val cache = repository.cache
            cache.importLatestPage("alice", "demo", page(61, 80))
            val request = requireNotNull(cache.historyKey("alice", "demo", "run-A"))
            coroutineScope {
                listOf(
                    async { repository.receive("alice", "demo", message(81)) },
                    async { LocalPushAdapter(repository).receive("alice", PushEnvelope("alice", "demo", "run-A", 50, message(50))) },
                    async { cache.importOlderPage(request, page(41, 60, 81)) },
                ).awaitAll()
            }
            cache.importOlderPage(request, page(41, 60, 81))
            cache.importLatestPage("alice", "demo", page(62, 81))
            assertEquals(41, rows(cache).size)
            assertEquals((41L..81L).toList(), rows(cache).map { it.sequence })
            assertEquals(41L, cache.historyKey("alice", "demo", "run-A")!!.oldestSequence)
            assertTrue(rows(cache, "bob").isEmpty())
        } finally { repository.close(); db.close() }
    }
    @Test fun progressedCursorIsNotRegressedByLateDuplicateOlderOrLatestPage() = runBlocking {
        val db = db(); val cache = MessageCacheStore(db, OutboxStore(db))
        try {
            cache.importLatestPage("alice", "demo", page(61, 80))
            val first = cache.historyKey("alice", "demo", "run-A")!!
            cache.importOlderPage(first, page(41, 60, 80))
            cache.importOlderPage(cache.historyKey("alice", "demo", "run-A")!!, page(21, 40, 80))
            val progressed = cache.historyKey("alice", "demo", "run-A")!!
            cache.importOlderPage(first, page(41, 60, 80))
            cache.importLatestPage("alice", "demo", page(61, 80))
            assertEquals(progressed, cache.historyKey("alice", "demo", "run-A"))
            cache.importOlderPage(progressed, page(1, 20, 80))
            assertTrue(cache.historyKey("alice", "demo", "run-A")!!.endReached)
            assertNull(cache.historyKey("alice", "demo", "run-A")!!.nextBefore)
            assertEquals((1L..80L).toList(), rows(cache).map { it.sequence })
        } finally { db.close() }
    }
    @Test fun failedMergeAndWrongScopeNeverAdvanceCursorAndSameRequestCanRetry() = runBlocking {
        val db = db(); val cache = MessageCacheStore(db, OutboxStore(db))
        try {
            cache.importLatestPage("alice", "demo", page(61, 80))
            cache.importMessage("alice", "demo", message(50))
            val request = cache.historyKey("alice", "demo", "run-A")!!
            val wrongBody = page(41, 60, 80).let { it.copy(messages = it.messages.map { m -> if (m.sequence == 50L) m.copy(text = "conflict") else m }) }
            assertTrue(runCatching { cache.importOlderPage(request, wrongBody) }.isFailure)
            assertEquals(request, cache.historyKey("alice", "demo", "run-A"))
            assertEquals(21, rows(cache).size)
            assertTrue(runCatching { cache.importOlderPage(request, page(41, 60, 80, "run-B")) }.isFailure)
            assertTrue(runCatching { cache.importOlderPage(request, page(21, 40, 80)) }.isFailure)
            assertEquals(request, cache.historyKey("alice", "demo", "run-A"))
            assertEquals(21, rows(cache).size)
            cache.importOlderPage(request, page(41, 60, 80))
            assertEquals(40, rows(cache).size)
            assertEquals(41L, cache.historyKey("alice", "demo", "run-A")!!.oldestSequence)
        } finally { db.close() }
    }
    @Test fun roomPagingSourceLoadsNewestFirstAndInvalidatesOnLiveCommit() = runBlocking {
        val db = db(); val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
        try {
            cache.importSnapshot("alice", "demo", "run-A", (1L..80L).map { message(it) })
            outbox.enqueue(OutboxEntry("alice", "demo", "pending", "unknown", SendStatus.UNKNOWN))
            val source = cache.pagingSource("alice", "demo")
            val initial = source.load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page<Int, MessageRow>
            assertEquals("pending", initial.data.first().clientMessageId)
            assertEquals((80L downTo 62L).toList(), initial.data.drop(1).map { it.sequence })
            source.invalidate() // The UI owns a Pager collector, rather than this standalone sample source.
            val generations = AtomicInteger()
            val pager = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false)) {
                generations.incrementAndGet(); cache.pagingSource("alice", "demo")
            }
            val snapshot = pager.flow.asSnapshot {
                scrollTo(40)
                val beforeCommit = generations.get()
                cache.importMessage("alice", "demo", message(81))
                withTimeout(30_000) { while (generations.get() == beforeCommit) delay(20) }
                scrollTo(0)
                scrollTo(81)
            }
            assertEquals(82, snapshot.size)
            assertEquals((81L downTo 1L).toList(), snapshot.drop(1).map { it.sequence })
        } finally { db.close() }
    }
    @Test fun interruptedGapResetsTraversalButServerRunAndOwnerKeysStaySeparate() = runBlocking {
        val db = db(); val cache = MessageCacheStore(db, OutboxStore(db))
        try {
            cache.importLatestPage("alice", "demo", page(1, 20))
            cache.importLatestPage("alice", "demo", page(81, 100)) // >20 missed while disconnected; old end=true cannot imply complete now.
            assertEquals(81L, cache.historyKey("alice", "demo", "run-A")!!.oldestSequence)
            assertFalse(cache.historyKey("alice", "demo", "run-A")!!.endReached)
            cache.importLatestPage("bob", "demo", page(81, 100))
            cache.importLatestPage("alice", "demo", page(1, 2, run = "run-B"))
            assertTrue(cache.historyKey("alice", "demo", "run-B")!!.endReached)
            assertFalse(cache.historyKey("alice", "demo", "run-A")!!.endReached)
            assertEquals(42, rows(cache).size)
            assertEquals(20, rows(cache, "bob").size)
        } finally { db.close() }
    }
    @Test fun reopeningKeepsOlderPageCursorAndCachedRowsWithoutNetwork() = runBlocking {
        val name = "paging-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, OutboxDatabase::class.java, name).build()
        try {
            var cache = MessageCacheStore(db, OutboxStore(db))
            cache.importLatestPage("alice", "demo", page(61, 80))
            cache.importOlderPage(cache.historyKey("alice", "demo", "run-A")!!, page(41, 60, 80))
            val key = cache.historyKey("alice", "demo", "run-A")!!
            db.close(); db = Room.databaseBuilder(context, OutboxDatabase::class.java, name).build()
            cache = MessageCacheStore(db, OutboxStore(db))
            assertEquals(key, cache.historyKey("alice", "demo", "run-A"))
            assertEquals(40, rows(cache).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
