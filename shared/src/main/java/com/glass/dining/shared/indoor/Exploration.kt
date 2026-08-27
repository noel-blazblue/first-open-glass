package com.glass.dining.shared.indoor

data class ExploreHint(
    val text: String,
    val turn: String = "straight",
    val mode: String = "ground",
    val headingDeg: Float = 0f,
    val meters: Int = 6,
    val arrived: Boolean = false,
    val tracking: String = TrackQuality.GOOD.name.lowercase(),
    val scanRequired: Boolean = false,
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
    ): ExploreHint {
        if (quality == TrackQuality.LOST) {
            return ExploreHint(
                text = "定位丢失，停一下环视",
                mode = "",
                tracking = "tracking_lost",
                scanRequired = true,
            )
        }
        if (observation.blocked) {
            occupancy.markBlocked(pose)
            return ExploreHint(text = "前方可能挡住，换条路", turn = "left", mode = "ground", headingDeg = -18f)
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
                meters = 0,
            )
        }
        val needVertical = goalFloor.isNotBlank() && currentFloor.isNotBlank() &&
            TopologyBuilder.floorRank(currentFloor) != TopologyBuilder.floorRank(goalFloor)
        if (needVertical) {
            val down = TopologyBuilder.floorRank(currentFloor) > TopologyBuilder.floorRank(goalFloor)
            val lift = nearest(topology, pose, NodeKind.ELEVATOR) ?: nearest(topology, pose, NodeKind.STAIRS)
            if (lift != null) {
                val heading = headingFromYaw(pose.yawDeg, yawTo(pose, lift.pose.position))
                occupancy.markCorridor(pose)
                return ExploreHint(
                    text = if (down) "去电梯下${goalFloor}楼" else "去电梯上${goalFloor}楼",
                    turn = turnOf(heading),
                    mode = if (lift.kind == NodeKind.STAIRS) "stairs" else "elevator",
                    headingDeg = heading,
                    meters = lift.pose.distanceTo(pose).toInt().coerceAtLeast(4),
                    tracking = quality.name.lowercase(),
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
                )
            }
            return ExploreHint(
                text = if (down) "找电梯下楼" else "找电梯或扶梯",
                mode = "ground",
                scanRequired = observation.spaceType.isBlank(),
                tracking = quality.name.lowercase(),
            )
        }
        val guide = headingOf(observation.guideDir.ifBlank { observation.exits.firstOrNull()?.dir.orEmpty() })
        if (observation.guideDir.isNotBlank() || observation.exits.isNotEmpty()) {
            occupancy.markCorridor(pose)
            return ExploreHint(
                text = observation.exits.firstOrNull()?.label?.ifBlank { "沿导视走" } ?: "沿导视走",
                turn = turnOf(guide),
                mode = "ground",
                headingDeg = guide,
                tracking = quality.name.lowercase(),
            )
        }
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
            )
        }
        val retreat = retreatNode(topology, pose)
        if (retreat != null) {
            val heading = headingFromYaw(pose.yawDeg, yawTo(pose, retreat.pose.position))
            return ExploreHint(
                text = "沿原路往回",
                turn = turnOf(heading),
                mode = "ground",
                headingDeg = heading,
                tracking = quality.name.lowercase(),
            )
        }
        return ExploreHint(
            text = "请缓慢环视找导视",
            mode = "ground",
            scanRequired = true,
            tracking = if (quality == TrackQuality.GOOD) "scan_required" else quality.name.lowercase(),
        )
    }

    companion object {
        fun headingOf(dir: String): Float {
            return when (dir.lowercase()) {
                "left", "左", "←" -> -18f
                "right", "右", "→" -> 18f
                "behind", "后" -> 180f
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
    }
}
