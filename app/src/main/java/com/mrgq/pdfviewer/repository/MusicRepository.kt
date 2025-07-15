package com.mrgq.pdfviewer.repository

import android.content.Context
import com.mrgq.pdfviewer.database.MusicDatabase
import com.mrgq.pdfviewer.database.entity.DisplayMode
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.database.entity.UserPreference
import kotlinx.coroutines.flow.Flow

class MusicRepository(context: Context) {
    
    private val database = MusicDatabase.getDatabase(context)
    private val pdfFileDao = database.pdfFileDao()
    private val userPreferenceDao = database.userPreferenceDao()
    
    // PDF File operations
    fun getAllPdfFiles(): Flow<List<PdfFile>> = pdfFileDao.getAllPdfFiles()
    
    fun getAllPdfFilesByDate(): Flow<List<PdfFile>> = pdfFileDao.getAllPdfFilesByDate()
    
    suspend fun getPdfFileById(id: String): PdfFile? = pdfFileDao.getPdfFileById(id)
    
    suspend fun getPdfFileByPath(filePath: String): PdfFile? = pdfFileDao.getPdfFileByPath(filePath)
    
    fun searchPdfFiles(query: String): Flow<List<PdfFile>> = pdfFileDao.searchPdfFiles(query)
    
    suspend fun insertPdfFile(pdfFile: PdfFile) = pdfFileDao.insertPdfFile(pdfFile)
    
    suspend fun updatePdfFile(pdfFile: PdfFile) = pdfFileDao.updatePdfFile(pdfFile)
    
    suspend fun deletePdfFile(pdfFile: PdfFile) = pdfFileDao.deletePdfFile(pdfFile)
    
    suspend fun deletePdfFileById(id: String) = pdfFileDao.deletePdfFileById(id)
    
    suspend fun deleteAllPdfFiles() = pdfFileDao.deleteAllPdfFiles()
    
    suspend fun getPdfFileCount(): Int = pdfFileDao.getPdfFileCount()
    
    // User Preference operations
    fun getAllUserPreferences(): Flow<List<UserPreference>> = userPreferenceDao.getAllUserPreferences()
    
    suspend fun getUserPreference(pdfFileId: String): UserPreference? = 
        userPreferenceDao.getUserPreference(pdfFileId)
    
    fun getUserPreferenceFlow(pdfFileId: String): Flow<UserPreference?> = 
        userPreferenceDao.getUserPreferenceFlow(pdfFileId)
    
    suspend fun insertUserPreference(userPreference: UserPreference) = 
        userPreferenceDao.insertUserPreference(userPreference)
    
    suspend fun updateUserPreference(userPreference: UserPreference) = 
        userPreferenceDao.updateUserPreference(userPreference)
    
    suspend fun deleteUserPreference(userPreference: UserPreference) = 
        userPreferenceDao.deleteUserPreference(userPreference)
    
    suspend fun deleteUserPreferenceById(pdfFileId: String) = 
        userPreferenceDao.deleteUserPreferenceById(pdfFileId)
    
    suspend fun deleteAllUserPreferences() = userPreferenceDao.deleteAllUserPreferences()
    
    suspend fun updateLastPageNumber(pdfFileId: String, pageNumber: Int) = 
        userPreferenceDao.updateLastPageNumber(pdfFileId, pageNumber)
    
    suspend fun updateDisplayMode(pdfFileId: String, displayMode: DisplayMode) = 
        userPreferenceDao.updateDisplayMode(pdfFileId, displayMode)
    
    suspend fun updateBookmarkedPages(pdfFileId: String, bookmarkedPages: String) = 
        userPreferenceDao.updateBookmarkedPages(pdfFileId, bookmarkedPages)
    
    suspend fun getUserPreferenceCount(): Int = userPreferenceDao.getUserPreferenceCount()
    
    // Convenience methods
    suspend fun getOrCreateUserPreference(pdfFileId: String): UserPreference {
        return getUserPreference(pdfFileId) ?: UserPreference(
            pdfFileId = pdfFileId,
            displayMode = DisplayMode.AUTO
        ).also {
            insertUserPreference(it)
        }
    }
    
    suspend fun setDisplayModeForFile(pdfFileId: String, displayMode: DisplayMode) {
        android.util.Log.d("MusicRepository", "=== setDisplayModeForFile 시작 ===")
        android.util.Log.d("MusicRepository", "pdfFileId: $pdfFileId, displayMode: $displayMode")
        
        // 기존 설정을 보장하기 위해 먼저 레코드 생성
        val existingPref = getOrCreateUserPreference(pdfFileId)
        android.util.Log.d("MusicRepository", "기존 설정: $existingPref")
        
        // 부분 업데이트로 DisplayMode만 변경
        userPreferenceDao.updateDisplayMode(pdfFileId, displayMode, System.currentTimeMillis())
        android.util.Log.d("MusicRepository", "DisplayMode 업데이트 완료")
        
        // 저장 확인
        val updatedPref = getUserPreference(pdfFileId)
        android.util.Log.d("MusicRepository", "저장 후 설정: $updatedPref")
    }
    
    suspend fun setLastPageForFile(pdfFileId: String, pageNumber: Int) {
        val preference = getOrCreateUserPreference(pdfFileId)
        updateUserPreference(preference.copy(lastPageNumber = pageNumber, updatedAt = System.currentTimeMillis()))
    }
}