package com.glass.dining.shared.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.dining.shared.model.Store
import kotlin.math.roundToInt

object HudColors {
    val background = Color.Black
    val green = Color(0xFF22FF88)
    val greenDim = Color(0xFF8AD9A8)
    val warn = Color(0xFFFFDD66)
    val closed = Color(0xFFFF6B6B)
    val line = Color(0x3322FF88)
    val title = Color(0xFFF5F7FA)
    val muted = Color(0xFFB0BCCC)
    val gold = Color(0xFFFFC857)
    val cyan = Color(0xFF7EE0F2)
    val hint = Color(0xFFA8E6C4)
}

@Composable
fun StoreHud(
    state: HudUiState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HudColors.background)
            .padding(start = 16.dp, end = 16.dp, top = 44.dp, bottom = 12.dp),
    ) {
        val store = state.store
        if (store == null) {
            IdleHud(state)
        } else {
            StoreCardHud(store = store, state = state)
        }
    }
}

@Composable
private fun IdleHud(state: HudUiState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Hi",
            color = HudColors.green,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.idleHint,
                color = HudColors.green,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (state.recognizing) {
                    "正在查看…"
                } else if (state.listening) {
                    "正在听，请说话"
                } else {
                    "直接说话即可"
                },
                color = HudColors.greenDim,
                fontSize = 14.sp,
            )
        }
        HintBar(listening = state.listening, recognizing = state.recognizing)
    }
}

@Composable
private fun StoreCardHud(store: Store, state: HudUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${store.distanceMeters}m · ${store.category}",
                color = HudColors.greenDim,
                fontSize = 14.sp,
            )
            Text(
                text = if (store.openNow) "营业中" else "已打烊",
                color = if (store.openNow) HudColors.green else HudColors.closed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Text(
                text = store.shortName,
                color = HudColors.green,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "★ ${store.rating}",
                    color = HudColors.warn,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${formatCount(store.reviewCount)}评",
                    color = HudColors.greenDim,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "人均¥${store.avgPrice}",
                    color = HudColors.green,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = waitLine(store),
                color = if (store.waitMinutes >= 20) HudColors.warn else HudColors.green,
                fontSize = 16.sp,
            )
            state.confidence?.let {
                Text(
                    text = "识别 ${(it * 100).roundToInt()}%",
                    color = HudColors.greenDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            store.deals.take(2).forEach { deal ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .border(1.dp, HudColors.line, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = deal.title,
                        color = HudColors.green,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "¥${deal.price}",
                        color = HudColors.warn,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "¥${deal.original}",
                        color = HudColors.greenDim,
                        fontSize = 12.sp,
                    )
                }
            }
            val answer = state.answer
            if (!answer.isNullOrBlank()) {
                Text(
                    text = answer,
                    color = HudColors.green,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        HintBar(listening = state.listening, recognizing = state.recognizing)
    }
}

@Composable
private fun HintBar(listening: Boolean, recognizing: Boolean) {
    val text = when {
        recognizing -> "对准店招，正在识别…"
        listening -> "正在听，直接说话"
        else -> "说话发给电脑 · 单击仍可拍照"
    }
    Text(
        text = text,
        color = HudColors.greenDim,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
    )
}

private fun waitLine(store: Store): String {
    if (!store.openNow) return "今天已打烊 ${store.hours}"
    if (store.waitMinutes <= 0) return "不用排队，可以直接坐"
    return "排队约 ${store.waitTables} 桌 / ${store.waitMinutes} 分钟"
}

private fun formatCount(count: Int): String {
    return if (count >= 10000) {
        String.format("%.1f万", count / 10000.0)
    } else {
        count.toString()
    }
}
