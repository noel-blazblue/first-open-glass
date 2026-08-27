package com.glass.dining.shared.indoor

data class ExploreHint(
    val text: String,
    val turn: String = "straight",
    val mode: String = "ground",
    val headingDeg: Float = 0f,
    val meters: Int = 0,
    val arrived: Boolean = false,
    val tracking: String = TrackQuality.GOOD.name.lowercase(),
    val scanRequired: Boolean = false,
    val hasGuide: Boolean = false,
)

class ExplorationPlanner {
    fun decide(
        topology: LiveTopology,
        observation: SemanticObservation,
        pose: Pose3,
        quality: TrackQuality,
        goalName: String,
        goalFloor: String,
        currentFloor: String,
        occupancy: OccupancyGrid,
        hadGoodTrack: Boolean = true,
        lostMs: Long = 0L,
        stage: String = "",
    ): ExploreHint {
        if (quality == TrackQuality.LOST && hadGoodTrack && lostMs >= SUSTAINED_LOST_MS) {
            return ExploreHint(
                text = "请环视找导视",
                mode = "ground",
                tracking = TrackQuality.WEAK.name.lowercase(),
                scanRequired = true,
            )
        }
        if (observation.blocked) {
            occupancy.markBlocked(pose)
            return ExploreHint(text = "前方可能挡住，换条路", turn = "left", mode = "ground")
        }
        val storeHit = observation.storeNames.firstOrNull { name ->
            goalName.isNotBlank() && (name.contains(goalName) || goalName.contains(name))
        }
        if (storeHit != null && observation.confidence >= 0.55f) {
            return ExploreHint(
                text = "前面就是$goalName",
                turn = "arrive",
                mode = "arrive",
                arrived = true,
            )
        }
        val seekingExit = stage == "seek_exit"
        followVisibleExit(observation, topology, pose, occupancy, quality, seekingExit)?.let { return it }
        if (!seekingExit) {
            verticalHint(topology, observation, pose, occupancy, quality, goalFloor, currentFloor)?.let { return it }
        }
        return wander(topology, observation, pose, occupancy, quality)
    }

    private fun followVisibleExit(
        observation: SemanticObservation,
        topology: LiveTopology,
        pose: Pose3,
        occupancy: OccupancyGrid,
        quality: TrackQuality,
        seekingExit: Boolean,
    ): ExploreHint? {
        val fromScene = observation.kind == NodeKind.ENTRANCE ||
            observation.exits.isNotEmpty() ||
            observation.guideDir.isNotBlank()
        if (!seekingExit && !fromScene) return null
        if (seekingExit && !fromScene) {
            val node = nearest(topology, pose, NodeKind.ENTRANCE) ?: return null
            val heading = headingFromYaw(pose.yawDeg, yawTo(pose, node.pose.position))
            occupancy.markCorridor(pose)
            return guided(heading, "找出口出门", quality)
        }
        if (!fromScene) return null
        val dir = observation.guideDir.ifBlank { observation.exits.firstOrNull()?.dir.orEmpty() }
        val heading = headingOf(dir)
        val label = observation.exits.firstOrNull()?.label?.ifBlank { null }
        occupancy.markCorridor(pose)
        val text = when {
            seekingExit -> label ?: "找出口出门"
            else -> label ?: "沿导视走"
        }
        return guided(heading, text, quality)
    }

    private fun verticalHint(
        topology: LiveTopology,
        observation: SemanticObservation,
        pose: Pose3,
        occupancy: OccupancyGrid,
        quality: TrackQuality,
        goalFloor: String,
        currentFloor: String,
    ): ExploreHint? {
        if (goalFloor.isBlank() || currentFloor.isBlank()) return null
        if (TopologyBuilder.floorRank(currentFloor) == TopologyBuilder.floorRank(goalFloor)) return null
        val down = TopologyBuilder.floorRank(currentFloor) > TopologyBuilder.floorRank(goalFloor)
        val lift = nearest(topology, pose, NodeKind.ELEVATOR) ?: nearest(topology, pose, NodeKind.STAIRS)
        if (lift != null) {
            val heading = headingFromYaw(pose.yawDeg, yawTo(pose, lift.pose.position))
            occupancy.markCorridor(pose)
            val meters = lift.pose.distanceTo(pose).toInt().coerceAtLeast(0)
            return ExploreHint(
                text = if (down) "去电梯下${goalFloor}楼" else "去电梯上${goalFloor}楼",
                turn = turnOf(heading),
                mode = if (lift.kind == NodeKind.STAIRS) "stairs" else "elevator",
                headingDeg = heading,
                meters = meters,
                tracking = quality.name.lowercase(),
                hasGuide = true,
            )
        }
        if (observation.kind == NodeKind.ELEVATOR || observation.kind == NodeKind.STAIRS) {
            val heading = headingOf(observation.guideDir)
            return ExploreHint(
                text = if (down) "乘电梯下${goalFloor}楼" else "乘电梯上${goalFloor}楼",
                turn = turnOf(heading),
                mode = if (observation.kind == NodeKind.STAIRS) "stairs" else "elevator",
                headingDeg = heading,
                tracking = quality.name.lowercase(),
                hasGuide = observation.guideDir.isNotBlank(),
            )
        }
        return ExploreHint(
            text = if (down) "找电梯下楼" else "找电梯或扶梯",
            mode = "ground",
            scanRequired = observation.spaceType.isBlank(),
            tracking = quality.name.lowercase(),
        )
    }

    private fun wander(
        topology: LiveTopology,
        observation: SemanticObservation,
        pose: Pose3,
        occupancy: OccupancyGrid,
        quality: TrackQuality,
    ): ExploreHint {
        if (observation.kind == NodeKind.CORRIDOR || observation.kind == NodeKind.JUNCTION) {
            occupancy.markCorridor(pose)
            val frontier = unexplored(topology, pose)
            val heading = if (frontier != null) headingFromYaw(pose.yawDeg, yawTo(pose, frontier.pose.position)) else 0f
            return ExploreHint(
                text = if (frontier != null) "沿走廊再看看" else "请缓慢环视",
                turn = turnOf(heading),
                mode = "ground",
                headingDeg = heading,
                scanRequired = frontier == null,
                tracking = quality.name.lowercase(),
                hasGuide = frontier != null,
            )
        }
        val retreat = retreatNode(topology, pose)
        if (retreat != null) {
            val heading = headingFromYaw(pose.yawDeg, yawTo(pose, retreat.pose.position))
            occupancy.markCorridor(pose)
            return guided(heading, "沿原路往回", quality)
        }
        return ExploreHint(
            text = "请缓慢环视找导视",
            mode = "ground",
            scanRequired = true,
            tracking = if (quality == TrackQuality.GOOD) "scan_required" else quality.name.lowercase(),
        )
    }

    companion object {
        const val SUSTAINED_LOST_MS = 1500L

        fun headingOf(dir: String): Float {
            return when (dir.lowercase()) {
                "left", "左", "←" -> -18f
                "right", "右", "→" -> 18f
                "behind", "后" -> 180f
                "ahead", "forward", "straight", "前", "前向" -> 0f
                else -> 0f
            }
        }

        fun turnOf(heading: Float): String {
            return when {
                heading <= -12f -> "left"
                heading >= 12f && heading < 90f -> "right"
                heading >= 90f || heading <= -90f -> "left"
                else -> "straight"
            }
        }

        fun yawTo(from: Pose3, target: Vec3): Float {
            val d = target - from.position
            return Math.toDegrees(kotlin.math.atan2(d.x.toDouble(), d.y.toDouble())).toFloat()
        }

        fun nearest(topology: LiveTopology, pose: Pose3, kind: NodeKind): TopologyNode? {
            return topology.nodes.filter { it.kind == kind }.minByOrNull { it.pose.distanceTo(pose) }
        }

        fun unexplored(topology: LiveTopology, pose: Pose3): TopologyNode? {
            return topology.nodes
                .filter { it.kind == NodeKind.JUNCTION || it.kind == NodeKind.CORRIDOR }
                .filter { node -> topology.neighbors(node.id).size <= 1 }
                .minByOrNull { it.pose.distanceTo(pose) }
        }

        fun retreatNode(topology: LiveTopology, pose: Pose3): TopologyNode? {
            val current = topology.nodes.minByOrNull { it.pose.distanceTo(pose) } ?: return null
            val path = topology.nodes.firstOrNull()?.let { topology.path(current.id, it.id) }.orEmpty()
            if (path.size < 2) return topology.nodes.firstOrNull()
            return topology.node(path[path.lastIndex - 1])
        }

        private fun guided(heading: Float, text: String, quality: TrackQuality): ExploreHint {
            return ExploreHint(
                text = text,
                turn = turnOf(heading),
                mode = "ground",
                headingDeg = heading,
                tracking = quality.name.lowercase(),
                hasGuide = true,
            )
        }
    }
}
