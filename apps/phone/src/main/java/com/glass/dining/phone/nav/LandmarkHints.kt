package com.glass.dining.phone.nav

import com.glass.dining.shared.nav.LandmarkHint
import com.glass.dining.shared.nav.NavHint

fun LandmarkHint.toNavHint(): NavHint {
    return NavHint(
        turn = turn,
        meters = meters,
        text = text,
        storeName = storeName,
        remaining = remaining,
        mode = mode,
        headingDeg = headingDeg,
        elevationDeg = elevationDeg,
        stage = stage,
        sessionId = sessionId,
        tracking = tracking,
        waypoints = waypoints,
    )
}
