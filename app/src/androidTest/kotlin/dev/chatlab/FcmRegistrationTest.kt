package dev.chatlab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FcmRegistrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun bindingNeedsSdkReceiptAndRebindingInvalidatesIt() {
        val name="fcm-test-${UUID.randomUUID()}"
        val store=FcmBindingStore(context,name)
        try {
            assertNull(store.confirmed())
            assertTrue(runCatching{store.recordSdkRegistration("synthetic-project","synthetic-fid")}.isFailure)
            store.bindForRegistration("alice")
            assertEquals("alice",store.boundAccount());assertNull(store.confirmed())
            val receipt=store.recordSdkRegistration("synthetic-project","synthetic-fid")
            assertEquals(receipt,store.confirmed())
            assertTrue(receipt.registeredAtMillis>0)
            assertFalse(receipt.toString().contains("synthetic-fid"))
            store.bindForRegistration("bob")
            assertEquals("bob",store.boundAccount());assertNull(store.confirmed())
            assertEquals("bob",store.recordSdkRegistration("synthetic-project","rotated-synthetic-fid").ownerId)
        } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun defaultBuildCannotRegisterOrInitializeFirebase() {
        val name="fcm-disabled-${UUID.randomUUID()}"
        val store=FcmBindingStore(context,name)
        try {
            assertFalse(BuildConfig.CHAT_FCM_ENABLED)
            assertTrue(FirebaseApp.getApps(context).isEmpty())
            assertTrue(runCatching{FcmRegistrationController(context,store).registerForApprovedLocalTest("alice")}.isFailure)
            assertNull(store.boundAccount());assertNull(store.confirmed())
            assertTrue(FirebaseApp.getApps(context).isEmpty())
        } finally { context.deleteSharedPreferences(name) }
    }
}
