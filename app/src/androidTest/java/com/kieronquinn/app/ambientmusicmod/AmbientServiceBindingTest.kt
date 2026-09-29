package com.kieronquinn.app.ambientmusicmod

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kieronquinn.app.ambientmusicmod.repositories.AmbientServiceRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AmbientServiceBindingTest {
    @Test fun rejectedBindDoesNotAttemptToUnbind() = runBlocking {
        val context = object: ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            var unbinds = 0
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int) = false
            override fun unbindService(conn: ServiceConnection) { unbinds++ }
        }
        assertNull(AmbientServiceRepositoryImpl(context).getService())
        assertEquals(0, context.unbinds)
    }

    @Test fun cancellationDuringBindUnbindsOnceAfterRegistration() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unbinds = AtomicInteger()
        val context = object: ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return true
            }
            override fun unbindService(conn: ServiceConnection) { unbinds.incrementAndGet() }
        }
        val call = async(Dispatchers.IO) { AmbientServiceRepositoryImpl(context).getService() }
        try {
            check(entered.await(3, TimeUnit.SECONDS))
            call.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(3000) { call.join() }
        assertEquals(1, unbinds.get())
    }
}
