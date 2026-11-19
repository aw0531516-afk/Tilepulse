package com.example.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.model.DetectedTile
import com.example.model.RoiCalibration
import com.example.model.TraySlot
import com.example.model.UNKNOWN_TILE_TYPE
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BoardVisionAnalyzer(
    private val prototypeBank: TilePrototypeBank = TilePrototypeBank(),
) {

    private data class CandidateBox(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val score: Float,
        val luminance: Float,
        val isUnlocked: Boolean,
        val isSideStack: Boolean,
    )

    fun resetPrototypes() {
        prototypeBank.clear()
    }

    fun analyzeRaw(
        bitmap: Bitmap,
        roi: RoiCalibration,
    ): RawVisionDetection {
        val origW = bitmap.width
        val origH = bitmap.height
        val workW = 540
        val workH = max(1, (workW.toFloat() * origH / origW).roundToInt())

        val scaled = if (origW == workW && origH == workH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, workW, workH, true)
        }

        val pixels = IntArray(workW * workH)
        scaled.getPixels(pixels, 0, workW, 0, 0, workW, workH)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val scaleX = origW.toFloat() / workW
        val scaleY = origH.toFloat() / workH

        // 0. Guard: verify we are actually looking at the puzzle game (desert margins + wooden 7-slot dock),
        // never Android Home Screen, launcher icons, or other apps.
        if (!isGameScreenPresent(pixels, workW, workH, roi)) {
            return RawVisionDetection(
                imageWidth = origW,
                imageHeight = origH,
                tiles = emptyList(),
                traySlots = emptyList(),
                prototypeCount = prototypeBank.size,
            )
        }

        // Precompute pixel masks and integral images for O(1) rectangular density queries
        val creamIntegral = IntArray((workW + 1) * (workH + 1))
        val nonBgIntegral = IntArray((workW + 1) * (workH + 1))
        val darkCardIntegral = IntArray((workW + 1) * (workH + 1))
        val lumaGrid = FloatArray(workW * workH)

        val stride = workW + 1
        for (y in 0 until workH) {
            var rowCream = 0
            var rowNonBg = 0
            var rowDark = 0
            val pyOff = y * workW
            val iOff = (y + 1) * stride
            val pOff = y * stride

            for (x in 0 until workW) {
                val c = pixels[pyOff + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                lumaGrid[pyOff + x] = luma
                val sat = if (maxC == 0) 0f else (maxC - minC) / maxC.toFloat()

                // Unlocked cream card background: warm bright ivory/cream (calibrated to real game pixels)
                val isCream = (
                    r >= 200 && g >= 165 && b >= 115 &&
                        r >= g - 10 && g >= b - 10 &&
                        (maxC - minC) <= 118 &&
                        luma >= 0.70f
                    )
                // Desert sandy background: warm orange-tan with noticeably lower luma and high R-B spread
                val isDesertBg = (
                    r in 135..220 && g in 88..165 && b in 40..115 &&
                        (r - b) >= 62 && sat >= 0.44f && luma in 0.36f..0.68f
                    )
                // Darkened locked card surface underneath upper tiles (lower R-B spread than desert sand)
                val isDarkCard = (
                    !isCream && !isDesertBg &&
                        luma in 0.16f..0.54f &&
                        (r - b) <= 60 && sat <= 0.48f
                    )

                if (isCream) rowCream++
                if (!isDesertBg) rowNonBg++
                if (isDarkCard) rowDark++

                creamIntegral[iOff + x + 1] = creamIntegral[pOff + x + 1] + rowCream
                nonBgIntegral[iOff + x + 1] = nonBgIntegral[pOff + x + 1] + rowNonBg
                darkCardIntegral[iOff + x + 1] = darkCardIntegral[pOff + x + 1] + rowDark
            }
        }

        // Try candidate tile widths (handles both 6-column and 7-column boards cleanly)
        val candidateRatios = listOf(0.104f, 0.111f, roi.tileWidthRatio).distinct()
        var bestTileW = (roi.tileWidthRatio * workW).roundToInt().coerceIn(28, 120)
        var bestTileH = (bestTileW * 1.07f).roundToInt()
        var unlockedCandidates = emptyList<CandidateBox>()

        for (ratio in candidateRatios) {
            val tw = (ratio * workW).roundToInt().coerceIn(28, 120)
            val th = (tw * 1.07f).roundToInt()
            val bLeft = (roi.boardLeft * workW).roundToInt().coerceIn(0, workW - tw - 1)
            val bRight = (roi.boardRight * workW).roundToInt().coerceIn(bLeft + tw + 1, workW)
            val bTop = (roi.boardTop * workH).roundToInt().coerceIn(0, workH - th - 1)
            val bBottom = (roi.boardBottom * workH).roundToInt().coerceIn(bTop + th + 1, workH)
            val sideStackY = (roi.sideStackTop * workH).roundToInt().coerceIn(bTop, bBottom)

            val found = findUnlockedCandidates(
                creamIntegral = creamIntegral,
                nonBgIntegral = nonBgIntegral,
                lumaGrid = lumaGrid,
                workW = workW,
                bLeft = bLeft,
                bTop = bTop,
                bRight = bRight,
                bBottom = bBottom,
                sideStackY = sideStackY,
                tileW = tw,
                tileH = th,
            )
            if (found.size > unlockedCandidates.size) {
                unlockedCandidates = found
                bestTileW = tw
                bestTileH = th
            }
        }

        val tileW = bestTileW
        val tileH = bestTileH
        val bLeft = (roi.boardLeft * workW).roundToInt().coerceIn(0, workW - tileW - 1)
        val bRight = (roi.boardRight * workW).roundToInt().coerceIn(bLeft + tileW + 1, workW)
        val bTop = (roi.boardTop * workH).roundToInt().coerceIn(0, workH - tileH - 1)
        val bBottom = (roi.boardBottom * workH).roundToInt().coerceIn(bTop + tileH + 1, workH)
        val sideStackY = (roi.sideStackTop * workH).roundToInt().coerceIn(bTop, bBottom)

        // Build occlusion mask covering all unlocked tiles
        val unlockedOcclusionMask = BooleanArray(workW * workH)
        for (box in unlockedCandidates) {
            val x0 = (box.x + 1).coerceIn(0, workW - 1)
            val y0 = (box.y + 1).coerceIn(0, workH - 1)
            val x1 = (box.x + box.w - 1).coerceIn(x0, workW)
            val y1 = (box.y + box.h - 1).coerceIn(y0, workH)
            for (y in y0 until y1) {
                val off = y * workW
                for (x in x0 until x1) {
                    unlockedOcclusionMask[off + x] = true
                }
            }
        }

        // 2. Detect Locked Underneath Tiles in unclaimed dark-card areas overlapping upper tiles
        val lockedCandidates = findLockedCandidates(
            darkCardIntegral = darkCardIntegral,
            unlockedMask = unlockedOcclusionMask,
            unlockedBoxes = unlockedCandidates,
            lumaGrid = lumaGrid,
            workW = workW,
            bLeft = bLeft,
            bTop = bTop,
            bRight = bRight,
            sideStackY = sideStackY,
            tileW = tileW,
            tileH = tileH,
        )

        // Combine & sort descending by luminance so DAG parents come before children
        val allCandidates = (unlockedCandidates + lockedCandidates)
            .sortedWith(compareByDescending<CandidateBox> { it.isUnlocked }.thenByDescending { it.luminance })
            .take(250)

        // 3. Build Dependency DAG
        val n = allCandidates.size
        val parentLists = Array(n) { mutableListOf<Int>() }
        val childLists = Array(n) { mutableListOf<Int>() }
        val tileArea = (tileW * tileH).toFloat()

        for (i in 0 until n) {
            val lower = allCandidates[i]
            if (lower.isUnlocked) continue
            for (j in 0 until i) {
                val upper = allCandidates[j]
                if (!upper.isUnlocked && upper.luminance < lower.luminance + 0.04f) continue
                val inter = intersectionArea(
                    upper.x, upper.y, upper.x + upper.w, upper.y + upper.h,
                    lower.x, lower.y, lower.x + lower.w, lower.y + lower.h,
                )
                if (inter / tileArea >= 0.04f) {
                    parentLists[i].add(j)
                    childLists[j].add(i)
                }
            }
        }

        // 4. Read 7-Slot Tray Dock
        val traySlots = detectTraySlots(
            pixels = pixels,
            creamIntegral = creamIntegral,
            workW = workW,
            workH = workH,
            roi = roi,
        )

        // 5. Extract patches & classify
        val detectedTiles = MutableList<DetectedTile?>(n) { null }

        // Pass A: Unoccluded Unlocked Tiles
        for (i in 0 until n) {
            val box = allCandidates[i]
            if (!box.isUnlocked) continue
            val patch = TilePrototypeBank.extractPatch(
                pixels = pixels,
                imgW = workW,
                imgH = workH,
                boxLeft = box.x,
                boxTop = box.y,
                boxRight = box.x + box.w,
                boxBottom = box.y + box.h,
                occludedMask = null,
            )
            val (typeId, conf) = prototypeBank.matchOrRegisterUnoccluded(patch)
            detectedTiles[i] = buildDetectedTile(
                id = i,
                box = box,
                workW = workW,
                workH = workH,
                scaleX = scaleX,
                scaleY = scaleY,
                typeId = typeId,
                conf = conf,
                visibleRatio = 1f,
                repColor = prototypeBank.representativeColorFor(typeId),
                parents = emptyList(),
                children = childLists[i],
                tapX = (box.x + box.w * 0.5f) * scaleX,
                tapY = (box.y + box.h * 0.48f) * scaleY,
            )
        }

        // Pass B: Locked Underneath Tiles
        for (i in 0 until n) {
            val box = allCandidates[i]
            if (box.isUnlocked) continue
            val tileOcclusion = BooleanArray(workW * workH)
            for (pId in parentLists[i]) {
                val pBox = allCandidates[pId]
                val x0 = max(box.x, pBox.x - 1).coerceIn(0, workW - 1)
                val y0 = max(box.y, pBox.y - 1).coerceIn(0, workH - 1)
                val x1 = min(box.x + box.w, pBox.x + pBox.w + 1).coerceIn(x0, workW)
                val y1 = min(box.y + box.h, pBox.y + pBox.h + 1).coerceIn(y0, workH)
                for (y in y0 until y1) {
                    val off = y * workW
                    for (x in x0 until x1) {
                        tileOcclusion[off + x] = true
                    }
                }
            }

            val patch = TilePrototypeBank.extractPatch(
                pixels = pixels,
                imgW = workW,
                imgH = workH,
                boxLeft = box.x,
                boxTop = box.y,
                boxRight = box.x + box.w,
                boxBottom = box.y + box.h,
                occludedMask = tileOcclusion,
            )
            val visibleRatio = patch.visibleCount.toFloat() / IconPatch.PATCH_SIZE
            val (typeId, conf) = prototypeBank.matchOccluded(patch)
            val repColor = if (typeId != UNKNOWN_TILE_TYPE) {
                prototypeBank.representativeColorFor(typeId)
            } else {
                patch.representativeColor
            }

            detectedTiles[i] = buildDetectedTile(
                id = i,
                box = box,
                workW = workW,
                workH = workH,
                scaleX = scaleX,
                scaleY = scaleY,
                typeId = typeId,
                conf = conf,
                visibleRatio = visibleRatio,
                repColor = repColor,
                parents = parentLists[i],
                children = childLists[i],
                tapX = (box.x + box.w * 0.5f) * scaleX,
                tapY = (box.y + box.h * 0.48f) * scaleY,
            )
        }

        return RawVisionDetection(
            imageWidth = origW,
            imageHeight = origH,
            tiles = detectedTiles.filterNotNull(),
            traySlots = traySlots,
            prototypeCount = prototypeBank.size,
        )
    }

    /**
     * Confirms the foreground screen is actually the 3-tile desert puzzle game by checking:
     * 1) Warm sandy desert margins at left/right edges of the board, and
     * 2) Warm wooden brown dock frame or cream tray tiles in the 7-slot dock band.
     */
    private fun isGameScreenPresent(
        pixels: IntArray,
        workW: Int,
        workH: Int,
        roi: RoiCalibration,
    ): Boolean {
        var desertSamples = 0
        var desertHits = 0
        val y0 = (0.20f * workH).roundToInt()
        val y1 = (0.64f * workH).roundToInt()
        val xStrips = listOf((0.03f * workW).roundToInt(), (0.06f * workW).roundToInt(), (0.94f * workW).roundToInt(), (0.97f * workW).roundToInt())

        var y = y0
        while (y < y1) {
            for (x in xStrips) {
                desertSamples++
                val c = pixels[y * workW + x.coerceIn(0, workW - 1)]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r in 130..235 && g in 80..175 && b in 35..120 && (r - b) >= 55) {
                    desertHits++
                }
            }
            y += 10
        }

        val desertRatio = if (desertSamples > 0) desertHits.toFloat() / desertSamples else 0f
        if (desertRatio < 0.60f) return false

        // Also check wooden dock frame / slots along tray band
        val trayFrameY = ((roi.trayTop + 0.004f) * workH).roundToInt().coerceIn(0, workH - 1)
        val trayMidY = (((roi.trayTop + roi.trayBottom) * 0.5f) * workH).roundToInt().coerceIn(0, workH - 1)
        var trayHits = 0
        var traySamples = 0
        var x = (roi.trayLeft * workW).roundToInt().coerceIn(0, workW - 1)
        val xEnd = (roi.trayRight * workW).roundToInt().coerceIn(x + 1, workW)
        while (x < xEnd) {
            traySamples++
            val cTop = pixels[trayFrameY * workW + x]
            val cMid = pixels[trayMidY * workW + x]
            if (isWoodOrTrayCard(cTop) || isWoodOrTrayCard(cMid)) {
                trayHits++
            }
            x += 8
        }
        val trayRatio = if (traySamples > 0) trayHits.toFloat() / traySamples else 0f
        return trayRatio >= 0.55f
    }

    private fun isWoodOrTrayCard(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val isWood = (r in 85..185 && g in 45..135 && b in 25..95 && r > g && g > b && (r - b) >= 35)
        val isTrayCream = (r >= 195 && g >= 160 && b >= 110 && (r - b) <= 118)
        return isWood || isTrayCream
    }

    private fun findUnlockedCandidates(
        creamIntegral: IntArray,
        nonBgIntegral: IntArray,
        lumaGrid: FloatArray,
        workW: Int,
        bLeft: Int,
        bTop: Int,
        bRight: Int,
        bBottom: Int,
        sideStackY: Int,
        tileW: Int,
        tileH: Int,
    ): List<CandidateBox> {
        val stride = workW + 1
        val rimW = (tileW * 0.14f).roundToInt().coerceAtLeast(3)
        val rimH = (tileH * 0.13f).roundToInt().coerceAtLeast(3)
        val raw = mutableListOf<CandidateBox>()

        val step = 3
        var y = bTop
        while (y <= bBottom - tileH) {
            var x = bLeft
            while (x <= bRight - tileW) {
                val topRim = rectMean(creamIntegral, stride, x, y, x + tileW, y + rimH)
                if (topRim >= 0.44f) {
                    val leftRim = rectMean(creamIntegral, stride, x, y, x + rimW, y + tileH - rimH)
                    val rightRim = rectMean(creamIntegral, stride, x + tileW - rimW, y, x + tileW, y + tileH - rimH)
                    val totalCream = rectMean(creamIntegral, stride, x, y, x + tileW, y + tileH)
                    val nonBg = rectMean(nonBgIntegral, stride, x, y, x + tileW, y + tileH)
                    if (leftRim >= 0.36f && rightRim >= 0.36f && nonBg >= 0.80f && totalCream in 0.24f..0.90f) {
                        val rimScore = topRim * 0.35f + leftRim * 0.32f + rightRim * 0.33f
                        val cx = x + tileW / 2
                        val cy = y + tileH / 2
                        val luma = rectLumaMean(lumaGrid, workW, x, y, x + tileW, y + tileH)
                        val isSide = (cy >= sideStackY) && (cx < (workW * 0.34f) || cx > (workW * 0.66f))
                        raw.add(
                            CandidateBox(
                                x = x,
                                y = y,
                                w = tileW,
                                h = tileH,
                                score = rimScore + if (totalCream < 0.78f) 0.1f else 0f,
                                luminance = luma,
                                isUnlocked = true,
                                isSideStack = isSide,
                            ),
                        )
                    }
                }
                x += step
            }
            y += step
        }

        return nonMaxSuppression(raw, minDistX = tileW * 0.62f, minDistY = tileH * 0.62f)
    }

    private fun findLockedCandidates(
        darkCardIntegral: IntArray,
        unlockedMask: BooleanArray,
        unlockedBoxes: List<CandidateBox>,
        lumaGrid: FloatArray,
        workW: Int,
        bLeft: Int,
        bTop: Int,
        bRight: Int,
        sideStackY: Int,
        tileW: Int,
        tileH: Int,
    ): List<CandidateBox> {
        val stride = workW + 1
        val raw = mutableListOf<CandidateBox>()
        val tileArea = (tileW * tileH).toFloat()
        val step = 4

        var y = bTop
        while (y <= sideStackY - tileH / 3) {
            var x = bLeft
            while (x <= bRight - tileW) {
                val darkDensity = rectMean(darkCardIntegral, stride, x, y, x + tileW, y + tileH)
                if (darkDensity >= 0.18f) {
                    var overlapsUnlocked = false
                    for (u in unlockedBoxes) {
                        val inter = intersectionArea(x, y, x + tileW, y + tileH, u.x, u.y, u.x + u.w, u.y + u.h)
                        if (inter / tileArea in 0.04f..0.78f) {
                            overlapsUnlocked = true
                            break
                        }
                    }
                    if (overlapsUnlocked) {
                        var unclaimed = 0
                        var sampleCount = 0
                        var sy = y
                        while (sy < y + tileH) {
                            val rowOff = sy * workW
                            var sx = x
                            while (sx < x + tileW) {
                                sampleCount++
                                if (!unlockedMask[rowOff + sx]) unclaimed++
                                sx += 2
                            }
                            sy += 2
                        }
                        val unclaimedRatio = if (sampleCount > 0) unclaimed.toFloat() / sampleCount else 0f
                        if (unclaimedRatio in 0.18f..0.90f) {
                            val luma = rectLumaMean(lumaGrid, workW, x, y, x + tileW, y + tileH)
                            val score = darkDensity * 0.7f + (1f - abs(unclaimedRatio - 0.50f)) * 0.3f
                            raw.add(
                                CandidateBox(
                                    x = x,
                                    y = y,
                                    w = tileW,
                                    h = tileH,
                                    score = score,
                                    luminance = luma,
                                    isUnlocked = false,
                                    isSideStack = false,
                                ),
                            )
                        }
                    }
                }
                x += step
            }
            y += step
        }

        val filtered = raw.filter { cand ->
            unlockedBoxes.none { u ->
                abs((cand.x + cand.w * 0.5f) - (u.x + u.w * 0.5f)) < tileW * 0.42f &&
                    abs((cand.y + cand.h * 0.5f) - (u.y + u.h * 0.5f)) < tileH * 0.42f
            }
        }
        return nonMaxSuppression(filtered, minDistX = tileW * 0.56f, minDistY = tileH * 0.56f)
    }

    private fun detectTraySlots(
        pixels: IntArray,
        creamIntegral: IntArray,
        workW: Int,
        workH: Int,
        roi: RoiCalibration,
    ): List<TraySlot> {
        val stride = workW + 1
        val tLeft = (roi.trayLeft * workW).roundToInt().coerceIn(0, workW - 7)
        val tRight = (roi.trayRight * workW).roundToInt().coerceIn(tLeft + 7, workW)
        val tTop = (roi.trayTop * workH).roundToInt().coerceIn(0, workH - 2)
        val tBottom = (roi.trayBottom * workH).roundToInt().coerceIn(tTop + 2, workH)
        val slotW = (tRight - tLeft) / 7f

        val slots = mutableListOf<TraySlot>()
        for (i in 0 until 7) {
            val sx0 = (tLeft + i * slotW + slotW * 0.05f).roundToInt().coerceIn(0, workW - 2)
            val sx1 = (tLeft + (i + 1) * slotW - slotW * 0.05f).roundToInt().coerceIn(sx0 + 1, workW)
            val creamDensity = rectMean(creamIntegral, stride, sx0, tTop, sx1, tBottom)
            val occupied = creamDensity >= 0.22f

            val normBounds = RectF(
                sx0.toFloat() / workW,
                tTop.toFloat() / workH,
                sx1.toFloat() / workW,
                tBottom.toFloat() / workH,
            )

            if (occupied) {
                val insetW = ((sx1 - sx0) * 0.08f).roundToInt()
                val insetH = ((tBottom - tTop) * 0.06f).roundToInt()
                val patch = TilePrototypeBank.extractPatch(
                    pixels = pixels,
                    imgW = workW,
                    imgH = workH,
                    boxLeft = (sx0 + insetW).coerceIn(0, workW - 2),
                    boxTop = (tTop + insetH).coerceIn(0, workH - 2),
                    boxRight = (sx1 - insetW).coerceIn(sx0 + 2, workW),
                    boxBottom = (tBottom - insetH).coerceIn(tTop + 2, workH),
                    occludedMask = null,
                )
                val (typeId, _) = prototypeBank.matchOrRegisterUnoccluded(patch)
                slots.add(
                    TraySlot(
                        slotIndex = i,
                        isOccupied = true,
                        typeId = typeId,
                        representativeColor = prototypeBank.representativeColorFor(typeId),
                        normBounds = normBounds,
                    ),
                )
            } else {
                slots.add(
                    TraySlot(
                        slotIndex = i,
                        isOccupied = false,
                        typeId = UNKNOWN_TILE_TYPE,
                        representativeColor = 0,
                        normBounds = normBounds,
                    ),
                )
            }
        }
        return slots
    }

    private fun nonMaxSuppression(
        candidates: List<CandidateBox>,
        minDistX: Float,
        minDistY: Float,
    ): List<CandidateBox> {
        val sorted = candidates.sortedByDescending { it.score }
        val kept = mutableListOf<CandidateBox>()
        for (cand in sorted) {
            val cx = cand.x + cand.w * 0.5f
            val cy = cand.y + cand.h * 0.5f
            val clashes = kept.any { k ->
                abs(cx - (k.x + k.w * 0.5f)) < minDistX &&
                    abs(cy - (k.y + k.h * 0.5f)) < minDistY
            }
            if (!clashes) {
                kept.add(cand)
            }
        }
        return kept
    }

    private fun buildDetectedTile(
        id: Int,
        box: CandidateBox,
        workW: Int,
        workH: Int,
        scaleX: Float,
        scaleY: Float,
        typeId: Int,
        conf: Float,
        visibleRatio: Float,
        repColor: Int,
        parents: List<Int>,
        children: List<Int>,
        tapX: Float,
        tapY: Float,
    ): DetectedTile {
        val norm = RectF(
            box.x.toFloat() / workW,
            box.y.toFloat() / workH,
            (box.x + box.w).toFloat() / workW,
            (box.y + box.h).toFloat() / workH,
        )
        val pix = RectF(
            box.x * scaleX,
            box.y * scaleY,
            (box.x + box.w) * scaleX,
            (box.y + box.h) * scaleY,
        )
        return DetectedTile(
            id = id,
            normBounds = norm,
            pixelBounds = pix,
            tapX = tapX,
            tapY = tapY,
            isUnlocked = box.isUnlocked,
            isSideStack = box.isSideStack,
            typeId = typeId,
            matchConfidence = conf,
            visibleRatio = visibleRatio,
            luminance = box.luminance,
            representativeColor = repColor,
            parentIds = parents,
            childIds = children,
        )
    }

    private fun rectMean(integral: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val ax0 = x0.coerceIn(0, stride - 2)
        val ay0 = y0.coerceAtLeast(0)
        val ax1 = x1.coerceIn(ax0 + 1, stride - 1)
        val ay1 = y1.coerceAtLeast(ay0 + 1)
        val area = (ax1 - ax0) * (ay1 - ay0)
        val sum = integral[ay1 * stride + ax1] -
            integral[ay0 * stride + ax1] -
            integral[ay1 * stride + ax0] +
            integral[ay0 * stride + ax0]
        return sum.toFloat() / area
    }

    private fun rectLumaMean(lumaGrid: FloatArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var sum = 0f
        var count = 0
        var y = y0
        while (y < y1) {
            val rowOff = y * width
            var x = x0
            while (x < x1) {
                sum += lumaGrid[rowOff + x]
                count++
                x += 3
            }
            y += 3
        }
        return if (count > 0) sum / count else 0f
    }

    private fun intersectionArea(
        ax0: Int, ay0: Int, ax1: Int, ay1: Int,
        bx0: Int, by0: Int, bx1: Int, by1: Int,
    ): Int {
        val ix0 = max(ax0, bx0)
        val iy0 = max(ay0, by0)
        val ix1 = min(ax1, bx1)
        val iy1 = min(ay1, by1)
        return if (ix1 > ix0 && iy1 > iy0) (ix1 - ix0) * (iy1 - iy0) else 0
    }
}

data class RawVisionDetection(
    val imageWidth: Int,
    val imageHeight: Int,
    val tiles: List<DetectedTile>,
    val traySlots: List<TraySlot>,
    val prototypeCount: Int,
)
