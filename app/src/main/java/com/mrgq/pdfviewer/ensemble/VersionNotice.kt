package com.mrgq.pdfviewer.ensemble

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.mrgq.pdfviewer.GlobalCollaborationManager

/**
 * 합주 상대와 앱 버전이 다를 때의 안내 (#061) — 앞에 있는 화면이 대화상자로 띄운다.
 * 화면마다 onResume 에서 [attach], onPause 에서 [detach]. 앞에 화면이 없으면 GlobalCollaborationManager 가 토스트로.
 */
object VersionNotice {
    fun attach(activity: Activity) {
        GlobalCollaborationManager.getInstance().setOnVersionMismatch { message ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                AlertDialog.Builder(activity)
                    .setTitle("합주 기기의 버전이 다릅니다")
                    .setMessage(message)
                    .setPositiveButton("확인", null)
                    .show()
            }
        }
    }

    fun detach() {
        GlobalCollaborationManager.getInstance().setOnVersionMismatch(null)
    }
}
