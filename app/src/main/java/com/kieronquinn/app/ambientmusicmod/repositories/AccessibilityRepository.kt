package com.kieronquinn.app.ambientmusicmod.repositories

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kieronquinn.app.ambientmusicmod.BuildConfig
import com.kieronquinn.app.ambientmusicmod.service.LockscreenOverlayAccessibilityService
import com.kieronquinn.app.ambientmusicmod.ui.activities.MainActivity
import com.kieronquinn.app.ambientmusicmod.utils.extensions.getSettingAsFlow
import com.kieronquinn.app.ambientmusicmod.utils.extensions.whenCreated
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

interface AccessibilityRepository {

    val accessibilityStartBus: Flow<Unit>
    val enabled: Flow<Boolean>

    fun bringToFrontOnAccessibilityStart(fragment: Fragment)
    suspend fun onAccessibilityStarted()
    fun onAccessibilityStopped()

}

class AccessibilityRepositoryImpl(context: Context): AccessibilityRepository {

    companion object {
        private val COMPONENT_ACCESSIBILITY_SERVICE = ComponentName(
            BuildConfig.APPLICATION_ID, LockscreenOverlayAccessibilityService::class.java.name
        )
    }

    override val accessibilityStartBus = MutableSharedFlow<Unit>()
    private val lifecycleChange = MutableStateFlow(0)
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    override val enabled = merge(
        context.getSettingAsFlow(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ) { Unit },
        lifecycleChange.map { Unit }
    ).map {
        accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            info.resolveInfo?.serviceInfo?.let { service ->
                matchesAccessibilityService(
                    service.packageName, service.name,
                    COMPONENT_ACCESSIBILITY_SERVICE.packageName,
                    COMPONENT_ACCESSIBILITY_SERVICE.className
                )
            } ?: false
        }
    }.distinctUntilChanged()

    override fun bringToFrontOnAccessibilityStart(fragment: Fragment) {
        fragment.viewLifecycleOwner.whenCreated {
            this@AccessibilityRepositoryImpl.accessibilityStartBus.collect {
                fragment.bringToFront()
            }
        }
    }

    override suspend fun onAccessibilityStarted() {
        lifecycleChange.update { it + 1 }
        accessibilityStartBus.emit(Unit)
    }

    override fun onAccessibilityStopped() {
        lifecycleChange.update { it + 1 }
    }

    private fun Fragment.bringToFront() {
        startActivity(Intent(requireContext(), MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        })
    }

}

internal fun matchesAccessibilityService(
    packageName: String,
    className: String,
    expectedPackage: String,
    expectedClass: String
): Boolean {
    if(packageName != expectedPackage) return false
    val resolvedClass = when {
        className.startsWith(".") -> packageName + className
        '.' !in className -> "$packageName.$className"
        else -> className
    }
    return resolvedClass == expectedClass
}
