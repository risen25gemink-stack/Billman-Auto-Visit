package com.billmanautovisit.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.billmanautovisit.app.camera.CameraTapper
import com.billmanautovisit.app.core.AppConfig
import com.billmanautovisit.app.core.AutomationRuntime
import com.billmanautovisit.app.data.SqliteLocalDataStore
import com.billmanautovisit.app.engine.AutomationEngine
import com.billmanautovisit.app.screen.ScreenReader

class BillmanAccessibilityService : AccessibilityService() {

    private lateinit var screenReader: ScreenReader
    private lateinit var cameraTapper: CameraTapper
    private lateinit var localDataStore: SqliteLocalDataStore
    private lateinit var automationEngine: AutomationEngine

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        cameraTapper = CameraTapper(this)
        localDataStore = SqliteLocalDataStore(applicationContext)
        automationEngine = AutomationEngine(screenReader, localDataStore)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString() ?: return

        if (packageName != AppConfig.TARGET_PACKAGE) return
        if (!::automationEngine.isInitialized) return
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return

        automationEngine.onBillmanEvent()
    }

    override fun onInterrupt() {
        AutomationRuntime.requestStop()
    }

    override fun onDestroy() {
        if (::automationEngine.isInitialized) automationEngine.shutdown()
        if (::localDataStore.isInitialized) localDataStore.close()
        super.onDestroy()
    }
}
