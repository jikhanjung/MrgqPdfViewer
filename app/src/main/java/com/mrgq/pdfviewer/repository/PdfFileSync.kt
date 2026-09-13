package com.mrgq.pdfviewer.repository

import android.util.Log
import com.mrgq.pdfviewer.database.dao.PdfFileDao
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.utils.PdfAnalyzer
import com.mrgq.pdfviewer.utils.PdfDocumentInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 파일 시스템의 PDF 와 pdf_files 레코드를 맞춘다. 처음 보는 파일이나 바뀐 파일만 분석하고
 * (PdfRenderer + PdfBox), 나머지는 DB 에 캐시된 페이지 수·문서 정보를 그대로 쓴다.
 *
 * ⚠️ **기존 레코드 갱신은 반드시 update 로.** `insertPdfFile` 은 `OnConflictStrategy.REPLACE` 인데
 * SQLite 의 REPLACE 는 기존 행을 지우고 다시 넣는다. 그러면 user_preferences 의
 * `ON DELETE CASCADE` 가 발동해 **파일별 표시 설정이 지워진다** (PdfFileSyncTest 가 고정).
 */
object PdfFileSync {

    private const val TAG = "PdfFileSync"

    /**
     * 동기화를 한 번에 하나씩. MainActivity 는 onCreate 와 onResume 에서 목록 로드를 겹쳐 띄우고,
     * 뷰어도 같은 파일을 동기화한다. 직렬화하지 않으면 같은 파일을 두 번 분석하고(실기동에서 로그
     * 중복 확인), 둘 다 "레코드 없음"을 보고 insert(REPLACE) 를 겹쳐 실행한다.
     */
    private val mutex = Mutex()

    suspend fun sync(
        dao: PdfFileDao,
        file: File,
        analyze: (File) -> PdfFile? = PdfAnalyzer::analyzePdfFile,
    ): PdfFile? = mutex.withLock { syncLocked(dao, file, analyze) }

    private suspend fun syncLocked(
        dao: PdfFileDao,
        file: File,
        analyze: (File) -> PdfFile?,
    ): PdfFile? {
        val existing = dao.getPdfFileByPath(file.absolutePath)
        if (existing != null &&
            !PdfDocumentInfo.needsRescan(existing.docInfoReadAt, existing.createdAt, file.lastModified())
        ) {
            return existing
        }

        // 분석에 실패하면(손상된 파일 등) 있던 레코드를 그대로 쓰고 다음 로드에서 다시 시도한다
        val analyzed = analyze(file) ?: return existing
        Log.i(TAG, "문서 정보: ${file.name} title=${analyzed.title} author=${analyzed.author}")

        if (existing == null) {
            dao.insertPdfFile(analyzed)
            return analyzed
        }
        // 파일에서 얻을 수 없는 값(사용자가 입력할 악보 정보)은 보존한다.
        // scoreAnalyzedAt 은 일부러 보존하지 않는다 — 파일이 바뀌었으니 마디도 다시 분석해야 한다 (ScoreLayoutStore)
        val refreshed = analyzed.copy(
            id = existing.id,
            composer = existing.composer,
            key = existing.key,
            tempo = existing.tempo,
            genre = existing.genre,
            difficulty = existing.difficulty,
        )
        dao.updatePdfFile(refreshed)
        return refreshed
    }
}
