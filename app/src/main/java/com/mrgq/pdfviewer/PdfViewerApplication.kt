package com.mrgq.pdfviewer

import android.app.Application
import android.util.Log

/**
 * Application 클래스 - 앱 전체 생명주기 관리
 * 강제 종료나 시스템 종료 시에도 합주 모드를 정리
 */
class PdfViewerApplication : Application() {
    
    companion object {
        private const val TAG = "PdfViewerApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application 시작")
        
        // Global collaboration manager 초기화
        GlobalCollaborationManager.getInstance().initialize(this)
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "Application 종료 - 합주 모드 정리 중...")
        
        // 모든 합주 모드 강제 정리
        try {
            GlobalCollaborationManager.getInstance().deactivateCollaborationMode()
            Log.d(TAG, "합주 모드 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "합주 모드 정리 중 오류", e)
        }
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "메모리 부족 - 합주 모드 정리")
        
        // 메모리 부족 시에도 합주 모드 정리
        try {
            GlobalCollaborationManager.getInstance().deactivateCollaborationMode()
        } catch (e: Exception) {
            Log.e(TAG, "메모리 부족 시 합주 모드 정리 중 오류", e)
        }
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        // 메모리 트림 레벨에 따라 처리
        when (level) {
            TRIM_MEMORY_COMPLETE,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_BACKGROUND -> {
                Log.w(TAG, "메모리 트림 (레벨: $level) - 합주 모드 정리")
                try {
                    GlobalCollaborationManager.getInstance().deactivateCollaborationMode()
                } catch (e: Exception) {
                    Log.e(TAG, "메모리 트림 시 합주 모드 정리 중 오류", e)
                }
            }
        }
    }
}