package com.glass.dining.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.glass.dining.shared.link.HudDraw
import com.glass.dining.shared.link.HudDrawOp
import com.glass.dining.shared.link.HudDrawScene
import com.glass.dining.shared.link.HudPathSeg

object HudDrawRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(canvas: Canvas, scene: HudDrawScene, viewW: Float, viewH: Float) {
        val sx = if (viewW <= 0f) 1f else viewW / HudDraw.WIDTH
        val sy = if (viewH <= 0f) 1f else viewH / HudDraw.HEIGHT
        scene.ops.forEach { op ->
            val gray = (op.gray * 255f).toInt().coerceIn(0, 255)
            paint.color = Color.argb(255, gray, gray, gray)
            when (op.type) {
                "path" -> drawPath(canvas, op, sx, sy)
                "circle" -> drawCircle(canvas, op, sx, sy)
                "rect" -> drawRect(canvas, op, sx, sy)
                else -> drawText(canvas, op, sx, sy)
            }
        }
        paint.textAlign = Paint.Align.CENTER
        paint.alpha = 255
    }

    private fun drawPath(canvas: Canvas, op: HudDrawOp, sx: Float, sy: Float) {
        path.reset()
        var started = false
        HudDraw.parsePath(op.d).forEach { seg ->
            when (seg) {
                is HudPathSeg.Move -> {
                    path.moveTo(seg.x * sx, seg.y * sy)
                    started = true
                }
                is HudPathSeg.Line -> {
                    if (!started) {
                        path.moveTo(seg.x * sx, seg.y * sy)
                        started = true
                    } else {
                        path.lineTo(seg.x * sx, seg.y * sy)
                    }
                }
                is HudPathSeg.Cubic -> {
                    path.cubicTo(seg.x1 * sx, seg.y1 * sy, seg.x2 * sx, seg.y2 * sy, seg.x * sx, seg.y * sy)
                    started = true
                }
                is HudPathSeg.Quad -> {
                    path.quadTo(seg.x1 * sx, seg.y1 * sy, seg.x * sx, seg.y * sy)
                    started = true
                }
                HudPathSeg.Close -> path.close()
            }
        }
        paintShape(op, sx, sy)
        paintOnce(op.kind) { canvas.drawPath(path, paint) }
    }

    private fun drawCircle(canvas: Canvas, op: HudDrawOp, sx: Float, sy: Float) {
        val radius = op.radius * minOf(sx, sy)
        paintShape(op, sx, sy)
        paintOnce(op.kind) { canvas.drawCircle(op.x * sx, op.y * sy, radius, paint) }
    }

    private fun drawRect(canvas: Canvas, op: HudDrawOp, sx: Float, sy: Float) {
        paintShape(op, sx, sy)
        paintOnce(op.kind) {
            canvas.drawRect(op.x * sx, op.y * sy, (op.x + op.boxW) * sx, (op.y + op.boxH) * sy, paint)
        }
    }

    private fun drawText(canvas: Canvas, op: HudDrawOp, sx: Float, sy: Float) {
        paint.style = Paint.Style.FILL
        paint.textSize = op.size * sy
        paint.textAlign = when (op.align) {
            "l" -> Paint.Align.LEFT
            "r" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        canvas.drawText(op.text, op.x * sx, op.y * sy, paint)
    }

    private fun paintShape(op: HudDrawOp, sx: Float, sy: Float) {
        paint.strokeWidth = (op.stroke * (sx + sy) / 2f).coerceAtLeast(1f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
    }

    private inline fun paintOnce(kind: String, draw: () -> Unit) {
        when (kind) {
            "f" -> {
                paint.style = Paint.Style.FILL
                draw()
            }
            "b" -> {
                paint.style = Paint.Style.FILL
                draw()
                paint.style = Paint.Style.STROKE
                draw()
            }
            else -> {
                paint.style = Paint.Style.STROKE
                draw()
            }
        }
    }
}
