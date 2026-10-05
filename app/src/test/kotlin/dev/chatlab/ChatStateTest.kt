package dev.chatlab

import org.junit.Assert.*
import org.junit.Test

class ChatStateTest {
    private val pending = MessageRow("id", "alice", "hello", status = SendStatus.UNKNOWN)
    private val sent = pending.copy(serverId = "server", sequence = 1, status = SendStatus.SENT, serverInstanceId = "instance")

    @Test fun databaseEmissionReplacesListWithoutAnIndependentMemoryMerge() {
        val state = ChatState(messages = listOf(pending))
        assertEquals(listOf(sent), withDatabaseRows(state, listOf(sent)).messages)
        assertTrue(withDatabaseRows(state, emptyList()).messages.isEmpty())
    }
    @Test fun ownAcceptedDatabaseRowClearsOnlyItsCorrelatedError() {
        val state = ChatState(error = UiError("timeout", "id"))
        assertNull(withDatabaseRows(state, listOf(sent)).error)
        assertNotNull(withDatabaseRows(state.copy(error = UiError("other", "other")), listOf(sent)).error)
    }
    @Test fun anotherSenderOrRoomDoesNotClearSendError() {
        val state = ChatState(error = UiError("timeout", "id"))
        assertNotNull(withDatabaseRows(state, listOf(sent.copy(senderId = "bob"))).error)
        assertNotNull(withDatabaseRows(state, listOf(sent.copy(roomId = "other"))).error)
    }
    @Test fun cacheReceiptDoesNotHideConnectionOrStorageError() {
        val state = ChatState(error = UiError("network offline"))
        assertEquals(state.error, withDatabaseRows(state, listOf(sent)).error)
    }
    @Test fun lateFailureCannotDisplayUnknownAfterAcceptedDatabaseRow() {
        val state = ChatState(messages = listOf(sent))
        assertEquals(state, withSendError(state, "id", "timeout"))
        val unresolved = state.copy(messages = listOf(pending))
        assertEquals(listOf(pending), withSendError(unresolved, "id", "timeout").messages)
        assertNotNull(withSendError(unresolved, "id", "timeout").error)
    }
    @Test fun manualRetryKeepsIdAndTextAndRequiresOwnConnectedUnknown() {
        val state = ChatState(connected = true, messages = listOf(pending))
        assertEquals(SendMessage("id", "hello"), retryRequest(state, "id"))
        assertNull(retryRequest(state.copy(connected = false), "id"))
        assertNull(retryRequest(state.copy(user = "bob"), "id"))
        assertNull(retryRequest(state.copy(roomId = "other"), "id"))
        assertNull(retryRequest(state.copy(messages = listOf(pending.copy(status = SendStatus.SENDING))), "id"))
        assertNull(retryRequest(state.copy(messages = listOf(sent)), "id"))
    }
}
