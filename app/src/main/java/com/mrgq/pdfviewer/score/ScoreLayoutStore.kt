package com.mrgq.pdfviewer.score

import android.util.Log
import androidx.room.withTransaction
import com.mrgq.pdfviewer.database.MusicDatabase
import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 마디 레이아웃 캐시. 파일마다 **처음 한 번만** 분석해 score_measures 에 저장한다.
 *
 * "분석했음"은 `PdfFile.scoreAnalyzedAt` 으로 표시한다 — 마디가 0개인 결과(악보가 아님, 열 수 없음)도
 * 표시해서 다시 분석하지 않는다. 파일이 바뀌면 PdfFileSync 가 레코드를 새로 쓰면서 이 값이 비워지고,
 * 다음 조회에서 이전 마디를 지우고 다시 분석한다.
 */
object ScoreLayoutStore {

    private const val TAG = "ScoreLayoutStore"

    /** 같은 파일을 동시에 두 번 분석하지 않도록 (뷰어의 페이지 전환마다 조회가 들어온다). */
    private val mutex = Mutex()

    /**
     * @return 마디 목록 (없으면 빈 목록). 레코드가 없거나 **레코드의 경로가 [file] 과 다르면 null** —
     *   뷰어가 파일을 전환하는 사이 이전 파일 ID 로 새 파일을 분석해 엉뚱한 레코드에 저장하는 것을 막는다.
     */
    suspend fun getOrAnalyze(
        db: MusicDatabase,
        pdfFileId: String,
        file: File,
        analyze: (File) -> ScoreLayout? = ScoreLayoutAnalyzer::analyze,
    ): List<ScoreMeasure>? = mutex.withLock {
        val record = db.pdfFileDao().getPdfFileById(pdfFileId) ?: return@withLock null
        if (record.filePath != file.absolutePath) {
            Log.w(TAG, "레코드와 파일이 다름 — 분석하지 않음: ${record.filePath} ≠ ${file.absolutePath}")
            return@withLock null
        }
        if (record.scoreAnalyzedAt != null) {
            return@withLock db.scoreMeasureDao().getMeasures(pdfFileId)
        }

        val measures = analyze(file)?.toMeasures(pdfFileId).orEmpty()
        db.withTransaction {
            db.scoreMeasureDao().deleteForFile(pdfFileId)
            if (measures.isNotEmpty()) db.scoreMeasureDao().insertAll(measures)
            db.pdfFileDao().setScoreAnalyzedAt(pdfFileId, System.currentTimeMillis())
        }
        Log.i(TAG, "마디 ${measures.size}개 저장: ${file.name}")
        measures
    }
}
