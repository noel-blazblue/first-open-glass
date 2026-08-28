package com.glass.dining.shared.hud

import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.shared.place.PlaceRef

data class StorePanelLine(
    val kind: Kind,
    val text: String,
) {
    enum class Kind {
        SOURCE, TITLE, CATEGORY, SCORE, HOURS, WHERE, TEL, WAIT, DEAL, HINT,
    }
}

object StorePanel {
    const val DEFAULT_HINT = "说「带我去」开始导航"

    fun lines(place: PlaceProfile, caption: String = ""): List<StorePanelLine> {
        return buildList {
            add(StorePanelLine(StorePanelLine.Kind.SOURCE, sourceLabel(place)))
            add(StorePanelLine(StorePanelLine.Kind.TITLE, place.name))
            categoryOf(place)?.let { add(StorePanelLine(StorePanelLine.Kind.CATEGORY, it)) }
            scoreLine(place)?.let { add(StorePanelLine(StorePanelLine.Kind.SCORE, it)) }
            hoursLine(place)?.let { add(StorePanelLine(StorePanelLine.Kind.HOURS, it)) }
            whereLine(place)?.let { add(StorePanelLine(StorePanelLine.Kind.WHERE, it)) }
            if (place.tel.isNotBlank()) {
                add(StorePanelLine(StorePanelLine.Kind.TEL, "电话 ${place.tel}"))
            }
            if (place.catalogBacked && place.waitMinutes > 0) {
                add(StorePanelLine(StorePanelLine.Kind.WAIT, "排队约${place.waitMinutes}分钟"))
            }
            if (place.catalogBacked && place.dealLine.isNotBlank()) {
                add(StorePanelLine(StorePanelLine.Kind.DEAL, place.dealLine))
            }
            add(StorePanelLine(StorePanelLine.Kind.HINT, caption.ifBlank { DEFAULT_HINT }))
        }
    }

    private fun sourceLabel(place: PlaceProfile): String {
        return if (place.source == PlaceRef.SOURCE_CATALOG) "已录入" else "附近门店"
    }

    private fun categoryOf(place: PlaceProfile): String? {
        val raw = place.keytag.ifBlank { place.category }
        if (raw.isBlank()) return null
        return raw.split(";").map { it.trim() }.lastOrNull { it.isNotBlank() }
    }

    private fun scoreLine(place: PlaceProfile): String? {
        val bits = buildList {
            if (place.rating > 0) add("${trimRating(place.rating)}分")
            if (place.avgCost > 0) add("人均${place.avgCost}")
        }
        return bits.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun hoursLine(place: PlaceProfile): String? {
        val hours = place.hours
        if (hours.isBlank()) return null
        if (hours.startsWith("今日") || hours.contains("周")) return hours
        return "今日 $hours"
    }

    private fun whereLine(place: PlaceProfile): String? {
        val bits = buildList {
            if (place.businessArea.isNotBlank()) add(place.businessArea)
            if (place.floor.isNotBlank()) add(place.floor)
            if (place.distanceMeters > 0) add("约${place.distanceMeters}米")
        }
        return bits.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun trimRating(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }
}
