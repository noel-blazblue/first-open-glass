package com.glass.dining.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.dining.shared.catalog.StoreGeo
import com.glass.dining.shared.model.Store

private val Panel = Color(0xFF1A1A1A)
private val TextMain = Color(0xFFEEFFEE)
private val TextDim = Color(0xFF99BB99)
private val Green = Color(0xFF7CFF7C)

@Composable
fun StoreScreen(viewModel: StoreViewModel) {
    val state by viewModel.ui.collectAsState()
    val editing = state.editing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("门店录入", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(state.status, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        if (editing == null) {
            Button(
                onClick = { viewModel.startNew() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF145C38)),
            ) {
                Text("新增门店", color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            state.stores.forEach { store ->
                StoreRow(store) { viewModel.edit(store) }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Editor(editing, viewModel)
        }
    }
}

@Composable
private fun StoreRow(store: Store, onClick: () -> Unit) {
    val coord = if (StoreGeo.hasCoords(store)) {
        "${"%.5f".format(store.lat)}, ${"%.5f".format(store.lng)}"
    } else {
        "还没有坐标"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(store.shortName.ifBlank { store.name }, color = TextMain, fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(
                store.category.ifBlank { null },
                store.floor.trim().ifBlank { null },
                coord,
            ).joinToString(" · "),
            color = TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (store.address.isNotBlank()) {
            Text(store.address, color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Editor(form: StoreForm, viewModel: StoreViewModel) {
    Field("店名", form.name) { viewModel.updateForm { it.copy(name = this) } }
    Field("简称", form.shortName) { viewModel.updateForm { it.copy(shortName = this) } }
    Field("分类", form.category) { viewModel.updateForm { it.copy(category = this) } }
    Field("地址", form.address) { viewModel.updateForm { it.copy(address = this) } }
    Field("楼层（如 4 / 4F / B1，室内导航用）", form.floor) { viewModel.updateForm { it.copy(floor = this) } }
    Field("电话", form.phone) { viewModel.updateForm { it.copy(phone = this) } }
    Field("营业时间", form.hours) { viewModel.updateForm { it.copy(hours = this) } }
    Field("人均", form.avgPrice) { viewModel.updateForm { it.copy(avgPrice = this) } }
    Field("评分", form.rating) { viewModel.updateForm { it.copy(rating = this) } }
    Field("排队分钟", form.waitMinutes) { viewModel.updateForm { it.copy(waitMinutes = this) } }
    Field("排队桌数", form.waitTables) { viewModel.updateForm { it.copy(waitTables = this) } }
    Field("招牌（顿号分隔）", form.signatures) { viewModel.updateForm { it.copy(signatures = this) } }
    Field("标签", form.tags) { viewModel.updateForm { it.copy(tags = this) } }
    Field("优惠 标题,现价,原价；可多条", form.deals) { viewModel.updateForm { it.copy(deals = this) } }
    Text(
        if (StoreGeo.hasCoords(form.lat, form.lng)) {
            "坐标 ${"%.6f".format(form.lat)}, ${"%.6f".format(form.lng)}"
        } else {
            "还没有坐标"
        },
        color = Green,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { viewModel.captureGps() },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF145C38)),
    ) {
        Text("记录当前位置", color = Color.White)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { viewModel.save() },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF145C38)),
        ) { Text("保存", color = Color.White) }
        Button(
            onClick = { viewModel.cancelEdit() },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
        ) { Text("取消", color = Color.White) }
    }
    if (form.id.isNotBlank() && form.name.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.deleteCurrent() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B1E1E)),
        ) { Text("删除这家店", color = Color.White) }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: String.() -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { it.onChange() },
        label = { Text(label, color = TextDim, fontSize = 12.sp) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain,
            focusedBorderColor = Green,
            unfocusedBorderColor = TextDim,
            focusedLabelColor = Green,
            unfocusedLabelColor = TextDim,
            cursorColor = Green,
        ),
    )
}
