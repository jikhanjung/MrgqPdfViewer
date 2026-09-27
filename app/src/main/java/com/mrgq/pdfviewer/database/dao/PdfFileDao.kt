package com.mrgq.pdfviewer.database.dao

import androidx.room.*
import com.mrgq.pdfviewer.database.entity.PdfFile
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfFileDao {
    
    @Query("SELECT * FROM pdf_files ORDER BY filename ASC")
    fun getAllPdfFiles(): Flow<List<PdfFile>>
    
    @Query("SELECT * FROM pdf_files ORDER BY createdAt DESC")
    fun getAllPdfFilesByDate(): Flow<List<PdfFile>>
    
    @Query("SELECT * FROM pdf_files WHERE id = :id")
    suspend fun getPdfFileById(id: String): PdfFile?
    
    @Query("SELECT * FROM pdf_files WHERE filePath = :filePath")
    suspend fun getPdfFileByPath(filePath: String): PdfFile?
    
    @Query("SELECT * FROM pdf_files WHERE filename LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' OR composer LIKE '%' || :query || '%'")
    fun searchPdfFiles(query: String): Flow<List<PdfFile>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPdfFile(pdfFile: PdfFile)
    
    @Update
    suspend fun updatePdfFile(pdfFile: PdfFile)

    /** 파일이 옮겨졌다 (ScoreMate 동기화의 이름 바꾸기) — 레코드를 update 해 파일별 설정을 유지한다 */
    @Query("UPDATE pdf_files SET filePath = :newPath, filename = :newName WHERE filePath = :oldPath")
    suspend fun movePath(oldPath: String, newPath: String, newName: String)

    @Query("DELETE FROM pdf_files WHERE filePath = :filePath")
    suspend fun deleteByPath(filePath: String)

    @Query("UPDATE pdf_files SET scoreAnalyzedAt = :analyzedAt WHERE id = :id")
    suspend fun setScoreAnalyzedAt(id: String, analyzedAt: Long?)
    
    @Delete
    suspend fun deletePdfFile(pdfFile: PdfFile)
    
    @Query("DELETE FROM pdf_files WHERE id = :id")
    suspend fun deletePdfFileById(id: String)
    
    @Query("DELETE FROM pdf_files")
    suspend fun deleteAllPdfFiles()
    
    @Query("SELECT COUNT(*) FROM pdf_files")
    suspend fun getPdfFileCount(): Int
}