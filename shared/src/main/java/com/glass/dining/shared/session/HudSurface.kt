package com.glass.dining.shared.session

enum class HudSurface {
    NAV,
    CONFIRM,
    STORE,
    TALK,
}

enum class DismissReason {
    STOP_NAV,
    CANCEL,
    ARRIVED,
    IDLE,
    OFF_TOPIC,
    REPLACE,
}
