package com.glass.dining.shared.indoor

enum class NodeKind {
    CORRIDOR,
    JUNCTION,
    ELEVATOR,
    STAIRS,
    ENTRANCE,
    STOREFRONT,
    SIGNAGE,
    OTHER,
}

enum class EdgeKind {
    WALK,
    TURN,
    UP,
    DOWN,
    ELEVATOR,
}

data class ExitHint(
    val dir: String = "",
    val label: String = "",
)

data class SemanticObservation(
    val spaceType: String = "",
    val exits: List<ExitHint> = emptyList(),
    val guideDir: String = "",
    val storeNames: List<String> = emptyList(),
    val blocked: Boolean = false,
    val floorCandidate: String = "",
    val evidence: String = "",
    val confidence: Float = 0f,
    val episodeId: String = "",
) {
    val kind: NodeKind
        get() = when (spaceType.lowercase()) {
            "junction", "fork" -> NodeKind.JUNCTION
            "elevator" -> NodeKind.ELEVATOR
            "stairs", "escalator" -> NodeKind.STAIRS
            "entrance", "exit", "outdoor" -> NodeKind.ENTRANCE
            "storefront", "store" -> NodeKind.STOREFRONT
            "signage" -> NodeKind.SIGNAGE
            "corridor" -> NodeKind.CORRIDOR
            else -> NodeKind.OTHER
        }
}

data class TopologyNode(
    val id: String,
    val kind: NodeKind,
    val pose: Pose3,
    val floor: String = "",
    val exits: List<ExitHint> = emptyList(),
    val evidence: String = "",
    val confidence: Float = 0f,
    val grid: IntArray = IntArray(0),
    val episodeId: String = "",
    val hits: Int = 1,
)

data class TopologyEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val lengthM: Float,
    val yawDeg: Float,
)

data class LiveTopology(
    val sessionId: String = "",
    val nodes: List<TopologyNode> = emptyList(),
    val edges: List<TopologyEdge> = emptyList(),
) {
    fun node(id: String): TopologyNode? = nodes.firstOrNull { it.id == id }

    fun neighbors(id: String): List<TopologyEdge> = edges.filter { it.from == id || it.to == id }

    fun path(fromId: String, toId: String): List<String> {
        if (fromId == toId) return listOf(fromId)
        val q = ArrayDeque<List<String>>()
        val seen = mutableSetOf(fromId)
        q.add(listOf(fromId))
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val last = cur.last()
            neighbors(last).forEach { edge ->
                val next = if (edge.from == last) edge.to else edge.from
                if (next in seen) return@forEach
                val path = cur + next
                if (next == toId) return path
                seen += next
                q.add(path)
            }
        }
        return emptyList()
    }
}
