package com.glass.dining.shared.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GlassesHudPreview(
    card: HudCard,
    modifier: Modifier = Modifier,
) {
    val clipped = card.clipped()
    Column(
        modifier = modifier
            .background(HudColors.background)
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
