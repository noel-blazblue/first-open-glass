package com.glass.dining.shared.nav

internal fun landmarkGround(
    goal: LandmarkGoal,
    remaining: Int,
    heading: Float,
    text: String,
    stage: LandmarkStage,
): LandmarkHint {
    return LandmarkHint(
        turn = LandmarkPlanner.headingTurn(heading),
        meters = 0,
        text = text,
        storeName = goal.shortName,
        remaining = remaining,
        mode = LandmarkPlanner.MODE_GROUND,
        headingDeg = heading,
        elevationDeg = 0f,
        stage = stage.name.lowercase(),
    )
}

internal fun landmarkPointing(
    goal: LandmarkGoal,
    remaining: Int,
    mode: String,
    heading: Float,
    elevation: Float,
    text: String,
    meters: Int,
    stage: LandmarkStage,
): LandmarkHint {
    return LandmarkHint(
        turn = LandmarkPlanner.headingTurn(heading),
        meters = meters,
        text = text,
        storeName = goal.shortName,
        remaining = remaining,
        mode = mode,
        headingDeg = heading,
        elevationDeg = elevation,
        stage = stage.name.lowercase(),
    )
}

internal fun landmarkArrive(goal: LandmarkGoal, remaining: Int): LandmarkHint {
    return LandmarkHint(
        turn = "arrive",
        meters = 0,
        text = "已到门口",
        storeName = goal.shortName,
        remaining = remaining.coerceAtMost(0),
        mode = LandmarkPlanner.MODE_ARRIVE,
        stage = LandmarkStage.ARRIVED.name.lowercase(),
    )
}
