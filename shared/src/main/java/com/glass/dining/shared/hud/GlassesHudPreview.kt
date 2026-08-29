package com.glass.dining.shared.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.glass.dining.shared.place.PlaceProfile

@Composable
fun GlassesHudPreview(
    card: HudCard,
    modifier: Modifier = Modifier,
    store: PlaceProfile? = null,
    storeCaption: String = "",
) {
    val clipped = card.clipped()
    Box(
        modifier = modifier.background(HudColors.background),
    ) {
        when {
            clipped.isNav -> NavPreview(clipped, Modifier.fillMaxSize())
            store != null -> StorePanelPreview(store, storeCaption, Modifier.fillMaxSize())
            clipped.isTalk -> TalkPreview(clipped, Modifier.fillMaxSize())
            else -> {
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
    val pose = TalkPose.of(card.pose, card.speech)
    var tSec by remember { mutableFloatStateOf(0f) }
    var talkKeyAt by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            tSec = (now - start) / 1_000_000_000f
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pending = TalkCaption.lines(w, h, card.speech)
        val key = TalkCaption.key(pending)
        LaunchedEffect(key) {
            talkKeyAt = System.nanoTime()
        }
        val scroll = ((System.nanoTime() - talkKeyAt) / 1_000_000f / TalkLayout.SCROLL_MS).coerceIn(0f, 1f)
        val lines = TalkCaption.lines(w, h, card.speech, scroll = scroll)
        val size = TalkLayout.characterSize(w, h)
        val charW = TalkLayout.characterWidth(w, h)
        val charCx = TalkLayout.characterCenterX(w, h)
        val charCy = TalkLayout.characterCenterY(h)
        val bob = kotlin.math.sin(tSec * 2.4f) * TalkLayout.BOB_PX
        Image(
            painter = painterResource(ReimuHud.resId(pose)),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (charCx - charW / 2f).roundToInt(),
                        (charCy - size / 2f + bob).roundToInt(),
                    )
                }
                .size(
                    width = with(density) { charW.toDp() },
                    height = with(density) { size.toDp() },
                ),
        )
        val textPx = TalkLayout.TEXT_SIZE_PX
        lines.forEach { line ->
            Text(
                text = line.text,
                color = HudColors.green.copy(alpha = line.alpha),
                fontSize = with(density) { textPx.toSp() },
                fontWeight = FontWeight.Normal,
                modifier = Modifier.offset {
                    IntOffset(
                        line.x.roundToInt(),
                        (line.y - textPx).roundToInt(),
                    )
                },
            )
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
    val shift = (card.headingDeg / 15f).coerceIn(-0.35f, 0.35f) * size.width * 0.28f
    val distances = listOf(0.58f, 0.44f, 0.32f)
    distances.forEachIndexed { index, t ->
        val y = size.height * t
        val half = size.width * (0.22f - index * 0.04f)
        val x = cx + shift * (index + 1) * 0.35f
        val p = Path().apply {
            moveTo(x - half, y + 8f)
            lineTo(x, y - 10f)
            lineTo(x + half, y + 8f)
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
