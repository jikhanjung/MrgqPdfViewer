package com.mrgq.pdfviewer.model

data class SettingsItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: String = "", // 이모지 아이콘
    val type: SettingsItemType,
    val isEnabled: Boolean = true,
    val hasArrow: Boolean = true
)

enum class SettingsItemType {
    CATEGORY,       // 카테고리 (하위 메뉴로 이동)
    TOGGLE,         // ON/OFF 스위치
    ACTION,         // 즉시 실행되는 액션
    INPUT,          // 입력 필드
    INFO            // 정보 표시만
}

// 설정 카테고리 정의
object SettingsCategories {
    const val FILE_MANAGEMENT = "file_management"
    const val WEB_SERVER = "web_server"
    const val COLLABORATION = "collaboration"
    const val SYSTEM = "system"
    const val INFO = "info"
}

// 설정 아이템 ID 정의
object SettingsItemIds {
    // 파일 관리
    const val PDF_FILES_INFO = "pdf_files_info"
    const val DELETE_ALL_PDF = "delete_all_pdf"
    const val FILE_SETTINGS_MANAGEMENT = "file_settings_management"
    
    // 웹서버
    const val WEB_SERVER_PORT = "web_server_port"
    const val WEB_SERVER_STATUS = "web_server_status"
    
    // 협업 모드
    const val COLLABORATION_MODE = "collaboration_mode"
    const val CONDUCTOR_MODE = "conductor_mode"
    const val PERFORMER_MODE = "performer_mode"
    const val COLLABORATION_OFF = "collaboration_off"
    const val CONNECTION_SETTINGS = "connection_settings"
    const val AUTO_DISCOVERY = "auto_discovery"
    
    // 시스템
    const val RESET_FILE_SETTINGS = "reset_file_settings"
    const val RESET_ALL_SETTINGS = "reset_all_settings"
    const val CACHE_MANAGEMENT = "cache_management"
    
    // 정보
    const val APP_VERSION = "app_version"
    const val CONNECTION_INFO = "connection_info"
    const val DEBUG_INFO = "debug_info"
}