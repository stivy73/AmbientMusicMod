package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.IShellProxy
import com.kieronquinn.app.ambientmusicmod.repositories.ShizukuServiceRepository.ShizukuServiceResponse
import com.kieronquinn.app.ambientmusicmod.repositories.ShizukuServiceRepository.ShizukuServiceResponse.FailureReason
import com.kieronquinn.app.ambientmusicmod.service.ShizukuService
import com.kieronquinn.app.ambientmusicmod.utils.extensions.suspendCancellableCoroutineWithTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

interface ShizukuServiceRepository {

    sealed class ShizukuServiceResponse<T> {
        data class Success<T>(val result: T): ShizukuServiceResponse<T>()
        data class Failed<T>(val reason: FailureReason): ShizukuServiceResponse<T>()

        enum class FailureReason {
            /**
             *  Shizuku is not bound, likely the user has not started it since rebooting
             */
            NO_BINDER,

            /**
             *  Permission to access Shizuku has not been granted
             */
            PERMISSION_DENIED,

            /**
             *  The service is not immediately available (only used in [runWithServiceIfAvailable])
             */
            NOT_AVAILABLE
        }

        /**
         *  Unwraps a result into either its value or null if it failed
         */
        fun unwrap(): T? {
            return (this as? Success)?.result
        }
    }

    val isReady: Flow<Boolean>
    suspend fun assertReady(): Boolean
    suspend fun <T> runWithService(block: (IShellProxy) -> T): ShizukuServiceResponse<T>
    fun <T> runWithServiceIfAvailable(block: (IShellProxy) -> T): ShizukuServiceResponse<T>
    fun disconnect()

}

class ShizukuServiceRepositoryImpl(
    private val settingsRepository: SettingsRepository,
    context: Context
): ShizukuServiceRepository {

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val SHIZUKU_TIMEOUT = 2500L
    }

    private enum class PermissionResult { GRANTED, DENIED, NO_BINDER }

    private val shizukuComponent by lazy {
        ComponentName(context, ShizukuService::class.java)
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(shizukuComponent).apply {
            daemon(false)
            debuggable(BuildConfig.DEBUG)
            version(BuildConfig.VERSION_CODE)
            processNameSuffix("shizuku")
        }
    }

    @Volatile private var serviceConnection: ServiceConnection? = null
    @Volatile private var service: IShellProxy? = null
    private val serviceLock = Mutex()
    private val runLock = Mutex()

    private val onConnectionChange = MutableStateFlow(0)

    override val isReady = onConnectionChange.map {
        assertReady()
    }.onEach {
        if(it) {
            callServiceOnCreate()
        }
    }

    override suspend fun assertReady(): Boolean {
        val result = runWithService {
            it.ping()
        }
        return result is ShizukuServiceResponse.Success
    }

    override suspend fun <T> runWithService(
        block: (IShellProxy) -> T
    ): ShizukuServiceResponse<T> = runLock.withLock {
        service?.let {
            if(!it.safePing()){
                clearService()
                return@let
            }
            return runOnService(it, block)
        }
        if(awaitShizuku() != true)
            return ShizukuServiceResponse.Failed(FailureReason.NO_BINDER)
        val permission = requestPermission()
        if(permission != null) return ShizukuServiceResponse.Failed(permission)
        val connected = getService() ?: return ShizukuServiceResponse.Failed(FailureReason.NO_BINDER)
        return runOnService(connected, block)
    }

    override fun <T> runWithServiceIfAvailable(
        block: (IShellProxy) -> T
    ): ShizukuServiceResponse<T> {
        return service?.let {
            if(!it.safePing()) {
                clearService()
                ShizukuServiceResponse.Failed(FailureReason.NOT_AVAILABLE)
            }else runOnService(it, block)
        } ?: ShizukuServiceResponse.Failed(FailureReason.NOT_AVAILABLE)
    }

    override fun disconnect() {
        clearService()
    }

    private fun clearService() {
        val connection = serviceConnection
        service = null
        serviceConnection = null
        onConnectionChange.update { it + 1 }
        connection?.let {
            try {
                Shizuku.unbindUserService(userServiceArgs, it, true)
            }catch (_: IllegalStateException){
                //Shizuku has already detached.
            }catch (_: RemoteException){
                //The binder has already died.
            }catch (_: IllegalArgumentException){
                //Binding did not complete before the client detached.
            }
        }
    }

    private fun <T> runOnService(
        connected: IShellProxy,
        block: (IShellProxy) -> T
    ): ShizukuServiceResponse<T> {
        return try {
            ShizukuServiceResponse.Success(block(connected))
        }catch (_: RemoteException){
            clearService()
            ShizukuServiceResponse.Failed(FailureReason.NO_BINDER)
        }
    }

    private suspend fun awaitShizuku() = suspendCancellableCoroutineWithTimeout<Boolean>(SHIZUKU_TIMEOUT) {
        var hasResumed = false
        if(Shizuku.pingBinder()) {
            if(!hasResumed) {
                hasResumed = true
                it.resume(true) //Already connected
            }
            return@suspendCancellableCoroutineWithTimeout
        }
        val listener = object: Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                if(!hasResumed) {
                    hasResumed = true
                    it.resume(true)
                }
            }
        }
        Shizuku.addBinderReceivedListener(listener)
        it.invokeOnCancellation {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    private suspend fun requestPermission(): FailureReason? {
        //pingBinder and checkSelfPermission are separate calls; the client can detach between them.
        val granted = try {
            if(!Shizuku.pingBinder()) return FailureReason.NO_BINDER
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }catch (_: IllegalStateException){
            return FailureReason.NO_BINDER
        }catch (_: RemoteException){
            return FailureReason.NO_BINDER
        }
        if(granted) return null
        return when(requestPermissionResult()) {
            PermissionResult.GRANTED -> null
            PermissionResult.DENIED -> FailureReason.PERMISSION_DENIED
            PermissionResult.NO_BINDER -> FailureReason.NO_BINDER
            null -> FailureReason.NO_BINDER
        }
    }

    private suspend fun requestPermissionResult() = suspendCancellableCoroutineWithTimeout<PermissionResult>(
        SHIZUKU_TIMEOUT
    ) { continuation ->
        val listener = object: Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if(requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if(continuation.isActive) continuation.resume(
                    if(grantResult == PackageManager.PERMISSION_GRANTED) PermissionResult.GRANTED
                    else PermissionResult.DENIED
                )
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        continuation.invokeOnCancellation {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }catch (_: IllegalStateException){
            Shizuku.removeRequestPermissionResultListener(listener)
            if(continuation.isActive) continuation.resume(PermissionResult.NO_BINDER)
        }catch (_: RemoteException){
            Shizuku.removeRequestPermissionResultListener(listener)
            if(continuation.isActive) continuation.resume(PermissionResult.NO_BINDER)
        }
    }

    private suspend fun getService() = serviceLock.withLock {
        suspendCancellableCoroutineWithTimeout<IShellProxy?>(SHIZUKU_TIMEOUT) { continuation ->
            val serviceConnection = object: ServiceConnection {
                override fun onServiceConnected(component: ComponentName, binder: IBinder) {
                    if(!continuation.isActive || !binder.isBinderAlive) return
                    serviceConnection = this
                    val service = IShellProxy.Stub.asInterface(binder)
                    this@ShizukuServiceRepositoryImpl.service = service
                    onConnectionChange.update { it + 1 }
                    continuation.resume(service)
                }

                override fun onServiceDisconnected(component: ComponentName) {
                    if(this@ShizukuServiceRepositoryImpl.serviceConnection === this) clearService()
                    if(continuation.isActive) continuation.resume(null)
                }
            }
            this@ShizukuServiceRepositoryImpl.serviceConnection = serviceConnection
            continuation.invokeOnCancellation {
                if(this@ShizukuServiceRepositoryImpl.serviceConnection === serviceConnection) {
                    clearService()
                }
            }
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }catch (_: IllegalStateException){
                clearService()
                if(continuation.isActive) continuation.resume(null)
            }catch (_: RemoteException){
                clearService()
                if(continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun IShellProxy.safePing(): Boolean {
        return try {
            asBinder().isBinderAlive && ping()
        }catch (e: RemoteException){
            false
        }
    }

    private suspend fun callServiceOnCreate() {
        //Notifications + Accessibility only became an issue on 13+
        val shouldSetPermissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val config = if(shouldSetPermissions){
            ShizukuService.createConfigBundle(
                !settingsRepository.hasSetNotificationPermission.get(),
                !settingsRepository.hasSetAccessibilityPermission.get()
            )
        }else Bundle.EMPTY
        val result = runWithService {
            it.onCreate(config)
        }
        if (result is ShizukuServiceResponse.Success && shouldSetPermissions) {
            settingsRepository.hasSetAccessibilityPermission.set(true)
            settingsRepository.hasSetNotificationPermission.set(true)
        }
    }

}
