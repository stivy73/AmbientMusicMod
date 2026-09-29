package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.pixelambientmusic.IRecognitionService
import com.kieronquinn.app.ambientmusicmod.utils.extensions.suspendCancellableCoroutineWithTimeout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

interface AmbientServiceRepository {

    suspend fun getService(): IRecognitionService?

}

class AmbientServiceRepositoryImpl(
    private val context: Context
): AmbientServiceRepository {

    companion object {
        private const val SERVICE_CONNECT_TIMEOUT = 2500L
        private const val TAG = "AmbientServiceRepository"
    }

    @Volatile private var service: IRecognitionService? = null
    @Volatile private var serviceConnection: BoundConnection? = null
    private val serviceLock = Mutex()

    private val serviceIntent by lazy {
        Intent("com.kieronquinn.app.pixelambientmusic.RECOGNITION_SERVICE").apply {
            `package` = PACKAGE_NAME_PAM
        }
    }

    override suspend fun getService() = serviceLock.withLock {
        if(!ApiRepository.assertCompatibility()) return@withLock null
        service?.let {
            if(!it.safePing()) return@let
            return@withLock it
        }
        serviceConnection?.let { staleConnection ->
            staleConnection.close()
        }
        suspendCancellableCoroutineWithTimeout<IRecognitionService?>(SERVICE_CONNECT_TIMEOUT) { continuation ->
            val connection = BoundConnection(continuation)
            continuation.invokeOnCancellation {
                connection.close()
            }
            serviceConnection = connection
            connection.bind()
        }
    }

    private inner class BoundConnection(
        private val continuation: CancellableContinuation<IRecognitionService?>
    ): ServiceConnection {
        // 0 = bindService has not returned, 1 = registered, 2 = closed.
        // Cancellation may run on another thread while bindService is still returning.
        private val state = AtomicInteger(0)

        fun bind() {
            val registered = try {
                context.bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)
            }catch (e: SecurityException) {
                Log.w(TAG, "Now Playing service binding was denied", e)
                false
            }
            if(!registered) {
                state.set(2)
                if(serviceConnection === this) serviceConnection = null
                if(continuation.isActive) continuation.resume(null)
                return
            }
            if(!state.compareAndSet(0, 1)) {
                // The continuation timed out while bindService was in progress.
                context.unbindService(this)
            }
        }

        fun close() {
            val previous = state.getAndSet(2)
            if(serviceConnection === this) {
                serviceConnection = null
                service = null
            }
            if(previous == 1) context.unbindService(this)
        }

        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            if(state.get() == 2 || serviceConnection !== this || !continuation.isActive) return
            if(!binder.isBinderAlive) {
                close()
                if(continuation.isActive) continuation.resume(null)
                return
            }
            val remote = IRecognitionService.Stub.asInterface(binder)
            service = remote
            continuation.resume(remote)
        }

        override fun onServiceDisconnected(component: ComponentName) {
            // Keep the registration until close() so Android can reconnect or we can unbind.
            if(serviceConnection === this) service = null
            if(continuation.isActive) {
                close()
                continuation.resume(null)
            }
        }

        override fun onBindingDied(component: ComponentName) {
            close()
            if(continuation.isActive) continuation.resume(null)
        }

        override fun onNullBinding(component: ComponentName) {
            close()
            if(continuation.isActive) continuation.resume(null)
        }
    }

    private fun IRecognitionService.safePing(): Boolean {
        return try {
            asBinder().isBinderAlive && ping()
        }catch (e: RemoteException){
            false
        }
    }

}
