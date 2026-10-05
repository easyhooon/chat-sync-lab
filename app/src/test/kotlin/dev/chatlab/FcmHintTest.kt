package dev.chatlab

import org.junit.Assert.*
import org.junit.Test

class FcmHintTest {
    private fun data() = mapOf("kind" to "catch_up", "recipientId" to "alice", "roomId" to "demo", "serverInstanceId" to "run-A", "throughSequence" to "120")
    @Test fun dataHintIsBoundedAndRejectsBadSequenceOrMissingScope() {
        assertEquals(PushEnvelope("alice", "demo", "run-A", 120), parseFcmHint(data()))
        for (bad in listOf(data()-"recipientId", data()+ ("throughSequence" to "-1"), data()+ ("throughSequence" to "bad"), data()+("kind" to "notification"), data()+ ("serverInstanceId" to "")))
            assertTrue(runCatching { parseFcmHint(bad) }.isFailure)
    }
    @Test fun registrationReceiptNeverPrintsIdentifier() {
        val receipt = RegisteredInstallation("alice", "test-project", "synthetic-private-fid", 1)
        assertFalse(receipt.toString().contains("synthetic-private-fid"))
    }
}
