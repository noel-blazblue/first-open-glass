package com.glass.dining.shared.hud

enum class TalkPose(val id: String) {
    IDLE("idle"),
    LISTEN("listen"),
    THINK("think"),
    SPEAK("speak"),
    LOOK("look"),
    ;

    companion object {
        fun parse(raw: String?): TalkPose {
            return entries.firstOrNull { it.id == raw } ?: IDLE
        }

        fun of(raw: String?, speech: String = ""): TalkPose {
            if (!raw.isNullOrBlank()) return parse(raw)
            return when {
                speech.contains("思考") -> THINK
                speech.contains("查看") || speech.contains("拍照") -> LOOK
                speech.isBlank() || speech == "Hi, 我在听" -> LISTEN
                else -> SPEAK
            }
        }
    }
}
