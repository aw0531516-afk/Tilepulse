package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BotDiagnosticLog
import com.example.data.BotPreferencesRepository
import com.example.data.TrainingMetrics
import com.example.model.BotLiveTelemetry
import com.example.model.BotSettings
import com.example.model.RoiCalibration
import com.example.service.BotCommand
import com.example.service.BotStateBridge
import com.example.service.TileBotAccessibilityService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BotViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = BotPreferencesRepository(application)

    val settings: StateFlow<BotSettings> = prefsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BotSettings(),
    )

    val calibration: StateFlow<RoiCalibration> = prefsRepo.calibrationFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RoiCalibration(),
    )

    val telemetry: StateFlow<BotLiveTelemetry> = BotStateBridge.telemetry

    val trainingMetrics: StateFlow<TrainingMetrics> = BotDiagnosticLog.metrics

    init {
        viewModelScope.launch {
            prefsRepo.settingsFlow.collect { BotStateBridge.updateSettings(it) }
        }
        viewModelScope.launch {
            prefsRepo.calibrationFlow.collect { BotStateBridge.updateCalibration(it) }
        }
        refreshSystemPermissions()
    }

    fun refreshSystemPermissions() {
        val ctx = getApplication<Application>()
        val overlayOk = Settings.canDrawOverlays(ctx)
        val accOk = TileBotAccessibilityService.current() != null || isAccessibilityEnabledInSettings(ctx)
        BotStateBridge.setOverlayGranted(overlayOk)
        BotStateBridge.setAccessibilityConnected(accOk)
    }

    fun updateSettings(transform: (BotSettings) -> BotSettings) {
        viewModelScope.launch {
            prefsRepo.updateSettings(transform)
        }
    }

    fun updateCalibration(newCalibration: RoiCalibration) {
        viewModelScope.launch {
            prefsRepo.updateCalibration(newCalibration)
        }
    }

    fun resetCalibrationDefaults() {
        updateCalibration(RoiCalibration())
    }

    fun sendBotCommand(command: BotCommand) {
        BotStateBridge.sendCommand(command)
    }

    fun getShareableTrainingLog(): String = BotDiagnosticLog.buildShareableReport()

    fun resetTrainingMemory() {
        BotDiagnosticLog.clearSession()
    }

    private fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, TileBotAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
