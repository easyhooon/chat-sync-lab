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
    @Test fun bothNotificationsLostRetryKeepsIdTextAndOneRow() {
        val pending = ChatState(connected = true, messages = listOf(MessageRow("client-1", "alice", "hello")))
        val unknown = recordSendError(pending, "client-1", SendStatus.UNKNOWN, "timeout")
        val request = retryRequest(unknown, "client-1")
        assertEquals(SendMessage("client-1", "hello"), request)
        val sending = unknown.copy(messages = markUnconfirmed(unknown.messages, "client-1", SendStatus.SENDING))
        val result = acceptMessage(sending, message)
        assertEquals(1, result.messages.size); assertEquals(SendStatus.SENT, result.messages.single().status)
        assertNull(result.error)
    }
    @Test fun websocketAcceptanceBeforeHttpTimeoutHasNoUnknownRetry() {
        val pending = ChatState(connected = true, messages = listOf(MessageRow("client-1", "alice", "hello")))
        val accepted = acceptMessage(pending, message)
        val timedOut = recordSendError(accepted, "client-1", SendStatus.UNKNOWN, "timeout")
        assertEquals(SendStatus.SENT, timedOut.messages.single().status)
        assertNull(timedOut.error); assertNull(retryRequest(timedOut, "client-1"))
    }
    @Test fun retryCannotClaimSendingOrOtherUserOrDisconnectedRow() {
        val unknown = ChatState(connected = true, messages = listOf(MessageRow("client-1", "alice", "hello", status = SendStatus.UNKNOWN)))
        assertNotNull(retryRequest(unknown, "client-1"))
        assertNull(retryRequest(unknown.copy(connected = false), "client-1"))
        assertNull(retryRequest(unknown.copy(user = "bob"), "client-1"))
        val claimed = unknown.copy(messages = markUnconfirmed(unknown.messages, "client-1", SendStatus.SENDING))
        assertNull(retryRequest(claimed, "client-1"))
    }
    @Test fun racingEchoWhileRetryInFlightWinsOverRetryHttpFailure() {
        val state = ChatState(connected = true, messages = listOf(MessageRow("client-1", "alice", "hello")))
        val accepted = acceptMessage(state, message)
        val result = recordSendError(accepted, "client-1", SendStatus.UNKNOWN, "retry timeout")
        assertEquals(accepted, result); assertEquals(1, result.messages.size)
    }
    @Test fun staleOutboxEmissionCannotUndoLiveAcceptance() {
        val accepted = mergeMessage(emptyList(), message)
        val restored = listOf(MessageRow("client-1", "alice", "hello", status = SendStatus.UNKNOWN))
        assertEquals(accepted, mergeOutbox(accepted, restored))
    }
    @Test fun storedSentReceiptIsNotInventedAsCurrentServerHistory() {
        val sent = mergeMessage(emptyList(), message)
        assertTrue(mergeOutbox(emptyList(), sent).isEmpty())
        val pending = MessageRow("pending", "alice", "retry later", status = SendStatus.UNKNOWN)
        assertEquals(listOf(pending), mergeOutbox(emptyList(), sent + pending))
    }
    @Test fun sameClientIdInAnotherRoomIsNotMergedOrRetried() {
        val otherRoom = MessageRow("client-1", "alice", "other", status = SendStatus.UNKNOWN, roomId = "other")
        val result = mergeMessage(listOf(otherRoom), message)
        assertEquals(2, result.size)
        assertNull(retryRequest(ChatState(connected = true, messages = listOf(otherRoom)), "client-1"))
    }
}
