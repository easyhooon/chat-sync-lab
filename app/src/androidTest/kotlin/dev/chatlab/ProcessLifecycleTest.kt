package dev.chatlab

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessLifecycleTest {
    @Test fun activityRecreationDoesNotBecomeProcessBackgroundButRealStopDoes() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ChatApplication>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val session = application.foregroundSession
            withTimeout(5000) { while (!session.isForeground) delay(20) }
            val started = session.sessionGeneration
            scenario.recreate()
            delay(1000) // ProcessLifecycleOwner delays background dispatch to avoid configuration-change gaps.
            assertTrue(session.isForeground)
            assertEquals(started, session.sessionGeneration)
            scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(5000) { while (session.isForeground) delay(20) }
            assertTrue(session.sessionGeneration > started)
            scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(5000) { while (!session.isForeground) delay(20) }
            assertTrue(session.sessionGeneration > started)
        }
    }
}
