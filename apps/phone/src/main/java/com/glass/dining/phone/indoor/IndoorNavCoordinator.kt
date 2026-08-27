package com.glass.dining.phone.indoor

import android.os.SystemClock
import android.util.Log
import com.glass.dining.shared.agent.EnvironmentEpisode
import com.glass.dining.shared.agent.EnvironmentLook
import com.glass.dining.shared.agent.SpatialEvidence
import com.glass.dining.shared.indoor.ExplorationPlanner
import com.glass.dining.shared.indoor.IndoorHintBinder
import com.glass.dining.shared.indoor.LiveTopology
import com.glass.dining.shared.indoor.OccupancyCue
import com.glass.dining.shared.indoor.OccupancyGrid
import com.glass.dining.shared.indoor.Pose3
import com.glass.dining.shared.indoor.Quat
import com.glass.dining.shared.indoor.SemanticLook
import com.glass.dining.shared.indoor.SemanticObservation
import com.glass.dining.shared.indoor.TopologyBuilder
import com.glass.dining.shared.indoor.TrackQuality
import com.glass.dining.shared.indoor.Vec3
import com.glass.dining.shared.nav.IndoorProtocol
import com.glass.dining.shared.nav.LandmarkGoal
import com.glass.dining.shared.nav.LandmarkPlanner
import com.glass.dining.shared.nav.LandmarkStage
import com.glass.dining.shared.nav.LandmarkWorld
import com.glass.dining.shared.nav.NavHint
import com.glass.dining.shared.nav.NavPose
import com.glass.dining.shared.vision.LocalExtract

class IndoorNavCoordinator {
    val landmarks = LandmarkPlanner()
    val topology = TopologyBuilder()
    val occupancy = OccupancyGrid()
    private val explorer = ExplorationPlanner()
    var sessionId: String = ""
        private set
    var lastPose: Pose3 = Pose3()
        private set
    var lastQuality: TrackQuality = TrackQuality.WEAK
        private set
    var lastObservation: SemanticObservation = SemanticObservation()
        private set
    private var goalName: String = ""
    private var goalFloor: String = ""

    fun start(goal: LandmarkGoal, gpsUsable: Boolean, spokenFloor: String = "") {
        sessionId = "nav-${SystemClock.elapsedRealtime()}"
        goalName = goal.shortName.ifBlank { goal.storeName }
        goalFloor = goal.floor
        lastPose = Pose3()
        lastQuality = TrackQuality.WEAK
        lastObservation = SemanticObservation()
        occupancy.recenter(lastPose)
        topology.start(sessionId)
        landmarks.start(goal, gpsUsable, spokenFloor)
        Log.i(TAG, "indoor start session=$sessionId gps=$gpsUsable floor=${goal.floor}")
    }

    fun reset() {
        landmarks.reset()
        topology.start("")
        occupancy.recenter(Pose3())
        sessionId = ""
        lastObservation = SemanticObservation()
    }

    fun onPose(pose: NavPose) {
        lastPose = Pose3(
            tNs = pose.tNs,
            position = Vec3(pose.x, pose.y, pose.z),
            orientation = Quat.fromYawPitchRoll(pose.yaw, pose.pitch, pose.roll),
        )
        lastQuality = qualityOf(pose.tracking)
        occupancy.recenter(lastPose, pose.pitch)
        if (lastQuality == TrackQuality.GOOD) occupancy.markCorridor(lastPose)
    }

    fun onSemantic(episode: EnvironmentEpisode, look: EnvironmentLook, raw: String): SpatialEvidence {
        val parsed = SemanticLook.parse(raw)
        val observation = if (parsed.spaceType.isNotBlank() || parsed.confidence > 0f) {
            parsed.copy(
                episodeId = episode.id,
                floorCandidate = parsed.floorCandidate.ifBlank { look.floorCandidate },
                evidence = parsed.evidence.ifBlank { look.salientText },
                confidence = if (parsed.confidence > 0f) parsed.confidence else look.confidence,
                spaceType = parsed.spaceType.ifBlank { look.spaceType },
                guideDir = parsed.guideDir.ifBlank { look.guideDir },
                storeNames = parsed.storeNames.ifEmpty { look.storeNames },
                blocked = parsed.blocked || look.blocked,
            )
        } else {
            SemanticLook.fromLook(look).copy(episodeId = episode.id)
        }
        lastObservation = observation
        val pose = Pose3(
            tNs = episode.settledAt,
            position = Vec3(episode.poseX, episode.poseY, episode.poseZ),
            orientation = Quat.fromYawPitchRoll(episode.yawDeg, 0f, 0f),
        )
        OccupancyCue.apply(occupancy, pose, observation)
        val node = topology.ingest(pose, observation, episode.grid, observation.floorCandidate)
        Log.i(
            TAG,
            "topology node=${node?.id} kind=${observation.kind} nodes=${topology.topology.nodes.size} " +
                "edges=${topology.topology.edges.size} ep=${episode.id}",
        )
        return SpatialEvidence(
            episodeId = episode.id,
            poseX = episode.poseX,
            poseY = episode.poseY,
            poseZ = episode.poseZ,
            yawDeg = episode.yawDeg,
            tracking = episode.tracking,
            topologyNodeId = node?.id.orEmpty(),
            loopClosed = (node?.hits ?: 0) > 1,
        )
    }

    fun liveMap(): LiveTopology = topology.topology

    fun observe(
        extract: LocalExtract,
        goal: LandmarkGoal,
        gpsRemaining: Int,
        gpsArrive: Boolean,
        world: LandmarkWorld,
    ): NavHint? {
        val ocr = landmarks.observe(extract, goal, gpsRemaining, gpsArrive, world)
        if (landmarks.stage == LandmarkStage.OUTDOOR) return ocr?.let { decorateOutdoor(it) }
        val explore = explorer.decide(
            topology = topology.topology,
            observation = lastObservation,
            pose = lastPose,
            quality = lastQuality,
            goalName = goal.shortName.ifBlank { goal.storeName },
            goalFloor = goal.floor,
            currentFloor = landmarks.currentFloor.ifBlank { world.spokenFloor },
            occupancy = occupancy,
        )
        val guide = IndoorHintBinder.bind(
            ocr = ocr,
            explore = explore,
            pose = lastPose,
            occupancy = occupancy,
            quality = lastQuality,
            storeName = goal.shortName,
            remaining = gpsRemaining,
            stage = landmarks.stage.name.lowercase(),
        )
        return toHint(guide, goal.shortName, gpsRemaining)
    }

    fun bootstrapHint(goal: LandmarkGoal, remaining: Int): NavHint {
        val ocr = landmarks.bootstrapHint(goal, remaining)
        if (landmarks.stage == LandmarkStage.OUTDOOR) return decorateOutdoor(ocr)
        val explore = explorer.decide(
            topology.topology,
            lastObservation,
            lastPose,
            lastQuality,
            goal.shortName,
            goal.floor,
            landmarks.currentFloor,
            occupancy,
        )
        val guide = IndoorHintBinder.bind(
            ocr, explore, lastPose, occupancy, lastQuality,
            goal.shortName, remaining, landmarks.stage.name.lowercase(),
        )
        return toHint(guide, goal.shortName, remaining)
    }

    private fun decorateOutdoor(ocr: com.glass.dining.shared.nav.LandmarkHint): NavHint {
        return NavHint(
            turn = ocr.turn,
            meters = ocr.meters,
            text = ocr.text,
            storeName = ocr.storeName,
            remaining = ocr.remaining,
            mode = ocr.mode,
            headingDeg = ocr.headingDeg,
            elevationDeg = ocr.elevationDeg,
            stage = ocr.stage,
            sessionId = sessionId,
            tracking = "",
            waypoints = "",
        )
    }

    private fun toHint(
        guide: com.glass.dining.shared.indoor.IndoorGuide,
        storeName: String,
        remaining: Int,
    ): NavHint {
        return NavHint(
            turn = guide.turn,
            meters = guide.meters,
            text = guide.text,
            storeName = storeName,
            remaining = remaining,
            mode = guide.mode,
            headingDeg = guide.headingDeg,
            elevationDeg = guide.elevationDeg,
            stage = guide.stage,
            sessionId = sessionId,
            tracking = guide.tracking,
            waypoints = IndoorProtocol.encodeWaypoints(
                guide.waypoints.map { floatArrayOf(it.x, it.y, it.z) },
            ),
        )
    }

    companion object {
        private const val TAG = "GlassDiningPhone"

        fun qualityOf(raw: String): TrackQuality {
            return when (raw.lowercase()) {
                "good" -> TrackQuality.GOOD
                "tracking_lost", "lost" -> TrackQuality.LOST
                else -> TrackQuality.WEAK
            }
        }
    }
}
