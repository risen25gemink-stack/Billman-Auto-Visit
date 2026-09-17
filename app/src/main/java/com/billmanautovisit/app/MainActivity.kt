package com.billmanautovisit.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.billmanautovisit.app.data.SqliteLocalDataStore
import com.billmanautovisit.app.service.AutomationForegroundService

class MainActivity : Activity() {

    private lateinit var accessibilityBadge: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var completedValue: TextView
    private lateinit var skippedValue: TextView
    private lateinit var processedValue: TextView
    private lateinit var pageValue: TextView
    private lateinit var activeIdValue: TextView
    private lateinit var latestActionValue: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = CANVAS
        window.navigationBarColor = CANVAS
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
            setPadding(dp(20), dp(22), dp(20), dp(32))
            setBackgroundColor(CANVAS)
        }

        val eyebrow = TextView(this).apply {
            text = "AUTOMASI WORK ORDER"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(MUTED)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

        val title = TextView(this).apply {
            text = "Billman Auto Visit"
            textSize = 31f
            setTextColor(INK)
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setPadding(0, dp(3), 0, dp(5))
        }

        val subtitle = TextView(this).apply {
            text = "Kontrol automasi, pantau progres, lalu biarkan panel kecil mengikuti proses di Billman."
            textSize = 14f
            setTextColor(BODY)
            setLineSpacing(0f, 1.25f)
        }

        accessibilityBadge = TextView(this).apply {
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val topBadgeRow = LinearLayout(this).apply {
            gravity = Gravity.START
            setPadding(0, dp(14), 0, dp(18))
            addView(accessibilityBadge)
        }

        val statusCard = card(dp(12), SURFACE_CARD).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        statusTitle = TextView(this).apply {
            text = "Siap"
            textSize = 20f
            setTextColor(INK)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        statusDetail = TextView(this).apply {
            text = "Tekan Mulai untuk menjalankan automasi."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusDetail)

        val progressLabel = sectionLabel("Progres sesi", dp)
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        completedValue = metricCard(progressRow, "Selesai", SUCCESS, dp)
        skippedValue = metricCard(progressRow, "Dilewati", WARNING, dp)
        processedValue = metricCard(progressRow, "Diproses", PRIMARY, dp)

        val detailCard = card(dp(12), SURFACE_SOFT).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        pageValue = detailLine(detailCard, "Halaman", "—", dp)
        activeIdValue = detailLine(detailCard, "ID aktif", "—", dp)
        latestActionValue = detailLine(detailCard, "Tindakan terakhir", "Belum ada tindakan", dp)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        startButton = actionButton("Mulai", PRIMARY, Color.WHITE, dp)
        stopButton = actionButton("Hentikan", CANVAS, INK, dp).apply {
            background = roundedDrawable(CANVAS, dp(8), HAIRLINE)
            isEnabled = false
        }
        buttonRow.addView(
            startButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
        )
        buttonRow.addView(
            stopButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) }
        )

        val helperCard = card(dp(12), SURFACE_DARK).apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        helperCard.addView(TextView(this).apply {
            text = "Panel tracking"
            textSize = 15f
            setTextColor(ON_DARK)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        helperCard.addView(TextView(this).apply {
            text = "Saat automasi berjalan, panel kecil muncul di atas Billman. Di sana kamu bisa melihat progres, halaman, tahap aktif, tindakan terbaru, serta Jeda/Lanjut."
            textSize = 13f
            setTextColor(ON_DARK_SOFT)
            setPadding(0, dp(5), 0, 0)
        })

        val permissionButton = actionButton(
            "Buka Pengaturan Accessibility",
            CANVAS,
            INK,
            dp
        ).apply {
            background = roundedDrawable(CANVAS, dp(8), HAIRLINE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        container.addView(eyebrow)
        container.addView(title)
        container.addView(subtitle)
        container.addView(topBadgeRow)
        container.addView(statusCard)
        container.addView(progressLabel)
        container.addView(progressRow)
        container.addView(detailCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        container.addView(buttonRow)
        container.addView(helperCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22)
        })
        container.addView(permissionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(12)
        })

        return ScrollView(this).apply {
            setBackgroundColor(CANVAS)
            addView(container)
        }
    }

    private fun bindActions() {
        startButton.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                statusTitle.text = "Accessibility belum aktif"
                statusDetail.text = "Aktifkan Billman Auto Visit lebih dulu."
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

            startButton.isEnabled = false
            stopButton.isEnabled = true
            moveTaskToBack(true)
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, AutomationForegroundService::class.java).apply {
                action = AutomationForegroundService.ACTION_STOP
            }
            startService(intent)
            refreshUiState()
        }
    }

    private fun refreshUiState() {
        val enabled = isAccessibilityServiceEnabled()
        accessibilityBadge.text = if (enabled) "● Accessibility aktif" else "● Accessibility belum aktif"
        accessibilityBadge.setTextColor(if (enabled) SUCCESS else ERROR)
        accessibilityBadge.background = roundedDrawable(
            if (enabled) Color.rgb(235, 246, 237) else Color.rgb(250, 235, 235),
            dp(999),
            null
        )

        val status = AutomationRuntime.statusMessage
        statusTitle.text = when {
            AutomationRuntime.isPaused -> "Automasi dijeda"
            status.startsWith("COMPLETED") -> "Semua data selesai"
            status.startsWith("ERROR") -> "Perlu diperiksa"
            AutomationRuntime.isRunning -> "Automasi berjalan"
            else -> "Siap"
        }
        statusDetail.text = status

        val stats = if (AutomationRuntime.sessionStartedAt > 0L) {
            SqliteLocalDataStore(applicationContext).use { store ->
                store.getSessionStats(AutomationRuntime.sessionStartedAt)
            }
        } else null

        completedValue.text = (stats?.completed ?: 0).toString()
        skippedValue.text = (stats?.skipped ?: 0).toString()
        processedValue.text = (stats?.totalSeen ?: 0).toString()

        pageValue.text = if (AutomationRuntime.currentPage != null && AutomationRuntime.totalPages != null) {
            "${AutomationRuntime.currentPage} dari ${AutomationRuntime.totalPages}"
        } else "—"
        activeIdValue.text = AutomationRuntime.currentIdPelanggan ?: "—"
        latestActionValue.text = AutomationRuntime.latestAction

        val sessionActive = AutomationRuntime.isRunning || AutomationRuntime.isPaused
        startButton.isEnabled = enabled && !sessionActive
        stopButton.isEnabled = sessionActive
    }

    private fun card(radius: Int, color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(color, radius, null)
    }

    private fun sectionLabel(text: String, dp: (Int) -> Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(INK)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(10))
    }

    private fun metricCard(
        parent: LinearLayout,
        label: String,
        accent: Int,
        dp: (Int) -> Int
    ): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedDrawable(SURFACE_CARD, dp(12), null)
        }
        val value = TextView(this).apply {
            text = "0"
            textSize = 22f
            setTextColor(accent)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        box.addView(value)
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        parent.addView(box, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
            marginEnd = if (parent.childCount < 2) dp(6) else 0
            marginStart = if (parent.childCount > 0) dp(6) else 0
        })
        return value
    }

    private fun detailLine(
        parent: LinearLayout,
        label: String,
        value: String,
        dp: (Int) -> Int
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        val valueView = TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(INK)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        row.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
        return valueView
    }

    private fun actionButton(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        dp: (Int) -> Int
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(textColor)
        background = roundedDrawable(backgroundColor, dp(8), null)
        minHeight = 0
        minimumHeight = 0
    }

    private fun roundedDrawable(color: Int, radius: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(1, strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    companion object {
        private val PRIMARY = Color.rgb(204, 120, 92)
        private val CANVAS = Color.rgb(250, 249, 245)
        private val SURFACE_SOFT = Color.rgb(245, 240, 232)
        private val SURFACE_CARD = Color.rgb(239, 233, 222)
        private val SURFACE_DARK = Color.rgb(24, 23, 21)
        private val INK = Color.rgb(20, 20, 19)
        private val BODY = Color.rgb(61, 61, 58)
        private val MUTED = Color.rgb(108, 106, 100)
        private val HAIRLINE = Color.rgb(230, 223, 216)
        private val ON_DARK = Color.rgb(250, 249, 245)
        private val ON_DARK_SOFT = Color.rgb(160, 157, 150)
        private val SUCCESS = Color.rgb(93, 184, 114)
        private val WARNING = Color.rgb(212, 160, 23)
        private val ERROR = Color.rgb(198, 69, 69)
    }
}
