package com.mrgq.pdfviewer.score

import kotlin.math.abs

/** 그려진 경로 하나의 바운딩 박스 — PDF 사용자 공간(원점 좌하단, y 가 위로 증가). */
data class PathBox(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    /** 곡선(c/v/y)이 섞인 경로인가 — 음표 머리 후보. */
    val curved: Boolean,
) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

/**
 * 악보 한 줄(시스템). 좌표는 **위→아래** PDF 포인트, 원점은 페이지 좌상단.
 *
 * @param staffBands 보표별 (위 오선, 아래 오선) — 위 보표부터
 * @param barlines 마디 경계 x. 첫 값은 시스템 왼쪽 끝, 마지막 값은 끝 마디선
 */
data class SystemLayout(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val staffBands: List<Pair<Float, Float>>,
    val barlines: List<Float>,
) {
    val measureCount: Int get() = (barlines.size - 1).coerceAtLeast(0)
}

/** 보표 하나: (아래 오선 y, 위 오선 y) — PDF y-up. */
private typealias Staff = Pair<Float, Float>

/**
 * 벡터 악보 페이지에서 시스템·보표·마디선을 찾는다.
 *
 * `data/segment_score.py`(2026-06, devlog #037)의 `analyze()` 를 그대로 옮겼다. 파이썬 결과와의
 * 일치는 계측 테스트 `ScoreLayoutAnalyzerTest` 가 골든 데이터로 확인한다.
 *
 * ⚠️ 임계값이 **포인트 단위로 고정**돼 있다. Sibelius → Microsoft Print to PDF 로 만든 악보
 * (오선 간격 ≈5pt)에 맞춘 값이라, 크기가 다른 악보나 다른 작성기에서는 못 찾을 수 있다.
 *
 * 판정 규칙:
 *  - 오선 = 높이 < 1.5pt, 폭 > 80pt 인 곡선 없는 경로. 5개씩(폭 28pt 미만) 묶어 보표
 *  - 보표는 세로 간격 55pt 초과로 시스템을 나눈다
 *  - 마디선 = 폭 < 2pt 세로 경로 중 **양 끝이 보표 위·아래 오선에 ±1.5pt 로 붙고**, 끝에 음표
 *    머리가 없으며(있으면 기둥), 시스템의 거의 모든 보표(max(3, n-1))에서 같은 x 에 나타나는 것
 *  - 15pt 미만으로 붙은 마디선은 더 많은 보표에 나타난 쪽 하나만 남긴다 (겹세로줄)
 */
object StaffSystemDetector {

    private const val STAFF_LINE_MAX_HEIGHT = 1.5f
    private const val STAFF_LINE_MIN_WIDTH = 80f
    private const val VERTICAL_MAX_WIDTH = 2f
    private const val VERTICAL_MIN_HEIGHT = 6f
    private const val LINE_CLUSTER_TOL = 2f
    private const val STAFF_MAX_SPAN = 28f
    private const val SYSTEM_GAP = 55f
    private const val BAR_END_TOL = 1.5f
    private const val NOTEHEAD_TOL = 4.5f
    private const val BAR_CLUSTER_TOL = 3f
    private const val MIN_MEASURE_WIDTH = 15f
    private const val LEFT_EDGE_TOL = 4f

    private data class StaffLine(val cy: Float, val x0: Float, val x1: Float)
    private data class Vertical(val cx: Float, val y0: Float, val y1: Float)

    fun detect(boxes: List<PathBox>, pageHeight: Float): List<SystemLayout> {
        val staffLines = ArrayList<StaffLine>()
        val verticals = ArrayList<Vertical>()
        val heads = ArrayList<Pair<Float, Float>>()

        for (b in boxes) {
            val w = b.width
            val h = b.height
            if (b.curved) {
                // 음표 머리: 오선 간격 절반 남짓한 곡선 채움
                if (w > 4f && w < 9f && h > 3f && h < 7f) heads += (b.x0 + b.x1) / 2 to (b.y0 + b.y1) / 2
                continue
            }
            if (h < STAFF_LINE_MAX_HEIGHT && w > STAFF_LINE_MIN_WIDTH) {
                staffLines += StaffLine((b.y0 + b.y1) / 2, b.x0, b.x1)
            } else if (w < VERTICAL_MAX_WIDTH && h > VERTICAL_MIN_HEIGHT) {
                verticals += Vertical((b.x0 + b.x1) / 2, b.y0, b.y1)
            }
        }
        if (staffLines.isEmpty()) return emptyList()

        val centers = cluster(staffLines.map { it.cy }, LINE_CLUSTER_TOL)
        val staves = ArrayList<Staff>()
        var i = 0
        while (i + 5 <= centers.size) {
            val g = centers.subList(i, i + 5)
            if (g[4] - g[0] < STAFF_MAX_SPAN) {
                staves += g[0] to g[4]
                i += 5
            } else {
                i += 1
            }
        }
        if (staves.isEmpty()) return emptyList()

        staves.sortWith(compareBy<Staff> { it.first }.thenBy { it.second })
        val groups = ArrayList<List<Staff>>()
        var current = arrayListOf(staves[0])
        for (s in staves.drop(1)) {
            if (s.first - current.last().second > SYSTEM_GAP) {
                groups += current
                current = arrayListOf(s)
            } else {
                current += s
            }
        }
        groups += current
        groups.sortByDescending { it[0].first } // 페이지 위(큰 y)부터

        fun hasHeadAt(x: Float, y: Float) =
            heads.any { (hx, hy) -> abs(hx - x) < NOTEHEAD_TOL && abs(hy - y) < NOTEHEAD_TOL }

        return groups.map { system -> buildSystem(system, staffLines, verticals, pageHeight, ::hasHeadAt) }
    }

    private fun buildSystem(
        system: List<Staff>,
        staffLines: List<StaffLine>,
        verticals: List<Vertical>,
        pageHeight: Float,
        hasHeadAt: (Float, Float) -> Boolean,
    ): SystemLayout {
        val topPdf = system.last().second
        val bottomPdf = system.first().first

        val systemLines = staffLines.filter { line ->
            system.any { (st, sb) -> abs(line.cy - st) < 2f || abs(line.cy - sb) < 2f }
        }
        val xLeft = systemLines.minOf { it.x0 }
        val xRight = systemLines.maxOf { it.x1 }

        // 보표별 마디선 후보: 위·아래 오선에 정확히 붙고, 끝에 음표 머리가 없는 세로선
        val perStaff = system.map { (st, sb) ->
            verticals
                .filter { abs(it.y0 - st) <= BAR_END_TOL && abs(it.y1 - sb) <= BAR_END_TOL }
                .filterNot { hasHeadAt(it.cx, st) || hasHeadAt(it.cx, sb) }
                .map { it.cx }
                .sorted()
        }
        fun staffCount(x: Float) = perStaff.count { xs -> xs.any { abs(it - x) <= BAR_CLUSTER_TOL } }

        var bars = ArrayList<Float>()
        val allX = perStaff.flatten()
        if (allX.isNotEmpty()) {
            for (cx in cluster(allX, BAR_CLUSTER_TOL)) {
                if (staffCount(cx) >= maxOf(3, system.size - 1)) bars += round1(cx)
            }
        }
        if (bars.isNotEmpty()) {
            val merged = arrayListOf(bars[0])
            for (b in bars.drop(1)) {
                if (b - merged.last() < MIN_MEASURE_WIDTH) {
                    if (staffCount(b) > staffCount(merged.last())) merged[merged.lastIndex] = b
                } else {
                    merged += b
                }
            }
            bars = merged
        }
        // 끝 마디선이 안 잡혔으면 오선 오른쪽 끝을 경계로 쓴다 — 단, 남은 폭이 마디 하나가 될 만큼일 때만.
        // 끝세로줄(가는 선 + 굵은 선)의 굵은 선은 폭이 2pt 를 넘어 마디선 후보가 아니라서, 가는 선과 오선 끝
        // 사이 약 5pt 가 가짜 마디로 잡혔다 (실기기 몰다우.pdf 마지막 마디 "64", #049). 파이썬 원본은 4pt 기준이었다.
        if (bars.isNotEmpty() && xRight - bars.last() >= MIN_MEASURE_WIDTH) bars += round1(xRight)

        val bounds = listOf(round1(xLeft)) + bars.filter { it > xLeft + LEFT_EDGE_TOL }

        return SystemLayout(
            top = round1(pageHeight - topPdf),
            bottom = round1(pageHeight - bottomPdf),
            left = round1(xLeft),
            right = round1(xRight),
            staffBands = system.reversed().map { (st, sb) -> round1(pageHeight - sb) to round1(pageHeight - st) },
            barlines = bounds,
        )
    }

    /** 정렬 후 인접 간격이 [tol] 이하인 값끼리 묶어 평균을 낸다. */
    internal fun cluster(values: List<Float>, tol: Float): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val out = ArrayList<Float>()
        var group = arrayListOf(sorted[0])
        for (v in sorted.drop(1)) {
            if (v - group.last() <= tol) {
                group += v
            } else {
                out += group.average().toFloat()
                group = arrayListOf(v)
            }
        }
        out += group.average().toFloat()
        return out
    }

    private fun round1(v: Float): Float = Math.round(v * 10f) / 10f
}
