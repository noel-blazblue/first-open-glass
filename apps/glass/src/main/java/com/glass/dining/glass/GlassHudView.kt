package com.glass.dining.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.glass.dining.shared.hud.HudOverlay
import com.glass.dining.shared.hud.ReimuHud
import com.glass.dining.shared.hud.TalkCaption
import com.glass.dining.shared.hud.TalkLayout
import com.glass.dining.shared.hud.TalkPose
import com.glass.dining.shared.indoor.OccupancyGrid
import com.glass.dining.shared.indoor.Pose3
import com.glass.dining.shared.indoor.TrackQuality
import com.glass.dining.shared.link.HudDrawScene
import com.glass.dining.shared.link.HudLines
import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.glass.spatial.SensorProbe
import com.glass.dining.glass.spatial.WorldAnchorRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.glass.dining.shared.link.HudWire

class GlassHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile var card: HudLines = HudLines.idle()
    @Volatile var draw: HudDrawScene? = null
    @Volatile var store: PlaceProfile? = null
    @Volatile var storeCaption: String = ""
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
    private var talkKey = ""
    private var talkKeyAt = 0L

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val shown = store
        val scene = draw
        when (HudOverlay.face(scene != null, shown != null, card.isNav, card.isTalk)) {
            HudOverlay.DRAW -> scene?.let { HudDrawRenderer.draw(canvas, it, w, h) }
            HudOverlay.STORE -> shown?.let { StoreDetailHud.draw(canvas, it, storeCaption, w, h) }
            HudOverlay.NAV -> drawNav(canvas, cx, w, h)
            HudOverlay.TALK -> drawTalk(canvas, w, h)
            else -> drawCard(canvas, cx, h)
        }
        green.textAlign = Paint.Align.LEFT
        green.textSize = 14f
        canvas.drawText(clockFmt.format(Date()), 16f, h - 16f, green)
        if (isProductStatus(status)) {
            green.textAlign = Paint.Align.CENTER
            green.textSize = 13f
            canvas.drawText(status.take(16), cx, h - 36f, green)
        }
        green.textAlign = Paint.Align.CENTER
    }

    private fun isProductStatus(line: String): Boolean {
        return line.contains("权限")
    }

    private fun drawTalk(canvas: Canvas, w: Float, h: Float) {
        val now = SystemClock.uptimeMillis()
        val size = TalkLayout.characterSize(w, h)
        val charCx = TalkLayout.characterCenterX(w, h)
        val charCy = TalkLayout.characterCenterY(h)
        green.textSize = TalkLayout.TEXT_SIZE_PX
        green.textAlign = Paint.Align.LEFT
        val pending = TalkCaption.lines(w, h, card.speech, green.fontMetrics.descent)
        val key = TalkCaption.key(pending)
        if (key != talkKey) {
            talkKey = key
            talkKeyAt = now
        }
        val scroll = ((now - talkKeyAt) / TalkLayout.SCROLL_MS).coerceIn(0f, 1f)
        val lines = TalkCaption.lines(w, h, card.speech, green.fontMetrics.descent, scroll = scroll)
        lines.forEach { line ->
            green.alpha = (line.alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawText(line.text, line.x, line.y, green)
        }
        green.alpha = 255
        ReimuHud.draw(
            canvas,
            resources,
            charCx,
            charCy,
            size,
            TalkPose.of(card.pose, card.speech),
            now / 1000f,
        )
        green.style = Paint.Style.FILL
        green.textAlign = Paint.Align.CENTER
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
            else -> drawGround(canvas, w, h)
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
        screens.forEach { chevron ->
            drawChevron(canvas, chevron.x, chevron.y, chevron.size, chevron.angleDeg, chevron.alpha)
        }
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, size: Float, angleDeg: Float, alpha: Float) {
        val a = (alpha * 255f).toInt().coerceIn(40, 255)
        chevronPaint.alpha = a
        val half = size * 0.42f
        val depth = size * 0.28f
        canvas.save()
        canvas.rotate(angleDeg, x, y)
        path.reset()
        path.moveTo(x - half, y + depth)
        path.lineTo(x, y - depth)
        path.lineTo(x + half, y + depth)
        canvas.drawPath(path, chevronPaint)
        canvas.restore()
        chevronPaint.alpha = 255
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
        if (card.title.isNotBlank()) canvas.drawText(card.title, cx, top, green)
        green.textSize = 18f
        if (card.meta.isNotBlank()) canvas.drawText(card.meta, cx, top + gap, green)
        if (card.wait.isNotBlank()) canvas.drawText(card.wait, cx, top + gap * 2, green)
        if (card.extra.isNotBlank()) canvas.drawText(card.extra, cx, top + gap * 3, green)
    }
}
