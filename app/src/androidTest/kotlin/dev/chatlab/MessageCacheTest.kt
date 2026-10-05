package dev.chatlab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class MessageCacheTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun memory() = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
    private suspend fun <T> OutboxDatabase.use(block: suspend (OutboxDatabase) -> T): T =
        try { block(this) } finally { close() }
    private fun message(n: Long, sender: String = "bob", instance: String = "run-A", clientId: String = "client-$sender-$n") =
        Message("server-$n", clientId, "demo", sender, "body-$n", n, "2026-10-05T00:00:00Z", instance)
    private suspend fun rows(cache: MessageCacheStore, owner: String = "alice", room: String = "demo") = cache.observe(owner, room).first()

    @Test fun migrationFromCommittedVersionOnePreservesUnknownSendingAndSentWithoutInventingHistory() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = JSONObject(assets.open("dev.chatlab.OutboxDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
                old.execSQL("INSERT INTO outbox VALUES ('alice','demo','unknown','old unknown','UNKNOWN',10,NULL,NULL)")
                old.execSQL("INSERT INTO outbox VALUES ('alice','demo','sending','old sending','SENDING',20,NULL,NULL)")
                old.execSQL("INSERT INTO outbox VALUES ('alice','demo','sent','old sent','SENT',30,'old-server',7)")
                old.version = 1
            }
            Room.databaseBuilder(context, OutboxDatabase::class.java, name).build().use { db ->
                val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
                val migrated = outbox.load("alice", "demo") // Forces migration and Room's schema validation.
                assertEquals(listOf(SendStatus.UNKNOWN, SendStatus.SENDING, SendStatus.SENT), migrated.map { it.status })
                assertEquals(listOf(10L, 20L, 30L), migrated.map { it.createdAtMillis })
                assertEquals("old-server", migrated.last().serverId)
                assertEquals(7L, migrated.last().sequence)
                assertTrue(migrated.all { it.serverInstanceId == null })
                assertEquals(1, outbox.initialize())
                assertEquals(listOf("unknown", "sending"), rows(cache).map { it.clientMessageId })
                cache.importMessage("alice", "demo", message(1))
                assertEquals(3, rows(cache).size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldHttpSnapshotAfterLiveMessageNeverDeletesNewerRow() = runBlocking {
        memory().use { db ->
            val cache = MessageCacheStore(db, OutboxStore(db))
            cache.importMessage("alice", "demo", message(2))
            cache.importSnapshot("alice", "demo", "run-A", listOf(message(1)))
            cache.importSnapshot("alice", "demo", "run-A", emptyList())
            assertEquals(listOf(1L, 2L), rows(cache).map { it.sequence })
        }
    }
    @Test fun concurrentSnapshotAndRepeatedEventsHaveOneRowPerServerId() = runBlocking {
        memory().use { db ->
            val cache = MessageCacheStore(db, OutboxStore(db))
            coroutineScope {
                (1..12).map { launch(Dispatchers.IO) {
                    cache.importSnapshot("alice", "demo", "run-A", listOf(message(1), message(2)))
                    cache.importMessage("alice", "demo", message(3))
                } }.joinAll()
            }
            assertEquals(listOf(1L, 2L, 3L), rows(cache).map { it.sequence })
        }
    }
    @Test fun sameSequenceAndServerIdAfterRestartHaveSeparateKeysAndPreserveHistory() = runBlocking {
        memory().use { db ->
            val cache = MessageCacheStore(db, OutboxStore(db))
            cache.importMessage("alice", "demo", message(1))
            cache.importSnapshot("alice", "demo", "run-B", emptyList())
            assertEquals(1, rows(cache).size)
            cache.importMessage("alice", "demo", message(1, instance = "run-B"))
            val rows = rows(cache)
            assertEquals(listOf("run-A", "run-B"), rows.map { it.serverInstanceId })
            assertEquals(2, rows.map { it.stableKey }.toSet().size)
        }
    }
    @Test fun ownerRoomIsolationAlsoAppliesToLateOldAccountHttpResult() = runBlocking {
        memory().use { db ->
            val cache = MessageCacheStore(db, OutboxStore(db))
            cache.importMessage("alice", "demo", message(1))
            assertTrue(rows(cache, "bob").isEmpty())
            cache.importMessage("bob", "demo", message(2))
            cache.importMessage("alice", "demo", message(3))
            assertEquals(listOf(1L, 3L), rows(cache).map { it.sequence })
            assertEquals(listOf(2L), rows(cache, "bob").map { it.sequence })
            assertTrue(rows(cache, room = "other").isEmpty())
        }
    }
    @Test fun ownReceiptAndCacheCommitReplacePendingWithSameUiKeyAndSentWinsLateFailure() = runBlocking {
        memory().use { db ->
            val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
            val intent = OutboxEntry("alice", "demo", "own", "body-1", createdAtMillis = 20)
            outbox.enqueue(intent)
            val pendingKey = rows(cache).single().stableKey
            cache.importMessage("alice", "demo", message(1, "alice", clientId = "own"))
            cache.importMessage("alice", "demo", message(1, "alice", clientId = "own"))
            assertEquals(pendingKey, rows(cache).single().stableKey)
            assertEquals(SendStatus.SENT, rows(cache).single().status)
            assertEquals(0, outbox.unconfirmed("alice", "demo", "own", SendStatus.UNKNOWN))
            assertNull(outbox.claimRetry("alice", "demo", "own"))
            assertEquals("run-A", outbox.load("alice", "demo").single().serverInstanceId)
        }
    }
    @Test fun cacheFailureRollsBackOwnReceiptAndOtherSnapshotRows() = runBlocking {
        memory().use { db ->
            val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
            cache.importMessage("alice", "demo", message(2))
            outbox.enqueue(OutboxEntry("alice", "demo", "own", "body-1"))
            assertTrue(runCatching { cache.importSnapshot("alice", "demo", "run-A",
                listOf(message(1, "alice", clientId = "own"), message(2).copy(text = "corrupt"))) }.isFailure)
            assertEquals(SendStatus.SENDING, outbox.load("alice", "demo").single().status)
            assertEquals(2, rows(cache).size)
            assertEquals("body-2", rows(cache).first().text)
        }
    }
    @Test fun liveFlowNeverExposesPendingAndAcceptedCopiesOfOwnMessageInOneEmission() = runBlocking {
        memory().use { db ->
            val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
            outbox.enqueue(OutboxEntry("alice", "demo", "own", "body-1"))
            val ready = CompletableDeferred<Unit>()
            val observed = async(Dispatchers.IO) { cache.observe("alice", "demo").onEach { ready.complete(Unit) }.take(2).toList() }
            ready.await()
            cache.importMessage("alice", "demo", message(1, "alice", clientId = "own"))
            val emissions = withTimeout(5000) { observed.await() }
            assertTrue(emissions.all { it.size == 1 })
            assertEquals(listOf(SendStatus.SENDING, SendStatus.SENT), emissions.map { it.single().status })
            assertEquals(1, emissions.map { it.single().stableKey }.toSet().size)
        }
    }
    @Test fun restartCannotOverwriteOldSentReceiptWithNewServerAcceptance() = runBlocking {
        memory().use { db ->
            val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
            outbox.enqueue(OutboxEntry("alice", "demo", "own", "body-1", status = SendStatus.UNKNOWN))
            cache.importSnapshot("alice", "demo", "run-A", listOf(message(1, "alice", clientId = "own")))
            val oldReceipt = outbox.load("alice", "demo").single()
            cache.importMessage("alice", "demo", message(1, "alice", "run-B", "own").copy(id = "new-server"))
            assertEquals(oldReceipt, outbox.load("alice", "demo").single())
            assertEquals(2, rows(cache).size)
            assertEquals(2, rows(cache).map { it.stableKey }.toSet().size)
        }
    }
    @Test fun logicalIdAndSequenceConflictsAreRejectedWithoutOverwrite() = runBlocking {
        memory().use { db ->
            val cache = MessageCacheStore(db, OutboxStore(db))
            val original = message(1)
            cache.importMessage("alice", "demo", original)
            assertTrue(runCatching { cache.importMessage("alice", "demo", original.copy(id = "another", sequence = 2)) }.isFailure)
            assertTrue(runCatching { cache.importMessage("alice", "demo", original.copy(id = "another", clientMessageId = "another")) }.isFailure)
            assertEquals(original, db.messages().find("alice", "demo", "run-A", original.id)?.message())
            assertEquals(1, rows(cache).size)
        }
    }
    @Test fun invalidSnapshotRoomOrInstanceIsRejectedWithoutCacheChanges() = runBlocking {
        memory().use { db ->
            val cache = MessageCacheStore(db, OutboxStore(db))
            assertTrue(runCatching { cache.importSnapshot("alice", "demo", "run-A", listOf(message(1).copy(roomId = "other"))) }.isFailure)
            assertTrue(runCatching { cache.importSnapshot("alice", "demo", "run-B", listOf(message(1))) }.isFailure)
            assertTrue(rows(cache).isEmpty())
        }
    }
    @Test fun pendingRowsFollowServerSequenceAndRemainSeparateFromOtherSenderSameClientId() = runBlocking {
        memory().use { db ->
            val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
            outbox.enqueue(OutboxEntry("alice", "demo", "second", "pending two", createdAtMillis = 20))
            outbox.enqueue(OutboxEntry("alice", "demo", "first", "pending one", createdAtMillis = 10))
            cache.importMessage("alice", "demo", message(2, clientId = "first"))
            cache.importMessage("alice", "demo", message(1))
            assertEquals(listOf("body-1", "body-2", "pending one", "pending two"), rows(cache).map { it.text })
        }
    }
    @Test fun fileDatabaseReopenShowsReceivedRowsWithoutNetwork() = runBlocking {
        val name = "cache-test-${UUID.randomUUID()}.db"
        try {
            Room.databaseBuilder(context, OutboxDatabase::class.java, name).build().use { db ->
                MessageCacheStore(db, OutboxStore(db)).importMessage("alice", "demo", message(1))
            }
            Room.databaseBuilder(context, OutboxDatabase::class.java, name).build().use { db ->
                val cache = MessageCacheStore(db, OutboxStore(db))
                assertEquals("body-1", rows(cache).single().text)
                assertTrue(rows(cache, "bob").isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
}
