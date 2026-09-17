package com.billmanautovisit.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.billmanautovisit.app.core.AutomationRuntime
import com.billmanautovisit.app.data.LocalDataStore
import com.billmanautovisit.app.screen.ScreenReader
import kotlin.math.abs

class TrackingOverlayController(
    private val service: AccessibilityService,
    private val dataStore: LocalDataStore,
    private val screenReader: ScreenReader,
    private val onResumeAutomation: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var hiddenForSessionStartedAt: Long = -1L

    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var pageText: TextView
    private lateinit var stateText: TextView
    private lateinit var actionText: TextView
    private lateinit var pauseButton: Button

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    fun destroy() {
        handler.removeCallbacks(refreshRunnable)
        removeOverlay()
    }

    private fun refresh() {
        val sessionId = AutomationRuntime.sessionStartedAt
        val shouldShow = AutomationRuntime.isRunning ||
            AutomationRuntime.isPaused ||
            AutomationRuntime.statusMessage.startsWith("COMPLETED") ||
            AutomationRuntime.statusMessage.startsWith("ERROR")

        if (!shouldShow || (hiddenForSessionStartedAt == sessionId && sessionId != 0L)) {
            removeOverlay()
            return
        }

        ensureOverlay()

        screenReader.readPaginationInfo()?.let {
            AutomationRuntime.setPageInfo(it.currentPage, it.totalPages)
        }

        val stats = if (sessionId > 0L) {
            dataStore.getSessionStats(sessionId)
        } else {
            null
        }

        val paused = AutomationRuntime.isPaused
        val completed = AutomationRuntime.statusMessage.startsWith("COMPLETED")
        val error = AutomationRuntime.statusMessage.startsWith("ERROR")

        statusText.text = when {
            completed -> "SELESAI"
            error -> "ERROR"
            paused -> "DIJEDA"
            else -> "BERJALAN"
        }
        statusText.setTextColor(
            when {
                completed -> SUCCESS
                error -> ERROR
                paused -> WARNING
                else -> SUCCESS
            }
        )

        progressText.text = if (stats != null) {
            "Selesai ${stats.completed}  •  Dilewati ${stats.skipped}  •  Diproses ${stats.totalSeen}"
        } else {
            "Belum ada data sesi"
        }

        val currentPage = AutomationRuntime.currentPage
        val totalPages = AutomationRuntime.totalPages
        pageText.text = if (currentPage != null && totalPages != null) {
            "Halaman $currentPage dari $totalPages"
        } else {
            "Halaman —"
        }

        val id = AutomationRuntime.currentIdPelanggan
        stateText.text = buildString {
            append(prettyState(AutomationRuntime.currentState.name))
            if (!id.isNullOrBlank()) append("  •  ID ").append(id)
        }
        actionText.text = AutomationRuntime.latestAction

        pauseButton.text = if (paused) "Lanjut" else "Jeda"
        pauseButton.isEnabled = !completed && !error && (AutomationRuntime.isRunning || paused)
    }

    private fun ensureOverlay() {
        if (rootView != null) return

        val density = service.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(CANVAS, dp(12), HAIRLINE)
            elevation = dp(3).toFloat()
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(service).apply {
            text = "Billman Auto Visit"
            textSize = 15f
            setTextColor(INK)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

        statusText = TextView(service).apply {
            text = "BERJALAN"
            textSize = 11f
            gravity = Gravity.END
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

        val close = TextView(service).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener {
                hiddenForSessionStartedAt = AutomationRuntime.sessionStartedAt
                removeOverlay()
            }
        }

        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(statusText)
        header.addView(close, LinearLayout.LayoutParams(dp(32), dp(32)))

        progressText = TextView(service).apply {
            textSize = 13f
            setTextColor(BODY)
            setPadding(0, dp(8), 0, 0)
        }

        pageText = TextView(service).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, 0)
        }

        stateText = TextView(service).apply {
            textSize = 12f
            setTextColor(INK)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }

        actionText = TextView(service).apply {
            textSize = 12f
            setTextColor(MUTED)
            maxLines = 2
            setPadding(0, dp(3), 0, dp(10))
        }

        pauseButton = Button(service).apply {
            text = "Jeda"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedDrawable(PRIMARY, dp(8), null)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                if (AutomationRuntime.isPaused) {
                    AutomationRuntime.resume()
                    onResumeAutomation()
                } else {
                    AutomationRuntime.pause()
                }
                refresh()
            }
        }

        card.addView(header)
        card.addView(progressText)
        card.addView(pageText)
        card.addView(stateText)
        card.addView(actionText)
        card.addView(
            pauseButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
        )

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(84)
        }

        makeDraggable(card, params)
        windowManager.addView(card, params)
        rootView = card
        layoutParams = params
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchX - event.rawX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        rootView?.let { windowManager.updateViewLayout(it, params) }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        runCatching { windowManager.removeView(view) }
        rootView = null
        layoutParams = null
    }

    private fun prettyState(raw: String): String = raw
        .lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun roundedDrawable(color: Int, radius: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(1, strokeColor)
        }

    companion object {
        private const val REFRESH_INTERVAL_MS = 500L
        private val CANVAS = Color.rgb(250, 249, 245)
        private val PRIMARY = Color.rgb(204, 120, 92)
        private val INK = Color.rgb(20, 20, 19)
        private val BODY = Color.rgb(61, 61, 58)
        private val MUTED = Color.rgb(108, 106, 100)
        private val HAIRLINE = Color.rgb(230, 223, 216)
        private val SUCCESS = Color.rgb(93, 184, 114)
        private val WARNING = Color.rgb(212, 160, 23)
        private val ERROR = Color.rgb(198, 69, 69)
    }
}
