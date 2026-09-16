package com.billmanautovisit.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.billmanautovisit.app.accessibility.BillmanAccessibilityService
import com.billmanautovisit.app.core.AutomationRuntime
import com.billmanautovisit.app.service.AutomationForegroundService

class MainActivity : Activity() {

    private lateinit var accessibilityStatus: TextView
    private lateinit var currentStatus: TextView
    private lateinit var processedId: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        bindActions()
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()
    }

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }

        accessibilityStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, dp(16))
        }

        currentStatus = TextView(this).apply {
            text = "Status Saat Ini: IDLE"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }

        processedId = TextView(this).apply {
            text = "ID Pelanggan diproses: -"
            textSize = 15f
            setPadding(0, dp(16), 0, dp(6))
        }

        val phaseInfo = TextView(this).apply {
            text = "Build uji: WO_LIST → READ_ID → INPUT_ID → TAP_CARI → WAIT_CARD → TAP_CARD → MENUJU_WO. Berhenti sebelum tap Tagging."
            textSize = 14f
            setPadding(0, 0, 0, dp(18))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        startButton = Button(this).apply {
            text = "▶ START"
        }

        stopButton = Button(this).apply {
            text = "■ STOP"
            isEnabled = false
        }

        buttonRow.addView(
            startButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        )
        buttonRow.addView(
            stopButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        )

        val logTitle = TextView(this).apply {
            text = "Log Aktivitas"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(8))
        }

        val logBody = TextView(this).apply {
            text = "Log detail disimpan lokal di SQLite untuk debugging."
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val permissionButton = Button(this).apply {
            text = "Buka Pengaturan Accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        container.addView(title)
        container.addView(accessibilityStatus)
        container.addView(currentStatus)
        container.addView(processedId)
        container.addView(phaseInfo)
        container.addView(buttonRow)
        container.addView(logTitle)
        container.addView(logBody)
        container.addView(permissionButton)

        return ScrollView(this).apply {
            addView(container)
        }
    }

    private fun bindActions() {
        startButton.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                currentStatus.text = "Status Saat Ini: Accessibility belum aktif"
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            val intent = Intent(this, AutomationForegroundService::class.java).apply {
                action = AutomationForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            currentStatus.text = "Status Saat Ini: RUNNING: WO_LIST"
            processedId.text = "ID Pelanggan diproses: -"
            startButton.isEnabled = false
            stopButton.isEnabled = true
            moveTaskToBack(true)
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, AutomationForegroundService::class.java).apply {
                action = AutomationForegroundService.ACTION_STOP
            }
            startService(intent)
            currentStatus.text = "Status Saat Ini: IDLE"
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun refreshUiState() {
        val enabled = isAccessibilityServiceEnabled()
        accessibilityStatus.text = if (enabled) {
            "Status Accessibility: ● Aktif"
        } else {
            "Status Accessibility: ● Tidak Aktif"
        }

        currentStatus.text = "Status Saat Ini: ${AutomationRuntime.statusMessage}"
        processedId.text = "ID Pelanggan diproses: ${AutomationRuntime.currentIdPelanggan ?: "-"}"
        startButton.isEnabled = enabled && !AutomationRuntime.isRunning
        stopButton.isEnabled = AutomationRuntime.isRunning
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val expectedComponent = "$packageName/${BillmanAccessibilityService::class.java.name}"

        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.let { serviceInfo ->
                "${serviceInfo.packageName}/${serviceInfo.name}" == expectedComponent
            }
        }
    }
}
