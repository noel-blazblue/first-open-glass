package com.glass.dining.phone

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.dining.shared.hud.HudColors
import com.glass.dining.shared.hud.StoreHud
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
        Text("到店餐饮", color = HudColors.green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(state.status, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))

        Text("商圈", color = TextMain, fontSize = 14.sp)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.scenes.forEach { scene ->
                val selected = scene.id == state.sceneId
                Chip(scene.name, selected) { viewModel.selectScene(scene.id) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("眼镜 HUD 预览", color = TextMain, fontSize = 14.sp)
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, HudColors.green.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .width(480.dp)
                        .height(640.dp)
                        .graphicsLayer {
                            scaleX = 0.5f
                            scaleY = 0.5f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        },
                ) {
                    StoreHud(state.hud, Modifier.fillMaxSize())
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("模拟看店识别") { viewModel.look() }
            ActionButton("下一家") { viewModel.next() }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("排队多久", "招牌菜", "有什么优惠", "人均多少", "有包间吗").forEach { q ->
                Chip(q, false) { viewModel.ask(q) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.question,
            onValueChange = viewModel::updateQuestion,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("随便问问这家店", color = TextDim) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                focusedBorderColor = HudColors.green,
                unfocusedBorderColor = TextDim,
            ),
        )
        Spacer(Modifier.height(8.dp))
        ActionButton("提问") { viewModel.ask(state.question) }

        state.lastQa?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.answer, color = HudColors.green, fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("门店详情", color = TextMain, fontSize = 14.sp)
        state.match?.store?.let { StoreDetail(it) }

        Spacer(Modifier.height(16.dp))
        Text("Debug 强制命中", color = TextMain, fontSize = 14.sp)
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            viewModel.currentStores().forEach { store ->
                val selected = store.id == state.match?.store?.id
                Chip("${store.shortName} · ${store.distanceMeters}m", selected) {
                    viewModel.look(store.id)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("连接眼镜（可选）", color = TextMain, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        ActionButton("扫描 Glass3") { viewModel.scanGlasses() }
        state.devices.forEach { device ->
            val label = deviceLabel(device)
            Text(
                text = "连接 $label",
                color = HudColors.green,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { viewModel.connect(device) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
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
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF145C38)),
    ) {
        Text(label, color = Color.White)
    }
}

@SuppressLint("MissingPermission")
private fun deviceLabel(device: android.bluetooth.BluetoothDevice): String {
    return device.name ?: device.address
}
