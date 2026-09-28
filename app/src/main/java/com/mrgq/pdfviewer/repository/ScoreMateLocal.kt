package com.mrgq.pdfviewer.repository

import android.content.Context
import com.mrgq.pdfviewer.database.MusicDatabase
import com.mrgq.pdfviewer.database.entity.ServerScore
import com.mrgq.pdfviewer.scoremate.LocalPdfRecords
import com.mrgq.pdfviewer.scoremate.SyncedScore
import com.mrgq.pdfviewer.scoremate.SyncedScoreStore
import java.io.File

/** ScoreMate 동기화의 DB 쪽 (P05 C2) — 받은 서버 악보(`server_scores`)와 파일 레코드(`pdf_files`) */
class ScoreMateLocal(context: Context) : SyncedScoreStore, LocalPdfRecords {

    private val database = MusicDatabase.getDatabase(context)
    private val serverScores = database.serverScoreDao()
    private val pdfFiles = database.pdfFileDao()

    override suspend fun all(): List<SyncedScore> = serverScores.getAll().map { it.toModel() }

    override suspend fun upsert(score: SyncedScore) = serverScores.upsert(score.toEntity())

    override suspend fun delete(serverId: Long) = serverScores.delete(serverId)

    /** 이 경로의 파일이 서버에서 받은 악보인가 — 파일 목록의 ☁️ 표시 · 지울 때 숨김 처리 */
    suspend fun byPath(filePath: String): SyncedScore? = serverScores.getByPath(filePath)?.toModel()

    override suspend fun beforeMove(oldPath: String, newPath: String) {
        // 새 경로에 남은 레코드(예전 파일의 흔적)가 있으면 지운다 — 같은 경로 레코드가 둘이면 경로 조회가 흔들린다
        if (oldPath != newPath) pdfFiles.deleteByPath(newPath)
        pdfFiles.movePath(oldPath, newPath, File(newPath).name)
    }

    override suspend fun afterDelete(path: String) = pdfFiles.deleteByPath(path)

    private fun ServerScore.toModel() = SyncedScore(
        serverId, filePath, versionNumber, sha256, title, composer, partName, ensembleId, ensembleName, hidden, syncedAt, arranger,
    )

    private fun SyncedScore.toEntity() = ServerScore(
        serverId, filePath, versionNumber, sha256, title, composer, partName, ensembleId, ensembleName, hidden, syncedAt, arranger,
    )
}
