package com.glass.nav.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.glass.dining.shared.indoor.OccupancyGrid
import com.glass.dining.shared.indoor.Pose3
import com.glass.dining.shared.indoor.TrackQuality
import com.glass.dining.shared.nav.HudLines
import com.glass.dining.shared.nav.IndoorProtocol
import com.glass.dining.shared.nav.NavProtocol
import com.glass.nav.glass.spatial.SensorProbe
import com.glass.nav.glass.spatial.WorldAnchorRenderer
import kotlin.math.atan
import kotlin.math.sin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NavHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile var card: HudLines = HudLines.idle()
    @Volatile var status: String = ""
    @Volatile var pitchDeg: Float = 0f
    @Volatile var pose: Pose3 = Pose3()
    @Volatile var quality: TrackQuality = TrackQuality.LOST
    var occupancy: OccupancyGrid = OccupancyGrid()
    private val renderer = WorldAnchorRenderer()

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
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val path = Path()
    private val chevronMeters = floatArrayOf(2.2f, 3.6f, 5.2f)

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
        canvas.drawText(dest, cx, h * 0.12f, green)
        when {
            card.mode == "arrive" || card.turn == "arrive" -> {
                drawTurn(canvas, cx, h * 0.42f, "arrive", w * 0.16f)
            }
            card.mode == "elevator" ||
                card.mode == "stairs" ||
                card.mode == "corridor" ||
                card.mode == "exit" -> {
                drawPointer(canvas, w, h)
            }
            card.visual -> drawGround(canvas, w, h)
            else -> drawTurn(canvas, cx, h * 0.42f, card.turn, w * 0.16f)
        }
        val meters = if (card.turn == "arrive") "到了" else if (card.meters > 0) "${card.meters}米" else card.meta
        green.textSize = 28f
        canvas.drawText(meters, cx, h * 0.78f, green)
        green.textSize = 16f
        if (card.wait.isNotBlank() && card.turn != "arrive") {
            canvas.drawText(card.wait, cx, h * 0.86f, green)
        }
        val remain = if (card.remaining > 0) "剩余${card.remaining}米" else card.extra
        if (remain.isNotBlank()) {
            green.textSize = 14f
            canvas.drawText(remain, cx, h * 0.93f, green)
        }
    }

    private fun drawGround(canvas: Canvas, w: Float, h: Float) {
        if (hideArrows()) return
        val screens = renderer.screens(
            pose = pose,
            quality = quality,
            waypoints = card.waypoints,
            occupancy = occupancy,
            headingDeg = card.headingDeg,
            viewW = w,
            viewH = h,
            calib = SensorProbe.calibration,
        )
        if (screens.isNotEmpty()) {
            screens.forEachIndexed { index, point ->
                val meters = 2.2f + index * 1.4f
                val scale = (1.15f / meters).coerceIn(0.16f, 0.48f) * w
                drawChevron(canvas, point.first, point.second, scale, card.headingDeg)
            }
            return
        }
        if (card.tracking.isNotBlank()) return
        chevronMeters.forEach { distance ->
            val point = projectGround(distance, card.headingDeg, pitchDeg, w, h) ?: return@forEach
            val scale = (1.15f / distance).coerceIn(0.16f, 0.48f) * w
            drawChevron(canvas, point.first, point.second, scale, card.headingDeg)
        }
    }

    private fun hideArrows(): Boolean {
        return quality == TrackQuality.LOST || card.tracking == IndoorProtocol.TRACK_LOST
    }

    private fun drawPointer(canvas: Canvas, w: Float, h: Float) {
        val hudHalf = 15f
        val x = (w / 2f + (card.headingDeg / hudHalf) * (w * 0.42f)).coerceIn(w * 0.12f, w * 0.88f)
        val y = (h * 0.46f - (card.elevationDeg / hudHalf) * (h * 0.18f)).coerceIn(h * 0.22f, h * 0.68f)
        val size = w * 0.11f
        path.reset()
        path.moveTo(x, y - size)
        path.lineTo(x - size * 0.62f, y + size * 0.38f)
        path.lineTo(x + size * 0.62f, y + size * 0.38f)
        path.close()
        canvas.drawPath(path, arrow)
        stroke.strokeWidth = 2.2f
        canvas.drawCircle(x, y, size * 1.35f, stroke)
        green.textAlign = Paint.Align.CENTER
        green.textSize = 15f
        val label = when (card.mode) {
            "elevator" -> "电梯"
            "stairs" -> if (card.elevationDeg < 0f) "下楼梯" else "上楼梯"
            "exit" -> "出口"
            else -> "通道"
        }
        canvas.drawText(label, x, y + size * 2.1f, green)
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, size: Float, heading: Float) {
        val rad = Math.toRadians(heading.toDouble())
        val dx = (sin(rad) * size * 0.08f).toFloat()
        val half = size * 0.42f
        val depth = size * 0.28f
        path.reset()
        path.moveTo(x - half + dx, y + depth)
        path.lineTo(x + dx, y - depth)
        path.lineTo(x + half + dx, y + depth)
        canvas.drawPath(path, chevronPaint)
    }

    private fun projectGround(
        distanceM: Float,
        headingDeg: Float,
        pitchDeg: Float,
        w: Float,
        h: Float,
    ): Pair<Float, Float>? {
        if (kotlin.math.abs(headingDeg) > 22f) return null
        val lookDown = (-pitchDeg).coerceIn(-8f, 50f)
        val groundEl = Math.toDegrees(atan((CAM_HEIGHT_M / distanceM).toDouble())).toFloat()
        val el = (groundEl - lookDown).coerceIn(4f, 36f)
        val hudHalf = 15f
        val x = w / 2f + (headingDeg / hudHalf) * (w * 0.42f)
        val bandTop = h * 0.20f
        val bandBot = h * 0.68f
        val t = ((el - 6f) / 24f).coerceIn(0f, 1f)
        val y = bandTop + t * (bandBot - bandTop)
        return x to y
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

    companion object {
        private const val CAM_HEIGHT_M = 1.55f
    }
}
