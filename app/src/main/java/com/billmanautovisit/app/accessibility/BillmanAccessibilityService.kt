package com.billmanautovisit.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.billmanautovisit.app.camera.CameraTapper
import com.billmanautovisit.app.core.AppConfig
import com.billmanautovisit.app.engine.AutomationEngine
import com.billmanautovisit.app.screen.ScreenReader

class BillmanAccessibilityService : AccessibilityService() {

    private lateinit var screenReader: ScreenReader
    private lateinit var cameraTapper: CameraTapper
    private val automationEngine = AutomationEngine()

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        cameraTapper = CameraTapper(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString() ?: return

        if (packageName != AppConfig.TARGET_PACKAGE) {
            return
        }

        // Foundation stage only: verify that Billman events and node access are available.
        // State-machine actions are intentionally not started in this build.
        if (::screenReader.isInitialized) {
            screenReader.currentRoot()
        }
    }

    override fun onInterrupt() {
        automationEngine.requestStop()
    }
}
