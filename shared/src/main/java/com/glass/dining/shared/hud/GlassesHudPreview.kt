package com.glass.dining.shared.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GlassesHudPreview(
    card: HudCard,
    modifier: Modifier = Modifier,
) {
    val clipped = card.clipped()
    Box(
        modifier = modifier.background(HudColors.background),
    ) {
        if (clipped.isTalk) {
            TalkPreview(clipped, Modifier.fillMaxSize())
        } else if (clipped.isNav) {
            NavPreview(clipped, Modifier.fillMaxSize())
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PreviewLine(clipped.title, 22, FontWeight.Bold)
                if (clipped.meta.isNotBlank()) PreviewLine(clipped.meta, 15, FontWeight.Medium)
                if (clipped.wait.isNotBlank()) PreviewLine(clipped.wait, 15, FontWeight.Normal)
                if (clipped.extra.isNotBlank()) PreviewLine(clipped.extra, 15, FontWeight.Normal)
            }
        }
        Text(
            text = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()),
            color = HudColors.green,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun TalkPreview(card: HudCard, modifier: Modifier = Modifier) {
    val lines = HudCard.wrapSpeech(card.speech)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 36.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        lines.forEach { line ->
            PreviewLine(line, 18, FontWeight.Normal)
        }
        Canvas(
            modifier = Modifier
                .padding(top = 12.dp)
                .size(36.dp),
        ) {
            val stroke = Stroke(width = 2.5.dp.toPx())
            val r = size.minDimension / 2f - stroke.width
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = HudColors.green, radius = r, center = c, style = stroke)
            val eyeY = c.y - r * 0.12f
            val eyeR = 2.2.dp.toPx()
            val gap = r * 0.28f
            drawCircle(color = HudColors.green, radius = eyeR, center = Offset(c.x - gap, eyeY))
            drawCircle(color = HudColors.green, radius = eyeR, center = Offset(c.x + gap, eyeY))
        }
    }
}

@Composable
private fun NavPreview(card: HudCard, modifier: Modifier = Modifier) {
    val meters = if (card.turn == "arrive") "到了" else if (card.meters > 0) "${card.meters}米" else card.meta
    val remain = if (card.remaining > 0) "剩余${card.remaining}米" else card.extra
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewLine(card.title.ifBlank { "去目的店" }, 14, FontWeight.Medium)
        Canvas(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(72.dp),
        ) {
            if (card.visual && card.mode != "arrive" && card.turn != "arrive") {
                drawVisual(card)
            } else {
                drawTurn(card.turn)
            }
        }
        PreviewLine(meters, 28, FontWeight.Bold)
        if (card.wait.isNotBlank() && card.turn != "arrive") {
            PreviewLine(card.wait, 13, FontWeight.Normal)
        }
        if (remain.isNotBlank()) {
            PreviewLine(remain, 12, FontWeight.Normal)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVisual(card: HudCard) {
    val color = HudColors.green
    val cx = size.width / 2f
    if (card.mode == "elevator" || card.mode == "stairs" || card.mode == "corridor" || card.mode == "exit") {
        val x = (cx + (card.headingDeg / 15f) * size.width * 0.28f).coerceIn(size.width * 0.2f, size.width * 0.8f)
        val y = size.height * 0.42f
        val s = size.minDimension * 0.22f
        val p = Path().apply {
            moveTo(x, y - s)
            lineTo(x - s * 0.62f, y + s * 0.38f)
            lineTo(x + s * 0.62f, y + s * 0.38f)
            close()
        }
        drawPath(p, color)
        return
    }
    val distances = listOf(0.28f, 0.48f, 0.68f)
    distances.forEach { t ->
        val y = size.height * t
        val half = size.width * (0.38f - t * 0.18f)
        val p = Path().apply {
            moveTo(cx - half, y + 8f)
            lineTo(cx, y - 10f)
            lineTo(cx + half, y + 8f)
        }
        drawPath(p, color, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTurn(turn: String) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val s = size.minDimension * 0.38f
    val color = HudColors.green
    when (turn) {
        "left" -> {
            val p = Path().apply {
                moveTo(cx + s * 0.45f, cy - s)
                lineTo(cx - s * 0.55f, cy)
                lineTo(cx + s * 0.45f, cy + s)
            }
            drawPath(p, color, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        "right" -> {
            val p = Path().apply {
                moveTo(cx - s * 0.45f, cy - s)
                lineTo(cx + s * 0.55f, cy)
                lineTo(cx - s * 0.45f, cy + s)
            }
            drawPath(p, color, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        "arrive" -> {
            drawCircle(color, radius = s * 0.85f, center = Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))
            drawCircle(color, radius = s * 0.28f, center = Offset(cx, cy), style = Fill)
        }
        else -> {
            val p = Path().apply {
                moveTo(cx, cy - s)
                lineTo(cx - s * 0.72f, cy + s * 0.42f)
                lineTo(cx + s * 0.72f, cy + s * 0.42f)
                close()
            }
            drawPath(p, color)
        }
    }
}

@Composable
private fun PreviewLine(text: String, size: Int, weight: FontWeight) {
    Text(
        text = text,
        color = HudColors.green,
        fontSize = size.sp,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
