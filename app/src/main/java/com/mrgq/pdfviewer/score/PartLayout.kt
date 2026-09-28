package com.mrgq.pdfviewer.score

import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.database.entity.ScoreStaff
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 조각 안 보표 하나 — [index] 는 총보의 보표 순번(파트 번호 표시), 위 · 아래 오선은 원본 좌표 */
data class StaffMark(val index: Int, val top: Float, val bottom: Float)

/**
 * 파트보 한 조각 — 원본 쪽 [srcPage] 의 사각형(보표 한 줄)을 가상 쪽 [dstPage] 의 [dstTop] 에 그대로(1:1) 옮긴다.
 * 좌표는 모두 **PDF 포인트, 쪽 좌상단 원점, 위→아래** (ScoreMeasure · ScoreStaff 와 같다). 가로 위치는 원본 그대로다.
 *
 * @param staffMarks 조각 안 보표들 (위부터, 원본 좌표) — 왼쪽 여백의 번호를 보표 높이에 맞춘다
 * @param firstMeasure 이 조각의 첫 마디 번호 — 왼쪽 여백에 적는다 (총보는 보통 맨 위 보표에만 마디 번호가 있다)
 * @param clips 실제로 잘라 낼 모양 = 사각형들의 합 (원본 좌표). 기본 띠 + 소속에 따라 넓힌 부분([PartClip]).
 *   [srcTop] ~ [srcBottom] 은 이 모두를 덮는 범위 — 가상 쪽에 놓을 높이
 */
data class PartStrip(
    val srcPage: Int,
    val srcSystem: Int,
    val srcLeft: Float,
    val srcTop: Float,
    val srcRight: Float,
    val srcBottom: Float,
    val dstPage: Int,
    val dstTop: Float,
    val firstMeasure: Int?,
    val staffMarks: List<StaffMark> = emptyList(),
    val clips: List<ClipRect> = listOf(ClipRect(srcLeft, srcTop, srcRight, srcBottom)),
) {
    val height: Float get() = srcBottom - srcTop
    val dstBottom: Float get() = dstTop + height
}

/**
 * 파트보 보기의 배치 (P07 §2.2) — 총보의 시스템마다 고른 파트의 보표 띠를 잘라 **세로 A4(원본 쪽 크기)** 가상 쪽에 위에서부터 쌓는다.
 * 크기는 바꾸지 않는다(1:1) — 원본과 같은 선 굵기 · 글자 크기. 가상 쪽을 PDF 로 만들면(PartPdfBuilder) 뷰어의 한 쪽 / 두 쪽 모드 ·
 * 넘김 애니메이션을 그대로 쓴다 (P07 결정: 세로 A4).
 *
 * 띠의 위아래는 **이웃 보표와의 가운데까지** — 덧줄 · 셈여림 · 가사를 살리고 옆 파트는 들이지 않는다. 시스템 맨 위 · 맨 아래 보표는
 * 반대쪽 이웃과의 간격을 그대로 쓴다. 쪽의 경로 박스를 주면 가운데선을 걸친 슬러 · 빔 · 덧줄 음을 소속에 따라 넓혀 자른다([PartClip]). 왼쪽은 시스템 첫 마디선에서 [LEFT_PAD] 더 (괄호 · 음자리표 앞), 오른쪽은 끝 마디선에서 [RIGHT_PAD] 더.
 *
 * **여러 파트** (사용자 요청 2026-09-28): 고른 보표 중 **이웃한 것은 한 조각으로 이어** 자른다(사이 슬러 · 빔이 끊기지 않게). 떨어진 것은
 * 시스템마다 조각 여럿을 붙여 놓고, 한 시스템의 조각들은 쪽을 넘길 때 갈라지지 않는다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
data class PartLayout(
    /** 고른 보표 순번 (오름차순) */
    val staves: List<Int>,
    val pageWidth: Float,
    val pageHeight: Float,
    val pageCount: Int,
    val strips: List<PartStrip>,
) {
    /** 가상 쪽 [dstPages] 에 담긴 원본 쪽 범위 (0부터). 없으면 null — 화면의 "총보 n~m쪽" 표시 */
    fun sourcePages(dstPages: IntRange): IntRange? {
        val pages = strips.filter { it.dstPage in dstPages }.map { it.srcPage }
        return if (pages.isEmpty()) null else pages.min()..pages.max()
    }

    /**
     * 원본 마디를 **파트 PDF 좌표**로 옮긴다 (P07 3단계) — 쪽은 가상 쪽, 세로는 그 조각(줄) 범위, 가로는 그대로, 시스템 번호는 가상 쪽 안의
     * 줄 순서. 마디 번호 · 박자표는 그대로라 마디 박스 · 시작 마디 커서(←→ 마디, ↑↓ 줄) · 현재 마디 · 자동 넘김이 파트 화면에서 그대로 돈다.
     * 조각이 없는 시스템의 마디는 뺀다 (보표 수가 같은 악보만 파트 보기를 쓰므로 생기지 않는다)
     */
    fun mapMeasures(measures: List<ScoreMeasure>): List<ScoreMeasure> {
        // 한 시스템의 조각들(떨어진 파트 여럿)은 한 줄 — 마디 박스는 첫 조각 위에서 끝 조각 아래까지
        val groups = strips.groupBy { it.srcPage to it.srcSystem }
        val lineOf = groups.entries.groupBy { it.value.first().dstPage }.values.flatMap { onPage ->
            onPage.sortedBy { it.value.first().dstTop }.mapIndexed { line, group -> group.key to line }
        }.toMap()
        return measures.mapNotNull { m ->
            val key = m.pageIndex to m.systemIndex
            val group = groups[key] ?: return@mapNotNull null
            m.copy(
                pageIndex = group.first().dstPage,
                systemIndex = lineOf.getValue(key),
                topPt = group.minOf { it.dstTop },
                bottomPt = group.maxOf { it.dstBottom },
                pageWidthPt = pageWidth,
                pageHeightPt = pageHeight,
            )
        }
    }

    /**
     * 합주 (P07 4단계) — 지휘자가 보낸 **원본 쪽**(0부터)을 이 파트 PDF 의 쪽으로. 그 원본 쪽의 첫 줄이 놓인 가상 쪽,
     * 줄이 없는 쪽(표지 등)이면 그 뒤 첫 줄의 쪽, 끝 너머면 마지막 쪽.
     */
    fun dstPageForSource(srcPage: Int): Int =
        (strips.firstOrNull { it.srcPage >= srcPage } ?: strips.last()).dstPage

    /** 합주 (P07 4단계) — 파트 PDF 의 쪽 [dstPage] 에 해당하는 **원본 쪽**(0부터): 그 쪽 첫 줄의 원본 쪽. 지휘자가 파트 보기로 볼 때 보낼 쪽 */
    fun sourcePageFor(dstPage: Int): Int =
        (strips.firstOrNull { it.dstPage == dstPage } ?: strips.lastOrNull { it.dstPage < dstPage } ?: strips.first()).srcPage

    /** 조각 [index] 에 원본 쪽 번호를 적을까 — 원본 쪽이 바뀌는 첫 조각과 가상 쪽마다 첫 조각 */
    fun showsSourcePage(index: Int): Boolean {
        val strip = strips[index]
        val previous = strips.getOrNull(index - 1) ?: return true
        return previous.srcPage != strip.srcPage || previous.dstPage != strip.dstPage
    }

    companion object {
        const val TOP_MARGIN = 36f
        /**
         * 두 쪽 모드에서 **왼쪽에 오는 쪽**(짝수 순번 — 뷰어는 0-1, 2-3 … 으로 짝짓는다)의 위 여백. 메트로놈 박 표시(화면 왼쪽 위, 여백 8dp +
         * 높이 28dp)가 첫 줄을 가리지 않게 넉넉히 (1080p 에서 쪽 높이를 맞추면 약 80px). 오른쪽 쪽은 [TOP_MARGIN] — 좌우 줄 높이는 덧줄로
         * 어차피 다르니 맞추지 않는다 (사용자 판단). 한 쪽 모드는 쪽이 화면 가운데라 박 표시와 겹치지 않는다
         */
        const val LEFT_PAGE_TOP_MARGIN = 64f

        /** 가상 쪽 [page] 의 위 여백 */
        fun topMargin(page: Int): Float = if (page % 2 == 0) LEFT_PAGE_TOP_MARGIN else TOP_MARGIN
        const val BOTTOM_MARGIN = 28f
        const val STRIP_GAP = 4f
        /** 한 시스템 안 떨어진 파트 조각 사이 — 시스템 사이([STRIP_GAP])보다 좁혀 한 줄로 보이게 */
        const val PART_GAP = 1f
        const val LEFT_PAD = 8f
        const val RIGHT_PAD = 4f

        /**
         * @param staves 이 파일의 보표 전부 (ScoreParts 가 [ScoreParts.Result.Parts] 를 낸 것 — 시스템마다 보표 수가 같다)
         * @param measures 이 파일의 마디 전부 (시스템 가로 범위와 첫 마디 번호)
         * @return 고른 보표가 있는 시스템이 없으면 null
         */
        fun build(
            staves: List<ScoreStaff>,
            measures: List<ScoreMeasure>,
            selected: Set<Int>,
            pageBoxes: Map<Int, List<PathBox>> = emptyMap(),
        ): PartLayout? {
            val measuresBySystem = measures.groupBy { it.pageIndex to it.systemIndex }
            val first = measures.firstOrNull() ?: return null
            val pageWidth = first.pageWidthPt
            val pageHeight = first.pageHeightPt

            val strips = ArrayList<PartStrip>()
            var dstPage = 0
            var cursor = topMargin(0)
            val systems = staves.groupBy { it.pageIndex to it.systemIndex }.toSortedMap(compareBy({ it.first }, { it.second }))
            for ((key, systemStaves) in systems) {
                val bands = systemStaves.sortedBy { it.staffIndex }
                val chosen = bands.indices.filter { bands[it].staffIndex in selected }
                if (chosen.isEmpty()) continue
                val systemMeasures = measuresBySystem[key].orEmpty()
                if (systemMeasures.isEmpty()) continue
                val srcPageHeight = systemMeasures.first().pageHeightPt
                val srcPageWidth = systemMeasures.first().pageWidthPt
                val left = (systemMeasures.minOf { it.leftPt } - LEFT_PAD).coerceAtLeast(0f)
                val right = (systemMeasures.maxOf { it.rightPt } + RIGHT_PAD).coerceAtMost(srcPageWidth)
                val bandPairs = bands.map { it.topPt to it.bottomPt }

                // 이웃한 보표끼리 묶는다 — 묶음 하나가 조각 하나
                val runs = ArrayList<IntRange>()
                for (k in chosen) {
                    val last = runs.lastOrNull()
                    if (last != null && last.last == k - 1) runs[runs.size - 1] = last.first..k else runs += k..k
                }
                val pieces = runs.map { run ->
                    val firstBand = bands[run.first]
                    val lastBand = bands[run.last]
                    val above = if (run.first > 0) (firstBand.topPt - bands[run.first - 1].bottomPt) / 2 else null
                    val below = if (run.last < bands.size - 1) (bands[run.last + 1].topPt - lastBand.bottomPt) / 2 else null
                    val fallback = (firstBand.bottomPt - firstBand.topPt) * 1.5f // 보표 하나뿐인 시스템 (ScoreParts 가 막지만 안전하게)
                    val top = (firstBand.topPt - (above ?: below ?: fallback)).coerceAtLeast(0f)
                    val bottom = (lastBand.bottomPt + (below ?: above ?: fallback)).coerceAtMost(srcPageHeight)
                    val extras = pageBoxes[key.first]?.let { boxes ->
                        PartClip.extras(boxes, srcPageHeight, bandPairs, run.first, run.last, top, bottom, left, right)
                    }.orEmpty()
                    Triple(run, listOf(ClipRect(left, top, right, bottom)) + extras, run.map { StaffMark(bands[it].staffIndex, bands[it].topPt, bands[it].bottomPt) })
                }
                val heights = pieces.map { (_, clips, _) -> clips.maxOf { it.bottom } - clips.minOf { it.top } }
                val groupHeight = heights.sum() + PART_GAP * (pieces.size - 1)

                // 한 시스템의 조각들은 같은 쪽에
                if (cursor + groupHeight > pageHeight - BOTTOM_MARGIN && cursor > topMargin(dstPage)) {
                    dstPage++
                    cursor = topMargin(dstPage)
                }
                for ((i, piece) in pieces.withIndex()) {
                    val (_, clips, marks) = piece
                    strips += PartStrip(
                        srcPage = key.first,
                        srcSystem = key.second,
                        srcLeft = left,
                        srcTop = clips.minOf { it.top },
                        srcRight = right,
                        srcBottom = clips.maxOf { it.bottom },
                        dstPage = dstPage,
                        dstTop = cursor,
                        // 마디 번호는 시스템의 첫 조각에만
                        firstMeasure = if (i == 0) systemMeasures.minOf { it.measureNumber } else null,
                        staffMarks = marks,
                        clips = clips,
                    )
                    cursor += heights[i] + if (i < pieces.size - 1) PART_GAP else STRIP_GAP
                }
            }
            if (strips.isEmpty()) return null
            return PartLayout(selected.sorted(), pageWidth, pageHeight, dstPage + 1, strips)
        }

        /**
         * 파트 PDF 옆에 저장하는 배치 (`.json`) — 넓히기에 쪽 경로를 다시 읽어야 해서, 한 번 만든 배치는 저장해 두고 화면의
         * "총보 n쪽" 표시에 쓴다. Gson 트리로 직접 쓴다 (리플렉션 없이 — release 는 R8 로 줄인다)
         */
        fun encode(layout: PartLayout): String = JsonObject().apply {
            add("staves", JsonArray().apply { layout.staves.forEach { add(it) } })
            addProperty("w", layout.pageWidth)
            addProperty("h", layout.pageHeight)
            addProperty("pages", layout.pageCount)
            add("strips", JsonArray().apply {
                layout.strips.forEach { s ->
                    add(JsonObject().apply {
                        addProperty("sp", s.srcPage); addProperty("ss", s.srcSystem)
                        addProperty("l", s.srcLeft); addProperty("t", s.srcTop); addProperty("r", s.srcRight); addProperty("b", s.srcBottom)
                        addProperty("dp", s.dstPage); addProperty("dt", s.dstTop)
                        s.firstMeasure?.let { addProperty("m", it) }
                        add("sm", JsonArray().apply {
                            s.staffMarks.forEach { m -> add(JsonArray().apply { add(m.index); add(m.top); add(m.bottom) }) }
                        })
                        add("c", JsonArray().apply {
                            s.clips.forEach { c -> add(JsonArray().apply { add(c.left); add(c.top); add(c.right); add(c.bottom) }) }
                        })
                    })
                }
            })
        }.toString()

        /** [encode] 의 반대. 깨졌으면 null (다시 만든다) */
        fun decode(json: String): PartLayout? = try {
            val o = JsonParser.parseString(json).asJsonObject
            PartLayout(
                staves = o["staves"].asJsonArray.map { it.asInt },
                pageWidth = o["w"].asFloat,
                pageHeight = o["h"].asFloat,
                pageCount = o["pages"].asInt,
                strips = o["strips"].asJsonArray.map { e ->
                    val s = e.asJsonObject
                    PartStrip(
                        srcPage = s["sp"].asInt, srcSystem = s["ss"].asInt,
                        srcLeft = s["l"].asFloat, srcTop = s["t"].asFloat, srcRight = s["r"].asFloat, srcBottom = s["b"].asFloat,
                        dstPage = s["dp"].asInt, dstTop = s["dt"].asFloat,
                        firstMeasure = s["m"]?.asInt,
                        staffMarks = s["sm"].asJsonArray.map { m ->
                            val a = m.asJsonArray
                            StaffMark(a[0].asInt, a[1].asFloat, a[2].asFloat)
                        },
                        clips = s["c"].asJsonArray.map { c ->
                            val a = c.asJsonArray
                            ClipRect(a[0].asFloat, a[1].asFloat, a[2].asFloat, a[3].asFloat)
                        },
                    )
                },
            )
        } catch (e: RuntimeException) {
            null
        }
    }
}
