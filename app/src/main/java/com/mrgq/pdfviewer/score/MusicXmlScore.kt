package com.mrgq.pdfviewer.score

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MusicXML(`score-partwise`) 에서 반주에 필요한 것만 읽은 결과 (P07 5단계).
 *
 * @param parts 파트 (문서 순서 = 총보 위→아래가 보통이다). [XmlPart.staves] 는 그 파트가 차지하는 보표 수 (피아노 2)
 * @param measures 마디마다 박자 (첫 파트 기준, 앞 마디 것을 이어 쓴다) — PDF 분석과 맞춰 보는 데 쓴다
 * @param notes 파트마다 소리 나는 음 — 붙임줄은 하나로 합쳤고, 꾸밈음은 뺐다
 * @param rehearsals 리허설 마크(레터 A · B … 또는 번호) — 모든 파트에서 모아 마디 순서대로, 같은 마디의 같은 글은 하나 (P12 4단계, #112)
 */
data class MusicXmlScore(
    val parts: List<XmlPart>,
    val measures: List<XmlMeasure>,
    val notes: List<List<XmlNote>>,
    val rehearsals: List<XmlRehearsal> = emptyList(),
) {
    /**
     * 리허설 마크 [mark]("B", "12")가 붙은 마디 순서(0부터) — 없으면 null. 글자 크기 · 괄호 · 공백은 보지 않는다("[B]" = "b").
     * 같은 마크가 여럿이면 첫 것
     */
    fun rehearsalMeasure(mark: String): Int? {
        val key = rehearsalKey(mark)
        if (key.isEmpty()) return null
        return rehearsals.firstOrNull { rehearsalKey(it.text) == key }?.measure
    }

    /** 파트 [part] 가 차지하는 보표 순번들 (총보 위부터 0) — 파트 순서대로 보표 수를 쌓는다 */
    fun stavesOf(part: Int): IntRange {
        val start = parts.take(part).sumOf { it.staves }
        return start until start + parts[part].staves
    }

    val staffCount: Int get() = parts.sumOf { it.staves }

    /**
     * 보표 [staff](총보 위부터 0)의 이름 — 그 보표를 차지하는 파트 이름, 보표가 여럿인 파트면 뒤에 몇째 보표인지. 없으면 null.
     * PDF 에서 파트 이름을 글자로 읽지 못한 악보(Sibelius → Microsoft Print to PDF)의 "보표 n" 을 채운다
     */
    fun staffName(staff: Int): String? {
        val p = parts.indices.firstOrNull { staff in stavesOf(it) } ?: return null
        val part = parts[p]
        return if (part.staves > 1) "${part.name} ${staff - stavesOf(p).first + 1}" else part.name
    }
}

data class XmlPart(val id: String, val name: String, val staves: Int)

/** 리허설 마크 — [measure] 는 마디 순서(0부터), [text] 는 적힌 글("B", "12") */
data class XmlRehearsal(val measure: Int, val text: String)

/** 리허설 마크 비교용 — 글자 · 숫자만, 대문자로 */
internal fun rehearsalKey(text: String): String = text.filter { it.isLetterOrDigit() }.uppercase()

/** 마디 하나 — [index] 는 문서 순서(0부터), [number] 는 적힌 번호(못갖춘마디 "0" 등) */
data class XmlMeasure(val index: Int, val number: String, val beats: Int, val beatType: Int)

/**
 * 음 하나. [measure] 는 마디 순서(0부터), [onset] · [duration] 은 **4분음표 단위**(마디 첫머리부터), [midi] 는 음높이 (가온 다 = 60).
 * [velocity] 는 셈여림(0 ~ 1, #114) — 셈여림표가 하나도 없는 파트는 모두 1(예전 그대로)
 */
data class XmlNote(val measure: Int, val onset: Double, val duration: Double, val midi: Int, val velocity: Float = 1f)

/**
 * 셈여림 (#114). 반주 소리 크기에 곱한다 — 크게(fff) 1 에서 작게(ppp) 0.28 까지. 악기 소리의 실제 차이보다 좁게 잡았다
 * (반주가 너무 작아 안 들리지 않게, 크기는 반주 크기로 따로 맞춘다)
 */
object Dynamics {
    val LEVELS: Map<String, Float> = mapOf(
        "ppp" to 0.28f, "pp" to 0.38f, "p" to 0.5f, "mp" to 0.62f,
        "mf" to 0.74f, "f" to 0.86f, "ff" to 0.94f, "fff" to 1f,
        "pppp" to 0.22f, "ffff" to 1f,
    )
    /** 한 음만 세게 — 그다음은 앞의 셈여림으로 돌아간다(fp 는 p 로) */
    val ACCENTS: Map<String, Float> = mapOf(
        "sf" to 0.94f, "sfz" to 0.94f, "sffz" to 1f, "sfp" to 0.94f, "sfpp" to 0.94f, "fz" to 0.94f, "rf" to 0.9f, "rfz" to 0.9f, "fp" to 0.86f,
    )
    /** 셈여림표가 있는 파트에서 첫 표 앞의 음 */
    const val UNMARKED = 0.74f
    /** `<sound dynamics="X">` — 포르테(MIDI 90)에 대한 백분율 */
    fun ofSound(percent: Double): Float = (LEVELS.getValue("f") * percent / 100.0).toFloat().coerceIn(0.05f, 1f)
}

/**
 * MusicXML 읽기. `javax.xml` DOM — Android 와 JVM 단위 테스트 모두에서 돈다. 670KB(K488 2파트 99마디)도 가볍다.
 *
 * 다루는 것: `divisions`(도중에 바뀌어도), `backup` · `forward`(성부), `chord`(같은 시각), `rest`, `tie`(이어 붙이기), `grace`(빼기),
 * 셋잇단음표 등은 `duration` 에 이미 들어 있다. 반복 · 도돌이표는 펼치지 않는다 — 악보 연동과 같이 적힌 순서대로 (#050).
 * `.mxl`(압축)은 아직 읽지 않는다 — 서버(P06 §11)는 `.musicxml` 을 준다.
 */
object MusicXmlReader {

    fun read(file: File): MusicXmlScore? = file.inputStream().use { read(it) }

    fun read(input: InputStream): MusicXmlScore? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // DTD 를 내려받지 않는다 (MusicXML 은 보통 partwise DTD 를 선언한다)
            trySet("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            trySet("http://xml.org/sax/features/external-general-entities", false)
            trySet("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        parse(builder.parse(input).documentElement)
    } catch (e: Exception) {
        null
    }

    private fun DocumentBuilderFactory.trySet(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (e: Exception) {
            // 지원하지 않는 구현 — 무시
        }
    }

    private fun parse(root: Element): MusicXmlScore? {
        if (root.tagName != "score-partwise") return null
        val names = root.child("part-list")?.children("score-part")?.associate { sp ->
            sp.getAttribute("id") to (sp.child("part-name")?.textContent?.trim().orEmpty())
        }.orEmpty()
        val partElements = root.children("part")
        if (partElements.isEmpty()) return null

        val parts = ArrayList<XmlPart>()
        val notes = ArrayList<List<XmlNote>>()
        var measures: List<XmlMeasure> = emptyList()
        val rehearsals = ArrayList<XmlRehearsal>()
        for ((p, part) in partElements.withIndex()) {
            val read = readPart(part)
            val id = part.getAttribute("id")
            parts += XmlPart(id, names[id]?.takeIf { it.isNotEmpty() } ?: "파트 ${p + 1}", read.staves)
            notes += read.notes
            if (p == 0) measures = read.measures
            // 리허설 마크는 보통 맨 위 파트에만, 편집기에 따라 모든 파트에 — 모아서 같은 것은 하나로
            rehearsals += read.rehearsals
        }
        val marks = rehearsals.distinctBy { it.measure to rehearsalKey(it.text) }.sortedBy { it.measure }
        return MusicXmlScore(parts, measures, notes, marks)
    }

    private class PartRead(val notes: List<XmlNote>, val measures: List<XmlMeasure>, val staves: Int, val rehearsals: List<XmlRehearsal>)

    private fun readPart(part: Element): PartRead {
        val out = ArrayList<XmlNote>()
        val measures = ArrayList<XmlMeasure>()
        val rehearsals = ArrayList<XmlRehearsal>()
        // 셈여림 (#114): 지금 셈여림(null = 아직 표 없음), 다음 한 음만 세게(sf 등), 그 뒤 돌아갈 셈여림(fp → p)
        var dynamic: Float? = null
        var accentNext: Float? = null
        // 세게 친 음의 자리(마디, 시각)와 크기 — 화음이면 같은 시각의 음 모두
        var accentAt: Pair<Int, Double>? = null
        var accentLevel = 0f
        var anyDynamics = false
        var divisions = 1.0
        var beats = 4
        var beatType = 4
        var staves = 1
        // 붙임줄로 이어지는 중인 음: 음높이 → out 의 위치
        val openTies = HashMap<Int, Int>()

        for ((m, measure) in part.children("measure").withIndex()) {
            var cursor = 0.0 // 4분음표 단위
            var lastOnset = 0.0
            for (node in measure.childElements()) {
                when (node.tagName) {
                    "attributes" -> {
                        node.child("divisions")?.textContent?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { divisions = it }
                        node.child("staves")?.textContent?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { staves = it }
                        node.child("time")?.let { t ->
                            t.child("beats")?.textContent?.trim()?.toIntOrNull()?.let { beats = it }
                            t.child("beat-type")?.textContent?.trim()?.toIntOrNull()?.let { beatType = it }
                        }
                    }
                    "direction" -> {
                        node.children("direction-type").flatMap { it.children("rehearsal") }.forEach { r ->
                            r.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { rehearsals += XmlRehearsal(m, it) }
                        }
                        for (marks in node.children("direction-type").flatMap { it.children("dynamics") }) {
                            for (mark in marks.childElements()) {
                                val name = mark.tagName
                                Dynamics.LEVELS[name]?.let { dynamic = it; anyDynamics = true }
                                Dynamics.ACCENTS[name]?.let { level ->
                                    accentNext = level
                                    anyDynamics = true
                                    // fp · sfp — 친 뒤 여리게
                                    if (name == "fp" || name == "sfp") dynamic = Dynamics.LEVELS.getValue("p")
                                    if (name == "sfpp") dynamic = Dynamics.LEVELS.getValue("pp")
                                }
                            }
                        }
                        // 편집기가 적어 둔 재생 크기가 있으면 그것을 따른다
                        node.child("sound")?.getAttribute("dynamics")?.toDoubleOrNull()?.let { dynamic = Dynamics.ofSound(it); anyDynamics = true }
                    }
                    "sound" -> node.getAttribute("dynamics").toDoubleOrNull()?.let { dynamic = Dynamics.ofSound(it); anyDynamics = true }
                    "backup" -> cursor -= node.durationQuarters(divisions)
                    "forward" -> cursor += node.durationQuarters(divisions)
                    "note" -> {
                        if (node.child("grace") != null) continue // 꾸밈음은 박을 먹지 않는다 — 반주에서는 뺀다
                        val duration = node.durationQuarters(divisions)
                        val chord = node.child("chord") != null
                        val onset = if (chord) lastOnset else cursor
                        if (!chord) {
                            lastOnset = cursor
                            cursor += duration
                        }
                        if (node.child("rest") != null) continue
                        val midi = node.child("pitch")?.let(::midiOf) ?: continue
                        val ties = node.children("tie").map { it.getAttribute("type") }
                        val continuing = "stop" in ties && openTies.containsKey(midi)
                        if (continuing) {
                            // 앞 음에 길이만 더한다 (다시 치지 않는다)
                            val at = openTies.getValue(midi)
                            out[at] = out[at].copy(duration = out[at].duration + duration)
                            if ("start" !in ties) openTies.remove(midi)
                        } else {
                            // 음에 적힌 재생 크기(`<note dynamics>`) > 한 음 세게 > 지금 셈여림. 아직 표가 없으면 -1(뒤에서 정한다)
                            val own = node.getAttribute("dynamics").toDoubleOrNull()?.let { anyDynamics = true; Dynamics.ofSound(it) }
                            accentNext?.let { level ->
                                if (!chord) {
                                    accentAt = m to onset
                                    accentLevel = level
                                    accentNext = null
                                }
                            }
                            val accent = accentLevel.takeIf { accentAt == m to onset }
                            val velocity = own ?: accent ?: dynamic ?: -1f
                            out += XmlNote(m, onset, duration, midi, velocity)
                            if ("start" in ties) openTies[midi] = out.size - 1 else openTies.remove(midi)
                        }
                    }
                }
            }
            measures += XmlMeasure(m, measure.getAttribute("number"), beats, beatType)
        }
        // 셈여림표가 하나도 없는 파트는 모두 1(예전과 같은 크기), 있으면 첫 표 앞의 음은 보통(mf)
        val unmarked = if (anyDynamics) Dynamics.UNMARKED else 1f
        for (i in out.indices) if (out[i].velocity < 0f) out[i] = out[i].copy(velocity = unmarked)
        return PartRead(out, measures, staves, rehearsals)
    }

    private val STEPS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    private fun midiOf(pitch: Element): Int? {
        val step = pitch.child("step")?.textContent?.trim()?.firstOrNull()?.uppercaseChar()?.let { STEPS[it] } ?: return null
        val octave = pitch.child("octave")?.textContent?.trim()?.toIntOrNull() ?: return null
        val alter = pitch.child("alter")?.textContent?.trim()?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0
        return (octave + 1) * 12 + step + alter
    }

    private fun Element.durationQuarters(divisions: Double): Double =
        (child("duration")?.textContent?.trim()?.toDoubleOrNull() ?: 0.0) / divisions

    private fun Element.childElements(): List<Element> {
        val list = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element) list += n
            n = n.nextSibling
        }
        return list
    }

    private fun Element.children(tag: String): List<Element> = childElements().filter { it.tagName == tag }

    private fun Element.child(tag: String): Element? = childElements().firstOrNull { it.tagName == tag }
}
