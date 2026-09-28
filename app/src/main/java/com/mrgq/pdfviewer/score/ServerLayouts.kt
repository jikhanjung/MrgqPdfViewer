package com.mrgq.pdfviewer.score

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mrgq.pdfviewer.scoremate.ScoreMateSync
import java.io.File

/**
 * 서버가 분석한 보표 · 마디 (P06 §12, 서버 0.10.x) — ScoreMate 동기화가 PDF 옆에 둔 `.layout.json`.
 * 서버는 앱 `score/` 를 그대로 옮겨 돌리므로 모양이 [ScoreLayout] 그대로다(Kotlin 필드 이름). 있으면 앱이 직접 분석하지 않는다.
 *
 * 쓰는 조건: 파일의 `pdf_sha256` 이 이 PDF 의 sha256 과 같을 때만 — 판이 다르면 마디가 어긋난다.
 * 분석기가 바뀌면(`analyzer_version`) 서버가 다시 분석하고 파일 sha 가 바뀌어 동기화가 새로 받는다. [ScoreLayoutStore] 는 파일이 캐시보다
 * 새로우면 다시 읽는다 — 앱이 DB 마이그레이션으로 캐시를 비우던 것을 대신한다.
 */
object ServerLayouts {

    private const val TAG = "ServerLayouts"

    data class Parsed(val analyzerVersion: String?, val pdfSha256: String?, val layout: ScoreLayout)

    /** [pdf] 옆 분석 파일 (없을 수 있다) */
    fun fileFor(pdf: File): File = ScoreMateSync.layoutFileOf(pdf)

    /** [pdf] 의 서버 분석 — 파일이 없거나 · 깨졌거나 · 다른 판(pdf_sha256)이면 null (그때는 앱이 분석한다) */
    fun read(pdf: File): ScoreLayout? {
        val file = fileFor(pdf)
        if (!file.isFile) return null
        val parsed = parse(file.readText())
        if (parsed == null) {
            Log.w(TAG, "분석 파일을 읽지 못함: ${file.name}")
            return null
        }
        val sha = ScoreMateSync.sha256Of(pdf)
        if (!sha.equals(parsed.pdfSha256, ignoreCase = true)) {
            Log.w(TAG, "분석 파일의 판이 다름 — 앱이 분석: ${file.name}")
            return null
        }
        Log.i(TAG, "서버 분석 사용: ${pdf.name} (analyzer ${parsed.analyzerVersion}, 마디 ${parsed.layout.measureCount})")
        return parsed.layout
    }

    /** 앱에서 분석하기 전에 서버 분석부터 — [ScoreLayoutStore] 의 기본 분석 */
    fun readOrAnalyze(pdf: File): ScoreLayout? = read(pdf) ?: ScoreLayoutAnalyzer.analyze(pdf)

    fun parse(json: String): Parsed? = try {
        val o = JsonParser.parseString(json).asJsonObject
        val pages = o.getAsJsonArray("pages").map { e ->
            val p = e.asJsonObject
            PageLayout(
                pageIndex = p["pageIndex"].asInt,
                widthPt = p["widthPt"].asFloat,
                heightPt = p["heightPt"].asFloat,
                systems = p.array("systems").map { s ->
                    val sys = s.asJsonObject
                    SystemLayout(
                        top = sys["top"].asFloat,
                        bottom = sys["bottom"].asFloat,
                        left = sys["left"].asFloat,
                        right = sys["right"].asFloat,
                        staffBands = sys.array("staffBands").map { b -> b.asJsonArray.let { it[0].asFloat to it[1].asFloat } },
                        barlines = sys.array("barlines").map { it.asFloat },
                        staffLabels = sys.array("staffLabels").map { it.takeUnless(JsonElement::isJsonNull)?.asString },
                    )
                },
                timeSignatures = p.array("timeSignatures").map { t ->
                    val mark = t.asJsonObject
                    TimeSignatureMark(mark["systemIndex"].asInt, mark["x"].asFloat, mark["numerator"].asInt, mark["denominator"].asInt)
                },
            )
        }
        Parsed(o.str("analyzer_version"), o.str("pdf_sha256"), ScoreLayout(pages))
    } catch (e: RuntimeException) {
        null
    }

    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
