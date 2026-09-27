package com.mrgq.pdfviewer.scoremate

import android.content.Context
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.Tokens

/**
 * ScoreMate 연결 정보 — 앱 전용 SharedPreferences `scoremate` (앱 샌드박스).
 *
 * ⚠️ **백업에서 뺀다** (`res/xml/backup_rules.xml`, P05 §3.1): 매니페스트가 `allowBackup="true"` 라 백업 · 복원되면
 * 다른 TV 가 같은 `device_id` · refresh 를 쓰게 된다. 이 파일 이름을 바꾸면 백업 규칙도 함께 바꿀 것.
 */
class ScoreMateStore(context: Context) : ScoreMateTokenStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var server: String
        get() = prefs.getString(KEY_SERVER, null) ?: ScoreMateProtocol.DEFAULT_SERVER
        set(value) {
            prefs.edit().putString(KEY_SERVER, value).apply()
        }

    override val tokens: Tokens?
        get() {
            val access = prefs.getString(KEY_ACCESS, null) ?: return null
            val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
            val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
            return Tokens(access, refresh, deviceId)
        }

    override var syncCursor: String?
        get() = prefs.getString(KEY_CURSOR, null)
        set(value) {
            prefs.edit().putString(KEY_CURSOR, value).commit()
        }

    override var setlistsBody: String?
        get() = prefs.getString(KEY_SETLISTS, null)
        set(value) {
            prefs.edit().putString(KEY_SETLISTS, value).apply()
        }

    /** 이 TV 가 서버에 알려진 이름 (연결할 때 · 기기 정보에서) — 화면 표시용 */
    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_DEVICE_NAME, value).apply()
        }

    // commit — refresh 가 회전하므로 디스크에 쓰기 전에 앱이 죽으면 다시 연결해야 한다
    override fun saveTokens(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.access)
            .putString(KEY_REFRESH, tokens.refresh)
            .putString(KEY_DEVICE_ID, tokens.deviceId)
            .commit()
    }

    override fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_CURSOR)
            .remove(KEY_SETLISTS)
            .commit()
    }

    companion object {
        /** 백업 규칙(`backup_rules.xml`)이 이 이름으로 뺀다 */
        const val PREFS = "scoremate"
        private const val KEY_SERVER = "server"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_CURSOR = "sync_cursor"
        private const val KEY_SETLISTS = "setlists"
    }
}
