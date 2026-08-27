package com.glass.dining.shared.nav

import com.glass.dining.shared.model.Store
import com.glass.dining.shared.vision.LocalExtract
import com.glass.dining.shared.vision.OcrBlock
import com.glass.dining.shared.vision.QualityReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkNavTest {
    @Test
    fun parsesFloorFromAddressAndField() {
        assertEquals("4", LandmarkSignage.normalizeFloor("北京市朝阳区望京 4楼"))
        assertEquals("B1", LandmarkSignage.normalizeFloor("B1层餐饮"))
        assertEquals("5", LandmarkSignage.normalizeFloor("5F"))
        assertEquals("3", LandmarkSignage.normalizeFloor("第3层"))
        assertEquals("4", LandmarkSignage.normalizeFloor("4"))
        assertEquals("B2", LandmarkSignage.normalizeFloor("地下2层"))
        assertEquals("", LandmarkSignage.normalizeFloor("望京西路"))
    }

    @Test
    fun parsesSpokenCurrentFloor() {
        assertEquals("5", LandmarkSignage.parseSpokenFloor("我在5楼，去巴奴"))
        assertEquals("4", LandmarkSignage.parseSpokenFloor("现在在4层"))
        assertEquals("B1", LandmarkSignage.parseSpokenFloor("我在B1"))
        assertEquals("B1", LandmarkSignage.parseSpokenFloor("我在地下1层"))
        assertEquals("", LandmarkSignage.parseSpokenFloor("去4楼的巴奴"))
    }

    @Test
    fun comparesFloorsUpDownSame() {
        assertEquals(FloorRel.DOWN, LandmarkSignage.relate("5", "4"))
        assertEquals(FloorRel.UP, LandmarkSignage.relate("1", "4"))
        assertEquals(FloorRel.SAME, LandmarkSignage.relate("4F", "4"))
        assertEquals(FloorRel.UP, LandmarkSignage.relate("B1", "1"))
        assertEquals(FloorRel.DOWN, LandmarkSignage.relate("3", "B1"))
        assertEquals(FloorRel.UNKNOWN, LandmarkSignage.relate("", "4"))
        assertEquals(-1, LandmarkSignage.floorRank("B1"))
        assertEquals(1, LandmarkSignage.floorRank("1"))
    }

    @Test
    fun staysOutdoorUntilClose() {
        val planner = LandmarkPlanner()
        val extract = extract(OcrBlock("电梯", 40, 40, 120, 90))
        val hint = planner.observe(extract, goal(floor = "4"), gpsRemaining = 400, gpsArrive = false)
        assertEquals(LandmarkStage.OUTDOOR, planner.stage)
        assertEquals(null, hint)
    }

    @Test
    fun entersVerticalWhenCloseAndHasFloor() {
        val planner = LandmarkPlanner()
        val extract = extract(OcrBlock("客梯", 200, 80, 280, 140))
        planner.observe(extract, goal(floor = "4"), gpsRemaining = 60, gpsArrive = false)
        planner.observe(extract, goal(floor = "4"), gpsRemaining = 60, gpsArrive = false)
        assertEquals(LandmarkStage.SEEK_VERTICAL, planner.stage)
        val hint = planner.observe(extract, goal(floor = "4"), gpsRemaining = 60, gpsArrive = false)!!
        assertEquals(LandmarkPlanner.MODE_ELEVATOR, hint.mode)
        assertTrue(hint.headingDeg > 0f)
        assertEquals("乘电梯上4楼", hint.text)
        assertFalse(planner.verticalDown)
    }

    @Test
    fun floorOcrAdvancesToStoreHunt() {
        val planner = LandmarkPlanner()
        planner.enterIndoor(goal(floor = "4"))
        val elevator = extract(OcrBlock("电梯", 80, 60, 140, 110))
        planner.observe(elevator, goal(floor = "4"), 40, false)
        planner.observe(elevator, goal(floor = "4"), 40, false)
        val floor = extract(OcrBlock("4F 餐饮", 60, 20, 180, 70))
        planner.observe(floor, goal(floor = "4"), 30, false)
        val next = planner.observe(floor, goal(floor = "4"), 30, false)!!
        assertEquals(LandmarkStage.SEEK_STORE, planner.stage)
        assertEquals(LandmarkPlanner.MODE_GROUND, next.mode)
        assertEquals("沿走廊看店招", next.text)
    }

    @Test
    fun sameFloorSkipsElevator() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = "4"), gpsUsable = false, spokenFloor = "我在4楼")
        assertEquals(LandmarkStage.SEEK_STORE, planner.stage)
        assertEquals("4", planner.currentFloor)
        val hint = planner.bootstrapHint(goal(floor = "4"), 0)
        assertEquals("沿走廊看店招", hint.text)
    }

    @Test
    fun higherFloorGoesDownstairs() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = "4"), gpsUsable = false, spokenFloor = "我在5楼")
        assertEquals(LandmarkStage.SEEK_VERTICAL, planner.stage)
        assertTrue(planner.verticalDown)
        val stairs = extract(OcrBlock("楼梯", 40, 80, 120, 140))
        planner.observe(stairs, goal(floor = "4"), 20, false, indoorWorld())
        val hint = planner.observe(stairs, goal(floor = "4"), 20, false, indoorWorld())!!
        assertEquals(LandmarkPlanner.MODE_STAIRS, hint.mode)
        assertEquals("下楼梯", hint.text)
        assertTrue(hint.elevationDeg < 0f)
    }

    @Test
    fun indoorStartWithoutGpsLocatesFloor() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = "4"), gpsUsable = false)
        assertEquals(LandmarkStage.LOCATE_FLOOR, planner.stage)
        val floor = extract(OcrBlock("5F", 40, 20, 120, 60))
        planner.observe(floor, goal(floor = "4"), 0, false, indoorWorld())
        val hint = planner.observe(floor, goal(floor = "4"), 0, false, indoorWorld())!!
        assertEquals("5", planner.currentFloor)
        assertEquals(LandmarkStage.SEEK_VERTICAL, planner.stage)
        assertTrue(planner.verticalDown)
        assertTrue(hint.text.contains("下"))
    }

    @Test
    fun indoorStartTowardOutdoorDestSeeksExit() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = ""), gpsUsable = false, spokenFloor = "我在1楼")
        assertEquals(LandmarkStage.SEEK_EXIT, planner.stage)
        val exit = extract(OcrBlock("A出口", 200, 60, 280, 120))
        planner.observe(exit, goal(floor = ""), 0, false, indoorWorld())
        val hint = planner.observe(exit, goal(floor = ""), 0, false, indoorWorld())!!
        assertEquals(LandmarkPlanner.MODE_EXIT, hint.mode)
        assertEquals("从这里出门", hint.text)
        assertTrue(hint.headingDeg > 0f)
    }

    @Test
    fun indoorStartOnUpperFloorGoesDownThenExit() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = ""), gpsUsable = false, spokenFloor = "我在5楼")
        assertEquals(LandmarkStage.SEEK_VERTICAL, planner.stage)
        assertTrue(planner.verticalDown)
        val one = extract(OcrBlock("1F", 40, 20, 100, 50))
        planner.observe(one, goal(floor = ""), 0, false, indoorWorld())
        planner.observe(one, goal(floor = ""), 0, false, indoorWorld())
        assertEquals(LandmarkStage.SEEK_EXIT, planner.stage)
        assertEquals("1", planner.currentFloor)
    }

    @Test
    fun exitThenGpsLockReturnsOutdoor() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = ""), gpsUsable = false, spokenFloor = "我在1楼")
        assertEquals(LandmarkStage.SEEK_EXIT, planner.stage)
        val street = extract(OcrBlock("路口", 40, 40, 100, 80))
        planner.observe(street, goal(floor = ""), 200, false, indoorWorld())
        val hint = planner.observe(
            street,
            goal(floor = ""),
            200,
            false,
            LandmarkWorld(gpsUsable = true, gpsAccuracyM = 12f),
        )
        assertEquals(LandmarkStage.OUTDOOR, planner.stage)
        assertNull(hint)
    }

    @Test
    fun lostGpsWhileWalkingEntersIndoor() {
        val planner = LandmarkPlanner()
        val hint = planner.observe(
            extract(OcrBlock("电梯", 40, 40, 100, 80)),
            goal(floor = "4"),
            gpsRemaining = 300,
            gpsArrive = false,
            world = indoorWorld(),
        )
        assertNotNull(hint)
        assertEquals(LandmarkStage.SEEK_EXIT, planner.stage)
        assertEquals("找出口出门", hint!!.text)
    }

    @Test
    fun farIndoorIgnoresDestFloorSeeksExit() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = "3"), gpsUsable = false, remaining = 400)
        assertEquals(LandmarkStage.SEEK_EXIT, planner.stage)
        assertEquals("1", planner.targetFloor(goal(floor = "3")))
        val hint = planner.bootstrapHint(goal(floor = "3"), 400)
        assertEquals("找出口出门", hint.text)
    }

    @Test
    fun farIndoorExitThenGpsLocksOutdoor() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = "3"), gpsUsable = false, remaining = 400)
        assertEquals(LandmarkStage.SEEK_EXIT, planner.stage)
        val street = extract(OcrBlock("路口", 40, 40, 100, 80))
        planner.observe(street, goal(floor = "3"), 400, false, indoorWorld())
        val hint = planner.observe(
            street,
            goal(floor = "3"),
            400,
            false,
            LandmarkWorld(gpsUsable = true, gpsAccuracyM = 12f),
        )
        assertEquals(LandmarkStage.OUTDOOR, planner.stage)
        assertNull(hint)
    }

    @Test
    fun nearDestUsesPoiFloor() {
        val planner = LandmarkPlanner()
        planner.start(goal(floor = "3"), gpsUsable = true, remaining = 400)
        assertEquals(LandmarkStage.OUTDOOR, planner.stage)
        planner.observe(extract(), goal(floor = "3"), gpsRemaining = 60, gpsArrive = false)
        assertEquals(LandmarkStage.LOCATE_FLOOR, planner.stage)
        assertEquals("3", planner.targetFloor(goal(floor = "3")))
    }

    @Test
    fun storefrontOcrArrives() {
        val planner = LandmarkPlanner()
        planner.enterIndoor(goal(floor = ""))
        assertEquals(LandmarkStage.SEEK_STORE, planner.stage)
        val sign = extract(OcrBlock("巴奴毛肚火锅", 50, 40, 240, 100))
        planner.observe(sign, goal(floor = ""), 18, false)
        val arrived = planner.observe(sign, goal(floor = ""), 18, false)!!
        assertEquals(LandmarkStage.ARRIVED, planner.stage)
        assertEquals("arrive", arrived.turn)
        assertEquals(LandmarkPlanner.MODE_ARRIVE, arrived.mode)
    }

    @Test
    fun outdoorGpsArriveWithoutFloorStillArrives() {
        val planner = LandmarkPlanner()
        val hint = planner.observe(extract(), goal(floor = ""), gpsRemaining = 12, gpsArrive = true)!!
        assertEquals(LandmarkStage.ARRIVED, planner.stage)
        assertEquals("arrive", hint.turn)
    }

    @Test
    fun indoorGpsArriveDoesNotSkipElevatorHunt() {
        val planner = LandmarkPlanner()
        planner.observe(extract(), goal(floor = "4"), gpsRemaining = 15, gpsArrive = true)
        assertEquals(LandmarkStage.LOCATE_FLOOR, planner.stage)
        assertFalse(planner.stage == LandmarkStage.ARRIVED)
    }

    @Test
    fun bboxOnRightYieldsPositiveHeading() {
        val pose = LandmarkSignage.poseOf(
            OcrBlock("电梯", left = 240, top = 40, right = 300, bottom = 90),
            width = 320,
            height = 180,
        )
        assertTrue(pose.first > 10f)
    }

    @Test
    fun goalOfReadsStoreFloor() {
        val store = Store(
            id = "s1",
            name = "巴奴毛肚火锅",
            shortName = "巴奴",
            category = "火锅",
            rating = 4.5,
            reviewCount = 1,
            avgPrice = 90,
            distanceMeters = 0,
            openNow = true,
            hours = "",
            phone = "",
            address = "望京",
            waitTables = 0,
            waitMinutes = 0,
            tags = emptyList(),
            deals = emptyList(),
            signatures = emptyList(),
            suitable = emptyList(),
            hasPrivateRoom = false,
            answers = emptyMap(),
            floor = "4F",
        )
        assertEquals("4", LandmarkPlanner.goalOf(store).floor)
    }

    @Test
    fun shouldEnterVisualAtEightyMeters() {
        assertTrue(LandmarkPlanner.shouldEnterVisual(80, null))
        assertFalse(LandmarkPlanner.shouldEnterVisual(200, extract(OcrBlock("电梯", 0, 0, 40, 20))))
        assertTrue(LandmarkPlanner.shouldEnterVisual(100, extract(OcrBlock("2F 电梯", 0, 0, 80, 30))))
    }

    @Test
    fun gpsLooksIndoorWithoutFix() {
        assertTrue(LandmarkPlanner.gpsLooksIndoor(12f, hasFix = false))
        assertTrue(LandmarkPlanner.gpsLooksIndoor(80f, hasFix = true))
        assertTrue(LandmarkPlanner.gpsLooksIndoor(40f, hasFix = true))
        assertFalse(LandmarkPlanner.gpsLooksIndoor(18f, hasFix = true))
    }

    private fun goal(floor: String): LandmarkGoal {
        return LandmarkGoal(storeName = "巴奴毛肚火锅", shortName = "巴奴", floor = floor)
    }

    private fun indoorWorld(): LandmarkWorld {
        return LandmarkWorld(gpsUsable = false, gpsAccuracyM = 80f)
    }

    private fun extract(vararg blocks: OcrBlock): LocalExtract {
        return LocalExtract(
            quality = QualityReport(ok = true),
            ocr = blocks.toList(),
            width = 320,
            height = 180,
        )
    }
}
