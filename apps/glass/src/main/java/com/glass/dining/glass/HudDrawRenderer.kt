package com.glass.dining.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.glass.dining.shared.link.HudDraw
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
            if (op.type == "path") {
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
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (op.stroke * (sx + sy) / 2f).coerceAtLeast(1f)
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawPath(path, paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.textSize = op.size * sy
                paint.textAlign = when (op.align) {
                    "l" -> Paint.Align.LEFT
                    "r" -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                canvas.drawText(op.text, op.x * sx, op.y * sy, paint)
            }
        }
        paint.textAlign = Paint.Align.CENTER
        paint.alpha = 255
    }
}
