package dev.chatlab

import org.junit.Assert.*
import org.junit.Test

class ChatStateTest {
    private val message = Message("server-1", "client-1", "demo", "alice", "hello", 1, "2026-10-04T00:00:00Z")
    @Test fun echoAndPostResponseBecomeOneAcceptedRow() {
        val pending = listOf(MessageRow("client-1", "alice", "hello"))
        val result = mergeMessage(mergeMessage(pending, message), message)
        assertEquals(1, result.size); assertEquals(SendStatus.SENT, result.single().status)
    }
    @Test fun lateHttpFailureCannotUndoSocketAcceptance() {
        val result = markUnconfirmed(mergeMessage(emptyList(), message), "client-1", SendStatus.UNKNOWN)
        assertEquals(SendStatus.SENT, result.single().status)
    }
    @Test fun snapshotResolvesUnknownAndKeepsUnconfirmedRows() {
        val rows = listOf(MessageRow("client-1", "alice", "hello", status = SendStatus.UNKNOWN), MessageRow("unsent", "alice", "maybe", status = SendStatus.UNKNOWN))
        val result = mergeSnapshot(rows, listOf(message))
        assertEquals(2, result.size); assertEquals(SendStatus.SENT, result.first().status); assertEquals(SendStatus.UNKNOWN, result.last().status)
    }
    @Test fun newServerSnapshotDoesNotRetainOldProcessHistory() {
        assertTrue(mergeSnapshot(mergeMessage(emptyList(), message), emptyList()).isEmpty())
    }
    @Test fun anotherSenderWithSameClientIdIsNotMergedIntoOwnPending() {
        val rows = listOf(MessageRow("client-1", "alice", "hello"))
        assertEquals(2, mergeMessage(rows, message.copy(senderId = "bob")).size)
    }
    @Test fun lateHttpFailureDoesNotShowUncertaintyAfterSocketAcceptance() {
        val state = acceptMessage(ChatState(messages = listOf(MessageRow("client-1", "alice", "hello"))), message)
        val result = recordSendError(state, "client-1", SendStatus.UNKNOWN, "unknown")
        assertNull(result.error); assertEquals(SendStatus.SENT, result.messages.single().status)
    }
    @Test fun delayedSocketAcceptanceClearsOnlyItsOwnSendError() {
        val pending = ChatState(messages = listOf(MessageRow("client-1", "alice", "hello")))
        val uncertain = recordSendError(pending, "client-1", SendStatus.UNKNOWN, "unknown")
        assertNotNull(uncertain.error)
        val result = acceptMessage(uncertain, message)
        assertNull(result.error); assertEquals(SendStatus.SENT, result.messages.single().status)
    }
}
