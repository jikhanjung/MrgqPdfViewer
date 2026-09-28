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
 */
data class MusicXmlScore(
    val parts: List<XmlPart>,
    val measures: List<XmlMeasure>,
    val notes: List<List<XmlNote>>,
) {
    /** 파트 [part] 가 차지하는 보표 순번들 (총보 위부터 0) — 파트 순서대로 보표 수를 쌓는다 */
    fun stavesOf(part: Int): IntRange {
        val start = parts.take(part).sumOf { it.staves }
        return start until start + parts[part].staves
    }

    val staffCount: Int get() = parts.sumOf { it.staves }
}

data class XmlPart(val id: String, val name: String, val staves: Int)

/** 마디 하나 — [index] 는 문서 순서(0부터), [number] 는 적힌 번호(못갖춘마디 "0" 등) */
data class XmlMeasure(val index: Int, val number: String, val beats: Int, val beatType: Int)

/**
 * 음 하나. [measure] 는 마디 순서(0부터), [onset] · [duration] 은 **4분음표 단위**(마디 첫머리부터), [midi] 는 음높이 (가온 다 = 60).
 */
data class XmlNote(val measure: Int, val onset: Double, val duration: Double, val midi: Int)

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
        for ((p, part) in partElements.withIndex()) {
            val (partNotes, partMeasures, staves) = readPart(part)
            val id = part.getAttribute("id")
            parts += XmlPart(id, names[id]?.takeIf { it.isNotEmpty() } ?: "파트 ${p + 1}", staves)
            notes += partNotes
            if (p == 0) measures = partMeasures
        }
        return MusicXmlScore(parts, measures, notes)
    }

    private fun readPart(part: Element): Triple<List<XmlNote>, List<XmlMeasure>, Int> {
        val out = ArrayList<XmlNote>()
        val measures = ArrayList<XmlMeasure>()
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
                            out += XmlNote(m, onset, duration, midi)
                            if ("start" in ties) openTies[midi] = out.size - 1 else openTies.remove(midi)
                        }
                    }
                }
            }
            measures += XmlMeasure(m, measure.getAttribute("number"), beats, beatType)
        }
        return Triple(out, measures, staves)
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
