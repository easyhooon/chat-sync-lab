package dev.chatlab

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxDatabaseTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun memory() = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
    private fun accepted(entry: OutboxEntry, serverId: String = "server-${entry.clientMessageId}") =
        Message(serverId, entry.clientMessageId, entry.roomId, entry.userId, entry.text, 1, "2026-10-05T00:00:00Z")

    @Test fun reopeningPreservesUnknownAndRecoversAbandonedSendingWithSameIdentity() = runBlocking {
        val name = "outbox-test-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, OutboxDatabase::class.java, name).build()
        val unknown = OutboxEntry("alice", "demo", UUID.randomUUID().toString(), "unknown body", SendStatus.UNKNOWN, 10)
        val sending = unknown.copy(clientMessageId = UUID.randomUUID().toString(), text = "interrupted body", status = SendStatus.SENDING, createdAtMillis = 20)
        val sent = unknown.copy(clientMessageId = UUID.randomUUID().toString(), text = "already accepted", status = SendStatus.SENT, createdAtMillis = 30, serverId = "original-server", sequence = 9)
        var database = open()
        try {
            val first = OutboxStore(database)
            first.initialize()
            listOf(unknown, sending, sent).forEach { first.enqueue(it) }
            database.close()
            database = open()
            val restarted = OutboxStore(database)
            assertEquals(1, restarted.initialize())
            assertEquals(listOf(unknown, sending.copy(status = SendStatus.UNKNOWN), sent), restarted.load("alice", "demo"))
            assertEquals(sending.copy(status = SendStatus.SENDING), restarted.claimRetry("alice", "demo", sending.clientMessageId))
            assertNull(restarted.claimRetry("alice", "demo", sending.clientMessageId))
            assertNull(restarted.claimRetry("alice", "demo", sent.clientMessageId))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun accountAndRoomArePartOfEveryReadClaimAndAcceptanceKey() = runBlocking {
        val database = memory()
        try {
            val store = OutboxStore(database)
            store.initialize()
            val id = UUID.randomUUID().toString()
            val alice = OutboxEntry("alice", "demo", id, "Alice body", SendStatus.UNKNOWN)
            val bob = alice.copy(userId = "bob", text = "Bob body")
            val otherRoom = alice.copy(roomId = "other", text = "Other room body")
            listOf(alice, bob, otherRoom).forEach { store.enqueue(it) }
            assertEquals(listOf(alice), store.load("alice", "demo"))
            assertEquals(listOf(bob), store.load("bob", "demo"))
            assertEquals(listOf(otherRoom), store.load("alice", "other"))
            assertEquals(0, store.accept("bob", "demo", accepted(alice)))
            assertEquals(0, store.accept("alice", "other", accepted(alice)))
            assertEquals(alice.copy(status = SendStatus.SENDING), store.claimRetry("alice", "demo", id))
            assertEquals(1, store.accept("alice", "demo", accepted(alice)))
            assertEquals(SendStatus.SENT, store.load("alice", "demo").single().status)
            assertEquals(listOf(bob), store.load("bob", "demo"))
            assertEquals(listOf(otherRoom), store.load("alice", "other"))
        } finally { database.close() }
    }

    @Test fun concurrentRetryClicksClaimExactlyOneExistingRow() = runBlocking {
        val database = memory()
        try {
            val store = OutboxStore(database)
            store.initialize()
            val entry = OutboxEntry("alice", "demo", UUID.randomUUID().toString(), "retry unchanged", SendStatus.UNKNOWN)
            store.enqueue(entry)
            val claimed = coroutineScope {
                List(24) { async(Dispatchers.IO) { store.claimRetry("alice", "demo", entry.clientMessageId) } }.awaitAll().filterNotNull()
            }
            assertEquals(listOf(entry.copy(status = SendStatus.SENDING)), claimed)
            assertEquals(listOf(entry.copy(status = SendStatus.SENDING)), store.load("alice", "demo"))
        } finally { database.close() }
    }

    @Test fun duplicateAcceptanceAndRacingLateFailuresNeverRegressSent() = runBlocking {
        val database = memory()
        try {
            val store = OutboxStore(database)
            store.initialize()
            repeat(20) {
                val entry = OutboxEntry("alice", "demo", UUID.randomUUID().toString(), "accepted $it")
                store.enqueue(entry)
                val message = accepted(entry)
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { store.accept("alice", "demo", message) },
                        async(Dispatchers.IO) { store.unconfirmed("alice", "demo", entry.clientMessageId, SendStatus.UNKNOWN) },
                        async(Dispatchers.IO) { store.accept("alice", "demo", message) },
                    ).awaitAll()
                }
                assertEquals(0, store.unconfirmed("alice", "demo", entry.clientMessageId, SendStatus.FAILED))
                val persisted = database.outbox().find("alice", "demo", entry.clientMessageId)!!
                assertEquals(SendStatus.SENT, persisted.status)
                assertEquals(message.id, persisted.serverId)
                assertEquals(message.sequence, persisted.sequence)
            }
            assertEquals(20, store.load("alice", "demo").size)
        } finally { database.close() }
    }

    @Test fun initializeDoesNotResetActiveSendingOnReconnect() = runBlocking {
        val database = memory()
        try {
            val store = OutboxStore(database)
            store.initialize()
            val entry = OutboxEntry("alice", "demo", UUID.randomUUID().toString(), "still active")
            store.enqueue(entry)
            assertEquals(0, store.initialize())
            assertEquals(listOf(entry), store.load("alice", "demo"))
        } finally { database.close() }
    }

    @Test fun snapshotResolvesOwnRowsWithoutChangingOtherAccountsOrUnconfirmedRows() = runBlocking {
        val database = memory()
        try {
            val store = OutboxStore(database)
            store.initialize()
            val own = OutboxEntry("alice", "demo", UUID.randomUUID().toString(), "resolve", SendStatus.UNKNOWN)
            val unmatched = own.copy(clientMessageId = UUID.randomUUID().toString(), text = "not in history")
            val bob = own.copy(userId = "bob", text = "Bob pending")
            listOf(own, unmatched, bob).forEach { store.enqueue(it) }
            store.acceptSnapshot("alice", "demo", listOf(accepted(own), accepted(bob)))
            assertEquals(SendStatus.SENT, database.outbox().find("alice", "demo", own.clientMessageId)!!.status)
            assertEquals(unmatched, database.outbox().find("alice", "demo", unmatched.clientMessageId))
            assertEquals(listOf(bob), store.load("bob", "demo"))
        } finally { database.close() }
    }

    @Test fun enqueueCommitIsVisibleFromAnotherConnectionBeforeNetworkMayStart() = runBlocking {
        val name = "outbox-test-${UUID.randomUUID()}.db"
        val writer = Room.databaseBuilder(context, OutboxDatabase::class.java, name).build()
        val reader = Room.databaseBuilder(context, OutboxDatabase::class.java, name).build()
        try {
            val store = OutboxStore(writer)
            store.initialize()
            val entry = OutboxEntry("alice", "demo", UUID.randomUUID().toString(), "durable before POST")
            store.enqueue(entry)
            assertEquals(entry, reader.outbox().find("alice", "demo", entry.clientMessageId))
        } finally { reader.close(); writer.close(); context.deleteDatabase(name) }
    }
    @Test fun cancelledRetryTransactionRollsBackTheClaim() = runBlocking {
        val database = memory()
        try {
            val store = OutboxStore(database)
            store.initialize()
            val entry = OutboxEntry("alice", "demo", UUID.randomUUID().toString(), "cancelled claim", SendStatus.UNKNOWN)
            store.enqueue(entry)
            val claimed = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                database.withTransaction {
                    assertNotNull(store.claimRetry("alice", "demo", entry.clientMessageId))
                    claimed.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(5000) { claimed.await() }
            job.cancelAndJoin()
            assertEquals(listOf(entry), store.load("alice", "demo"))
        } finally { database.close() }
    }
}
