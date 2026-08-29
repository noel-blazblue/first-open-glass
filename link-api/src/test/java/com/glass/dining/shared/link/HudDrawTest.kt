package com.glass.dining.shared.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudDrawTest {
    @Test
    fun parseKeepsPathAndText() {
        val raw = """{"seq":7,"ops":[{"t":"path","d":"M240 80 L240 200","w":3,"g":1},{"t":"text","x":240,"y":520,"s":22,"g":0.8,"a":"c","v":"沿走廊 20 米"}]}"""
        val scene = HudDraw.parse(raw)!!
        assertEquals(7, scene.seq)
        assertEquals(2, scene.ops.size)
        assertEquals("path", scene.ops[0].type)
        assertEquals("text", scene.ops[1].type)
        assertEquals("沿走廊 20 米", scene.ops[1].text)
        val segs = HudDraw.parsePath(scene.ops[0].d)
        assertEquals(2, segs.size)
        assertTrue(segs[0] is HudPathSeg.Move)
        assertTrue(segs[1] is HudPathSeg.Line)
    }

    @Test
    fun rejectEmptyOrUnknownOps() {
        assertNull(HudDraw.parse("""{"seq":1,"ops":[]}"""))
        assertNull(HudDraw.parse("""{"seq":1,"ops":[{"t":"circle","d":"M0 0"}]}"""))
        assertNull(HudDraw.parse("""{"seq":1,"ops":[{"t":"path","d":"??"}]}"""))
    }

    @Test
    fun dropInvalidKeepValid() {
        val raw = """{"ops":[{"t":"path","d":"bad"},{"t":"text","x":10,"y":20,"v":"药店"}]}"""
        val scene = HudDraw.parse(raw)!!
        assertEquals(1, scene.ops.size)
        assertEquals("药店", scene.ops[0].text)
    }

    @Test
    fun encodeThenParseRoundTrip() {
        val scene = HudDraw.parse(
            """{"seq":3,"ops":[{"t":"path","d":"M10 10 L20 20 Z","w":2,"g":0.5}]}""",
        )!!
        val again = HudDraw.parse(HudDraw.encode(scene))!!
        assertEquals(scene.seq, again.seq)
        assertEquals(scene.ops[0].d, again.ops[0].d)
    }
}
