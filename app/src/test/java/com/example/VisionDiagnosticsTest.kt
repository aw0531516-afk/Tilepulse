package com.example

import android.graphics.BitmapFactory
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VisionDiagnosticsTest {

    @Test
    fun detectsGameBoardsAndRejectsHomeScreen() {
        val game1 = findFile("public/assets/aistudio/Screenshot_20261119_100821_4fd83fcee830ed007aacc9cec66270f7_06cc319b.jpg")
        val game2 = findFile("public/assets/aistudio/Screenshot_20261119_201012_4fd83fcee830ed007aacc9cec66270f7_397ab1df.jpg")
        val home = findFile("public/assets/aistudio/Screenshot_20261119_201016_2c79667a.jpg")

        val analyzer = BoardVisionAnalyzer()
        val solver = TileSolver()
        val roi = RoiCalibration()

        // Game Screenshot 1 (3 tiles in tray)
        run {
            val bmp = BitmapFactory.decodeFile(game1.absolutePath)
            analyzer.resetPrototypes()
            val solved = solver.solve(analyzer.analyzeRaw(bmp, roi), BotSettings())
            println(
                "GAME1: unlocked=${solved.unlockedCount} locked=${solved.lockedCount} " +
                    "knownLocked=${solved.knownLockedCount} tray=${solved.trayOccupiedCount}/7 " +
                    "protos=${solved.prototypeCount} plan=${solved.plan.size}",
            )
            assertTrue("Expected >=12 unlocked tiles in Game 1", solved.unlockedCount >= 12)
            assertTrue("Expected >=3 locked tiles in Game 1", solved.lockedCount >= 3)
            assertEquals("Expected 3 tray tiles in Game 1", 3, solved.trayOccupiedCount)
            assertTrue("Expected non-empty plan in Game 1", solved.plan.isNotEmpty())
            bmp.recycle()
        }

        // Game Screenshot 2 (0 tiles in tray, dense board)
        run {
            val bmp = BitmapFactory.decodeFile(game2.absolutePath)
            analyzer.resetPrototypes()
            val solved = solver.solve(analyzer.analyzeRaw(bmp, roi), BotSettings())
            println(
                "GAME2: unlocked=${solved.unlockedCount} locked=${solved.lockedCount} " +
                    "knownLocked=${solved.knownLockedCount} tray=${solved.trayOccupiedCount}/7 " +
                    "protos=${solved.prototypeCount} plan=${solved.plan.size}",
            )
            assertTrue("Expected >=20 unlocked tiles in Game 2", solved.unlockedCount >= 20)
            assertEquals("Expected 0 tray tiles in Game 2", 0, solved.trayOccupiedCount)
            assertTrue("Expected non-empty plan in Game 2", solved.plan.isNotEmpty())
            bmp.recycle()
        }

        // Home Screen (must be rejected completely)
        run {
            val bmp = BitmapFactory.decodeFile(home.absolutePath)
            analyzer.resetPrototypes()
            val solved = solver.solve(analyzer.analyzeRaw(bmp, roi), BotSettings())
            println("HOME: unlocked=${solved.unlockedCount} locked=${solved.lockedCount} tray=${solved.trayOccupiedCount}/7")
            assertEquals("Home screen must not detect tiles", 0, solved.tiles.size)
            assertEquals("Home screen must not detect tray", 0, solved.trayOccupiedCount)
            bmp.recycle()
        }
    }

    private fun findFile(relPath: String): File {
        val f = File(relPath)
        return if (f.exists()) f else File("..", relPath)
    }
}
