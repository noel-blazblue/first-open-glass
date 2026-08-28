package com.glass.dining.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.glass.dining.phone.link.LinkUiState
import com.glass.dining.shared.hud.GlassesHudPreview
import com.glass.dining.shared.hud.HudColors
import com.glass.dining.shared.model.Store

private val Panel = Color(0xFF1A1A1A)
private val TextMain = Color(0xFFEEFFEE)
private val TextDim = Color(0xFF99BB99)

@Composable
fun PhoneDiningScreen(viewModel: DiningViewModel) {
    val state by viewModel.ui.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("到餐 Agent", color = HudColors.green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(state.status, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        LinkDiagnostics(state.link)
        Spacer(Modifier.height(12.dp))
        Text(
            "对话是入口。选定门店后，说走、出发就可以导航。没有「开始导航」按钮。",
            color = TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (state.skill.name != "NONE") {
            Text(
                "当前技能：${state.skill.name.lowercase()}" +
                    (state.navHint?.let { " · ${it.text}" } ?: ""),
                color = HudColors.green,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Agent Context（对话前与开流采样时刷新）", color = TextMain, fontSize = 14.sp)
        Text(
            state.agentContext.ifBlank { "还没有世界状态。开眼镜画面并说话后会出现。" },
            color = HudColors.green,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 15.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
        if (state.agentDebug.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("诊断状态", color = TextDim, fontSize = 12.sp)
            Text(
                state.agentDebug,
                color = TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        TalkButton(talking = state.talking) { viewModel.toggleTalk() }
        Spacer(Modifier.height(8.dp))
        ActionButton(
            if (state.rtcOn) "关闭眼镜画面" else "开眼镜画面",
            Modifier.fillMaxWidth(),
        ) { viewModel.toggleRtc() }
        if (state.rtcOn || state.rtcLine.isNotBlank()) {
            Text(
                state.rtcLine.ifBlank { "视频流准备中" },
                color = HudColors.green,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            AndroidView(
                factory = { ctx ->
                    org.webrtc.SurfaceViewRenderer(ctx).also { PhoneRtc.attachRenderer(it) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp)),
                onRelease = { PhoneRtc.detachRenderer(it) },
            )
        }
        Spacer(Modifier.height(12.dp))

        Text(
            "已加载 ${state.storeCount} 家门店。数据来自门店录入 App，改完切回即可。",
            color = TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        ActionButton("刷新门店目录", Modifier.fillMaxWidth().padding(top = 8.dp)) {
            viewModel.reloadCatalog()
        }

        Spacer(Modifier.height(16.dp))
        Text("镜片预览", color = TextMain, fontSize = 14.sp)
        GlassesHudPreview(
            card = state.card,
            store = state.store,
            storeCaption = state.storeCaption,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, HudColors.green.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("看店识别", Modifier.weight(1f)) { viewModel.look() }
            ActionButton("下一家", Modifier.weight(1f)) { viewModel.next() }
        }
        state.photo?.let { bitmap ->
            Spacer(Modifier.height(12.dp))
            Text("本次照片", color = TextMain, fontSize = 14.sp)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "店招照片",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("附近火锅", "不要排队", "去第一家", "出发", "取消导航", "排队多久", "有包间吗").forEach { q ->
                Chip(q, false) { viewModel.ask(q) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.question,
            onValueChange = viewModel::updateQuestion,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("说想吃什么，或问这家店", color = TextDim) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedBorderColor = HudColors.green,
                unfocusedBorderColor = TextDim,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton("提问", Modifier.weight(1f)) { viewModel.ask(state.question) }
            ActionButton("对着手机说话", Modifier.weight(1f)) { viewModel.startVoice() }
        }

        state.lastQa?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.answer, color = HudColors.green, fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("门店详情", color = TextMain, fontSize = 14.sp)
        state.match?.store?.let { StoreDetail(it) }

        Spacer(Modifier.height(16.dp))
        Text("已录入门店", color = TextMain, fontSize = 14.sp)
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            viewModel.currentStores().forEach { store ->
                val selected = store.id == state.match?.store?.id
                Chip("${store.shortName} · ${if (store.distanceMeters > 0) "${store.distanceMeters}m" else "测距中"}", selected) {
                    viewModel.look(store.id)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LinkDiagnostics(link: LinkUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text("连接诊断", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            "phase=${link.phase}  attempt=${link.attemptId.ifBlank { "-" }}  ${link.elapsedMs}ms",
            color = HudColors.green,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )
        LinkRow("CXR", link.cxr)
        LinkRow("眼镜 App", link.glassApp)
        LinkRow("麦克风", link.mic)
        LinkRow("眼镜 Wi-Fi", link.glassWifi)
        LinkRow("Direct", link.direct)
        LinkRow("RTC", link.rtc)
        if (link.lastError.isNotBlank()) {
            Text(
                "上次失败${if (link.failedPhase.isNotBlank()) " @ ${link.failedPhase}" else ""}：${link.lastError}",
                color = HudColors.warn,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String) {
    Text(
        "$label  $value",
        color = TextDim,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun StoreDetail(store: Store) {
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(store.name, color = TextMain, fontWeight = FontWeight.Bold)
        Text("${store.address} · ${store.phone}", color = TextDim, fontSize = 12.sp)
        Text(store.hours, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text(store.tags.joinToString(" / "), color = HudColors.greenDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text("招牌：${store.signatures.joinToString("、")}", color = TextMain, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        store.deals.forEach { deal ->
            Text("${deal.title}  ¥${deal.price}  原价¥${deal.original}", color = HudColors.warn, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) HudColors.green.copy(alpha = 0.25f) else Panel
    val border = if (selected) HudColors.green else Color(0xFF333333)
    Text(
        text = label,
        color = if (selected) HudColors.green else TextMain,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun TalkButton(talking: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (talking) Color(0xFF8B1E1E) else Color(0xFF145C38),
        ),
    ) {
        Text(
            if (talking) "结束对话" else "开始听",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF145C38)),
    ) {
        Text(label, color = Color.White)
    }
}
