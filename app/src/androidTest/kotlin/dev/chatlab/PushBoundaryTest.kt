package dev.chatlab

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushBoundaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun message() = Message("server-1", "own", "demo", "alice", "body", 1, "2026-10-05T00:00:00Z", "run-A")
    @Test fun delayedOriginalAccountResultAndDuplicatePushDoNotLeakToNewAccountOrUndoSent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        val outbox = OutboxStore(db); val cache = MessageCacheStore(db, outbox)
        val repository = ChatRepository(outbox, cache); val session = ForegroundChatSession(repository)
        try {
            outbox.enqueue(OutboxEntry("alice", "demo", "own", "body", SendStatus.UNKNOWN))
            session.connect("bob") // Selected UI changes while original Alice result is still in flight.
            session.onBackground()
            val original = message()
            coroutineScope { listOf(
                async { repository.receive("alice", "demo", original) }, // An already in-flight original-scope callback.
                async { LocalPushAdapter(repository).receive("alice", PushEnvelope("alice", "demo", "run-A", 1, original)) },
                async { LocalPushAdapter(repository).receive("alice", PushEnvelope("alice", "demo", "run-A", 1, original)) },
            ).awaitAll() }
            assertEquals(1, cache.observe("alice", "demo").first().size)
            assertTrue(cache.observe("bob", "demo").first().isEmpty())
            assertEquals("bob", session.state.value.user)
            assertTrue(session.state.value.sendRows.isEmpty())
            assertFalse(session.isForeground)
            assertEquals(SendStatus.SENT, outbox.load("alice", "demo").single().status)
            assertEquals(0, outbox.unconfirmed("alice", "demo", "own", SendStatus.UNKNOWN))
        } finally { session.close(); db.close() }
    }
    @Test fun boundAccountRoomAndServerMetadataAreValidatedBeforePushMerge() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        val cache = MessageCacheStore(db, OutboxStore(db)); val repository = ChatRepository(OutboxStore(db), cache)
        try {
            val adapter = LocalPushAdapter(repository)
            val payload = PushEnvelope("alice", "demo", "run-A", 1, message())
            assertTrue(runCatching { adapter.receive("bob", payload) }.isFailure)
            assertTrue(runCatching { adapter.receive("alice", payload.copy(roomId = "other")) }.isFailure)
            assertTrue(runCatching { adapter.receive("alice", payload.copy(serverInstanceId = "run-B")) }.isFailure)
            assertTrue(runCatching { adapter.receive("alice", payload.copy(throughSequence = 2)) }.isFailure)
            assertTrue(cache.observe("alice", "demo").first().isEmpty())
            val hint = adapter.receive("alice", payload.copy(message = null))
            assertEquals(PushOutcome.NeedsCatchUp(CatchUpRequest("alice", "demo", "run-A", 1)), hint)
            assertTrue(cache.observe("alice", "demo").first().isEmpty())
        } finally { repository.close(); db.close() }
    }
    @Test fun processForegroundIsIdempotentAndBackgroundInvalidatesSessionWithoutStartingPushSocket() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OutboxDatabase::class.java).build()
        val repository = ChatRepository(OutboxStore(db), MessageCacheStore(db, OutboxStore(db)))
        val session = ForegroundChatSession(repository)
        try {
            session.onForeground()
            val foregroundGeneration = session.sessionGeneration
            session.onForeground() // Multiple UI owners / recreation must not open another session.
            assertEquals(foregroundGeneration, session.sessionGeneration)
            session.onBackground()
            assertFalse(session.isForeground)
            assertTrue(session.sessionGeneration > foregroundGeneration)
            val backgroundGeneration = session.sessionGeneration
            LocalPushAdapter(repository).receive("alice", PushEnvelope("alice", "demo", "run-A", 1, message()))
            assertEquals(backgroundGeneration, session.sessionGeneration)
            assertFalse(session.isForeground)
            assertFalse(session.state.value.connected)
        } finally { session.close(); db.close() }
    }
}
