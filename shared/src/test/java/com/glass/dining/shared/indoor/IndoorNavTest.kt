package com.glass.dining.shared.indoor

import com.glass.dining.shared.nav.LandmarkHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccupancyTest {
    @Test
    fun blockedCellDropsChevron() {
        val grid = OccupancyGrid()
        val pose = Pose3()
        grid.recenter(pose)
        grid.markCorridor(pose, 6f)
        grid.markBlocked(pose, 0f, 6f)
        val pts = LocalPath.conservative(grid, pose, 0f)
        assertTrue(pts.none { it.y in 5.5f..6.5f })
    }

    @Test
    fun corridorKeepsForwardPoints() {
        val grid = OccupancyGrid()
        val pose = Pose3()
        grid.recenter(pose)
        grid.markCorridor(pose, 6f)
        val pts = LocalPath.conservative(grid, pose, 0f)
        assertTrue(pts.size >= 2)
    }
}

class TopologyBuilderTest {
    @Test
    fun nearbySameKindMerges() {
        val builder = TopologyBuilder()
        builder.start("s1")
        val grid = IntArray(256) { 20 }
        val a = builder.ingest(
            Pose3(),
            SemanticObservation(spaceType = "junction", confidence = 0.8f, episodeId = "e1"),
            grid,
        )
        val b = builder.ingest(
            Pose3(position = Vec3(0.4f, 0.3f, 0f)),
            SemanticObservation(spaceType = "junction", confidence = 0.7f, episodeId = "e2"),
            grid,
        )
        assertEquals(a!!.id, b!!.id)
        assertEquals(1, builder.topology.nodes.size)
        assertEquals(2, b.hits)
    }

    @Test
    fun farNodesGetEdgeAndPathBack() {
        val builder = TopologyBuilder()
        builder.start("s1")
        val empty = IntArray(0)
        builder.ingest(Pose3(), SemanticObservation(spaceType = "entrance", confidence = 0.8f), empty)
        builder.ingest(
            Pose3(position = Vec3(0f, 6f, 0f)),
            SemanticObservation(spaceType = "elevator", confidence = 0.8f),
            empty,
        )
        val g = builder.topology
        assertEquals(2, g.nodes.size)
        assertEquals(1, g.edges.size)
        val path = g.path(g.nodes.last().id, g.nodes.first().id)
        assertEquals(2, path.size)
    }

    @Test
    fun textAloneDoesNotMergeDifferentPlaces() {
        val builder = TopologyBuilder()
        builder.start("s1")
        val g1 = IntArray(256) { 10 }
        val g2 = IntArray(256) { 80 }
        builder.ingest(
            Pose3(),
            SemanticObservation(spaceType = "corridor", evidence = "走廊", confidence = 0.9f),
            g1,
        )
        builder.ingest(
            Pose3(position = Vec3(0f, 8f, 0f)),
            SemanticObservation(spaceType = "corridor", evidence = "走廊", confidence = 0.9f),
            g2,
        )
        assertEquals(2, builder.topology.nodes.size)
    }
}

class ExplorationTest {
    @Test
    fun briefLostKeepsGuideWithoutLostCopy() {
        val hint = ExplorationPlanner().decide(
            LiveTopology(),
            SemanticObservation(),
            Pose3(),
            TrackQuality.LOST,
            "海底捞",
            "1",
            "",
            OccupancyGrid(),
            hadGoodTrack = true,
            lostMs = 200L,
        )
        assertTrue(hint.text.contains("定位丢失").not())
        assertTrue(hint.tracking != "tracking_lost")
        assertEquals("ground", hint.mode)
    }

    @Test
    fun sustainedLostAsksScanWithoutLostCopy() {
        val hint = ExplorationPlanner().decide(
            LiveTopology(),
            SemanticObservation(),
            Pose3(),
            TrackQuality.LOST,
            "海底捞",
            "4",
            "1",
            OccupancyGrid(),
            hadGoodTrack = true,
            lostMs = 1600L,
        )
        assertTrue(hint.scanRequired)
        assertTrue(hint.tracking != "tracking_lost")
        assertTrue(hint.text.contains("定位丢失").not())
        assertTrue(hint.text.contains("环视") || hint.text.contains("导视"))
        assertEquals("ground", hint.mode)
    }

    @Test
    fun coldStartLostIsNotTrackingLost() {
        val hint = ExplorationPlanner().decide(
            LiveTopology(),
            SemanticObservation(),
            Pose3(),
            TrackQuality.LOST,
            "海底捞",
            "1",
            "",
            OccupancyGrid(),
            hadGoodTrack = false,
        )
        assertTrue(hint.tracking != "tracking_lost")
        assertTrue(hint.text.contains("环视") || hint.text.contains("导视"))
    }

    @Test
    fun storeNameArrives() {
        val hint = ExplorationPlanner().decide(
            LiveTopology(),
            SemanticObservation(storeNames = listOf("海底捞"), confidence = 0.8f, spaceType = "storefront"),
            Pose3(),
            TrackQuality.GOOD,
            "海底捞",
            "4",
            "4",
            OccupancyGrid(),
        )
        assertTrue(hint.arrived)
    }

    @Test
    fun parseSemanticJson() {
        val look = SemanticLook.parse(
            """{"spaceType":"junction","guideDir":"right","exits":[{"dir":"right","label":"餐饮"}],"floorCandidate":"7F","confidence":0.8,"salientText":"7F餐饮"}""",
        )
        assertEquals(NodeKind.JUNCTION, look.kind)
        assertEquals("right", look.guideDir)
        assertEquals("7", look.floorCandidate)
        assertEquals(1, look.exits.size)
    }

    @Test
    fun parseNestedNavigationObject() {
        val look = SemanticLook.parse(
            """{"observation":{"sceneBrief":"走廊分叉","salientText":"餐饮"},"navigation":{"spaceType":"junction","guideDir":"right","exits":[{"dir":"right","label":"餐饮"}],"floorCandidate":"7F"},"confidence":0.8}""",
        )
        assertEquals(NodeKind.JUNCTION, look.kind)
        assertEquals("right", look.guideDir)
        assertEquals("7", look.floorCandidate)
        assertEquals(1, look.exits.size)
    }
}

class IndoorHintBinderTest {
    @Test
    fun scanRequiredKeepsGroundMode() {
        val guide = IndoorHintBinder.bind(
            ocr = null,
            explore = ExploreHint(text = "请缓慢环视", scanRequired = true, mode = "ground"),
            pose = Pose3(),
            occupancy = OccupancyGrid(),
            quality = TrackQuality.WEAK,
            storeName = "海底捞",
            remaining = 0,
            stage = "seek_store",
        )
        assertEquals("ground", guide.mode)
        assertTrue(guide.scanRequired)
        assertTrue(guide.waypoints.isEmpty())
        assertEquals(0, guide.meters)
    }

    @Test
    fun seekExitKeepsGroundMode() {
        val guide = IndoorHintBinder.bind(
            ocr = LandmarkHint(text = "找出口出门", mode = "ground", turn = "straight"),
            explore = ExploreHint(text = "请缓慢环视找导视", scanRequired = true, mode = "ground"),
            pose = Pose3(),
            occupancy = OccupancyGrid(),
            quality = TrackQuality.WEAK,
            storeName = "海底捞",
            remaining = 400,
            stage = "seek_exit",
        )
        assertEquals("ground", guide.mode)
        assertEquals("找出口出门", guide.text)
        assertTrue(guide.waypoints.isEmpty())
        assertEquals(0, guide.meters)
    }

    @Test
    fun seekExitFlashLostKeepsExitCopyAndGround() {
        val guide = IndoorHintBinder.bind(
            ocr = LandmarkHint(text = "找出口出门", mode = "ground", turn = "straight"),
            explore = ExploreHint(text = "定位丢失", tracking = "tracking_lost", mode = "", scanRequired = true),
            pose = Pose3(),
            occupancy = OccupancyGrid(),
            quality = TrackQuality.LOST,
            storeName = "海底捞",
            remaining = 400,
            stage = "seek_exit",
        )
        assertEquals("ground", guide.mode)
        assertEquals("找出口出门", guide.text)
        assertTrue(guide.text.contains("定位丢失").not())
        assertTrue(guide.waypoints.isEmpty())
        assertEquals(0, guide.meters)
    }

    @Test
    fun seekExitEntrancePointsAheadWithoutFakeMeters() {
        val explore = ExplorationPlanner().decide(
            topology = LiveTopology(),
            observation = SemanticObservation(spaceType = "entrance", confidence = 0.8f),
            pose = Pose3(),
            quality = TrackQuality.GOOD,
            goalName = "海底捞",
            goalFloor = "1",
            currentFloor = "",
            occupancy = OccupancyGrid(),
            stage = "seek_exit",
        )
        assertTrue(explore.hasGuide)
        assertEquals(0, explore.meters)
        val guide = IndoorHintBinder.bind(
            ocr = LandmarkHint(text = "找出口出门", mode = "ground", turn = "straight"),
            explore = explore,
            pose = Pose3(),
            occupancy = OccupancyGrid(),
            quality = TrackQuality.GOOD,
            storeName = "海底捞",
            remaining = 400,
            stage = "seek_exit",
        )
        assertTrue(guide.waypoints.isNotEmpty())
        assertEquals(0, guide.meters)
        assertEquals("找出口出门", guide.text)
    }

    @Test
    fun seekExitAheadExitUsesGuideDir() {
        val explore = ExplorationPlanner().decide(
            topology = LiveTopology(),
            observation = SemanticObservation(
                spaceType = "corridor",
                exits = listOf(ExitHint(dir = "ahead", label = "大门")),
                guideDir = "ahead",
                confidence = 0.7f,
            ),
            pose = Pose3(),
            quality = TrackQuality.GOOD,
            goalName = "海底捞",
            goalFloor = "1",
            currentFloor = "",
            occupancy = OccupancyGrid(),
            stage = "seek_exit",
        )
        assertTrue(explore.hasGuide)
        assertEquals(0f, explore.headingDeg, 0.1f)
        assertEquals(0, explore.meters)
        val guide = IndoorHintBinder.bind(
            ocr = LandmarkHint(text = "找出口出门", mode = "ground"),
            explore = explore,
            pose = Pose3(),
            occupancy = OccupancyGrid(),
            quality = TrackQuality.GOOD,
            storeName = "海底捞",
            remaining = 400,
            stage = "seek_exit",
        )
        assertTrue(guide.waypoints.isNotEmpty())
        assertEquals(0, guide.meters)
    }
}

class WorldAnchorTest {
    @Test
    fun lostQualityStillHasChevrons() {
        val pts = WorldAnchor.chevrons(Pose3(), 0f, OccupancyGrid(), TrackQuality.LOST)
        assertTrue(pts.isNotEmpty())
        assertTrue(pts.all { it.z < -1f })
    }

    @Test
    fun groundPointSitsAtEyeHeightBelow() {
        val pose = Pose3()
        val world = pointAhead(pose, 6f, 0f)
        assertTrue(world.z < 0f)
        assertEquals(-SensorCalibration.rokidGlass3().eyeHeightM, world.z, 0.05f)
        val pix = WorldAnchor.project(world, pose, SensorCalibration.rokidGlass3(), 480f, 640f)
        assertTrue(pix != null)
        assertTrue(pix!!.first in 100f..380f)
    }

    @Test
    fun yawMovesProjectionOppositeOnScreen() {
        val calib = SensorCalibration.rokidGlass3()
        val world = pointAhead(Pose3(), 6f, 0f)
        val center = WorldAnchor.project(world, Pose3(), calib, 480f, 640f)!!
        val turned = Pose3(orientation = Quat.fromYawPitchRoll(20f, 0f, 0f))
        val shifted = WorldAnchor.project(world, turned, calib, 480f, 640f)!!
        assertTrue(shifted.first < center.first)
    }

    @Test
    fun nearerChevronIsLowerAndLarger() {
        val pose = Pose3()
        val calib = SensorCalibration.rokidGlass3()
        val drawn = GroundGuide.project(
            listOf(pointAhead(pose, 6f), pointAhead(pose, 12f)),
            pose,
            calib,
            480f,
            640f,
        )
        assertEquals(2, drawn.size)
        assertTrue(drawn[0].y > drawn[1].y)
        assertTrue(drawn[0].size > drawn[1].size)
    }
}
