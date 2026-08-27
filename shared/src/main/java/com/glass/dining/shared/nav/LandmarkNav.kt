package com.glass.dining.shared.nav

import com.glass.dining.shared.model.Store
import com.glass.dining.shared.vision.LocalExtract
import com.glass.dining.shared.vision.OcrBlock

enum class LandmarkStage {
    OUTDOOR,
    LOCATE_FLOOR,
    SEEK_VERTICAL,
    CONFIRM_FLOOR,
    SEEK_STORE,
    SEEK_EXIT,
    ARRIVED,
}

enum class FloorRel {
    UP,
    DOWN,
    SAME,
    UNKNOWN,
}

data class LandmarkGoal(
    val storeName: String,
    val shortName: String,
    val floor: String = "",
)

data class LandmarkWorld(
    val gpsUsable: Boolean = true,
    val gpsAccuracyM: Float = 20f,
    val spokenFloor: String = "",
)

data class LandmarkHint(
    val turn: String = "straight",
    val meters: Int = 0,
    val text: String = "",
    val storeName: String = "",
    val remaining: Int = 0,
    val mode: String = "",
    val headingDeg: Float = 0f,
    val elevationDeg: Float = 0f,
    val stage: String = "",
    val sessionId: String = "",
    val tracking: String = "",
    val waypoints: String = "",
)

/**
 * 路标状态机：OCR 只作楼层/标牌证据。室内路线由探索规划器决定。
 */
class LandmarkPlanner {
    var stage: LandmarkStage = LandmarkStage.OUTDOOR
        private set
    var currentFloor: String = ""
        private set
    var verticalDown: Boolean = false
        private set

    private var stickKind: String = ""
    private var stickHits: Int = 0
    private var indoorLostGps: Boolean = false

    fun reset() {
        stage = LandmarkStage.OUTDOOR
        currentFloor = ""
        verticalDown = false
        stickKind = ""
        stickHits = 0
        indoorLostGps = false
    }

    fun start(goal: LandmarkGoal, gpsUsable: Boolean, spokenFloor: String = "") {
        reset()
        applySpoken(spokenFloor)
        if (gpsUsable) {
            stage = LandmarkStage.OUTDOOR
        } else {
            indoorLostGps = true
            enterFromScene(goal)
        }
    }

    fun enterIndoor(goal: LandmarkGoal, spokenFloor: String = "") {
        indoorLostGps = false
        if (currentFloor.isBlank()) applySpoken(spokenFloor)
        enterFromScene(goal)
    }

    fun bootstrapHint(goal: LandmarkGoal, remaining: Int): LandmarkHint {
        val text = when (stage) {
            LandmarkStage.LOCATE_FLOOR -> "看现在几楼"
            LandmarkStage.SEEK_EXIT -> "找出口出门"
            LandmarkStage.SEEK_VERTICAL -> if (verticalDown) "找电梯下楼" else "找电梯或扶梯"
            LandmarkStage.CONFIRM_FLOOR -> "看楼层"
            LandmarkStage.SEEK_STORE -> "沿走廊看店招"
            LandmarkStage.ARRIVED -> "已到门口"
            LandmarkStage.OUTDOOR -> "开始走"
        }
        return ground(goal, remaining, 0f, text)
    }

    fun observe(
        extract: LocalExtract,
        goal: LandmarkGoal,
        gpsRemaining: Int,
        gpsArrive: Boolean,
        world: LandmarkWorld = LandmarkWorld(),
    ): LandmarkHint? {
        if (currentFloor.isBlank()) applySpoken(world.spokenFloor)
        if (stage == LandmarkStage.OUTDOOR) {
            if (!world.gpsUsable) {
                indoorLostGps = true
                enterFromScene(goal)
            } else if (shouldEnterVisual(gpsRemaining, extract) || gpsArrive) {
                enterIndoor(goal)
            } else {
                return null
            }
        }
        if (stage != LandmarkStage.ARRIVED &&
            gpsArrive &&
            goal.floor.isBlank() &&
            !indoorLostGps
        ) {
            stage = LandmarkStage.ARRIVED
            return arrive(goal, gpsRemaining)
        }
        maybeLockOutdoor(extract, world)
        if (stage == LandmarkStage.OUTDOOR) return null
        val storeBlock = LandmarkSignage.bestStore(extract, goal)
        if (storeBlock != null && confirm("store")) {
            stage = LandmarkStage.ARRIVED
            return arrive(goal, gpsRemaining)
        }
        return when (stage) {
            LandmarkStage.LOCATE_FLOOR -> locateFloor(extract, goal, gpsRemaining)
            LandmarkStage.SEEK_VERTICAL -> seekVertical(extract, goal, gpsRemaining)
            LandmarkStage.CONFIRM_FLOOR -> confirmFloor(extract, goal, gpsRemaining)
            LandmarkStage.SEEK_STORE -> seekStore(extract, goal, gpsRemaining, storeBlock)
            LandmarkStage.SEEK_EXIT -> seekExit(extract, goal, gpsRemaining)
            LandmarkStage.ARRIVED -> arrive(goal, gpsRemaining)
            LandmarkStage.OUTDOOR -> null
        }
    }

    private fun enterFromScene(goal: LandmarkGoal) {
        stickKind = ""
        stickHits = 0
        retargetVertical(goal)
        stage = when {
            currentFloor.isNotBlank() -> afterKnownFloor(goal)
            goal.floor.isNotBlank() -> LandmarkStage.LOCATE_FLOOR
            indoorLostGps -> LandmarkStage.LOCATE_FLOOR
            else -> LandmarkStage.SEEK_STORE
        }
    }

    private fun afterKnownFloor(goal: LandmarkGoal): LandmarkStage {
        retargetVertical(goal)
        val dest = destFloorOf(goal)
        if (dest.isBlank()) return LandmarkStage.SEEK_STORE
        return when (LandmarkSignage.relate(currentFloor, dest)) {
            FloorRel.SAME -> if (goal.floor.isBlank()) LandmarkStage.SEEK_EXIT else LandmarkStage.SEEK_STORE
            FloorRel.UNKNOWN -> LandmarkStage.LOCATE_FLOOR
            FloorRel.UP, FloorRel.DOWN -> LandmarkStage.SEEK_VERTICAL
        }
    }

    private fun locateFloor(extract: LocalExtract, goal: LandmarkGoal, remaining: Int): LandmarkHint {
        readFloor(extract)?.let { seen ->
            if (confirm("cur:$seen")) {
                currentFloor = seen
                stage = afterKnownFloor(goal)
                return observeCurrent(extract, goal, remaining)
            }
            return ground(goal, remaining, LandmarkSignage.signDirection(extract), "看现在几楼")
        }
        if (destFloorOf(goal).isNotBlank() && LandmarkSignage.bestVertical(extract) != null) {
            stage = LandmarkStage.SEEK_VERTICAL
            retargetVertical(goal)
            return seekVertical(extract, goal, remaining)
        }
        val hit = LandmarkSignage.bestVertical(extract)
        if (hit != null) {
            return pointing(
                goal, remaining, hit.kind, hit.headingDeg, hit.elevationDeg,
                "看现在几楼", 6,
            )
        }
        return ground(goal, remaining, LandmarkSignage.signDirection(extract), "看现在几楼")
    }

    private fun seekVertical(extract: LocalExtract, goal: LandmarkGoal, remaining: Int): LandmarkHint {
        maybeAdvanceByFloor(extract, goal, remaining)?.let { return it }
        if (onDestFloor(goal)) {
            stage = if (goal.floor.isBlank()) LandmarkStage.SEEK_EXIT else LandmarkStage.SEEK_STORE
            return observeCurrent(extract, goal, remaining)
        }
        val hit = LandmarkSignage.bestVertical(extract)
        if (hit != null && confirm(hit.kind)) {
            val elev = stairElevation(hit)
            return pointing(
                goal = goal,
                remaining = remaining,
                mode = hit.kind,
                heading = hit.headingDeg,
                elevation = elev,
                text = verticalText(hit.kind, destFloorOf(goal)),
                meters = 8,
            )
        }
        val dir = LandmarkSignage.signDirection(extract)
        val wait = if (verticalDown) "找电梯下楼" else "找电梯或扶梯"
        return ground(goal, remaining, dir, wait)
    }

    private fun confirmFloor(extract: LocalExtract, goal: LandmarkGoal, remaining: Int): LandmarkHint {
        maybeAdvanceByFloor(extract, goal, remaining)?.let { return it }
        if (onDestFloor(goal)) {
            stage = if (goal.floor.isBlank()) LandmarkStage.SEEK_EXIT else LandmarkStage.SEEK_STORE
            return observeCurrent(extract, goal, remaining)
        }
        val dest = destFloorOf(goal)
        val hit = LandmarkSignage.bestVertical(extract)
        if (hit != null) {
            val label = if (dest.isBlank()) "看楼层" else "确认${dest}楼"
            return pointing(
                goal, remaining, hit.kind, hit.headingDeg, stairElevation(hit),
                label, 6,
            )
        }
        return ground(goal, remaining, 0f, "看楼层")
    }

    private fun seekStore(
        extract: LocalExtract,
        goal: LandmarkGoal,
        remaining: Int,
        storeBlock: OcrBlock?,
    ): LandmarkHint {
        if (storeBlock != null) {
            val pose = LandmarkSignage.poseOf(storeBlock, extract.width, extract.height)
            return pointing(
                goal, remaining, MODE_GROUND, pose.first, pose.second,
                "前面就是${goal.shortName}", 12,
            )
        }
        if (LandmarkSignage.floorMatch(extract, goal.floor).not() &&
            goal.floor.isNotBlank() &&
            LandmarkSignage.hasFloorNumber(extract)
        ) {
            readFloor(extract)?.let { currentFloor = it }
            stage = LandmarkStage.CONFIRM_FLOOR
            return confirmFloor(extract, goal, remaining)
        }
        val sameFloor = onDestFloor(goal)
        val hit = LandmarkSignage.bestVertical(extract)
        if (hit != null && remaining > 25 && !sameFloor) {
            return pointing(
                goal, remaining, hit.kind, hit.headingDeg, stairElevation(hit),
                "先去${if (hit.kind == MODE_ELEVATOR) "电梯" else "楼梯"}", 8,
            )
        }
        val dir = LandmarkSignage.signDirection(extract)
        return ground(goal, remaining, dir, "沿走廊看店招")
    }

    private fun seekExit(extract: LocalExtract, goal: LandmarkGoal, remaining: Int): LandmarkHint {
        maybeAdvanceByFloor(extract, goal, remaining)?.let { return it }
        val hit = LandmarkSignage.bestExit(extract)
        if (hit != null && confirm("exit")) {
            return pointing(
                goal, remaining, MODE_EXIT, hit.headingDeg, hit.elevationDeg,
                "从这里出门", 10,
            )
        }
        if (hit != null) {
            return pointing(
                goal, remaining, MODE_EXIT, hit.headingDeg, hit.elevationDeg,
                "找出口出门", 8,
            )
        }
        val dir = LandmarkSignage.signDirection(extract)
        return ground(goal, remaining, dir, "找出口出门")
    }

    private fun maybeAdvanceByFloor(
        extract: LocalExtract,
        goal: LandmarkGoal,
        remaining: Int,
    ): LandmarkHint? {
        val seen = readFloor(extract) ?: return null
        if (seen == currentFloor) return null
        if (!confirm("cur:$seen")) return null
        currentFloor = seen
        val next = afterKnownFloor(goal)
        if (next == stage) return null
        stage = next
        return observeCurrent(extract, goal, remaining)
    }

    private fun maybeLockOutdoor(extract: LocalExtract, world: LandmarkWorld) {
        if (stage != LandmarkStage.SEEK_EXIT) return
        val outdoorFix = world.gpsUsable &&
            world.gpsAccuracyM in 1f..GPS_ACCURACY_OUTDOOR &&
            !LandmarkSignage.hasDeepIndoorCue(extract)
        val streetCue = LandmarkSignage.hasOutdoorCue(extract) && confirm("outdoor")
        if (outdoorFix || streetCue) {
            stage = LandmarkStage.OUTDOOR
            indoorLostGps = false
            stickKind = ""
            stickHits = 0
        }
    }

    private fun observeCurrent(
        extract: LocalExtract,
        goal: LandmarkGoal,
        remaining: Int,
    ): LandmarkHint {
        return when (stage) {
            LandmarkStage.SEEK_STORE -> seekStore(extract, goal, remaining, LandmarkSignage.bestStore(extract, goal))
            LandmarkStage.SEEK_EXIT -> seekExit(extract, goal, remaining)
            LandmarkStage.SEEK_VERTICAL -> seekVertical(extract, goal, remaining)
            LandmarkStage.LOCATE_FLOOR -> locateFloor(extract, goal, remaining)
            LandmarkStage.CONFIRM_FLOOR -> confirmFloor(extract, goal, remaining)
            LandmarkStage.ARRIVED -> arrive(goal, remaining)
            LandmarkStage.OUTDOOR -> ground(goal, remaining, 0f, "开始走")
        }
    }

    private fun applySpoken(spoken: String) {
        val floor = LandmarkSignage.parseSpokenFloor(spoken).ifBlank {
            LandmarkSignage.normalizeFloor(spoken)
        }
        if (floor.isBlank()) return
        currentFloor = floor
    }

    private fun readFloor(extract: LocalExtract): String? {
        return LandmarkSignage.bestFloor(extract)
    }

    private fun destFloorOf(goal: LandmarkGoal): String {
        return when {
            goal.floor.isNotBlank() -> goal.floor
            indoorLostGps -> "1"
            else -> ""
        }
    }

    private fun onDestFloor(goal: LandmarkGoal): Boolean {
        val dest = destFloorOf(goal)
        if (dest.isBlank()) return goal.floor.isBlank()
        return LandmarkSignage.relate(currentFloor, dest) == FloorRel.SAME
    }

    private fun retargetVertical(goal: LandmarkGoal) {
        val dest = destFloorOf(goal)
        verticalDown = when (LandmarkSignage.relate(currentFloor, dest)) {
            FloorRel.DOWN -> true
            FloorRel.UP -> false
            FloorRel.SAME -> false
            FloorRel.UNKNOWN -> {
                val rank = LandmarkSignage.floorRank(dest)
                rank != null && rank < 2
            }
        }
    }

    private fun verticalText(kind: String, dest: String): String {
        val floorLabel = dest.ifBlank { "1" }
        return when {
            kind == MODE_ELEVATOR && verticalDown -> "乘电梯下${floorLabel}楼"
            kind == MODE_ELEVATOR -> "乘电梯上${floorLabel}楼"
            kind == MODE_STAIRS && verticalDown -> "下楼梯"
            kind == MODE_STAIRS -> "上楼梯"
            else -> "沿通道走"
        }
    }

    private fun stairElevation(hit: LandmarkHit): Float {
        if (hit.kind != MODE_STAIRS) return hit.elevationDeg
        return if (verticalDown) {
            hit.elevationDeg.coerceAtMost(-8f)
        } else {
            hit.elevationDeg.coerceAtLeast(8f)
        }
    }

    private fun confirm(kind: String): Boolean {
        if (stickKind != kind) {
            stickKind = kind
            stickHits = 1
            return false
        }
        stickHits += 1
        return stickHits >= STICK_HITS
    }

    private fun ground(goal: LandmarkGoal, remaining: Int, heading: Float, text: String): LandmarkHint {
        return LandmarkHint(
            turn = headingTurn(heading),
            meters = 6,
            text = text,
            storeName = goal.shortName,
            remaining = remaining,
            mode = MODE_GROUND,
            headingDeg = heading,
            elevationDeg = 0f,
            stage = stage.name.lowercase(),
        )
    }

    private fun pointing(
        goal: LandmarkGoal,
        remaining: Int,
        mode: String,
        heading: Float,
        elevation: Float,
        text: String,
        meters: Int,
    ): LandmarkHint {
        return LandmarkHint(
            turn = headingTurn(heading),
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

    private fun arrive(goal: LandmarkGoal, remaining: Int): LandmarkHint {
        return LandmarkHint(
            turn = "arrive",
            meters = 0,
            text = "已到门口",
            storeName = goal.shortName,
            remaining = remaining.coerceAtMost(0),
            mode = MODE_ARRIVE,
            stage = LandmarkStage.ARRIVED.name.lowercase(),
        )
    }

    companion object {
        const val MODE_GROUND = "ground"
        const val MODE_STAIRS = "stairs"
        const val MODE_ELEVATOR = "elevator"
        const val MODE_CORRIDOR = "corridor"
        const val MODE_EXIT = "exit"
        const val MODE_ARRIVE = "arrive"
        const val VISUAL_ENTER_M = 80
        const val VISUAL_SIGN_M = 120
        const val STICK_HITS = 2
        const val CAMERA_HFOV = 77f
        const val CAMERA_VFOV = 94f
        const val GPS_ACCURACY_OUTDOOR = 25f
        const val GPS_ACCURACY_INDOOR = 45f

        fun goalOf(store: Store): LandmarkGoal {
            return LandmarkGoal(
                storeName = store.name,
                shortName = store.shortName.ifBlank { store.name },
                floor = LandmarkSignage.normalizeFloor(store.floor.ifBlank { store.address }),
            )
        }

        fun shouldEnterVisual(remaining: Int, extract: LocalExtract?): Boolean {
            if (remaining in 1..VISUAL_ENTER_M) return true
            if (remaining in (VISUAL_ENTER_M + 1)..VISUAL_SIGN_M && extract != null) {
                return LandmarkSignage.hasIndoorCue(extract)
            }
            return remaining == 0 && extract != null && LandmarkSignage.hasIndoorCue(extract)
        }

        fun headingTurn(heading: Float): String {
            return when {
                heading <= -12f -> "left"
                heading >= 12f -> "right"
                else -> "straight"
            }
        }

        fun gpsLooksIndoor(accuracyM: Float, hasFix: Boolean): Boolean {
            if (!hasFix) return true
            return accuracyM > GPS_ACCURACY_OUTDOOR
        }
    }
}
