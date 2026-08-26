package com.glass.nav.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF22FF88)
private val TextMain = Color(0xFFEEFFEE)
private val TextDim = Color(0xFF99BB99)

@Composable
fun NavPhoneScreen(viewModel: NavViewModel) {
    val state by viewModel.ui.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("室内导航实验室（不是产品入口）", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "产品入口是到餐 Agent（apps/phone）。这里只留 CustomApp 通道实验，不要当主 App 用。",
            color = TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(state.status, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "当前指引：${state.hint.text.ifBlank { "还没开始" }}",
            color = TextMain,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "照片 ${state.photoCount} 张 · 最近 ${state.lastPhotoBytes}B · 头偏 ${state.yaw.toInt()}°",
            color = TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NavButton("启动眼镜页", Modifier.weight(1f)) { viewModel.launchGlass() }
            NavButton("校准朝向", Modifier.weight(1f)) { viewModel.calibrate() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NavButton(
                if (state.navigating) "进行中…" else "开始室内指引",
                Modifier.weight(1f),
                enabled = !state.navigating,
            ) { viewModel.startIndoor() }
            NavButton("停止", Modifier.weight(1f)) { viewModel.stopIndoor() }
        }
        Spacer(Modifier.height(16.dp))
        val photo = state.photo
        if (photo != null) {
            Text("最近一张路标图（眼镜 5 秒一拍）", color = TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "路标",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun NavButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1E3D2A),
            contentColor = Green,
            disabledContainerColor = Color(0xFF222222),
            disabledContentColor = TextDim,
        ),
    ) {
        Text(label)
    }
}
