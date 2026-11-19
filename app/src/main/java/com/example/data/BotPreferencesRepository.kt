package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.BotSettings
import com.example.model.RoiCalibration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.botDataStore: DataStore<Preferences> by preferencesDataStore(name = "tile_bot_prefs")

class BotPreferencesRepository(private val context: Context) {

    private object Keys {
        val LOOKAHEAD_DEPTH = intPreferencesKey("lookahead_depth")
        val BEAM_WIDTH = intPreferencesKey("beam_width")
        val TAP_DELAY_MS = longPreferencesKey("tap_delay_ms")
        val CLEAR_SETTLE_MS = longPreferencesKey("clear_settle_ms")
        val AUTO_BOOSTERS = booleanPreferencesKey("auto_boosters")
        val SHOW_OVERLAY = booleanPreferencesKey("show_overlay")

        val BOARD_LEFT = floatPreferencesKey("board_left")
        val BOARD_RIGHT = floatPreferencesKey("board_right")
        val BOARD_TOP = floatPreferencesKey("board_top")
        val BOARD_BOTTOM = floatPreferencesKey("board_bottom")
        val TRAY_TOP = floatPreferencesKey("tray_top")
        val TRAY_BOTTOM = floatPreferencesKey("tray_bottom")
        val TILE_WIDTH_RATIO = floatPreferencesKey("tile_width_ratio")
    }

    val settingsFlow: Flow<BotSettings> = context.botDataStore.data.map { prefs ->
        BotSettings(
            lookaheadDepth = prefs[Keys.LOOKAHEAD_DEPTH] ?: 12,
            beamWidth = prefs[Keys.BEAM_WIDTH] ?: 280,
            tapDelayMs = prefs[Keys.TAP_DELAY_MS] ?: 180L,
            clearSettleDelayMs = prefs[Keys.CLEAR_SETTLE_MS] ?: 440L,
            autoUseBoosters = prefs[Keys.AUTO_BOOSTERS] ?: true,
            showMoveOverlay = prefs[Keys.SHOW_OVERLAY] ?: true,
        )
    }

    val calibrationFlow: Flow<RoiCalibration> = context.botDataStore.data.map { prefs ->
        val defaults = RoiCalibration()
        RoiCalibration(
            boardLeft = prefs[Keys.BOARD_LEFT] ?: defaults.boardLeft,
            boardRight = prefs[Keys.BOARD_RIGHT] ?: defaults.boardRight,
            boardTop = prefs[Keys.BOARD_TOP] ?: defaults.boardTop,
            boardBottom = prefs[Keys.BOARD_BOTTOM] ?: defaults.boardBottom,
            trayTop = prefs[Keys.TRAY_TOP] ?: defaults.trayTop,
            trayBottom = prefs[Keys.TRAY_BOTTOM] ?: defaults.trayBottom,
            tileWidthRatio = prefs[Keys.TILE_WIDTH_RATIO] ?: defaults.tileWidthRatio,
        )
    }

    suspend fun updateSettings(transform: (BotSettings) -> BotSettings) {
        context.botDataStore.edit { prefs ->
            val current = BotSettings(
                lookaheadDepth = prefs[Keys.LOOKAHEAD_DEPTH] ?: 12,
                beamWidth = prefs[Keys.BEAM_WIDTH] ?: 280,
                tapDelayMs = prefs[Keys.TAP_DELAY_MS] ?: 180L,
                clearSettleDelayMs = prefs[Keys.CLEAR_SETTLE_MS] ?: 440L,
                autoUseBoosters = prefs[Keys.AUTO_BOOSTERS] ?: true,
                showMoveOverlay = prefs[Keys.SHOW_OVERLAY] ?: true,
            )
            val updated = transform(current)
            prefs[Keys.LOOKAHEAD_DEPTH] = updated.lookaheadDepth
            prefs[Keys.BEAM_WIDTH] = updated.beamWidth
            prefs[Keys.TAP_DELAY_MS] = updated.tapDelayMs
            prefs[Keys.CLEAR_SETTLE_MS] = updated.clearSettleDelayMs
            prefs[Keys.AUTO_BOOSTERS] = updated.autoUseBoosters
            prefs[Keys.SHOW_OVERLAY] = updated.showMoveOverlay
        }
    }

    suspend fun updateCalibration(calibration: RoiCalibration) {
        context.botDataStore.edit { prefs ->
            prefs[Keys.BOARD_LEFT] = calibration.boardLeft
            prefs[Keys.BOARD_RIGHT] = calibration.boardRight
            prefs[Keys.BOARD_TOP] = calibration.boardTop
            prefs[Keys.BOARD_BOTTOM] = calibration.boardBottom
            prefs[Keys.TRAY_TOP] = calibration.trayTop
            prefs[Keys.TRAY_BOTTOM] = calibration.trayBottom
            prefs[Keys.TILE_WIDTH_RATIO] = calibration.tileWidthRatio
        }
    }
}
