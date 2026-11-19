package com.example

import android.graphics.BitmapFactory
import com.example.data.BotDiagnosticLog
import com.example.model.BotSettings
import com.example.model.RoiCalibration
import com.example.solver.TileSolver
import com.example.vision.BoardVisionAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VisionDiagnosticsTest {

    private fun runScreenshot(path: String): Pair<Int, Int> {
        BotDiagnosticLog.clearSession()
        val file = findFile(path)
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        val analyzer = BoardVisionAnalyzer()
        val solver = TileSolver()
        val raw = analyzer.analyzeRaw(bmp, RoiCalibration())
        val result = solver.solve(raw, BotSettings(), 10L)
        println("=== ${file.name} ===")
        val trayDesc = result.traySlots.joinToString(",") {
            if (it.isOccupied) "${it.slotIndex}:T${it.typeId}" else "${it.slotIndex}:_"
        }
        println(
            "  unlocked=${result.unlockedCount} locked=${result.lockedCount} " +
                "knownLocked=${result.knownLockedCount} tray=${result.trayOccupiedCount}/7 [$trayDesc] " +
                "protos=${result.prototypeCount} plan=${result.plan.size}",
        )
        result.tiles.filter { it.isUnlocked }.forEach { t ->
            println(
                String.format(
                    Locale.US,
                    "    tile#%02d %s T%d(%.2f) box=(%.3f,%.3f)-(%.3f,%.3f) lum=%.2f",
                    t.id,
                    if (t.isSideStack) "STACK" else "OPEN",
                    t.typeId,
                    t.matchConfidence,
                    t.normBounds.left,
                    t.normBounds.top,
                    t.normBounds.right,
                    t.normBounds.bottom,
                    t.luminance,
                ),
            )
        }
        result.plan.take(5).forEach { m ->
            println(
                String.format(
                    Locale.US,
                    "    plan#%d T%d tap=(%.3f,%.3f) clear=%s trayAfter=%d/7 (%s)",
                    m.stepNumber, m.typeId, m.normTapX, m.normTapY, m.clearsMatch3, m.predictedTrayAfter, m.reason,
                ),
            )
        }
        bmp.recycle()
        return result.unlockedCount to result.trayOccupiedCount
    }

    @Test
    fun verifyAllRealScreenshotsAndHomeScreen() {
        val home = runScreenshot("public/assets/aistudio/Screenshot_20261119_201016_2c79667a.jpg")
        val l2a = runScreenshot("public/assets/aistudio/Screenshot_20261119_100821_4fd83fcee830ed007aacc9cec66270f7_06cc319b.jpg")
        val l2b = runScreenshot("public/assets/aistudio/Screenshot_20261119_201012_4fd83fcee830ed007aacc9cec66270f7_397ab1df.jpg")
        val f1 = runScreenshot("public/assets/aistudio/Screenshot_20261119_203136_4fd83fcee830ed007aacc9cec66270f7__1__01766508.jpg")
        val f3 = runScreenshot("public/assets/aistudio/Screenshot_20261119_203518_4fd83fcee830ed007aacc9cec66270f7_97ea8009.jpg")
        val f4 = runScreenshot("public/assets/aistudio/Screenshot_20261119_203829_4fd83fcee830ed007aacc9cec66270f7_46c0b559.jpg")

        assertEquals("Home screen must yield 0 tiles", 0, home.first)
        assertTrue("Level 2a unlocked (was ${l2a.first})", l2a.first >= 18)
        assertEquals("Level 2a tray", 3, l2a.second)
        assertTrue("Level 2b unlocked (was ${l2b.first})", l2b.first >= 15)
        assertEquals("Level 2b tray", 0, l2b.second)
        assertTrue("Sparse F1 unlocked in 4..6 (was ${f1.first})", f1.first in 4..6)
        assertEquals("Sparse F1 tray", 4, f1.second)
        assertTrue("Sparse F3 unlocked in 2..4 (was ${f3.first})", f3.first in 2..4)
        assertEquals("Sparse F3 tray", 2, f3.second)
        assertTrue("Return-3 F4 unlocked >= 14 (was ${f4.first})", f4.first >= 14)
        assertEquals("Return-3 F4 tray", 6, f4.second)
    }

    @Test
    fun selfTrainingBlacklistsPhantomSpotAndExportsReport() {
        BotDiagnosticLog.clearSession()
        val file = findFile("public/assets/aistudio/Screenshot_20261119_203518_4fd83fcee830ed007aacc9cec66270f7_97ea8009.jpg")
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        val analyzer = BoardVisionAnalyzer()
        val solver = TileSolver()

        val initialRaw = analyzer.analyzeRaw(bmp, RoiCalibration())
        val initialResult = solver.solve(initialRaw, BotSettings(), 10L)
        val firstMove = initialResult.plan.first()

        BotDiagnosticLog.recordSnapshot(initialResult, extraNote = "unit_test")
        BotDiagnosticLog.recordTapOutcome(
            move = firstMove,
            beforeTray = initialResult.trayOccupiedCount,
            afterTray = initialResult.trayOccupiedCount,
            beforeTiles = initialResult.tiles.size,
            afterTiles = initialResult.tiles.size,
        )

        val metrics = BotDiagnosticLog.metrics.value
        assertEquals(1, metrics.phantomMisses)
        assertEquals(1, metrics.blacklistedSpots)

        val reRaw = analyzer.analyzeRaw(bmp, RoiCalibration())
        assertEquals(initialRaw.tiles.count { it.isUnlocked } - 1, reRaw.tiles.count { it.isUnlocked })

        val report = BotDiagnosticLog.buildShareableReport()
        assertTrue(report.contains("SELF_TRAIN_MISS"))
        assertTrue(report.contains("BLACKLISTED PHANTOM ZONES"))

        BotDiagnosticLog.clearSession()
        assertEquals(0, BotDiagnosticLog.metrics.value.blacklistedSpots)
        bmp.recycle()
    }

    private fun findFile(relPath: String): File {
        val f = File(relPath)
        return if (f.exists()) f else File("..", relPath)
    }
}
