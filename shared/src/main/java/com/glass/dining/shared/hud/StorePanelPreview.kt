package com.glass.dining.shared.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.dining.shared.place.PlaceProfile

@Composable
fun StorePanelPreview(
    place: PlaceProfile,
    caption: String = "",
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StorePanel.lines(place, caption).forEach { line ->
                Text(
                    text = line.text,
                    color = colorOf(line.kind),
                    fontSize = sizeOf(line.kind).sp,
                    fontWeight = if (line.kind == StorePanelLine.Kind.TITLE) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = if (line.kind == StorePanelLine.Kind.TITLE) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun colorOf(kind: StorePanelLine.Kind): Color {
    return when (kind) {
        StorePanelLine.Kind.SOURCE, StorePanelLine.Kind.WHERE -> HudColors.muted
        StorePanelLine.Kind.TITLE, StorePanelLine.Kind.TEL -> HudColors.title
        StorePanelLine.Kind.CATEGORY -> HudColors.muted
        StorePanelLine.Kind.SCORE -> HudColors.gold
        StorePanelLine.Kind.HOURS -> HudColors.cyan
        StorePanelLine.Kind.WAIT, StorePanelLine.Kind.DEAL -> HudColors.warn
        StorePanelLine.Kind.HINT -> HudColors.hint
    }
}

private fun sizeOf(kind: StorePanelLine.Kind): Int {
    return when (kind) {
        StorePanelLine.Kind.SOURCE -> 14
        StorePanelLine.Kind.TITLE -> 26
        StorePanelLine.Kind.SCORE -> 20
        StorePanelLine.Kind.HINT -> 16
        else -> 18
    }
}
