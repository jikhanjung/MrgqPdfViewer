package com.mrgq.pdfviewer.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.mrgq.pdfviewer.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 설정 화면의 "업데이트 확인" 흐름 (#054).
 *
 * 확인 → 새 버전 안내 → 다운로드(진행률, 취소 가능) → SHA-256 검증 → 설치 허용 확인 → 시스템 설치 화면.
 * 설치 허용을 켜러 시스템 설정에 다녀오면 [onResume] 에서 이어 간다.
 */
class UpdateController(
    private val activity: Activity,
    private val client: UpdateClient = UpdateClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** 설치 허용을 켜러 시스템 설정으로 보낸 APK — 돌아오면 설치를 이어 간다 */
    private var apkAwaitingPermission: File? = null

    fun checkForUpdate() {
        if (job?.isActive == true) return
        val checking = progressDialog("업데이트 확인", "GitHub 에서 최신 릴리스를 확인하는 중…", indeterminate = true)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { updatesDir(activity).deleteRecursively() }
                val release = client.fetchLatestRelease()
                checking.dialog.dismiss()
                val current = AppVersion.parse(BuildConfig.VERSION_NAME)
                if (current != null && release.version <= current) {
                    showMessage("최신 버전입니다", "현재 v${BuildConfig.VERSION_NAME} 이 최신입니다.")
                } else {
                    showUpdateOffer(release)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checking.dialog.dismiss()
                fail(e)
            }
        }
        checking.dialog.setOnCancelListener { job?.cancel() }
    }

    /**
     * 앱 시작 시 확인 (프로세스당 한 번). 조용히 확인하고 **새 버전이 있을 때만** 대화상자를 띄운다 —
     * 최신이거나 네트워크 오류면 아무것도 보이지 않는다.
     */
    fun checkOnStartup() {
        if (startupCheckDone || job?.isActive == true) return
        startupCheckDone = true
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { updatesDir(activity).deleteRecursively() }
                val release = client.fetchLatestRelease()
                val current = AppVersion.parse(BuildConfig.VERSION_NAME) ?: return@launch
                if (release.version > current && release.apk != null && !activity.isFinishing && !activity.isDestroyed) {
                    showUpdateOffer(release, fromStartup = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "시작 시 업데이트 확인 실패 (무시): ${e.message}")
            }
        }
    }

    /** 액티비티 onResume — 설치 허용 설정에서 돌아왔으면 설치를 이어 간다 */
    fun onResume() {
        val apk = apkAwaitingPermission ?: return
        apkAwaitingPermission = null
        if (activity.packageManager.canRequestPackageInstalls()) {
            launchInstaller(apk)
        } else {
            Toast.makeText(activity, "설치 허용이 꺼져 있어 설치하지 않았습니다", Toast.LENGTH_LONG).show()
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // ── 단계 ────────────────────────────────────────────────────────────────

    private fun showUpdateOffer(release: ReleaseInfo, fromStartup: Boolean = false) {
        val apk = release.apk
        if (apk == null) {
            showMessage("업데이트할 수 없음", "${release.tag} 릴리스에 설치할 APK(${ReleaseInfo.APK_SUFFIX})가 없습니다.")
            return
        }
        val message = buildString {
            append("v${BuildConfig.VERSION_NAME} → ${release.tag}")
            if (apk.size > 0) append("   (${formatMb(apk.size)})")
            append("\n\n")
            if (BuildConfig.DEBUG) {
                // debug 빌드는 서명 키가 달라 release APK 로 업데이트되지 않는다 (#054 §1.3)
                append("⚠️ 지금 앱은 debug 빌드라 서명이 달라 업데이트가 거부됩니다. 기존 앱을 지워야 설치할 수 있습니다.\n\n")
            }
            append(releaseNotesForDisplay(release.body).ifBlank { "(변경 내용 없음)" })
        }
        AlertDialog.Builder(activity)
            .setTitle("새 버전 ${release.tag}")
            .setMessage(message)
            .setPositiveButton("다운로드 및 설치") { _, _ -> download(release) }
            .setNegativeButton("나중에", null)
            .apply {
                if (fromStartup) {
                    setNeutralButton("시작할 때 확인 안 함") { _, _ ->
                        setCheckOnStartup(activity, false)
                        Toast.makeText(activity, "설정 → 앱 정보에서 다시 켤 수 있습니다", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun download(release: ReleaseInfo) {
        val progress = progressDialog("다운로드 중", "${release.apk?.name}", indeterminate = false)
        job = scope.launch {
            try {
                val file = client.downloadApk(release, updatesDir(activity)) { done, total ->
                    activity.runOnUiThread { progress.update(done, total) }
                }
                progress.dialog.dismiss()
                installWithPermission(file)
            } catch (e: CancellationException) {
                Toast.makeText(activity, "다운로드를 취소했습니다", Toast.LENGTH_SHORT).show()
                throw e
            } catch (e: Exception) {
                progress.dialog.dismiss()
                fail(e)
            }
        }
        progress.dialog.setOnCancelListener { job?.cancel() }
    }

    private fun installWithPermission(apk: File) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            launchInstaller(apk)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("설치 허용 필요")
            .setMessage(
                "앱을 업데이트하려면 이 앱에 \"출처를 알 수 없는 앱 설치\"를 허용해야 합니다.\n\n" +
                    "설정 화면에서 허용을 켠 뒤 뒤로 돌아오면 설치를 이어 갑니다."
            )
            .setPositiveButton("설정 열기") { _, _ -> openInstallPermissionSettings(apk) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openInstallPermissionSettings(apk: File) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        try {
            apkAwaitingPermission = apk
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 일부 TV 펌웨어는 이 화면을 막아 둔다
            apkAwaitingPermission = null
            showMessage(
                "설정 화면을 열 수 없음",
                "이 기기는 설치 허용 화면을 바로 열 수 없습니다.\n\n" +
                    "설정 → 앱 → 보안 및 제한사항 → 출처를 알 수 없는 앱 에서 " +
                    "이 앱을 허용한 뒤 다시 업데이트 확인을 누르세요."
            )
        }
    }

    private fun launchInstaller(apk: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(installUriFor(activity, apk), APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showMessage("설치할 수 없음", "이 기기에서 설치 화면을 열 수 없습니다.")
        }
    }

    // ── UI 도우미 ───────────────────────────────────────────────────────────

    private fun fail(e: Exception) {
        Log.w(TAG, "업데이트 실패", e)
        val message = (e as? UpdateException)?.message ?: "알 수 없는 오류: ${e.message}"
        showMessage("업데이트 실패", message)
    }

    private fun showMessage(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private class Progress(val dialog: AlertDialog, private val bar: ProgressBar, private val label: TextView) {
        fun update(done: Long, total: Long) {
            if (total > 0) {
                bar.isIndeterminate = false
                bar.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
                label.text = "${formatMb(done)} / ${formatMb(total)}  (${done * 100 / total}%)"
            } else {
                bar.isIndeterminate = true
                label.text = formatMb(done)
            }
        }
    }

    private fun progressDialog(title: String, message: String, indeterminate: Boolean): Progress {
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val label = TextView(activity).apply { text = message }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = indeterminate
            max = 1000
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            // 버튼이 없으면 리모컨으로는 뒤로만 누를 수 있다 — 취소를 명시한다
            .setNegativeButton("취소") { _, _ -> dialog.cancel() }
            .show()
        return Progress(dialog, bar, label)
    }

    companion object {
        private const val TAG = "UpdateController"
        private const val PREFS = "pdf_viewer_prefs"
        private const val PREF_CHECK_ON_START = "update_check_on_start"

        /** 이 프로세스에서 시작 시 확인을 이미 했나 — 화면을 돌아올 때마다 묻지 않게 */
        private var startupCheckDone = false

        fun isCheckOnStartup(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_CHECK_ON_START, true)

        fun setCheckOnStartup(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_ON_START, enabled).apply()
        }
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** 받은 APK 를 두는 곳 — `res/xml/file_paths.xml` 의 `cache-path updates/` 와 맞춘다 */
        fun updatesDir(context: Context) = File(context.cacheDir, "updates")

        fun installUriFor(context: Context, apk: File): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

        private fun formatMb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    }
}
