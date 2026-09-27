package com.mrgq.pdfviewer.metronome

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mrgq.pdfviewer.database.entity.ScoreMeasure

/**
 * 구간별 빠르기 (#057). 악보 박자가 바뀌는 마디마다 구간이 하나씩 생기고, 구간마다 빠르기와 세는 단위를 따로 정한다.
 *
 * - **박자는 악보가 정한다** — 구간 설정에 박자는 없다. 사용자가 박자를 다시 입력하면 악보와 어긋날 수 있다
 * - **빠르기는 앞 구간과의 관계로** 정한다 ([TempoRelation]). 악보에 적히는 방식(♪ = ♪)과 같고, 첫 구간 템포만 바꿔도
 *   "직접 입력"이 아닌 뒤 구간이 따라 바뀐다
 * - 첫 구간의 빠르기 · 세는 단위는 파일의 메트로놈 설정 그대로다 (대화상자 첫 화면). 여기서는 둘째 구간부터 다룬다
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
enum class TempoRelation(val wire: String) {
    /** 음표 길이 그대로 — 앞 구간의 같은 음표가 같은 길이 (4/4 ♩=96 → 6/8 ♪=192). 기본 (사용자 결정) */
    NOTE("note"),

    /** 박 길이 그대로 — 앞 구간 한 박 = 이 구간 한 박 (4/4 ♩=96 → 6/8 ♪=96) */
    BEAT("beat"),

    /** 새 템포를 직접 */
    SET("set");

    companion object {
        fun fromWire(value: String?): TempoRelation = values().firstOrNull { it.wire == value } ?: NOTE
    }
}

/** 둘째 구간부터 사용자가 정한 것 — 저장(user_preferences.metronomeSections) · 합주 방송에 쓴다. [bpm] 은 [TempoRelation.SET] 일 때만 */
data class TempoSectionSetting(
    val startMeasure: Int,
    val relation: TempoRelation = TempoRelation.NOTE,
    val bpm: Int = MetronomeClock.DEFAULT_BPM,
    val dotted: Boolean = false,
)

/** 악보 박자로 나눈 구간 하나 — [startMeasure]..[endMeasure] 가 [meter] */
data class SectionSpan(val startMeasure: Int, val endMeasure: Int, val measureCount: Int, val meter: TimeSignature)

/**
 * 설정을 입혀 풀어 쓴 구간. [bpm] 은 [dotted] 단위 기준이고 소수일 수 있다 (♩=97 → ♩.=64.67).
 * 박 간격을 계산할 때만 쓰고 화면에는 반올림해서 보인다.
 */
data class TempoSection(
    val span: SectionSpan,
    val dotted: Boolean,
    val relation: TempoRelation,
    val bpm: Double,
) {
    val startMeasure: Int get() = span.startMeasure
    val meter: TimeSignature get() = span.meter

    /** 한 박의 길이 (온음표 단위) — 6/8 은 1/8, 점음표로 세면 3/8 */
    val beatWhole: Double get() = beatWholeOf(meter, dotted)
}

object TempoSections {

    /** 구간 박 속도의 상한. 음표 길이 그대로 이어받으면 240 을 넘을 수 있어 [MetronomeClock.MAX_BPM] 보다 넉넉하다 */
    const val MAX_SECTION_BPM = 600.0

    /**
     * 악보 박자가 바뀌는 곳마다 구간. 박자를 아는 첫 마디부터 ([ScoreFollower.startableMeasures]) —
     * 비어 있으면 악보 연동을 못 하는 파일이다. 박자표가 빈 마디는 앞 마디 박자를 잇는다 ([ScoreFollower] 와 같다).
     */
    fun spans(measures: List<ScoreMeasure>): List<SectionSpan> {
        val ordered = ScoreFollower.startableMeasures(measures)
        val result = mutableListOf<SectionSpan>()
        var start = -1
        var last = -1
        var count = 0
        var meter: TimeSignature? = null
        for (m in ordered) {
            val written = m.timeSigNumerator?.let { TimeSignature.of(it, m.timeSigDenominator) } ?: meter
            if (written != meter) {
                meter?.let { result += SectionSpan(start, last, count, it) }
                start = m.measureNumber
                count = 0
                meter = written
            }
            last = m.measureNumber
            count++
        }
        meter?.let { result += SectionSpan(start, last, count, it) }
        return result
    }

    /**
     * 설정을 입힌다. 첫 구간은 [firstBpm] · [firstDotted] (파일 설정), 둘째부터는 시작 마디가 같은 [settings] —
     * 없으면 기본(음표 길이 그대로 · 분모 음표로 세기). 악보를 다시 분석해 구간이 바뀌어도 맞는 것만 쓴다.
     */
    fun resolve(
        spans: List<SectionSpan>,
        firstBpm: Int,
        firstDotted: Boolean,
        settings: List<TempoSectionSetting>,
    ): List<TempoSection> {
        val byStart = settings.associateBy { it.startMeasure }
        val result = mutableListOf<TempoSection>()
        for ((i, span) in spans.withIndex()) {
            val section = if (i == 0) {
                TempoSection(span, firstDotted, TempoRelation.SET, firstBpm.toDouble())
            } else {
                val setting = byStart[span.startMeasure] ?: TempoSectionSetting(span.startMeasure)
                val bpm = bpmFor(result.last(), span, setting.dotted, setting.relation, setting.bpm)
                TempoSection(span, setting.dotted, setting.relation, bpm)
            }
            result += section
        }
        return result
    }

    /**
     * 악보 연동에 쓸 구간 — 지휘자와 연주자가 같은 입력(악보 · 첫 구간 템포 · 설정)으로 같은 결과를 낸다.
     * 박자가 한 번도 바뀌지 않는 곡은 빈 목록: 곡 전체를 엔진 템포 하나로 센다 (v0.2.4 와 같다 — 연주 중 템포 조절도 그대로).
     */
    fun forFollowing(
        measures: List<ScoreMeasure>,
        firstBpm: Int,
        firstDotted: Boolean,
        settings: List<TempoSectionSetting>,
    ): List<TempoSection> {
        val spans = spans(measures)
        return if (spans.size <= 1) emptyList() else resolve(spans, firstBpm, firstDotted, settings)
    }

    /** [previous] 구간 다음의 [span] 을 [relation] 으로 이어받으면 몇 BPM 인가 — 대화상자의 선택지 옆에도 보인다 */
    fun bpmFor(previous: TempoSection, span: SectionSpan, dotted: Boolean, relation: TempoRelation, setBpm: Int): Double {
        val bpm = when (relation) {
            TempoRelation.NOTE -> previous.bpm * previous.beatWhole / beatWholeOf(span.meter, dotted)
            TempoRelation.BEAT -> previous.bpm
            TempoRelation.SET -> setBpm.toDouble()
        }
        return bpm.coerceIn(MetronomeClock.MIN_BPM.toDouble(), MAX_SECTION_BPM)
    }

    // ── 저장 · 방송 형식: [{"m":33,"rel":"note","bpm":120,"dotted":false}, …] ──

    fun toJson(settings: List<TempoSectionSetting>): JsonArray = JsonArray().apply {
        settings.forEach { s ->
            add(JsonObject().apply {
                addProperty("m", s.startMeasure)
                addProperty("rel", s.relation.wire)
                addProperty("bpm", s.bpm)
                addProperty("dotted", s.dotted)
            })
        }
    }

    /** 깨진 항목은 건너뛴다 — 구간 하나가 이상하다고 나머지 설정까지 버리지 않는다 */
    fun fromJson(json: JsonElement?): List<TempoSectionSetting> {
        if (json == null || !json.isJsonArray) return emptyList()
        return json.asJsonArray.mapNotNull { item ->
            try {
                val o = item.asJsonObject
                TempoSectionSetting(
                    startMeasure = o.get("m").asInt,
                    relation = TempoRelation.fromWire(o.get("rel")?.takeIf { it.isJsonPrimitive }?.asString),
                    bpm = (o.get("bpm")?.takeIf { it.isJsonPrimitive }?.asInt ?: MetronomeClock.DEFAULT_BPM)
                        .coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM),
                    dotted = o.get("dotted")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                )
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    fun encode(settings: List<TempoSectionSetting>): String? = if (settings.isEmpty()) null else toJson(settings).toString()

    fun decode(text: String?): List<TempoSectionSetting> = try {
        if (text.isNullOrBlank()) emptyList() else fromJson(JsonParser.parseString(text))
    } catch (e: RuntimeException) {
        emptyList()
    }
}

/** 한 박의 길이 (온음표 단위) */
internal fun beatWholeOf(meter: TimeSignature, dotted: Boolean): Double =
    (if (dotted && meter.isCompound) 3.0 else 1.0) / meter.denominator
