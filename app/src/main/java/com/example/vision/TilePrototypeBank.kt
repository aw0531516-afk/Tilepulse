package com.example.vision

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class IconPatch(
    val gridR: FloatArray,         // size = PATCH_DIM * PATCH_DIM
    val gridG: FloatArray,
    val gridB: FloatArray,
    val gridEdge: FloatArray,
    val visibleCells: BooleanArray,
    val visibleCount: Int,
    val representativeColor: Int,
) {
    companion object {
        const val PATCH_DIM = 8
        const val PATCH_SIZE = PATCH_DIM * PATCH_DIM
    }
}

class TilePrototypeBank {

    private data class Prototype(
        val typeId: Int,
        val gridR: FloatArray,
        val gridG: FloatArray,
        val gridB: FloatArray,
        val gridEdge: FloatArray,
        val representativeColor: Int,
        var sampleCount: Int,
    )

    private val prototypes = mutableListOf<Prototype>()

    val size: Int
        get() = prototypes.size

    fun clear() {
        prototypes.clear()
    }

    /**
     * Matches an unoccluded icon patch against known prototypes using +/-1 grid cell shift alignment
     * and chromatic hue guards, or registers a new prototype if no clean match exists.
     */
    fun matchOrRegisterUnoccluded(patch: IconPatch, threshold: Float = 0.785f): Pair<Int, Float> {
        var bestId = -1
        var bestSim = -1f

        for (proto in prototypes) {
            // Try micro-shifts (-1..1 in x, y) so 3px box-step or tray-vs-board crop offsets match cleanly
            var maxShiftSim = -1f
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val sim = shiftedSimilarity(patch, proto, dx, dy)
                    if (sim > maxShiftSim) {
                        maxShiftSim = sim
                    }
                }
            }
            if (maxShiftSim > bestSim) {
                bestSim = maxShiftSim
                bestId = proto.typeId
            }
        }

        if (bestId != -1 && bestSim >= threshold) {
            val proto = prototypes[bestId]
            // Gentle exponential moving average update only on zero-shift high-confidence matches
            if (shiftedSimilarity(patch, proto, 0, 0) >= threshold) {
                val alpha = 1f / (proto.sampleCount + 1).coerceAtMost(8)
                for (i in 0 until IconPatch.PATCH_SIZE) {
                    proto.gridR[i] = proto.gridR[i] * (1f - alpha) + patch.gridR[i] * alpha
                    proto.gridG[i] = proto.gridG[i] * (1f - alpha) + patch.gridG[i] * alpha
                    proto.gridB[i] = proto.gridB[i] * (1f - alpha) + patch.gridB[i] * alpha
                    proto.gridEdge[i] = proto.gridEdge[i] * (1f - alpha) + patch.gridEdge[i] * alpha
                }
                proto.sampleCount++
            }
            return bestId to bestSim
        }

        val newId = prototypes.size
        prototypes.add(
            Prototype(
                typeId = newId,
                gridR = patch.gridR.copyOf(),
                gridG = patch.gridG.copyOf(),
                gridB = patch.gridB.copyOf(),
                gridEdge = patch.gridEdge.copyOf(),
                representativeColor = patch.representativeColor,
                sampleCount = 1,
            ),
        )
        return newId to 1.0f
    }

    /**
     * Matches a partially occluded lower-layer tile ONLY across its unoccluded visible cells.
     * Never registers a new prototype from an occluded tile.
     */
    fun matchOccluded(patch: IconPatch, minVisibleFraction: Float = 0.30f, threshold: Float = 0.72f): Pair<Int, Float> {
        val minCells = (IconPatch.PATCH_SIZE * minVisibleFraction).toInt()
        if (patch.visibleCount < minCells || prototypes.isEmpty()) {
            return -1 to 0f
        }

        var bestId = -1
        var bestSim = -1f
        var secondSim = -1f

        for (proto in prototypes) {
            val sim = shiftedSimilarity(patch, proto, 0, 0)
            if (sim > bestSim) {
                secondSim = bestSim
                bestSim = sim
                bestId = proto.typeId
            } else if (sim > secondSim) {
                secondSim = sim
            }
        }

        val marginOk = (prototypes.size == 1) || ((bestSim - secondSim) >= 0.025f) || (bestSim >= 0.87f)
        return if (bestId != -1 && bestSim >= threshold && marginOk) {
            bestId to bestSim
        } else {
            -1 to bestSim.coerceAtLeast(0f)
        }
    }

    fun representativeColorFor(typeId: Int): Int {
        return prototypes.getOrNull(typeId)?.representativeColor ?: Color.GRAY
    }

    private fun shiftedSimilarity(patch: IconPatch, proto: Prototype, dx: Int, dy: Int): Float {
        val dim = IconPatch.PATCH_DIM
        var count = 0
        var sumPR = 0f; var sumPG = 0f; var sumPB = 0f; var sumPE = 0f
        var sumQR = 0f; var sumQG = 0f; var sumQB = 0f; var sumQE = 0f

        for (py in 0 until dim) {
            val qy = py + dy
            if (qy !in 0 until dim) continue
            for (px in 0 until dim) {
                val qx = px + dx
                if (qx !in 0 until dim) continue
                val pi = py * dim + px
                if (!patch.visibleCells[pi]) continue
                val qi = qy * dim + qx
                count++
                sumPR += patch.gridR[pi]; sumPG += patch.gridG[pi]; sumPB += patch.gridB[pi]; sumPE += patch.gridEdge[pi]
                sumQR += proto.gridR[qi]; sumQG += proto.gridG[qi]; sumQB += proto.gridB[qi]; sumQE += proto.gridEdge[qi]
            }
        }
        if (count < 16) return 0f

        val meanPR = sumPR / count; val meanPG = sumPG / count; val meanPB = sumPB / count; val meanPE = sumPE / count
        val meanQR = sumQR / count; val meanQG = sumQG / count; val meanQB = sumQB / count; val meanQE = sumQE / count

        var dotColor = 0f; var normPColor = 0f; var normQColor = 0f
        var dotEdge = 0f; var normPEdge = 0f; var normQEdge = 0f
        var hueDot = 0f; var hueNP = 0f; var hueNQ = 0f

        for (py in 0 until dim) {
            val qy = py + dy
            if (qy !in 0 until dim) continue
            for (px in 0 until dim) {
                val qx = px + dx
                if (qx !in 0 until dim) continue
                val pi = py * dim + px
                if (!patch.visibleCells[pi]) continue
                val qi = qy * dim + qx

                val pr = patch.gridR[pi] - meanPR
                val pg = patch.gridG[pi] - meanPG
                val pb = patch.gridB[pi] - meanPB
                val qr = proto.gridR[qi] - meanQR
                val qg = proto.gridG[qi] - meanQG
                val qb = proto.gridB[qi] - meanQB

                dotColor += pr * qr + pg * qg + pb * qb
                normPColor += pr * pr + pg * pg + pb * pb
                normQColor += qr * qr + qg * qg + qb * qb

                val pe = patch.gridEdge[pi] - meanPE
                val qe = proto.gridEdge[qi] - meanQE
                dotEdge += pe * qe
                normPEdge += pe * pe
                normQEdge += qe * qe

                val prg = patch.gridR[pi] - patch.gridG[pi]
                val pby = patch.gridB[pi] - 0.5f * (patch.gridR[pi] + patch.gridG[pi])
                val qrg = proto.gridR[qi] - proto.gridG[qi]
                val qby = proto.gridB[qi] - 0.5f * (proto.gridR[qi] + proto.gridG[qi])
                hueDot += prg * qrg + pby * qby
                hueNP += prg * prg + pby * pby
                hueNQ += qrg * qrg + qby * qby
            }
        }

        val colorSim = if (normPColor > 1e-5f && normQColor > 1e-5f) {
            dotColor / (sqrt(normPColor) * sqrt(normQColor))
        } else 0f

        val edgeSim = if (normPEdge > 1e-5f && normQEdge > 1e-5f) {
            dotEdge / (sqrt(normPEdge) * sqrt(normQEdge))
        } else 0f

        val hueSim = if (hueNP > 1e-5f && hueNQ > 1e-5f) {
            hueDot / (sqrt(hueNP) * sqrt(hueNQ))
        } else {
            val diff = abs(hueNP - hueNQ)
            if (diff < 0.08f) 0.7f else 0.1f
        }

        // Strict chromatic guard: differently colored saturated icons (e.g., green cucumber vs red strawberry) cannot collide
        if (hueNP > 0.16f && hueNQ > 0.16f && hueSim < 0.50f) {
            return 0f
        }

        // Penalize large spatial shifts gently so zero-shift exact alignments win over shifted false positives
        val shiftPenalty = (abs(dx) + abs(dy)) * 0.015f
        return (0.56f * colorSim + 0.24f * edgeSim + 0.20f * hueSim - shiftPenalty).coerceIn(-1f, 1f)
    }

    companion object {
        fun extractPatch(
            pixels: IntArray,
            imgW: Int,
            imgH: Int,
            boxLeft: Int,
            boxTop: Int,
            boxRight: Int,
            boxBottom: Int,
            occludedMask: BooleanArray?,
        ): IconPatch {
            val w = (boxRight - boxLeft).coerceAtLeast(1)
            val h = (boxBottom - boxTop).coerceAtLeast(1)
            // Sample inner 66% icon core so outer tile bevels/borders are excluded
            val innerL = boxLeft + (w * 0.17f).toInt()
            val innerT = boxTop + (h * 0.15f).toInt()
            val innerR = boxRight - (w * 0.17f).toInt()
            val innerB = boxBottom - (h * 0.21f).toInt()
            val iw = (innerR - innerL).coerceAtLeast(IconPatch.PATCH_DIM)
            val ih = (innerB - innerT).coerceAtLeast(IconPatch.PATCH_DIM)

            val dim = IconPatch.PATCH_DIM
            val gridR = FloatArray(IconPatch.PATCH_SIZE)
            val gridG = FloatArray(IconPatch.PATCH_SIZE)
            val gridB = FloatArray(IconPatch.PATCH_SIZE)
            val gridEdge = FloatArray(IconPatch.PATCH_SIZE)
            val visibleCells = BooleanArray(IconPatch.PATCH_SIZE)

            var visibleCount = 0
            var fgR = 0L; var fgG = 0L; var fgB = 0L; var fgCount = 0L

            for (gy in 0 until dim) {
                val py0 = (innerT + gy * ih / dim).coerceIn(0, imgH - 1)
                val py1 = (innerT + (gy + 1) * ih / dim).coerceIn(py0 + 1, imgH)
                for (gx in 0 until dim) {
                    val px0 = (innerL + gx * iw / dim).coerceIn(0, imgW - 1)
                    val px1 = (innerL + (gx + 1) * iw / dim).coerceIn(px0 + 1, imgW)

                    var sumR = 0f; var sumG = 0f; var sumB = 0f; var sumE = 0f
                    var visPx = 0; var totPx = 0

                    for (y in py0 until py1) {
                        val rowOffset = y * imgW
                        for (x in px0 until px1) {
                            totPx++
                            val idx = rowOffset + x
                            if (occludedMask != null && occludedMask[idx]) continue
                            visPx++
                            val c = pixels[idx]
                            val r = (c shr 16) and 0xFF
                            val g = (c shr 8) and 0xFF
                            val b = c and 0xFF
                            sumR += r / 255f
                            sumG += g / 255f
                            sumB += b / 255f

                            val cr = if (x + 1 < imgW) pixels[idx + 1] else c
                            val cb = if (y + 1 < imgH) pixels[idx + imgW] else c
                            val lum = 0.3f * r + 0.59f * g + 0.11f * b
                            val lumR = 0.3f * ((cr shr 16) and 0xFF) + 0.59f * ((cr shr 8) and 0xFF) + 0.11f * (cr and 0xFF)
                            val lumB = 0.3f * ((cb shr 16) and 0xFF) + 0.59f * ((cb shr 8) and 0xFF) + 0.11f * (cb and 0xFF)
                            sumE += (abs(lum - lumR) + abs(lum - lumB)) / 255f

                            val sat = if (max(r, max(g, b)) == 0) 0f else (max(r, max(g, b)) - min(r, min(g, b))) / max(r, max(g, b)).toFloat()
                            if (sat > 0.24f || lum < 175f) {
                                fgR += r; fgG += g; fgB += b; fgCount++
                            }
                        }
                    }

                    val cellIdx = gy * dim + gx
                    if (totPx > 0 && visPx * 2 >= totPx) {
                        visibleCells[cellIdx] = true
                        visibleCount++
                        val inv = 1f / visPx
                        gridR[cellIdx] = sumR * inv
                        gridG[cellIdx] = sumG * inv
                        gridB[cellIdx] = sumB * inv
                        gridEdge[cellIdx] = sumE * inv
                    } else {
                        visibleCells[cellIdx] = false
                    }
                }
            }

            val repColor = if (fgCount > 0) {
                Color.rgb((fgR / fgCount).toInt(), (fgG / fgCount).toInt(), (fgB / fgCount).toInt())
            } else {
                Color.rgb(220, 200, 170)
            }

            return IconPatch(
                gridR = gridR,
                gridG = gridG,
                gridB = gridB,
                gridEdge = gridEdge,
                visibleCells = visibleCells,
                visibleCount = visibleCount,
                representativeColor = repColor,
            )
        }
    }
}
