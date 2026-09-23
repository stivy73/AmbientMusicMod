package com.kieronquinn.app.ambientmusicmod.repositories

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceMatchingTest {

    private val packageName = "com.kieronquinn.app.ambientmusicmod"
    private val className = "$packageName.service.LockscreenOverlayAccessibilityService"

    @Test fun fullyQualifiedClassMatches() {
        assertTrue(matchesAccessibilityService(packageName, className, packageName, className))
    }

    @Test fun shorthandClassMatches() {
        assertTrue(matchesAccessibilityService(
            packageName, ".service.LockscreenOverlayAccessibilityService", packageName, className
        ))
    }

    @Test fun otherPackageOrClassDoesNotMatch() {
        assertFalse(matchesAccessibilityService("other.package", className, packageName, className))
        assertFalse(matchesAccessibilityService(packageName, "OtherService", packageName, className))
    }
}
