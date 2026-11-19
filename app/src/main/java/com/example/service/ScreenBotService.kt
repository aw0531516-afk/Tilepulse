package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BotDiagnosticLog
import com.example.model.BoardAnalysisResult
import com.example.model.BoosterType
import com.example.model.BotRuntimeStatus
import com.example.model.PlannedMove
import com.example.solver.TileSolver
import com.example.vision.BoardVisionAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ScreenBotService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenW = 1080
    private var screenH = 2400
    private var screenDpi = 420

    private val visionAnalyzer = BoardVisionAnalyzer()
    private val solver = TileSolver()

    private var hudView: View? = null
    private var hudStatusText: TextView? = null
    private var hudPlayButton: TextView? = null
    private var hudTrayText: TextView? = null
    private var moveOverlayView: FloatingMoveOverlayView? = null

    private var autoPlayJob: Job? = null
    @Volatile
    private var isAutoPlaying = false
    @Volatile
    private var isForegroundStarted = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopSelfSafely() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        resolveScreenMetrics()
        createNotificationChannel()
        observeCommands()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        val hasValidToken = resultCode == Activity.RESULT_OK && resultData != null
        if (mediaProjection == null && !hasValidToken) {
            // Never start a mediaProjection FGS without an active MediaProjection consent token
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundStarted = true

        if (hasValidToken && mediaProjection == null && resultData != null) {
            setupMediaProjection(resultCode, resultData)
        }

        if (Settings.canDrawOverlays(this) && hudView == null) {
            attachFloatingOverlays()
        }

        BotStateBridge.setProjectionState(
            active = mediaProjection != null,
            floatingVisible = hudView != null,
        )
        BotStateBridge.setStatus(
            status = BotRuntimeStatus.READY,
            action = "Floating board ready. Open your game & tap PLAY.",
        )

        return START_NOT_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        // Capture at half-res if ultra-high-res to keep RGBA buffer fast while scaling tap coords 1:1
        val capW = screenW
        val capH = screenH
        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "TilePulseCapture",
            capW,
            capH,
            screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )
    }

    private fun observeCommands() {
        serviceScope.launch {
            BotStateBridge.commands.collect { cmd ->
                when (cmd) {
                    BotCommand.TOGGLE_AUTO_PLAY -> withContext(Dispatchers.Main) { toggleAutoPlay() }
                    BotCommand.PAUSE_BOT -> withContext(Dispatchers.Main) { pauseAutoPlay() }
                    BotCommand.STEP_ONCE -> executeSingleStep()
                    BotCommand.RESCAN_NOW -> analyzeOnceAndPreview()
                    BotCommand.STOP_SERVICE -> withContext(Dispatchers.Main) { stopSelfSafely() }
                }
            }
        }
    }

    private fun toggleAutoPlay() {
        if (isAutoPlaying) {
            pauseAutoPlay()
        } else {
            startAutoPlay()
        }
    }

    private fun pauseAutoPlay() {
        isAutoPlaying = false
        autoPlayJob?.cancel()
        autoPlayJob = null
        BotStateBridge.setStatus(
            status = BotRuntimeStatus.PAUSED,
            action = "Paused",
        )
        updateHudUi()
    }

    private data class PendingVerification(
        val move: PlannedMove,
        val beforeTray: Int,
        val beforeTiles: Int,
    )

    private fun startAutoPlay() {
        if (TileBotAccessibilityService.current() == null) {
            BotStateBridge.setStatus(
                status = BotRuntimeStatus.NEEDS_INTERVENTION,
                action = "Enable Accessibility Service to auto-tap",
                errorMessage = "Accessibility service not enabled",
            )
            updateHudUi()
            return
        }

        isAutoPlaying = true
        visionAnalyzer.resetPrototypes()
        BotStateBridge.setStatus(
            status = BotRuntimeStatus.RUNNING,
            action = "Scanning board & planning lookahead...",
        )
        updateHudUi()

        autoPlayJob?.cancel()
        autoPlayJob = serviceScope.launch {
            var pendingVerification: PendingVerification? = null

            while (isActive && isAutoPlaying) {
                val analysis = captureAndAnalyze()
                if (analysis == null) {
                    delay(260L)
                    continue
                }

                // Verify previous tap against live captured screen state (self-training feedback loop)
                val pending = pendingVerification
                if (pending != null) {
                    pendingVerification = null
                    val afterTray = analysis.trayOccupiedCount
                    val afterTiles = analysis.tiles.size
                    val verified = (afterTray != pending.beforeTray) || (afterTiles < pending.beforeTiles)
                    BotDiagnosticLog.recordTapOutcome(
                        move = pending.move,
                        beforeTray = pending.beforeTray,
                        afterTray = afterTray,
                        beforeTiles = pending.beforeTiles,
                        afterTiles = afterTiles,
                    )
                    if (!verified && analysis.tiles.isNotEmpty()) {
                        // Previous tap had zero effect & was blacklisted; re-scan immediately without repeating it
                        delay(80L)
                        continue
                    }
                }

                if (analysis.tiles.isNotEmpty()) {
                    BotDiagnosticLog.recordSnapshot(analysis)
                }
                BotStateBridge.recordAnalysis(analysis)
                withContext(Dispatchers.Main) {
                    if (BotStateBridge.settings.value.showMoveOverlay) {
                        moveOverlayView?.updateAnalysis(analysis)
                    } else {
                        moveOverlayView?.updateAnalysis(null)
                    }
                    updateHudUi()
                }

                if (analysis.tiles.isEmpty()) {
                    BotStateBridge.setStatus(
                        status = BotRuntimeStatus.READY,
                        action = "Waiting for game board...",
                    )
                    delay(650L)
                    continue
                }

                val plan = analysis.plan
                val settings = BotStateBridge.settings.value
                val accService = TileBotAccessibilityService.current()

                if (accService == null) {
                    pauseAutoPlay()
                    break
                }

                if (plan.isEmpty()) {
                    val booster = analysis.recommendedBooster
                    if (booster != null && settings.autoUseBoosters) {
                        val (bx, by) = boosterCoordinates(booster)
                        BotStateBridge.setStatus(
                            status = BotRuntimeStatus.RUNNING,
                            action = "Using booster ($booster) to rescue tray",
                        )
                        accService.performTap(bx, by)
                        delay(700L)
                    } else {
                        BotStateBridge.setStatus(
                            status = BotRuntimeStatus.NEEDS_INTERVENTION,
                            action = "No safe move found — tap Shuffle or Undo",
                        )
                        delay(900L)
                    }
                    continue
                }

                // Closed-loop step execution: execute top predicted move, wait for settle, and re-capture to verify & see newly unlocked tiles
                val move = plan.first()
                BotStateBridge.setStatus(
                    status = BotRuntimeStatus.RUNNING,
                    action = "Tap #${move.stepNumber}: ${move.reason}",
                )
                withContext(Dispatchers.Main) { updateHudUi() }

                accService.performTap(move.tapX, move.tapY)
                BotStateBridge.recordMoveExecuted(move.clearsMatch3)
                pendingVerification = PendingVerification(
                    move = move,
                    beforeTray = analysis.trayOccupiedCount,
                    beforeTiles = analysis.tiles.size,
                )

                val waitMs = if (move.clearsMatch3) {
                    settings.clearSettleDelayMs
                } else {
                    settings.tapDelayMs.coerceAtLeast(220L)
                }
                delay(waitMs)
            }
        }
    }

    private fun executeSingleStep() {
        serviceScope.launch {
            val accService = TileBotAccessibilityService.current()
            if (accService == null) {
                BotStateBridge.setStatus(
                    status = BotRuntimeStatus.NEEDS_INTERVENTION,
                    action = "Accessibility Service disabled",
                )
                return@launch
            }
            val before = captureAndAnalyze() ?: return@launch
            BotDiagnosticLog.recordSnapshot(before, extraNote = "before_single_step")
            BotStateBridge.recordAnalysis(before)
            withContext(Dispatchers.Main) {
                if (BotStateBridge.settings.value.showMoveOverlay) {
                    moveOverlayView?.updateAnalysis(before)
                }
                updateHudUi()
            }
            val nextMove = before.plan.firstOrNull() ?: return@launch
            BotStateBridge.setStatus(
                status = BotRuntimeStatus.READY,
                action = "Stepped: ${nextMove.reason}",
            )
            accService.performTap(nextMove.tapX, nextMove.tapY)
            BotStateBridge.recordMoveExecuted(nextMove.clearsMatch3)
            delay(360L)

            val after = captureAndAnalyze() ?: return@launch
            BotDiagnosticLog.recordTapOutcome(
                move = nextMove,
                beforeTray = before.trayOccupiedCount,
                afterTray = after.trayOccupiedCount,
                beforeTiles = before.tiles.size,
                afterTiles = after.tiles.size,
            )
            BotDiagnosticLog.recordSnapshot(after, extraNote = "after_single_step")
            BotStateBridge.recordAnalysis(after)
            withContext(Dispatchers.Main) {
                if (BotStateBridge.settings.value.showMoveOverlay) {
                    moveOverlayView?.updateAnalysis(after)
                }
                updateHudUi()
            }
        }
    }

    private fun analyzeOnceAndPreview() {
        serviceScope.launch {
            val analysis = captureAndAnalyze() ?: return@launch
            BotDiagnosticLog.recordSnapshot(analysis, extraNote = "manual_scan")
            BotStateBridge.recordAnalysis(analysis)
            withContext(Dispatchers.Main) {
                if (BotStateBridge.settings.value.showMoveOverlay) {
                    moveOverlayView?.updateAnalysis(analysis)
                }
                updateHudUi()
            }
        }
    }

    private suspend fun captureAndAnalyze(): BoardAnalysisResult? {
        val reader = imageReader ?: return null
        val overlay = moveOverlayView

        // Temporarily hide move overlay so drawn boxes never contaminate computer vision
        if (overlay != null) {
            withContext(Dispatchers.Main) { overlay.visibility = View.INVISIBLE }
            delay(45L)
        }

        val bitmap = acquireLatestBitmap(reader)

        if (overlay != null) {
            withContext(Dispatchers.Main) { overlay.visibility = View.VISIBLE }
        }

        bitmap ?: return null
        return try {
            val startNs = System.nanoTime()
            val roi = BotStateBridge.calibration.value
            val settings = BotStateBridge.settings.value
            val raw = visionAnalyzer.analyzeRaw(bitmap, roi)
            val visionMs = (System.nanoTime() - startNs) / 1_000_000L
            solver.solve(raw, settings, visionMs)
        } finally {
            bitmap.recycle()
        }
    }

    private fun acquireLatestBitmap(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                paddedBitmap
            } else {
                val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
                paddedBitmap.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun boosterCoordinates(booster: BoosterType): Pair<Float, Float> {
        val roi = BotStateBridge.calibration.value
        val xRatio = when (booster) {
            BoosterType.RETURN_THREE -> roi.boosterReturn3X
            BoosterType.UNDO -> roi.boosterUndoX
            BoosterType.SHUFFLE -> roi.boosterShuffleX
        }
        return (xRatio * screenW) to (roi.boosterY * screenH)
    }

    private fun attachFloatingOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Non-touchable transparent move visualizer overlay aligned pixel-for-pixel with MediaProjection (cutout-aware)
        val visualizer = FloatingMoveOverlayView(this)
        val visParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(visualizer, visParams)
        moveOverlayView = visualizer

        // 2. Draggable floating control HUD pill
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 20, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6161B24"))
                cornerRadius = 48f
                setStroke(2, Color.parseColor("#F5A623"))
            }
        }

        val statusTv = TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(Color.parseColor("#2ECC71"))
            setPadding(0, 0, 14, 0)
        }
        hudStatusText = statusTv

        val trayTv = TextView(this).apply {
            text = "0/7"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#F4EFE6"))
            setPadding(0, 0, 16, 0)
        }
        hudTrayText = trayTv

        val playBtn = makeHudChip("PLAY", "#F5A623", "#0E1117") {
            BotStateBridge.sendCommand(BotCommand.TOGGLE_AUTO_PLAY)
        }
        hudPlayButton = playBtn

        val stepBtn = makeHudChip("1x", "#222733", "#F4EFE6") {
            BotStateBridge.sendCommand(BotCommand.STEP_ONCE)
        }

        val scanBtn = makeHudChip("SCAN", "#222733", "#38BDF8") {
            BotStateBridge.sendCommand(BotCommand.RESCAN_NOW)
        }

        val closeBtn = makeHudChip("✕", "#3E161B", "#EF4444") {
            BotStateBridge.sendCommand(BotCommand.STOP_SERVICE)
        }

        container.addView(statusTv)
        container.addView(trayTv)
        container.addView(playBtn)
        container.addView(stepBtn)
        container.addView(scanBtn)
        container.addView(closeBtn)

        // Allow dragging the floating board anywhere on screen
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = hudParams.x
                    initY = hudParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragging = true
                    hudParams.x = initX + dx
                    hudParams.y = initY + dy
                    windowManager.updateViewLayout(container, hudParams)
                    true
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }

        windowManager.addView(container, hudParams)
        hudView = container
    }

    private fun makeHudChip(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(fgHex))
            setPadding(20, 10, 20, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 32f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = 10
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun updateHudUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateHudUi() }
            return
        }
        val telemetry = BotStateBridge.telemetry.value
        val analysis = telemetry.lastAnalysis
        val trayCount = analysis?.trayOccupiedCount ?: 0

        hudTrayText?.text = "$trayCount/7"
        hudTrayText?.setTextColor(
            when {
                trayCount >= 6 -> Color.parseColor("#EF4444")
                trayCount >= 4 -> Color.parseColor("#F5A623")
                else -> Color.parseColor("#F4EFE6")
            },
        )

        if (isAutoPlaying) {
            hudPlayButton?.text = "PAUSE"
            hudStatusText?.setTextColor(Color.parseColor("#2ECC71"))
        } else {
            hudPlayButton?.text = "PLAY"
            hudStatusText?.setTextColor(Color.parseColor("#F5A623"))
        }
    }

    private fun stopSelfSafely() {
        isAutoPlaying = false
        autoPlayJob?.cancel()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hudView?.let { runCatching { windowManager.removeView(it) } }
            moveOverlayView?.let { runCatching { windowManager.removeView(it) } }
            hudView = null
            moveOverlayView = null
        } else {
            val h = hudView
            val m = moveOverlayView
            hudView = null
            moveOverlayView = null
            mainHandler.post {
                h?.let { runCatching { windowManager.removeView(it) } }
                m?.let { runCatching { windowManager.removeView(it) } }
            }
        }

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        BotStateBridge.setProjectionState(active = false, floatingVisible = false)
        BotStateBridge.setStatus(
            status = BotRuntimeStatus.IDLE,
            action = "Stopped",
        )
        if (isForegroundStarted) {
            isForegroundStarted = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (mediaProjection != null || hudView != null) {
            stopSelfSafely()
        }
    }

    private fun resolveScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenW = metrics.bounds.width()
            screenH = metrics.bounds.height()
            screenDpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            screenDpi = dm.densityDpi
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenBotService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop Bot", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "tile_pulse_bot_channel"
        const val NOTIFICATION_ID = 701
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP = "com.example.ACTION_STOP_BOT"
    }
}
