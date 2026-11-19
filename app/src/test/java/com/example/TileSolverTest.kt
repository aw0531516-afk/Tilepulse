package com.example

import android.graphics.RectF
import com.example.model.BotSettings
import com.example.model.DetectedTile
import com.example.model.TraySlot
import com.example.model.UNKNOWN_TILE_TYPE
import com.example.solver.BitSet256
import com.example.solver.TileSolver
import com.example.vision.RawVisionDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSolverTest {

    @Test
    fun bitSet256_handlesHighIndicesAccurately() {
        var bs = BitSet256()
        bs = bs.setBit(0).setBit(65).setBit(130).setBit(240)
        assertTrue(bs.hasBit(0))
        assertTrue(bs.hasBit(65))
        assertTrue(bs.hasBit(130))
        assertTrue(bs.hasBit(240))
        assertEquals(4, bs.popCount())

        bs = bs.clearBit(130)
        assertFalse(bs.hasBit(130))
        assertEquals(3, bs.popCount())
    }

    @Test
    fun solver_plansMultiStepUnlockToClearMatch3Before7SlotOverflow() {
        // Tray already has 5 items: [Type 0, Type 0, Type 2, Type 3, Type 4]
        // Only 2 slots left! Picking Type 5 + Type 6 would cause Game Over (7 slots full).
        // Unlocked tile #0 (Type 1) blocks Locked tile #1 (Type 0).
        // Smart lookahead must tap #0 (Tray=6), unlocking #1 (Type 0), then tap #1 -> Match-3 clears 3 slots (Tray=3)!
        val tiles = listOf(
            tile(id = 0, typeId = 1, unlocked = true, parents = emptyList(), children = listOf(1)),
            tile(id = 1, typeId = 0, unlocked = false, parents = listOf(0), children = emptyList()),
            tile(id = 2, typeId = 5, unlocked = true, parents = emptyList(), children = emptyList()),
            tile(id = 3, typeId = 6, unlocked = true, parents = emptyList(), children = emptyList()),
        )

        val trayTypes = listOf(0, 0, 2, 3, 4)
        val traySlots = (0 until 7).map { idx ->
            val t = trayTypes.getOrNull(idx) ?: UNKNOWN_TILE_TYPE
            TraySlot(
                slotIndex = idx,
                isOccupied = t != UNKNOWN_TILE_TYPE,
                typeId = t,
                representativeColor = 0,
                normBounds = RectF(),
            )
        }

        val solver = TileSolver()
        val result = solver.solve(
            detection = RawVisionDetection(
                imageWidth = 1080,
                imageHeight = 2400,
                tiles = tiles,
                traySlots = traySlots,
                prototypeCount = 7,
            ),
            settings = BotSettings(lookaheadDepth = 6, beamWidth = 64),
        )

        assertTrue("Expected at least 2 planned moves", result.plan.size >= 2)
        assertEquals("First move must remove blocker tile 0", 0, result.plan[0].tileId)
        assertEquals("Second move must tap newly unlocked tile 1", 1, result.plan[1].tileId)
        assertTrue("Second move must clear Match-3", result.plan[1].clearsMatch3)
        assertEquals("Tray count after Match-3 clear should drop to 4", 4, result.plan[1].predictedTrayAfter)
    }

    @Test
    fun solver_avoidsDeadlockTrapAndPlansTwoPlyChain() {
        // Tray has 4 items: [0, 0, 2, 3]
        // Path A (Trap): tap tile 2 (Type 5) -> Tray=5 -> dead end or high risk
        // Path B (Smart 2-ply): tap tile 0 (Type 1, unlocks tile 1 [Type 4]) -> tap tile 1 (unlocks tile 4 [Type 0]) -> tap tile 4 -> Match-3 clears 0!
        val tiles = listOf(
            tile(id = 0, typeId = 1, unlocked = true, parents = emptyList(), children = listOf(1)),
            tile(id = 1, typeId = 4, unlocked = false, parents = listOf(0), children = listOf(4)),
            tile(id = 4, typeId = 0, unlocked = false, parents = listOf(1), children = emptyList()),
            tile(id = 2, typeId = 5, unlocked = true, parents = emptyList(), children = emptyList()),
            tile(id = 3, typeId = 6, unlocked = true, parents = emptyList(), children = emptyList()),
        )

        val trayTypes = listOf(0, 0, 2, 3)
        val traySlots = (0 until 7).map { idx ->
            val t = trayTypes.getOrNull(idx) ?: UNKNOWN_TILE_TYPE
            TraySlot(
                slotIndex = idx,
                isOccupied = t != UNKNOWN_TILE_TYPE,
                typeId = t,
                representativeColor = 0,
                normBounds = RectF(),
            )
        }

        val solver = TileSolver()
        val result = solver.solve(
            detection = RawVisionDetection(
                imageWidth = 1080,
                imageHeight = 2400,
                tiles = tiles,
                traySlots = traySlots,
                prototypeCount = 7,
            ),
            settings = BotSettings(lookaheadDepth = 12, beamWidth = 280),
        )

        assertTrue("Expected 3-step chain plan", result.plan.size >= 3)
        assertEquals(0, result.plan[0].tileId)
        assertEquals(1, result.plan[1].tileId)
        assertEquals(4, result.plan[2].tileId)
        assertTrue(result.plan[2].clearsMatch3)
    }

    private fun tile(
        id: Int,
        typeId: Int,
        unlocked: Boolean,
        parents: List<Int>,
        children: List<Int>,
    ): DetectedTile {
        return DetectedTile(
            id = id,
            normBounds = RectF(0.1f, 0.2f, 0.2f, 0.3f),
            pixelBounds = RectF(108f, 480f, 216f, 720f),
            tapX = 162f,
            tapY = 600f,
            isUnlocked = unlocked,
            isSideStack = false,
            typeId = typeId,
            matchConfidence = 0.95f,
            visibleRatio = if (unlocked) 1f else 0.55f,
            luminance = if (unlocked) 0.9f else 0.45f,
            representativeColor = 0,
            parentIds = parents,
            childIds = children,
        )
    }
}
