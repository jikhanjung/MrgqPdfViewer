package com.mrgq.pdfviewer.notes

import kotlin.math.hypot

/** 펜 획 계산 — 줄이기 · 지우개 맞히기 · 테두리. 좌표는 x, y, x, y … (pt). JVM 단위 테스트 대상 */
object NoteGeometry {

    /**
     * Douglas–Peucker — [tolerance] 보다 덜 벗어나는 중간 점을 뺀다. 끝점은 늘 남긴다.
     * 손가락 · 펜 이벤트는 1 ~ 2px 마다 오므로 그대로 두면 획 하나에 수백 점이다
     */
    fun simplify(points: List<Float>, tolerance: Float): List<Float> {
        val n = points.size / 2
        if (n <= 2) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1
            var worstDist = tolerance
            for (i in a + 1 until b) {
                val d = distanceToSegment(points[2 * i], points[2 * i + 1], points[2 * a], points[2 * a + 1], points[2 * b], points[2 * b + 1])
                if (d > worstDist) {
                    worst = i
                    worstDist = d
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(a to worst)
                stack.addLast(worst to b)
            }
        }
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) {
            out += points[2 * i]
            out += points[2 * i + 1]
        }
        return out
    }

    /** 점 (px, py) 에서 선분 (ax, ay)–(bx, by) 까지 */
    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** 획이 점 (x, y) 에서 [radius] 안을 지나는가 — 지우개 (굵기의 절반만큼 넓혀 본다) */
    fun hits(ink: ScoreNote.Ink, x: Float, y: Float, radius: Float): Boolean {
        val r = radius + ink.widthPt / 2
        return ink.strokes.any { p ->
            when {
                p.size < 2 -> false
                p.size == 2 -> hypot(p[0] - x, p[1] - y) <= r
                else -> (0 until p.size / 2 - 1).any { i ->
                    distanceToSegment(x, y, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]) <= r
                }
            }
        }
    }

    /** 글자 상자 (pt) 가 점 (x, y) 에서 [radius] 안인가 — [widthPt] 는 가장 긴 줄 폭(재는 것은 그리는 쪽) */
    fun hitsText(note: ScoreNote.Text, widthPt: Float, x: Float, y: Float, radius: Float): Boolean =
        x >= note.x - radius && x <= note.x + widthPt + radius &&
            y >= note.y - radius && y <= note.y + TextLayout.height(note) + radius

    /** 위 · 아래 끝 (pt) — 보표 소속을 정할 때 */
    fun verticalExtent(points: List<Float>): Pair<Float, Float>? {
        if (points.size < 2) return null
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in 1 until points.size step 2) {
            top = minOf(top, points[i])
            bottom = maxOf(bottom, points[i])
        }
        return top to bottom
    }
}
