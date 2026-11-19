package com.example.data

import android.graphics.RectF
import com.example.model.BoardAnalysisResult
import com.example.model.PlannedMove
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs

data class TrainingMetrics(
    val totalTaps: Int = 0,
    val verifiedTaps: Int = 0,
    val phantomMisses: Int = 0,
    val blacklistedSpots: Int = 0,
    val logEntryCount: Int = 0,
)

object BotDiagnosticLog {

    private data class BlacklistSpot(val nx: Float, val ny: Float, var hits: Int)

    private val lock = Any()
    private val events = ArrayDeque<String>(40)
    private val blacklist = mutableListOf<BlacklistSpot>()
    private var latestSnapshot: String = "No board scan captured yet."

    private val _metrics = MutableStateFlow(TrainingMetrics())
    val metrics: StateFlow<TrainingMetrics> = _metrics.asStateFlow()

    fun clearSession() {
        synchronized(lock) {
            events.clear()
            blacklist.clear()
            latestSnapshot = "Session reset."
            _metrics.value = TrainingMetrics()
        }
    }

    fun isBlacklisted(normBounds: RectF): Boolean {
        val cx = (normBounds.left + normBounds.right) * 0.5f
        val cy = (normBounds.top + normBounds.bottom) * 0.5f
        synchronized(lock) {
            return blacklist.any { spot ->
                abs(cx - spot.nx) < 0.048f && abs(cy - spot.ny) < 0.035f
            }
        }
    }

    fun recordTapOutcome(
        move: PlannedMove,
        beforeTray: Int,
        afterTray: Int,
        beforeTiles: Int,
        afterTiles: Int,
    ) {
        val trayChanged = (afterTray != beforeTray)
        val boardChanged = (afterTiles < beforeTiles)
        val verified = trayChanged || boardChanged

        synchronized(lock) {
            if (!verified) {
                val existing = blacklist.firstOrNull {
                    abs(it.nx - move.normTapX) < 0.048f && abs(it.ny - move.normTapY) < 0.035f
                }
                if (existing != null) {
                    existing.hits++
                } else {
                    blacklist.add(BlacklistSpot(move.normTapX, move.normTapY, 1))
                }
                appendEventLocked(
                    String.format(
                        Locale.US,
                        "SELF_TRAIN_MISS tap=(%.3f,%.3f) type=T%d tray=%d->%d tiles=%d->%d -> BLACKLISTED",
                        move.normTapX, move.normTapY, move.typeId, beforeTray, afterTray, beforeTiles, afterTiles,
                    ),
                )
            } else {
                appendEventLocked(
                    String.format(
                        Locale.US,
                        "TAP_OK step=#%d tap=(%.3f,%.3f) type=T%d tray=%d->%d (%s)",
                        move.stepNumber, move.normTapX, move.normTapY, move.typeId, beforeTray, afterTray, move.reason,
                    ),
                )
            }

            val cur = _metrics.value
            _metrics.value = TrainingMetrics(
                totalTaps = cur.totalTaps + 1,
                verifiedTaps = cur.verifiedTaps + if (verified) 1 else 0,
                phantomMisses = cur.phantomMisses + if (!verified) 1 else 0,
                blacklistedSpots = blacklist.size,
                logEntryCount = events.size,
            )
        }
    }

    fun recordSnapshot(analysis: BoardAnalysisResult, extraNote: String? = null) {
        val sb = StringBuilder()
        sb.appendLine("=== TILEPULSE BOARD SNAPSHOT ===")
        sb.appendLine(
            String.format(
                Locale.US,
                "res=%dx%d scanMs=%d unlocked=%d locked=%d knownLocked=%d protos=%d tray=%d/7 score=%.1f",
                analysis.imageWidth,
                analysis.imageHeight,
                analysis.analysisTimeMs,
                analysis.unlockedCount,
                analysis.lockedCount,
                analysis.knownLockedCount,
                analysis.prototypeCount,
                analysis.trayOccupiedCount,
                analysis.predictedScore,
            ),
        )
        if (extraNote != null) sb.appendLine("note=$extraNote")

        sb.append("tray=[")
        analysis.traySlots.forEachIndexed { idx, slot ->
            if (idx > 0) sb.append(", ")
            sb.append(if (slot.isOccupied) "$idx:T${slot.typeId}" else "$idx:_")
        }
        sb.appendLine("]")

        sb.appendLine("--- DETECTED TILES (${analysis.tiles.size}) ---")
        for (t in analysis.tiles.take(45)) {
            val state = when {
                t.isSideStack -> "STACK"
                t.isUnlocked -> "OPEN"
                else -> "LOCK"
            }
            sb.appendLine(
                String.format(
                    Locale.US,
                    "#%02d %s T%d(%.2f) box=(%.3f,%.3f)-(%.3f,%.3f) lum=%.2f vis=%.2f p=%s",
                    t.id,
                    state,
                    t.typeId,
                    t.matchConfidence,
                    t.normBounds.left,
                    t.normBounds.top,
                    t.normBounds.right,
                    t.normBounds.bottom,
                    t.luminance,
                    t.visibleRatio,
                    t.parentIds.joinToString(","),
                ),
            )
        }

        sb.appendLine("--- SOLVER PLAN (${analysis.plan.size}) ---")
        for (m in analysis.plan.take(8)) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "step#%d tile=#%d T%d tap=(%.3f,%.3f) clear=%s trayAfter=%d/7 (%s)",
                    m.stepNumber, m.tileId, m.typeId, m.normTapX, m.normTapY, m.clearsMatch3, m.predictedTrayAfter, m.reason,
                ),
            )
        }

        synchronized(lock) {
            latestSnapshot = sb.toString()
            _metrics.value = _metrics.value.copy(logEntryCount = events.size)
        }
    }

    fun buildShareableReport(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            val m = _metrics.value
            sb.appendLine("=== TILEPULSE SELF-TRAINING REPORT ===")
            sb.appendLine(
                "totalTaps=${m.totalTaps} verified=${m.verifiedTaps} phantomMisses=${m.phantomMisses} blacklisted=${m.blacklistedSpots}",
            )
            if (blacklist.isNotEmpty()) {
                sb.appendLine("--- BLACKLISTED PHANTOM ZONES ---")
                blacklist.forEach { b ->
                    sb.appendLine(String.format(Locale.US, "(%.3f, %.3f) hits=%d", b.nx, b.ny, b.hits))
                }
            }
            sb.appendLine("--- RECENT TRAINING EVENTS (${events.size}) ---")
            events.forEach { sb.appendLine(it) }
            sb.appendLine(latestSnapshot)
            return sb.toString()
        }
    }

    private fun appendEventLocked(line: String) {
        if (events.size >= 35) events.removeFirst()
        events.addLast(line)
    }
}
