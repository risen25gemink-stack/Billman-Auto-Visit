package com.billmanautovisit.app.screen

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class ScreenReader(
    private val service: AccessibilityService
) {
    fun currentRoot(): AccessibilityNodeInfo? = service.rootInActiveWindow
}
