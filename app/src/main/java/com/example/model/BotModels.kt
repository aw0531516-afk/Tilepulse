package com.example.model

import android.graphics.RectF

const val UNKNOWN_TILE_TYPE = -1

data class RoiCalibration(
    val boardLeft: Float = 0.08f,
    val boardRight: Float = 0.92f,
    val boardTop: Float = 0.19f,
    val boardBottom: Float = 0.785f,
    val sideStackTop: Float = 0.545f,
    val trayLeft: Float = 0.055f,
    val trayRight: Float = 0.945f,
    val trayTop: Float = 0.795f,
    val trayBottom: Float = 0.865f,
    val boosterY: Float = 0.932f,
    val boosterReturn3X: Float = 0.132f,
    val boosterUndoX: Float = 0.50f,
    val boosterShuffleX: Float = 0.868f,
    val tileWidthRatio: Float = 0.118f,
)

data class BotSettings(
    val lookaheadDepth: Int = 12,
    val beamWidth: Int = 280,
    val tapDelayMs: Long = 180L,
    val clearSettleDelayMs: Long = 440L,
    val autoUseBoosters: Boolean = true,
    val showMoveOverlay: Boolean = true,
)

data class DetectedTile(
    val id: Int,
    val normBounds: RectF,
    val pixelBounds: RectF,
    val tapX: Float,
    val tapY: Float,
    val isUnlocked: Boolean,
    val isSideStack: Boolean,
    val typeId: Int,
    val matchConfidence: Float,
    val visibleRatio: Float,
    val luminance: Float,
    val representativeColor: Int,
    val parentIds: List<Int> = emptyList(),
    val childIds: List<Int> = emptyList(),
)

data class TraySlot(
    val slotIndex: Int,
    val isOccupied: Boolean,
    val typeId: Int,
    val representativeColor: Int,
    val normBounds: RectF,
)

enum class BoosterType {
    RETURN_THREE,
    UNDO,
    SHUFFLE,
}

data class PlannedMove(
    val stepNumber: Int,
    val tileId: Int,
    val typeId: Int,
    val tapX: Float,
    val tapY: Float,
    val normTapX: Float,
    val normTapY: Float,
    val clearsMatch3: Boolean,
    val predictedTrayAfter: Int,
    val unlockedChildIds: List<Int>,
    val uncoversUnknown: Boolean,
    val reason: String,
)

data class BoardAnalysisResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val tiles: List<DetectedTile>,
    val traySlots: List<TraySlot>,
    val plan: List<PlannedMove>,
    val predictedScore: Float,
    val recommendedBooster: BoosterType?,
    val analysisTimeMs: Long,
    val unlockedCount: Int,
    val lockedCount: Int,
    val knownLockedCount: Int,
    val prototypeCount: Int,
) {
    val trayOccupiedCount: Int
        get() = traySlots.count { it.isOccupied }
}

enum class BotRuntimeStatus {
    IDLE,
    READY,
    RUNNING,
    PAUSED,
    NEEDS_INTERVENTION,
}

data class BotLiveTelemetry(
    val status: BotRuntimeStatus = BotRuntimeStatus.IDLE,
    val isAccessibilityConnected: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val isProjectionActive: Boolean = false,
    val isFloatingBoardVisible: Boolean = false,
    val lastAnalysis: BoardAnalysisResult? = null,
    val movesExecuted: Int = 0,
    val triplesCleared: Int = 0,
    val lastActionLabel: String = "Standby",
    val errorMessage: String? = null,
)
