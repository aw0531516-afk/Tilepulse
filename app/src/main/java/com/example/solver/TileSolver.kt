package com.example.solver

import com.example.model.BoardAnalysisResult
import com.example.model.BoosterType
import com.example.model.BotSettings
import com.example.model.DetectedTile
import com.example.model.PlannedMove
import com.example.model.UNKNOWN_TILE_TYPE
import com.example.vision.RawVisionDetection

class TileSolver {

    private data class SolverNode(
        val id: Int,
        val typeId: Int,
        val isSideStack: Boolean,
        val parentMask: BitSet256,
        val childIds: IntArray,
        val tapX: Float,
        val tapY: Float,
        val normTapX: Float,
        val normTapY: Float,
    )

    private data class StateKey(
        val remaining: BitSet256,
        val trayHash: Int,
    )

    private data class SearchState(
        val remaining: BitSet256,
        val trayCounts: IntArray,
        val trayTotal: Int,
        val peakTray: Int,
        val clearedTriples: Int,
        val score: Float,
        val moveIds: IntArray,
        val moveReasons: Array<String>,
        val moveClears: BooleanArray,
        val moveTrayAfter: IntArray,
    )

    fun solve(
        detection: RawVisionDetection,
        settings: BotSettings,
        elapsedVisionMs: Long = 0L,
    ): BoardAnalysisResult {
        val startNs = System.nanoTime()
        val rawTiles = detection.tiles
        val n = rawTiles.size

        val maxTypeId = maxOf(
            detection.prototypeCount,
            rawTiles.maxOfOrNull { it.typeId + 1 } ?: 0,
            detection.traySlots.maxOfOrNull { it.typeId + 1 } ?: 0,
            1,
        )

        val initialTray = IntArray(maxTypeId)
        var initialTrayTotal = 0
        for (slot in detection.traySlots) {
            if (slot.isOccupied && slot.typeId >= 0 && slot.typeId < maxTypeId) {
                initialTray[slot.typeId]++
                initialTrayTotal++
            }
        }

        val solverNodes = Array(n) { i ->
            val t = rawTiles[i]
            SolverNode(
                id = t.id,
                typeId = t.typeId,
                isSideStack = t.isSideStack,
                parentMask = BitSet256.fromIndices(t.parentIds),
                childIds = t.childIds.toIntArray(),
                tapX = t.tapX,
                tapY = t.tapY,
                normTapX = (t.normBounds.left + t.normBounds.right) * 0.5f,
                normTapY = (t.normBounds.top + t.normBounds.bottom) * 0.48f,
            )
        }

        var initialRemaining = BitSet256()
        for (i in 0 until n) {
            initialRemaining = initialRemaining.setBit(rawTiles[i].id)
        }

        val rootState = SearchState(
            remaining = initialRemaining,
            trayCounts = initialTray,
            trayTotal = initialTrayTotal,
            peakTray = initialTrayTotal,
            clearedTriples = 0,
            score = evaluateState(
                remaining = initialRemaining,
                trayCounts = initialTray,
                trayTotal = initialTrayTotal,
                peakTray = initialTrayTotal,
                clearedTriples = 0,
                nodes = solverNodes,
            ),
            moveIds = IntArray(0),
            moveReasons = emptyArray(),
            moveClears = BooleanArray(0),
            moveTrayAfter = IntArray(0),
        )

        var beam = listOf(rootState)
        var bestTerminalOrDeepest = rootState
        val depthLimit = settings.lookaheadDepth.coerceIn(2, 18)
        val beamWidth = settings.beamWidth.coerceIn(40, 500)

        for (ply in 0 until depthLimit) {
            val candidates = ArrayList<SearchState>(beam.size * 6)
            val visited = HashSet<StateKey>(beam.size * 8)

            for (state in beam) {
                if (state.remaining.isEmpty()) {
                    if (state.score > bestTerminalOrDeepest.score || bestTerminalOrDeepest.moveIds.isEmpty()) {
                        bestTerminalOrDeepest = state
                    }
                    continue
                }

                // Pre-check how many visible copies exist per type from this state
                val visibleSupply = IntArray(maxTypeId)
                for (tId in 0 until maxTypeId) visibleSupply[tId] = state.trayCounts[tId]
                for (node in solverNodes) {
                    if (state.remaining.hasBit(node.id) && node.typeId in 0 until maxTypeId) {
                        visibleSupply[node.typeId]++
                    }
                }

                for (node in solverNodes) {
                    if (!state.remaining.hasBit(node.id)) continue
                    if (node.parentMask.intersects(state.remaining)) continue // Locked!
                    if (node.typeId == UNKNOWN_TILE_TYPE) continue

                    val nextTray = state.trayCounts.copyOf()
                    nextTray[node.typeId]++
                    var nextTotal = state.trayTotal + 1
                    var clears = false
                    var nextClearedTriples = state.clearedTriples

                    if (nextTray[node.typeId] == 3) {
                        nextTray[node.typeId] = 0
                        nextTotal -= 3
                        clears = true
                        nextClearedTriples++
                    }

                    // Hard Game Over pruning: 7 slots full with no match-3
                    if (nextTotal >= 7) continue

                    val nextRemaining = state.remaining.clearBit(node.id)
                    val key = StateKey(nextRemaining, trayHash(nextTray))
                    if (!visited.add(key)) continue

                    val nextPeakTray = maxOf(state.peakTray, nextTotal)
                    val reason = describeMove(
                        node = node,
                        clears = clears,
                        nextRemaining = nextRemaining,
                        nextTray = nextTray,
                        solverNodes = solverNodes,
                    )

                    // Extra foresight penalties/bonuses for smarter move ordering
                    var foresightDelta = 0f
                    if (node.isSideStack && !clears) {
                        // Avoid drawing blind cards from side stacks while tray is getting tight
                        foresightDelta += when {
                            state.trayTotal >= 4 -> -48f
                            state.trayCounts[node.typeId] == 1 -> +12f
                            else -> -12f
                        }
                    }
                    // Penalize picking a tile whose visible total is < 3 unless it unlocks a needed child
                    if (!clears && visibleSupply[node.typeId] < 3) {
                        val unlocksUsefulChild = node.childIds.any { cid ->
                            val c = solverNodes.getOrNull(cid) ?: return@any false
                            nextRemaining.hasBit(c.id) && !c.parentMask.intersects(nextRemaining) &&
                                (c.typeId == UNKNOWN_TILE_TYPE || (c.typeId >= 0 && nextTray[c.typeId] > 0))
                        }
                        if (!unlocksUsefulChild) {
                            foresightDelta -= 18f
                        }
                    }

                    val evalScore = evaluateState(
                        remaining = nextRemaining,
                        trayCounts = nextTray,
                        trayTotal = nextTotal,
                        peakTray = nextPeakTray,
                        clearedTriples = nextClearedTriples,
                        nodes = solverNodes,
                    ) + foresightDelta

                    val nextState = SearchState(
                        remaining = nextRemaining,
                        trayCounts = nextTray,
                        trayTotal = nextTotal,
                        peakTray = nextPeakTray,
                        clearedTriples = nextClearedTriples,
                        score = evalScore,
                        moveIds = state.moveIds + node.id,
                        moveReasons = state.moveReasons + reason,
                        moveClears = state.moveClears + clears,
                        moveTrayAfter = state.moveTrayAfter + nextTotal,
                    )
                    candidates.add(nextState)
                }
            }

            if (candidates.isEmpty()) break

            candidates.sortByDescending { it.score }
            beam = if (candidates.size > beamWidth) candidates.subList(0, beamWidth) else candidates

            val top = beam.first()
            if (top.moveIds.isNotEmpty() && (bestTerminalOrDeepest.moveIds.isEmpty() || top.score >= bestTerminalOrDeepest.score - 5f)) {
                bestTerminalOrDeepest = top
            }
            if (top.remaining.isEmpty()) break
        }

        val chosenState = if (beam.firstOrNull()?.moveIds?.isNotEmpty() == true) beam.first() else bestTerminalOrDeepest
        val plannedMoves = buildPlannedMoves(chosenState, rawTiles, solverNodes)

        val recommendedBooster = when {
            plannedMoves.isEmpty() && initialTrayTotal >= 5 -> BoosterType.RETURN_THREE
            plannedMoves.isEmpty() && rawTiles.any { it.isUnlocked } -> BoosterType.SHUFFLE
            else -> null
        }

        val solveMs = (System.nanoTime() - startNs) / 1_000_000L
        val unlockedCount = rawTiles.count { it.isUnlocked }
        val lockedCount = rawTiles.size - unlockedCount
        val knownLockedCount = rawTiles.count { !it.isUnlocked && it.typeId != UNKNOWN_TILE_TYPE }

        return BoardAnalysisResult(
            imageWidth = detection.imageWidth,
            imageHeight = detection.imageHeight,
            tiles = rawTiles,
            traySlots = detection.traySlots,
            plan = plannedMoves,
            predictedScore = chosenState.score,
            recommendedBooster = recommendedBooster,
            analysisTimeMs = elapsedVisionMs + solveMs,
            unlockedCount = unlockedCount,
            lockedCount = lockedCount,
            knownLockedCount = knownLockedCount,
            prototypeCount = detection.prototypeCount,
        )
    }

    private fun evaluateState(
        remaining: BitSet256,
        trayCounts: IntArray,
        trayTotal: Int,
        peakTray: Int,
        clearedTriples: Int,
        nodes: Array<SolverNode>,
    ): Float {
        // Strong reward for clearing triples + slight preference for lower peak tray risk
        var score = clearedTriples * 165f - maxOf(0, peakTray - 4) * 14f

        val trayPenalty = when (trayTotal) {
            0 -> 0f
            1 -> -3f
            2 -> -9f
            3 -> -22f
            4 -> -50f
            5 -> -115f
            6 -> -310f
            else -> -1200f
        }
        score += trayPenalty

        val unlockedCounts = IntArray(trayCounts.size)
        val reachable1PlyCounts = IntArray(trayCounts.size)
        val lockedVisibleCounts = IntArray(trayCounts.size)
        var unlockLookaheadBonus = 0f

        for (node in nodes) {
            if (!remaining.hasBit(node.id)) continue
            val isUnlocked = !node.parentMask.intersects(remaining)
            if (isUnlocked) {
                if (node.typeId in 0 until unlockedCounts.size) {
                    unlockedCounts[node.typeId]++
                }
                val afterRemove = remaining.clearBit(node.id)
                for (cid in node.childIds) {
                    val child = nodes.getOrNull(cid) ?: continue
                    if (!afterRemove.hasBit(child.id)) continue
                    if (!child.parentMask.intersects(afterRemove)) {
                        // 1-ply unlocked child!
                        if (child.typeId in 0 until trayCounts.size) {
                            reachable1PlyCounts[child.typeId]++
                            val inTray = trayCounts[child.typeId]
                            unlockLookaheadBonus += when (inTray) {
                                2 -> 48f  // Next tap completes a Match-3!
                                1 -> 22f  // Unlocks matching pair partner
                                else -> 8f
                            }
                            // 2-ply grandchild lookahead
                            val afterChild = afterRemove.clearBit(child.id)
                            for (gcid in child.childIds) {
                                val grandChild = nodes.getOrNull(gcid) ?: continue
                                if (afterChild.hasBit(grandChild.id) && !grandChild.parentMask.intersects(afterChild)) {
                                    if (grandChild.typeId in 0 until trayCounts.size && trayCounts[grandChild.typeId] > 0) {
                                        unlockLookaheadBonus += 12f
                                    }
                                }
                            }
                        } else if (trayTotal <= 4) {
                            unlockLookaheadBonus += 6f
                        }
                    }
                }
                if (node.isSideStack && trayTotal <= 3) {
                    unlockLookaheadBonus += 2f
                }
            } else if (node.typeId in 0 until lockedVisibleCounts.size) {
                lockedVisibleCounts[node.typeId]++
            }
        }

        // Deadlock trap check at 6/7 slots: if no tile currently in tray has an unlocked 3rd copy, huge penalty
        var hasImmediateFinisher = false
        for (typeId in trayCounts.indices) {
            val inTray = trayCounts[typeId]
            if (inTray == 0) continue
            val availUnlocked = unlockedCounts[typeId]
            val avail1Ply = reachable1PlyCounts[typeId]
            val availTotal = inTray + availUnlocked + lockedVisibleCounts[typeId]

            if (inTray == 2 && availUnlocked >= 1) {
                hasImmediateFinisher = true
            }

            if (inTray + availUnlocked >= 3) {
                score += if (inTray == 2) 62f else 32f
            } else if (inTray + availUnlocked + avail1Ply >= 3) {
                score += 18f // Completable after 1 unblock!
            } else if (availTotal < 3) {
                score -= 24f * inTray
            }
        }

        if (trayTotal == 6 && !hasImmediateFinisher) {
            score -= 550f
        }

        score -= remaining.popCount() * 1.5f
        return score + unlockLookaheadBonus
    }

    private fun describeMove(
        node: SolverNode,
        clears: Boolean,
        nextRemaining: BitSet256,
        nextTray: IntArray,
        solverNodes: Array<SolverNode>,
    ): String {
        if (clears) {
            return "Completes Match-3 & clears 3 tray slots"
        }

        val unlockedChildren = node.childIds.toList().mapNotNull { cid ->
            val child = solverNodes.getOrNull(cid) ?: return@mapNotNull null
            if (nextRemaining.hasBit(child.id) && !child.parentMask.intersects(nextRemaining)) child else null
        }

        val matchingChild = unlockedChildren.firstOrNull {
            it.typeId in 0 until nextTray.size && nextTray[it.typeId] > 0
        }
        if (matchingChild != null) {
            return "Unlocks matching tile underneath"
        }

        if (unlockedChildren.isNotEmpty()) {
            return "Unlocks ${unlockedChildren.size} lower tile(s)"
        }

        if (node.isSideStack) {
            return "Draws from side stack"
        }

        if (node.typeId in 0 until nextTray.size && nextTray[node.typeId] == 2) {
            return "Pairs in tray (1 away from Match-3)"
        }

        return "Safe staging move"
    }

    private fun buildPlannedMoves(
        state: SearchState,
        rawTiles: List<DetectedTile>,
        solverNodes: Array<SolverNode>,
    ): List<PlannedMove> {
        val result = ArrayList<PlannedMove>(state.moveIds.size)
        var mask = BitSet256()
        for (t in rawTiles) mask = mask.setBit(t.id)

        for (step in state.moveIds.indices) {
            val tileId = state.moveIds[step]
            val node = solverNodes[tileId]
            val nextMask = mask.clearBit(tileId)
            val unlockedChildren = node.childIds.filter { cid ->
                val child = solverNodes.getOrNull(cid) ?: return@filter false
                nextMask.hasBit(child.id) && !child.parentMask.intersects(nextMask)
            }
            val uncoversUnknown = node.isSideStack || unlockedChildren.any {
                solverNodes[it].typeId == UNKNOWN_TILE_TYPE
            }

            result.add(
                PlannedMove(
                    stepNumber = step + 1,
                    tileId = tileId,
                    typeId = node.typeId,
                    tapX = node.tapX,
                    tapY = node.tapY,
                    normTapX = node.normTapX,
                    normTapY = node.normTapY,
                    clearsMatch3 = state.moveClears[step],
                    predictedTrayAfter = state.moveTrayAfter[step],
                    unlockedChildIds = unlockedChildren,
                    uncoversUnknown = uncoversUnknown,
                    reason = state.moveReasons[step],
                ),
            )
            mask = nextMask
        }
        return result
    }

    private fun trayHash(tray: IntArray): Int {
        var h = 1
        for (v in tray) h = 31 * h + v
        return h
    }
}
