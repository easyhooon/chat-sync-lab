package dev.chatlab

import org.junit.Assert.*
import org.junit.Test

class ChatStateTest {
    private val pending = MessageRow("id", "alice", "hello", status = SendStatus.UNKNOWN)
    private val sent = pending.copy(serverId = "server", sequence = 1, status = SendStatus.SENT, serverInstanceId = "instance")

    @Test fun databaseEmissionReplacesListWithoutAnIndependentMemoryMerge() {
        val state = ChatState(sendRows = listOf(pending))
        assertEquals(listOf(sent), withOutboxRows(state, listOf(sent)).sendRows)
        assertTrue(withOutboxRows(state, emptyList()).sendRows.isEmpty())
    }
    @Test fun ownAcceptedDatabaseRowClearsOnlyItsCorrelatedError() {
        val state = ChatState(error = UiError("timeout", "id"))
        assertNull(withOutboxRows(state, listOf(sent)).error)
        assertNotNull(withOutboxRows(state.copy(error = UiError("other", "other")), listOf(sent)).error)
    }
    @Test fun anotherSenderOrRoomDoesNotClearSendError() {
        val state = ChatState(error = UiError("timeout", "id"))
        assertNotNull(withOutboxRows(state, listOf(sent.copy(senderId = "bob"))).error)
        assertNotNull(withOutboxRows(state, listOf(sent.copy(roomId = "other"))).error)
    }
    @Test fun cacheReceiptDoesNotHideConnectionOrStorageError() {
        val state = ChatState(error = UiError("network offline"))
        assertEquals(state.error, withOutboxRows(state, listOf(sent)).error)
    }
    @Test fun lateFailureCannotDisplayUnknownAfterAcceptedDatabaseRow() {
        val state = ChatState(sendRows = listOf(sent))
        assertEquals(state, withSendError(state, "id", "timeout"))
        val unresolved = state.copy(sendRows = listOf(pending))
        assertEquals(listOf(pending), withSendError(unresolved, "id", "timeout").sendRows)
        assertNotNull(withSendError(unresolved, "id", "timeout").error)
    }
    @Test fun manualRetryKeepsIdAndTextAndRequiresOwnConnectedUnknown() {
        val state = ChatState(connected = true, sendRows = listOf(pending))
        assertEquals(SendMessage("id", "hello"), retryRequest(state, "id"))
        assertNull(retryRequest(state.copy(connected = false), "id"))
        assertNull(retryRequest(state.copy(user = "bob"), "id"))
        assertNull(retryRequest(state.copy(roomId = "other"), "id"))
        assertNull(retryRequest(state.copy(sendRows = listOf(pending.copy(status = SendStatus.SENDING))), "id"))
        assertNull(retryRequest(state.copy(sendRows = listOf(sent)), "id"))
    }
}
