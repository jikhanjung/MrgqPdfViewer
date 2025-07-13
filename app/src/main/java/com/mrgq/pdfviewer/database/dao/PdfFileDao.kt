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
    
    @Delete
    suspend fun deletePdfFile(pdfFile: PdfFile)
    
    @Query("DELETE FROM pdf_files WHERE id = :id")
    suspend fun deletePdfFileById(id: String)
    
    @Query("DELETE FROM pdf_files")
    suspend fun deleteAllPdfFiles()
    
    @Query("SELECT COUNT(*) FROM pdf_files")
    suspend fun getPdfFileCount(): Int
}