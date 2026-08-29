package com.glass.dining.shared.link

internal object HudLayoutCompiler {
    fun lower(root: HudBlock, seq: Long): HudDrawScene? {
        val packer = Packer()
        val used = packer.place(
            node = root,
            left = HudDraw.SAFE_LEFT,
            top = HudDraw.SAFE_TOP,
            width = HudDraw.SAFE_RIGHT - HudDraw.SAFE_LEFT,
            bottom = HudDraw.SAFE_BOTTOM,
        )
        if (used <= 0f || packer.ops.isEmpty()) return null
        packer.ensureRule()
        val trimmed = trimToBudget(packer.ops)
        if (trimmed.none { it.type != "text" }) return null
        val scene = HudDrawScene(seq = seq, ops = trimmed)
        return HudDraw.parse(HudDraw.encode(scene))
    }

    private fun trimToBudget(ops: List<HudDrawOp>): List<HudDrawOp> {
        var kept = ops.take(HudDraw.MAX_OPS)
        while (kept.size > 2 && HudDraw.encode(HudDrawScene(0L, kept)).length > HudDraw.MAX_JSON) {
            kept = kept.dropLast(1)
        }
        return kept
    }

    private class Packer {
        val ops = ArrayList<HudDrawOp>(HudDraw.MAX_OPS)

        fun place(node: HudBlock, left: Float, top: Float, width: Float, bottom: Float): Float {
            if (top >= bottom || ops.size >= HudDraw.MAX_OPS) return 0f
            return when (node) {
                is HudBlock.Col -> placeCol(node, left, top, width, bottom)
                is HudBlock.Row -> placeRow(node, left, top, width, bottom)
                is HudBlock.Copy -> placeCopy(node, left, top, width, bottom)
                HudBlock.Rule -> placeRule(left, top, width, bottom)
                is HudBlock.Path -> placePath(node, top, bottom)
                is HudBlock.Circle -> placeCircle(node, left, top, width, bottom)
                is HudBlock.Rect -> placeRect(node, left, top, width, bottom)
            }
        }

        private fun placeCol(node: HudBlock.Col, left: Float, top: Float, width: Float, bottom: Float): Float {
            var y = top
            var used = 0f
            node.children.forEachIndexed { index, child ->
                if (index > 0) y += node.gap
                if (y >= bottom) return used
                val h = place(child, left, y, width, bottom)
                if (h <= 0f) {
                    y -= node.gap
                    return@forEachIndexed
                }
                y += h
                used = y - top
            }
            return used
        }

        private fun placeRow(node: HudBlock.Row, left: Float, top: Float, width: Float, bottom: Float): Float {
            val kids = node.children
            if (kids.isEmpty()) return 0f
            if (kids.size == 1) return place(kids[0], left, top, width, bottom)
            if (kids.size == 2 && kids[1] is HudBlock.Copy) {
                return placePair(kids[0], kids[1] as HudBlock.Copy, left, top, width, bottom)
            }
            var x = left
            var height = 0f
            val slot = ((width - HudLayout.ROW_GAP * (kids.size - 1)) / kids.size).coerceAtLeast(24f)
            kids.forEach { child ->
                height = maxOf(height, place(child, x, top, slot, bottom))
                x += slot + HudLayout.ROW_GAP
            }
            return height
        }

        private fun placePair(
            leftNode: HudBlock,
            rightCopy: HudBlock.Copy,
            left: Float,
            top: Float,
            width: Float,
            bottom: Float,
        ): Float {
            val size = HudType.size(rightCopy.role)
            val rightText = HudType.ellipsize(rightCopy.text, width * 0.42f, size)
            val rightW = HudType.measure(rightText, size)
            val gap = HudLayout.ROW_GAP
            val leftW = (width - rightW - gap).coerceAtLeast(size)
            val leftH = place(leftNode, left, top, leftW, bottom)
            if (!pushText(rightText, rightCopy.role, left + width, top, "r")) {
                return leftH
            }
            return maxOf(leftH, HudType.lineBox(size))
        }

        private fun placeCopy(node: HudBlock.Copy, left: Float, top: Float, width: Float, bottom: Float): Float {
            val size = HudType.size(node.role)
            val lines = HudType.wrap(node.text, width, size, HudType.maxLines(node.role))
            var y = top
            var used = 0f
            for (line in lines) {
                val box = HudType.lineBox(size)
                if (y + box > bottom + 0.5f) break
                if (!pushText(line, node.role, left, y, "l")) break
                y += box
                used += box
            }
            return used
        }

        private fun placeRule(left: Float, top: Float, width: Float, bottom: Float): Float {
            val y = (top + 5f).coerceAtMost(bottom)
            if (y > bottom) return 0f
            val d = "M${left.toInt()} ${y.toInt()} H${(left + width).toInt()}"
            if (HudDraw.parsePath(d).isEmpty()) return 0f
            ops.add(HudDrawOp(type = "path", d = d, stroke = 1.6f, gray = 0.42f))
            return 10f
        }

        private fun placePath(node: HudBlock.Path, top: Float, bottom: Float): Float {
            val segs = HudDraw.parsePath(node.d)
            if (segs.isEmpty()) return 0f
            ops.add(HudDrawOp(type = "path", d = node.d, stroke = 2f, gray = 1f, kind = node.kind))
            val boxBottom = pathBottom(segs).coerceIn(top, bottom)
            return (boxBottom - top + 8f).coerceAtLeast(24f)
        }

        private fun placeCircle(
            node: HudBlock.Circle,
            left: Float,
            top: Float,
            width: Float,
            bottom: Float,
        ): Float {
            val r = node.radius.coerceAtMost(width / 2f)
            val cy = (top + r).coerceAtMost(bottom - r)
            val cx = left + width / 2f
            ops.add(HudDrawOp(type = "circle", gray = 1f, x = cx, y = cy, radius = r, kind = node.kind, stroke = 2f))
            return (cy + r - top + 8f).coerceAtLeast(r * 2f)
        }

        private fun placeRect(
            node: HudBlock.Rect,
            left: Float,
            top: Float,
            width: Float,
            bottom: Float,
        ): Float {
            val rw = node.boxW.coerceAtMost(width)
            val rh = node.boxH.coerceAtMost((bottom - top).coerceAtLeast(8f))
            ops.add(
                HudDrawOp(type = "rect", gray = 0.85f, x = left, y = top, boxW = rw, boxH = rh, kind = node.kind, stroke = 2f),
            )
            return rh + 8f
        }

        private fun pushText(text: String, role: String, x: Float, top: Float, align: String): Boolean {
            if (ops.size >= HudDraw.MAX_OPS || text.isBlank()) return false
            val size = HudType.size(role)
            val clipped = text.take(HudDraw.MAX_TEXT)
            ops.add(
                HudDrawOp(
                    type = "text",
                    gray = HudType.gray(role),
                    x = x,
                    y = HudType.baseline(top, size),
                    size = size,
                    align = align,
                    text = clipped,
                ),
            )
            return true
        }

        fun ensureRule() {
            if (ops.any { it.type != "text" }) return
            val first = ops.firstOrNull { it.type == "text" } ?: return
            val y = (first.y - first.size - 8f).coerceAtLeast(HudDraw.SAFE_TOP)
            val d = "M${HudDraw.SAFE_LEFT.toInt()} ${y.toInt()} H${HudDraw.SAFE_RIGHT.toInt()}"
            ops.add(0, HudDrawOp(type = "path", d = d, stroke = 1.6f, gray = 0.42f))
        }
    }

    private fun pathBottom(segs: List<HudPathSeg>): Float {
        var maxY = HudDraw.SAFE_TOP
        segs.forEach { seg ->
            maxY = maxOf(
                maxY,
                when (seg) {
                    is HudPathSeg.Move -> seg.y
                    is HudPathSeg.Line -> seg.y
                    is HudPathSeg.Cubic -> maxOf(seg.y, seg.y1, seg.y2)
                    is HudPathSeg.Quad -> maxOf(seg.y, seg.y1)
                    HudPathSeg.Close -> maxY
                },
            )
        }
        return maxY
    }
}
