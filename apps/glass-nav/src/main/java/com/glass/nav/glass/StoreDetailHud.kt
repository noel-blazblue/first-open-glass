package com.glass.nav.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.glass.dining.shared.hud.StorePanel
import com.glass.dining.shared.hud.StorePanelLine
import com.glass.dining.shared.place.PlaceProfile

object StoreDetailHud {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    fun draw(canvas: Canvas, place: PlaceProfile, caption: String, viewW: Float, viewH: Float) {
        val lines = StorePanel.lines(place, caption)
        val maxText = viewW * 0.78f
        var height = 0f
        lines.forEach { line ->
            height += sizeOf(line.kind) + gapOf(line.kind)
        }
        var y = (viewH - height) * 0.42f
        val cx = viewW / 2f
        lines.forEach { line ->
            text.color = colorOf(line.kind)
            text.textSize = sizeOf(line.kind)
            text.typeface = typeOf(line.kind)
            canvas.drawText(ellipsize(line.text, maxText), cx, y, text)
            y += sizeOf(line.kind) + gapOf(line.kind)
        }
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private fun colorOf(kind: StorePanelLine.Kind): Int {
        return when (kind) {
            StorePanelLine.Kind.SOURCE, StorePanelLine.Kind.WHERE -> Color.rgb(176, 188, 204)
            StorePanelLine.Kind.TITLE, StorePanelLine.Kind.TEL -> Color.rgb(245, 247, 250)
            StorePanelLine.Kind.CATEGORY -> Color.rgb(196, 204, 214)
            StorePanelLine.Kind.SCORE -> Color.rgb(255, 200, 87)
            StorePanelLine.Kind.HOURS -> Color.rgb(126, 224, 242)
            StorePanelLine.Kind.WAIT, StorePanelLine.Kind.DEAL -> Color.rgb(255, 196, 120)
            StorePanelLine.Kind.HINT -> Color.rgb(168, 230, 196)
        }
    }

    private fun sizeOf(kind: StorePanelLine.Kind): Float {
        return when (kind) {
            StorePanelLine.Kind.SOURCE -> 18f
            StorePanelLine.Kind.TITLE -> 36f
            StorePanelLine.Kind.CATEGORY -> 24f
            StorePanelLine.Kind.SCORE -> 28f
            StorePanelLine.Kind.HINT -> 22f
            else -> 24f
        }
    }

    private fun gapOf(kind: StorePanelLine.Kind): Float {
        return when (kind) {
            StorePanelLine.Kind.SOURCE -> 12f
            StorePanelLine.Kind.TITLE -> 12f
            StorePanelLine.Kind.HINT -> 10f
            else -> 10f
        }
    }

    private fun typeOf(kind: StorePanelLine.Kind): Typeface {
        val style = if (kind == StorePanelLine.Kind.TITLE) Typeface.BOLD else Typeface.NORMAL
        return Typeface.create(Typeface.SANS_SERIF, style)
    }

    private fun ellipsize(value: String, maxWidth: Float): String {
        if (text.measureText(value) <= maxWidth) return value
        var cut = value
        while (cut.length > 1 && text.measureText("$cut…") > maxWidth) {
            cut = cut.dropLast(1)
        }
        return "$cut…"
    }
}
