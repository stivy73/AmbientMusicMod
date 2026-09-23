package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.kieronquinn.app.ambientmusicmod.PACKAGE_NAME_PAM
import com.kieronquinn.app.pixelambientmusic.IRecognitionService
import com.kieronquinn.app.ambientmusicmod.utils.extensions.suspendCancellableCoroutineWithTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

interface AmbientServiceRepository {

    suspend fun getService(): IRecognitionService?

}

class AmbientServiceRepositoryImpl(
    private val context: Context
): AmbientServiceRepository {

    companion object {
        private const val SERVICE_CONNECT_TIMEOUT = 2500L
    }

    private var service: IRecognitionService? = null
    private var serviceConnection: ServiceConnection? = null
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
            serviceConnection = null
            service = null
            context.unbindService(staleConnection)
        }
        suspendCancellableCoroutineWithTimeout<IRecognitionService?>(SERVICE_CONNECT_TIMEOUT) { continuation ->
            val serviceConnection = object: ServiceConnection {
                override fun onServiceConnected(component: ComponentName, binder: IBinder) {
                    if(!continuation.isActive || !binder.isBinderAlive) return
                    serviceConnection = this
                    val service = IRecognitionService.Stub.asInterface(binder)
                    this@AmbientServiceRepositoryImpl.service = service
                    continuation.resume(service)
                }

                override fun onServiceDisconnected(component: ComponentName) {
                    if(this@AmbientServiceRepositoryImpl.serviceConnection === this) {
                        serviceConnection = null
                        service = null
                    }
                    if(continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation {
                if(this@AmbientServiceRepositoryImpl.serviceConnection === serviceConnection) {
                    this@AmbientServiceRepositoryImpl.serviceConnection = null
                    service = null
                    context.unbindService(serviceConnection)
                }
            }
            this@AmbientServiceRepositoryImpl.serviceConnection = serviceConnection
            if(!context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                this@AmbientServiceRepositoryImpl.serviceConnection = null
                if(continuation.isActive) continuation.resume(null)
            }
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
