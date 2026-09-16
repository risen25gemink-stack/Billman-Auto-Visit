package com.billmanautovisit.app.camera

import android.accessibilityservice.AccessibilityService

class CameraTapper(
    private val service: AccessibilityService
) {
    fun hasActiveWindow(): Boolean = service.rootInActiveWindow != null
}
