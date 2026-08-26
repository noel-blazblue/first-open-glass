package com.glass.nav.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.glass.dining.shared.nav.HudLines
import com.glass.dining.shared.nav.NavProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NavHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile var card: HudLines = HudLines.idle()
    @Volatile var status: String = ""

    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 0)
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 0)
        style = Paint.Style.FILL
    }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        when {
            card.isTalk -> drawTalk(canvas, cx, h)
            card.isNav -> drawNav(canvas, cx, w, h)
            else -> drawCard(canvas, cx, h)
        }
        green.textAlign = Paint.Align.LEFT
        green.textSize = 14f
        canvas.drawText(clockFmt.format(Date()), 16f, h - 16f, green)
        if (status.isNotBlank()) {
            green.textAlign = Paint.Align.CENTER
            green.textSize = 13f
            canvas.drawText(status.take(16), cx, h - 36f, green)
        }
        green.textAlign = Paint.Align.CENTER
    }

    private fun drawTalk(canvas: Canvas, cx: Float, h: Float) {
        val lines = NavProtocol.wrapSpeech(card.speech)
        val gap = 28f
        val orbY = h * 0.78f
        val textBottom = orbY - 28f
        green.textSize = 20f
        green.textAlign = Paint.Align.CENTER
        lines.asReversed().forEachIndexed { index, line ->
            canvas.drawText(line, cx, textBottom - index * gap, green)
        }
        val r = 18f
        canvas.drawCircle(cx, orbY, r, stroke)
        green.style = Paint.Style.FILL
        canvas.drawCircle(cx - 6f, orbY - 2f, 2.2f, green)
        canvas.drawCircle(cx + 6f, orbY - 2f, 2.2f, green)
    }

    private fun drawNav(canvas: Canvas, cx: Float, w: Float, h: Float) {
        green.textAlign = Paint.Align.CENTER
        green.textSize = 16f
        val dest = card.title.ifBlank { "去目的店" }
        canvas.drawText(dest, cx, h * 0.16f, green)
        val arrowCy = h * 0.42f
        drawTurn(canvas, cx, arrowCy, card.turn, w * 0.16f)
        val meters = if (card.turn == "arrive") "到了" else if (card.meters > 0) "${card.meters}米" else card.meta
        green.textSize = 36f
        canvas.drawText(meters, cx, h * 0.64f, green)
        green.textSize = 16f
        if (card.wait.isNotBlank() && card.turn != "arrive") {
            canvas.drawText(card.wait, cx, h * 0.74f, green)
        }
        val remain = if (card.remaining > 0) "剩余${card.remaining}米" else card.extra
        if (remain.isNotBlank()) {
            green.textSize = 14f
            canvas.drawText(remain, cx, h * 0.84f, green)
        }
    }

    private fun drawTurn(canvas: Canvas, cx: Float, cy: Float, turn: String, size: Float) {
        path.reset()
        when (turn) {
            "left" -> {
                path.moveTo(cx + size * 0.45f, cy - size)
                path.lineTo(cx - size * 0.55f, cy)
                path.lineTo(cx + size * 0.45f, cy + size)
                canvas.drawPath(path, arrowStroke)
            }
            "right" -> {
                path.moveTo(cx - size * 0.45f, cy - size)
                path.lineTo(cx + size * 0.55f, cy)
                path.lineTo(cx - size * 0.45f, cy + size)
                canvas.drawPath(path, arrowStroke)
            }
            "arrive" -> {
                canvas.drawCircle(cx, cy, size * 0.85f, stroke)
                canvas.drawCircle(cx, cy, size * 0.28f, arrow)
            }
            else -> {
                path.moveTo(cx, cy - size)
                path.lineTo(cx - size * 0.72f, cy + size * 0.42f)
                path.lineTo(cx + size * 0.72f, cy + size * 0.42f)
                path.close()
                canvas.drawPath(path, arrow)
            }
        }
    }

    private fun drawCard(canvas: Canvas, cx: Float, h: Float) {
        val top = h * 0.28f
        val gap = 36f
        green.textAlign = Paint.Align.CENTER
        green.textSize = 22f
        canvas.drawText(card.title.ifBlank { "到店餐饮" }, cx, top, green)
        green.textSize = 18f
        if (card.meta.isNotBlank()) canvas.drawText(card.meta, cx, top + gap, green)
        if (card.wait.isNotBlank()) canvas.drawText(card.wait, cx, top + gap * 2, green)
        if (card.extra.isNotBlank()) canvas.drawText(card.extra, cx, top + gap * 3, green)
    }
}
