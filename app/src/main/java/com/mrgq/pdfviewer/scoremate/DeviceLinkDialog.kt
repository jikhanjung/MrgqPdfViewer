package com.mrgq.pdfviewer.scoremate

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mrgq.pdfviewer.BuildConfig
import com.mrgq.pdfviewer.R
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol.PollResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "이 TV 연결" 화면 (P05 C1, 서버 devlog 059 §1). 코드와 QR 을 보이고, 휴대폰에서 연결할 때까지 서버가 정한 간격으로 묻는다.
 *
 * - `slow_down` · 429 → 간격 +5초, 10분 지나면(`expired_token`) · `invalid_grant` → 저절로 새 코드
 * - 거절(`access_denied`)이나 코드를 못 받으면 멈추고 "새 코드" 를 기다린다. 폴링 중 네트워크 오류는 표시만 하고 계속 묻는다
 * - 연결되면 heartbeat(앱 버전 · 모델)로 서버의 기기 이름을 받아 두고 [onLinked]
 */
class DeviceLinkDialog(
    private val activity: Activity,
    private val client: ScoreMateClient,
    private val store: ScoreMateStore,
    private val onLinked: () -> Unit,
) {
    /** 화면 문구의 기기 이름 (TV · 태블릿 · 휴대폰) */
    private val device by lazy { com.mrgq.pdfviewer.utils.DeviceForm.noun(activity) }

    /** 휴대폰 — 폭이 좁아 QR 위 · 글 아래로 쌓는다 (코드가 줄바꿈되지 않게, 2026-10-06) */
    private val phone by lazy { com.mrgq.pdfviewer.utils.DeviceForm.isPhone(activity) }
    private val qrSizePx by lazy {
        if (phone) (220 * activity.resources.displayMetrics.density).toInt() else QR_SIZE_PX
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var dialog: AlertDialog

    private val qrView by lazy {
        ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(qrSizePx, qrSizePx).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setBackgroundColor(Color.WHITE)
        }
    }
    private val stepsView = TextView(activity).apply {
        textSize = 18f
        setLineSpacing(0f, 1.2f)
    }
    private val codeView = TextView(activity).apply {
        textSize = 44f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(0xFF90CAF9.toInt())
        setPadding(0, 24, 0, 24)
        maxLines = 1
    }
    private val statusView = TextView(activity).apply {
        textSize = 15f
        setTextColor(Color.LTGRAY)
    }

    fun show() {
        if (phone) {
            codeView.textSize = 34f
            codeView.gravity = Gravity.CENTER_HORIZONTAL
            stepsView.textSize = 15f
            statusView.textSize = 13f
        }
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (phone) setPadding(0, 32, 0, 0) else setPadding(40, 0, 0, 0)
            addView(stepsView)
            addView(codeView, LinearLayout.LayoutParams(
                if (phone) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(statusView)
        }
        val content = LinearLayout(activity).apply {
            // 휴대폰: QR 위, 안내 · 코드 · 상태 아래 / TV · 태블릿: QR 왼쪽, 글 오른쪽
            orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (phone) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            if (phone) setPadding(40, 30, 40, 10) else setPadding(50, 30, 50, 10)
            addView(qrView)
            addView(right)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.scoremate_link_title, device))
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.scoremate_link_new_code, null)
            .setOnDismissListener { scope.cancel() }
            .create()
        dialog.setOnShowListener {
            // 누르면 닫히지 않고 새 코드부터 다시
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { start() }
        }
        dialog.show()
        start()
    }

    private fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                qrView.setImageBitmap(null)
                codeView.text = "…"
                stepsView.text = activity.getString(R.string.scoremate_link_requesting)
                statusView.text = store.server
                val code = try {
                    client.requestDeviceCode(deviceName(activity), Build.MODEL, BuildConfig.VERSION_NAME)
                } catch (e: ScoreMateException) {
                    stepsView.text = e.text.resolve(activity)
                    statusView.text = activity.getString(R.string.scoremate_link_retry_hint, store.server)
                    return@launch
                }
                qrView.setImageBitmap(qrBitmap(code.verificationUriComplete, qrSizePx))
                stepsView.text = activity.getString(R.string.scoremate_link_steps, code.verificationUri.removePrefix("https://"))
                codeView.text = code.userCode

                val expiresAt = SystemClock.elapsedRealtime() + code.expiresInSec * 1000L
                var interval = code.intervalSec
                var note = ""
                while (isActive) {
                    val left = (expiresAt - SystemClock.elapsedRealtime()) / 1000
                    if (left <= 0) break // 새 코드
                    statusView.text = activity.getString(R.string.scoremate_link_waiting, left / 60, left % 60, note)
                    delay(interval * 1000L)
                    val result = client.pollToken(code.deviceCode)
                    interval = ScoreMateProtocol.nextIntervalSec(interval, result)
                    note = ""
                    when (result) {
                        PollResult.Pending, PollResult.SlowDown -> Unit
                        PollResult.Expired, PollResult.Invalid -> break
                        PollResult.Denied -> {
                            stepsView.text = activity.getString(R.string.scoremate_link_denied)
                            statusView.text = activity.getString(R.string.scoremate_link_denied_hint)
                            qrView.setImageBitmap(null)
                            codeView.text = ""
                            return@launch
                        }
                        is PollResult.Failed -> note = activity.getString(R.string.scoremate_link_retrying_note, result.text.resolve(activity))
                        is PollResult.Linked -> {
                            finishLinked()
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private suspend fun finishLinked() {
        statusView.text = activity.getString(R.string.scoremate_link_done)
        val info = try {
            client.heartbeat(BuildConfig.VERSION_NAME, Build.MODEL)
        } catch (e: ScoreMateException) {
            Log.w(TAG, "연결 직후 heartbeat 실패 (무시): ${e.message}")
            null
        }
        store.deviceName = info?.name?.takeIf { it.isNotBlank() } ?: deviceName(activity)
        Log.i(TAG, "ScoreMate 연결됨: device=${store.tokens?.deviceId} name=${store.deviceName}")
        if (dialog.isShowing) dialog.dismiss()
        onLinked()
    }

    companion object {
        private const val TAG = "ScoreMate"
        private const val QR_SIZE_PX = 360

        /** 서버에 보일 기본 이름 — TV 설정의 기기 이름, 없으면 모델명. 휴대폰에서 연결할 때 바꿀 수 있다 */
        fun deviceName(activity: Activity): String =
            Settings.Global.getString(activity.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: Build.MODEL

        fun qrBitmap(text: String, size: Int): Bitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
