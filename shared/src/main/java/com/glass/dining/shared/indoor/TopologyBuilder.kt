package com.glass.dining.shared.indoor

import kotlin.math.abs

/**
 * 把稳定 episode + VIO 位姿收成语义拓扑。合并靠距离/朝向/栅格，禁止只凭文案。
 */
class TopologyBuilder {
    var topology: LiveTopology = LiveTopology()
        private set
    private var seq: Int = 0
    private var lastNodeId: String = ""

    fun start(sessionId: String) {
        topology = LiveTopology(sessionId = sessionId)
        seq = 0
        lastNodeId = ""
    }

    fun ingest(
        pose: Pose3,
        observation: SemanticObservation,
        grid: IntArray,
        floor: String = observation.floorCandidate,
    ): TopologyNode? {
        if (observation.confidence < 0.25f && observation.spaceType.isBlank()) return null
        val existing = findMerge(pose, observation, grid)
        val node = if (existing != null) {
            existing.copy(
                pose = pose,
                floor = floor.ifBlank { existing.floor },
                exits = mergeExits(existing.exits, observation.exits),
                evidence = observation.evidence.ifBlank { existing.evidence },
                confidence = maxOf(existing.confidence, observation.confidence),
                grid = if (grid.isNotEmpty()) grid.copyOf() else existing.grid,
                episodeId = observation.episodeId.ifBlank { existing.episodeId },
                hits = existing.hits + 1,
            )
        } else {
            seq += 1
            TopologyNode(
                id = "n$seq",
                kind = observation.kind,
                pose = pose,
                floor = floor,
                exits = observation.exits,
                evidence = observation.evidence,
                confidence = observation.confidence,
                grid = grid.copyOf(),
                episodeId = observation.episodeId,
            )
        }
        val nodes = topology.nodes.filterNot { it.id == node.id } + node
        val edges = if (existing == null && lastNodeId.isNotBlank()) {
            topology.edges + edgeBetween(lastNodeId, node, topology.node(lastNodeId))
        } else {
            topology.edges
        }
        topology = topology.copy(nodes = nodes, edges = edges)
        lastNodeId = node.id
        return node
    }

    fun currentId(): String = lastNodeId

    private fun findMerge(pose: Pose3, observation: SemanticObservation, grid: IntArray): TopologyNode? {
        return topology.nodes.firstOrNull { node ->
            val dist = node.pose.distanceTo(pose)
            val yaw = abs(Angle.deltaDeg(node.pose.yawDeg, pose.yawDeg))
            val sameKind = node.kind == observation.kind || observation.kind == NodeKind.OTHER
            val gridOk = grid.isEmpty() || node.grid.isEmpty() ||
                VisualInertialFilter.gridDistance(node.grid, grid) <= VisualInertialFilter.LOOP_GRID
            dist <= MERGE_M && yaw <= MERGE_YAW && sameKind && gridOk
        }
    }

    private fun edgeBetween(fromId: String, to: TopologyNode, from: TopologyNode?): TopologyEdge {
        val length = from?.pose?.distanceTo(to.pose) ?: 0f
        val yaw = to.pose.yawDeg
        val kind = when {
            from?.kind == NodeKind.ELEVATOR || to.kind == NodeKind.ELEVATOR -> EdgeKind.ELEVATOR
            from != null && from.floor.isNotBlank() && to.floor.isNotBlank() && from.floor != to.floor -> {
                if (floorRank(to.floor) < floorRank(from.floor)) EdgeKind.DOWN else EdgeKind.UP
            }
            abs(Angle.deltaDeg(from?.pose?.yawDeg ?: yaw, yaw)) > 35f -> EdgeKind.TURN
            else -> EdgeKind.WALK
        }
        return TopologyEdge(fromId, to.id, kind, length, yaw)
    }

    companion object {
        const val MERGE_M = 3f
        const val MERGE_YAW = 40f

        fun mergeExits(a: List<ExitHint>, b: List<ExitHint>): List<ExitHint> {
            val byDir = LinkedHashMap<String, ExitHint>()
            (a + b).forEach { hint ->
                val key = hint.dir.ifBlank { hint.label }
                if (key.isBlank()) return@forEach
                byDir[key] = hint
            }
            return byDir.values.toList()
        }

        fun floorRank(floor: String): Int {
            val n = floor.trim().uppercase()
            if (n.startsWith("B")) return -(n.drop(1).toIntOrNull() ?: 1)
            return n.toIntOrNull() ?: 0
        }
    }
}
