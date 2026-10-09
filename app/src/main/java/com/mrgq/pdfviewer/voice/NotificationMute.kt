package com.mrgq.pdfviewer.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 👂 계속 듣기 동안 알림 소리를 끈다 (#092). 기기 음성 인식(Google)은 듣기를 시작할 때마다 시작음을 **알림 채널**
 * (`USAGE_NOTIFICATION_EVENT`)로 낸다. 계속 듣기는 조용하면 몇 초마다 다시 걸리므로 그때마다 딸깍거린다(사용자 보고 2026-10-09).
 * 이 소리만 끌 방법은 없어 알림 채널을 음소거한다.
 *
 * - **우리가 끈 것만** 되돌린다. 원래 꺼져 있었으면 손대지 않는다
 * - 앱이 도중에 죽어도 다음에 되돌리도록 표시를 남긴다 — 첫 화면 · 악보 화면이 돌아올 때 `set(false)`
 * - 휴대폰은 알림과 벨소리가 묶인 기기가 많아 그동안 전화가 진동으로 올 수 있다
 */
object NotificationMute {
    private const val TAG = "NotificationMute"
    private const val PREFS = "pdf_viewer_prefs"
    private const val KEY_MUTED_BY_APP = "notification_muted_by_app"

    /**
     * [mute] true = 끈다(이미 꺼져 있으면 그대로), false = 우리가 껐으면 되돌린다.
     * 기기가 막으면(방해 금지 접근 권한이 필요한 기기) false
     */
    fun set(context: Context, mute: Boolean): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ours = prefs.getBoolean(KEY_MUTED_BY_APP, false)
        return try {
            if (mute && !ours && !audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)) {
                audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                prefs.edit().putBoolean(KEY_MUTED_BY_APP, true).apply()
                Log.i(TAG, "👂 계속 듣기 — 알림 소리 끔")
            } else if (!mute && ours) {
                audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                prefs.edit().putBoolean(KEY_MUTED_BY_APP, false).apply()
                Log.i(TAG, "알림 소리 되돌림")
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "알림 소리를 바꾸지 못함 (방해 금지 접근 권한)", e)
            false
        }
    }
}
