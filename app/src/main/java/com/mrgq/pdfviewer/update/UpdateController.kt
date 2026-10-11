package com.mrgq.pdfviewer.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import com.mrgq.pdfviewer.R
import com.mrgq.pdfviewer.utils.DeviceForm
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
    // 업데이트는 ScoreMate 서버 먼저(설정의 서버 주소), 안 되면 GitHub (P15 §2.2, #118)
    // 서버에는 어느 기기인지도 알린다(#120)
    private val client: UpdateClient = UpdateClient(
        server = { com.mrgq.pdfviewer.scoremate.ScoreMateStore(activity).server },
        device = { updateDevice(activity) },
    )
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** 설치 허용을 켜러 시스템 설정으로 보낸 APK — 돌아오면 설치를 이어 간다 */
    private var apkAwaitingPermission: File? = null

    fun checkForUpdate() {
        if (job?.isActive == true) return
        val checking = progressDialog(
            activity.getString(R.string.update_check_title),
            activity.getString(if (isPrereleaseEnabled(activity)) R.string.update_checking_with_prerelease else R.string.update_checking),
            indeterminate = true,
        )
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { updatesDir(activity).deleteRecursively() }
                val release = client.fetchLatestRelease(isPrereleaseEnabled(activity))
                checking.dialog.dismiss()
                val current = AppVersion.parse(BuildConfig.VERSION_NAME)
                if (current != null && release.version <= current) {
                    showMessage(
                        activity.getString(R.string.update_up_to_date_title),
                        activity.getString(R.string.update_up_to_date_message, BuildConfig.VERSION_NAME, sourceLabel()),
                    )
                } else {
                    showUpdateOffer(release.copy(body = client.fetchReleaseNotes(release)))
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
     * 자동 확인 — 파일 목록이 떠 있는 동안 MainActivity 가 자주 부르고, 여기서 **[AUTO_CHECK_INTERVAL_MS] 마다** 한 번만 확인한다
     * (성공 · 실패와 상관없이). 조용히 확인하고 **새 버전이 있을 때만** 대화상자를 띄운다 — 최신이거나 네트워크 오류면 아무것도 보이지 않는다.
     *
     * 예전에는 6시간마다(실패하면 10분 뒤), 그것도 화면으로 돌아올 때만 확인해서 파일 목록에 켜 둔 TV 가 알림을 못 받았다
     * (v0.2.9: 절전에서 깬 직후 네트워크 오류로 한 번 실패한 뒤 그대로). "나중에" 를 누른 버전은 이 프로세스에서 다시 묻지 않는다.
     */
    fun checkIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (!isAutoCheckDue(nextAutoCheckAtMs, now) || job?.isActive == true) return
        // 에뮬레이터(CI)는 자동 확인 안 함 — 운영 서버의 기기 기록에 남지 않게 (#121)
        if (UpdateDevice.isEmulator(Build.HARDWARE, Build.PRODUCT, Build.FINGERPRINT)) return
        nextAutoCheckAtMs = now + AUTO_CHECK_INTERVAL_MS
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { updatesDir(activity).deleteRecursively() }
                val release = client.fetchLatestRelease(isPrereleaseEnabled(activity))
                val current = AppVersion.parse(BuildConfig.VERSION_NAME) ?: return@launch
                val offer = release.version > current && release.apk != null && release.tag != declinedTag &&
                    !activity.isFinishing && !activity.isDestroyed
                Log.i(TAG, "자동 업데이트 확인(${client.lastSource}): 현재 v${BuildConfig.VERSION_NAME}, 최신 ${release.tag} → ${if (offer) "알림" else "알림 없음"}")
                if (offer) {
                    val notes = client.fetchReleaseNotes(release)
                    if (!activity.isFinishing && !activity.isDestroyed) showUpdateOffer(release.copy(body = notes), fromStartup = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "자동 업데이트 확인 실패 (무시, ${AUTO_CHECK_INTERVAL_MS / 60_000}분 뒤 다시): ${e.message}")
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
            Toast.makeText(activity, R.string.update_install_blocked, Toast.LENGTH_LONG).show()
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // ── 단계 ────────────────────────────────────────────────────────────────

    /** 마지막 확인이 어디서 왔나 — 화면용 (P15 2단계 확인, #119). 서버가 안 돼 GitHub 으로 갔으면 그렇게 보인다 */
    private fun sourceLabel(): String = when (client.lastSource) {
        "ScoreMate" -> activity.getString(
            R.string.update_source_scoremate,
            com.mrgq.pdfviewer.scoremate.ScoreMateStore(activity).server.removePrefix("https://"),
        )
        "GitHub" -> "GitHub"
        else -> "?"
    }

    private fun showUpdateOffer(release: ReleaseInfo, fromStartup: Boolean = false) {
        val apk = release.apk
        if (apk == null) {
            showMessage(
                activity.getString(R.string.update_unavailable_title),
                activity.getString(R.string.update_no_apk_message, release.tag, ReleaseInfo.APK_SUFFIX),
            )
            return
        }
        val message = buildString {
            append("v${BuildConfig.VERSION_NAME} → ${release.tag}")
            if (apk.size > 0) append("   (${formatMb(apk.size)})")
            append("\n").append(activity.getString(R.string.update_offer_source, sourceLabel()))
            append("\n\n")
            if (BuildConfig.DEBUG) {
                // debug 빌드는 서명 키가 달라 release APK 로 업데이트되지 않는다 (#054 §1.3)
                append(activity.getString(R.string.update_debug_warning)).append("\n\n")
            }
            // 변경 내용(notes)은 서버 · CHANGELOG 글 그대로 — 번역하지 않는다 (P18 §4.3)
            append(releaseNotesForDisplay(release.body).ifBlank { activity.getString(R.string.update_no_notes) })
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_offer_title, release.tag))
            .setMessage(message)
            .setPositiveButton(R.string.update_download_and_install) { _, _ -> download(release) }
            .setNegativeButton(R.string.update_later) { _, _ -> if (fromStartup) declinedTag = release.tag }
            .apply {
                if (fromStartup) {
                    setNeutralButton(R.string.update_disable_auto) { _, _ ->
                        setCheckOnStartup(activity, false)
                        Toast.makeText(activity, R.string.update_reenable_hint, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun download(release: ReleaseInfo) {
        val progress = progressDialog(activity.getString(R.string.update_downloading_title), "${release.apk?.name}", indeterminate = false)
        job = scope.launch {
            try {
                val file = client.downloadApk(release, updatesDir(activity)) { done, total ->
                    activity.runOnUiThread { progress.update(done, total) }
                }
                progress.dialog.dismiss()
                installWithPermission(file)
            } catch (e: CancellationException) {
                Toast.makeText(activity, R.string.update_download_canceled, Toast.LENGTH_SHORT).show()
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
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setPositiveButton(R.string.update_open_settings) { _, _ -> openInstallPermissionSettings(apk) }
            .setNegativeButton(R.string.cancel, null)
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
                activity.getString(R.string.update_cannot_open_settings_title),
                activity.getString(R.string.update_cannot_open_settings_message),
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
            showMessage(activity.getString(R.string.update_cannot_install_title), activity.getString(R.string.update_cannot_install_message))
        }
    }

    // ── UI 도우미 ───────────────────────────────────────────────────────────

    private fun fail(e: Exception) {
        Log.w(TAG, "업데이트 실패", e)
        val message = (e as? UpdateException)?.text?.resolve(activity)
            ?: activity.getString(R.string.update_unknown_error, e.message.toString())
        showMessage(activity.getString(R.string.update_failed_title), message)
    }

    private fun showMessage(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
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
            .setNegativeButton(R.string.cancel) { _, _ -> dialog.cancel() }
            .show()
        return Progress(dialog, bar, label)
    }

    companion object {
        private const val TAG = "UpdateController"
        private const val PREFS = "pdf_viewer_prefs"
        private const val PREF_CHECK_ON_START = "update_check_on_start"
        /** 사전 릴리스(-alpha · -beta · -rc)도 받기 — 기본 꺼짐. 끄면 더 높은 정식이 나올 때까지 지금 판 그대로(내려가지 않는다) */
        private const val PREF_PRERELEASE = "update_include_prerelease"

        /** 다음 자동 확인 시각 (elapsedRealtime). 0 = 아직 안 함 — 프로세스 안에서 화면이 바뀌어도 유지된다 */
        private var nextAutoCheckAtMs = 0L

        /** 자동 확인 간격 — 성공 · 실패와 상관없이. GitHub 비인증 한도(IP 당 시간 60회)에 TV 여러 대가 같은 공유기여도 넉넉하다 */
        const val AUTO_CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /** 자동 알림에서 "나중에" 를 누른 릴리스 — 이 프로세스에서는 다시 자동으로 묻지 않는다(설정의 업데이트 확인은 그대로) */
        private var declinedTag: String? = null

        /** 자동 확인할 때가 됐나. [nextAtMs] 0 은 아직 한 번도 안 함 */
        internal fun isAutoCheckDue(nextAtMs: Long, nowMs: Long): Boolean = nextAtMs == 0L || nowMs >= nextAtMs

        fun isCheckOnStartup(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_CHECK_ON_START, true)

        fun setCheckOnStartup(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_CHECK_ON_START, enabled).apply()
        }
        fun isPrereleaseEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_PRERELEASE, false)

        fun setPrereleaseEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_PRERELEASE, enabled).apply()
        }

        /** 서버에 알릴 이 기기 (#120) */
        fun updateDevice(context: Context): UpdateDevice {
            val store = com.mrgq.pdfviewer.scoremate.ScoreMateStore(context)
            val form = when {
                DeviceForm.isTv(context) -> "tv"
                DeviceForm.isPhone(context) -> "phone"
                else -> "tablet"
            }
            return UpdateDevice(
                appVersion = BuildConfig.VERSION_NAME,
                form = form,
                model = UpdateDevice.modelName(Build.MANUFACTURER, Build.MODEL),
                android = Build.VERSION.RELEASE,
                installId = store.installId,
                scoreMateDeviceId = store.tokens?.deviceId,
            )
        }

        private const val APK_MIME = "application/vnd.android.package-archive"

        /** 받은 APK 를 두는 곳 — `res/xml/file_paths.xml` 의 `cache-path updates/` 와 맞춘다 */
        fun updatesDir(context: Context) = File(context.cacheDir, "updates")

        fun installUriFor(context: Context, apk: File): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

        private fun formatMb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    }
}
