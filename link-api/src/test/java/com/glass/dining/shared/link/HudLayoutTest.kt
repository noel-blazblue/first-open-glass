package com.glass.dining.shared.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayoutTest {
    @Test
    fun compileListIsLeftAlignedAndMonotonic() {
        val scene = HudLayout.compile(
            JSONObject(
                """{"layout":[
                  {"t":"text","role":"kicker","v":"附近 3 家"},
                  {"t":"text","role":"title","v":"今晚可以去"},
                  {"t":"rule"},
                  {"t":"row","left":"松月面馆","right":"420米"},
                  {"t":"text","role":"meta","v":"4.6 · 人均 64"},
                  {"t":"row","left":"北口火锅","right":"680米"}
                ]}""",
            ),
        )!!
        val texts = scene.ops.filter { it.type == "text" }
        assertTrue(texts.size >= 5)
        assertTrue(texts.all { it.align == "l" || it.align == "r" })
        assertTrue(texts.first { it.align == "l" }.x <= HudDraw.SAFE_LEFT + 1f)
        val lefts = texts.filter { it.align == "l" }
        for (i in 1 until lefts.size) {
            assertTrue(lefts[i].y >= lefts[i - 1].y)
        }
        val rights = texts.filter { it.align == "r" }
        assertTrue(rights.isNotEmpty())
        assertTrue(rights.all { it.x >= 300f })
        assertTrue(scene.ops.any { it.type == "path" })
        assertTrue(HudDraw.encode(scene).length <= HudDraw.MAX_JSON)
    }

    @Test
    fun textOnlyLayoutGetsRule() {
        val scene = HudLayout.compile(
            JSONObject("""{"layout":[{"t":"text","role":"title","v":"药店"}]}"""),
        )!!
        assertTrue(scene.ops.any { it.type == "path" })
        assertEquals("药店", scene.ops.first { it.type == "text" }.text)
    }

    @Test
    fun nestedColInRow() {
        val scene = HudLayout.compile(
            JSONObject(
                """{"layout":{"t":"col","ch":[
                  {"t":"row","ch":[
                    {"t":"col","ch":[
                      {"t":"text","role":"body","v":"松月面馆"},
                      {"t":"text","role":"meta","v":"4.6 · 人均 64"}
                    ]},
                    {"t":"text","role":"meta","v":"420米"}
                  ]}
                ]}}""",
            ),
        )!!
        val names = scene.ops.filter { it.type == "text" }.map { it.text }
        assertTrue(names.any { it.contains("松月") })
        assertTrue(names.any { it.contains("420") })
    }

    @Test
    fun wrapLongTitle() {
        val scene = HudLayout.compile(
            JSONObject(
                """{"layout":[{"t":"text","role":"title","v":"这是一段特别特别长的标题需要折行否则会贴出安全区"}]}""",
            ),
        )!!
        val titles = scene.ops.filter { it.type == "text" && it.size >= 24f }
        assertTrue(titles.size >= 2)
        assertTrue(titles.all { it.text.length <= HudDraw.MAX_TEXT })
    }

    @Test
    fun missingLayoutReturnsNull() {
        assertNull(HudLayout.compile(JSONObject("""{"ops":[{"t":"path","d":"M80 80 L200 80"}]}""")))
    }

    @Test
    fun compileThenWireParse() {
        val scene = HudLayout.compile(
            JSONObject("""{"seq":9,"layout":[{"t":"text","v":"北口"},{"t":"circle","r":20}]}"""),
        )
        assertNotNull(scene)
        assertEquals(9, scene!!.seq)
        assertTrue(scene.ops.any { it.type == "circle" })
    }
}
