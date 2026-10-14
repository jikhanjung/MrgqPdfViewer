package com.mrgq.pdfviewer.scoremate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 합주 동안만 두는 악보 (서버 0.25.0 멤버십 `retention: session`, 서버 devlog 084) — 합주 모드가 바뀔 때 서재에 두고 숨긴다.
 * 계산은 [ScoreMateSync.fetchSessionScores] · [ScoreMateSync.evictSessionScores], 여기는 언제 부를지만.
 *
 * - 합주 모드 시작(지휘자 · 연주자, 앱을 켤 때 저장된 합주 모드를 되살릴 때도) → 받기
 * - 합주 모드 끝(직접 끄기 · 지휘자가 끝냄) → 서재에서 숨은 캐시로 (30일 동안 열지 않으면 버림)
 * - 앱을 켤 때 합주 모드가 아니면 → 캐시로 (합주 중에 앱이 죽었을 때)
 * - 뷰어가 열면 → 마지막 접근 [touch]
 *
 * 순서는 [ScoreMateSync] 의 잠금이 지킨다(받기 · 지우기 · 일반 동기화가 차례로). 연결 안 된 기기는 아무것도 안 한다.
 * 바뀐 것이 있으면 [onChanged] (주 스레드 — 목록 화면이 다시 읽는다)
 */
object SessionScores {
    private const val TAG = "SessionScores"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** 목록 화면이 onResume 에서 걸고 onPause 에서 뗀다 */
    @Volatile var onChanged: (() -> Unit)? = null

    fun onEnsembleStarted(context: Context) = inBackground(context, "fetch") { sync ->
        val report = sync.fetchSessionScores()
        Log.i(TAG, "fetched: $report")
        report.downloaded + report.musicXml + report.layouts > 0
    }

    fun onEnsembleEnded(context: Context) = inBackground(context, "evict") { sync ->
        val n = sync.evictSessionScores()
        Log.i(TAG, "stashed $n")
        n > 0
    }

    /** 뷰어가 파일을 열었다 — 합주용 악보면 마지막 접근을 지금으로 (캐시가 30일 더 남는다) */
    fun touch(context: Context, filePath: String) = inBackground(context, "touch") { sync ->
        sync.touchSession(filePath)
        false
    }

    /** 지금 합주 모드인가 (지휘자 · 연주자) — [ScoreMateSync] 의 `inEnsemble` */
    fun inEnsemble(): Boolean =
        com.mrgq.pdfviewer.GlobalCollaborationManager.getInstance().getCurrentMode() != com.mrgq.pdfviewer.CollaborationMode.NONE

    private fun inBackground(context: Context, what: String, block: suspend (ScoreMateSync) -> Boolean) {
        val app = context.applicationContext
        val store = ScoreMateStore(app)
        if (store.tokens == null) return
        scope.launch {
            val local = com.mrgq.pdfviewer.repository.ScoreMateLocal(app)
            val changed = try {
                block(ScoreMateSync(ScoreMateClient(store), store, local, local, File(app.getExternalFilesDir(null), "PDFs"), inEnsemble = { inEnsemble() }))
            } catch (e: Exception) {
                Log.w(TAG, "$what failed: ${e.message}")
                false
            }
            if (changed) main.post { onChanged?.invoke() }
        }
    }
}
