package com.example.service

import com.example.model.BoardAnalysisResult
import com.example.model.BotLiveTelemetry
import com.example.model.BotRuntimeStatus
import com.example.model.BotSettings
import com.example.model.RoiCalibration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BotCommand {
    TOGGLE_AUTO_PLAY,
    PAUSE_BOT,
    STEP_ONCE,
    RESCAN_NOW,
    STOP_SERVICE,
}

object BotStateBridge {
    private val _telemetry = MutableStateFlow(BotLiveTelemetry())
    val telemetry: StateFlow<BotLiveTelemetry> = _telemetry.asStateFlow()

    private val _settings = MutableStateFlow(BotSettings())
    val settings: StateFlow<BotSettings> = _settings.asStateFlow()

    private val _calibration = MutableStateFlow(RoiCalibration())
    val calibration: StateFlow<RoiCalibration> = _calibration.asStateFlow()

    private val _commands = MutableSharedFlow<BotCommand>(extraBufferCapacity = 16)
    val commands = _commands.asSharedFlow()

    fun sendCommand(command: BotCommand) {
        _commands.tryEmit(command)
    }

    fun updateSettings(settings: BotSettings) {
        _settings.value = settings
    }

    fun updateCalibration(calibration: RoiCalibration) {
        _calibration.value = calibration
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _telemetry.update { it.copy(isAccessibilityConnected = connected) }
    }

    fun setOverlayGranted(granted: Boolean) {
        _telemetry.update { it.copy(isOverlayGranted = granted) }
    }

    fun setProjectionState(active: Boolean, floatingVisible: Boolean) {
        _telemetry.update {
            it.copy(
                isProjectionActive = active,
                isFloatingBoardVisible = floatingVisible,
                status = when {
                    !active -> BotRuntimeStatus.IDLE
                    it.status == BotRuntimeStatus.IDLE -> BotRuntimeStatus.READY
                    else -> it.status
                },
            )
        }
    }

    fun setStatus(
        status: BotRuntimeStatus,
        action: String,
        errorMessage: String? = null,
    ) {
        _telemetry.update {
            it.copy(
                status = status,
                lastActionLabel = action,
                errorMessage = errorMessage,
            )
        }
    }

    fun recordAnalysis(analysis: BoardAnalysisResult) {
        _telemetry.update { it.copy(lastAnalysis = analysis) }
    }

    fun recordMoveExecuted(clearedTriple: Boolean) {
        _telemetry.update {
            it.copy(
                movesExecuted = it.movesExecuted + 1,
                triplesCleared = it.triplesCleared + if (clearedTriple) 1 else 0,
            )
        }
    }
}
