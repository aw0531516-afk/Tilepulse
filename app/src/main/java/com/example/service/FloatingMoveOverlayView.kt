package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.model.BoardAnalysisResult

class FloatingMoveOverlayView(context: Context) : View(context) {

    private var analysis: BoardAnalysisResult? = null

    private val unlockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#2ECC71")
    }

    private val lockedKnownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#F5A623")
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val sideStackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#A78BFA")
    }

    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5A623")
    }

    private val badgeClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2ECC71")
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1117")
        textSize = 26f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#CC38BDF8")
    }

    fun updateAnalysis(result: BoardAnalysisResult?) {
        analysis = result
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = analysis ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Draw tile boxes
        for (tile in data.tiles) {
            val rect = RectF(
                tile.normBounds.left * w,
                tile.normBounds.top * h,
                tile.normBounds.right * w,
                tile.normBounds.bottom * h,
            )
            when {
                tile.isSideStack -> canvas.drawRoundRect(rect, 10f, 10f, sideStackPaint)
                tile.isUnlocked -> canvas.drawRoundRect(rect, 10f, 10f, unlockedPaint)
                tile.typeId >= 0 -> canvas.drawRoundRect(rect, 10f, 10f, lockedKnownPaint)
            }
        }

        // Draw predicted move chain arrows & numbered circles
        val moves = data.plan.take(5)
        for (i in 0 until moves.size - 1) {
            val a = moves[i]
            val b = moves[i + 1]
            canvas.drawLine(a.normTapX * w, a.normTapY * h, b.normTapX * w, b.normTapY * h, linePaint)
        }

        for (move in moves) {
            val cx = move.normTapX * w
            val cy = move.normTapY * h
            val paint = if (move.clearsMatch3) badgeClearPaint else badgeFillPaint
            canvas.drawCircle(cx, cy, 20f, paint)
            val textY = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) * 0.5f
            canvas.drawText("${move.stepNumber}", cx, textY, badgeTextPaint)
        }
    }
}
