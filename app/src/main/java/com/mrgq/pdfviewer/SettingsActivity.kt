package com.mrgq.pdfviewer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.databinding.ActivitySettingsNewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.mrgq.pdfviewer.server.WebServerManager
import com.mrgq.pdfviewer.repository.MusicRepository
import com.mrgq.pdfviewer.database.entity.DisplayMode
import com.mrgq.pdfviewer.update.UpdateController
import com.mrgq.pdfviewer.scoremate.DeviceLinkDialog
import com.mrgq.pdfviewer.scoremate.ScoreMateClient
import com.mrgq.pdfviewer.scoremate.ScoreMateException
import com.mrgq.pdfviewer.scoremate.ScoreMateProtocol
import com.mrgq.pdfviewer.scoremate.ScoreMateStore
import androidx.lifecycle.lifecycleScope

class SettingsActivity : AppCompatActivity() {
    /** 화면 문구의 기기 이름 (TV · 태블릿 · 휴대폰) */
    private val device by lazy { com.mrgq.pdfviewer.utils.DeviceForm.noun(this) }
    
    private lateinit var binding: ActivitySettingsNewBinding
    private lateinit var preferences: SharedPreferences
    
    // Web server manager (singleton)
    private val webServerManager = WebServerManager.getInstance()
    private var isWebServerRunning = false
    
    // Database repository
    private lateinit var musicRepository: MusicRepository
    
    // Settings adapter
    private lateinit var settingsAdapter: SettingsAdapter
    private var currentItems = mutableListOf<SettingsItem>()
    
    // Web server log management
    private val webServerLogs = mutableListOf<String>()
    private var isWebServerLogVisible = false

    // 앱 안 업데이트 (devlog #054)
    private lateinit var updateController: UpdateController

    // ScoreMate 서버 연결 (P05 C1)
    private val scoreMateStore by lazy { ScoreMateStore(this) }
    private val scoreMateClient by lazy { ScoreMateClient(scoreMateStore) }
    private val scoreMateLocal by lazy { com.mrgq.pdfviewer.repository.ScoreMateLocal(this) }
    private val scoreMateSync by lazy {
        com.mrgq.pdfviewer.scoremate.ScoreMateSync(
            scoreMateClient, scoreMateStore, scoreMateLocal, scoreMateLocal, File(getExternalFilesDir(null), "PDFs"),
        )
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mrgq.pdfviewer.utils.DeviceForm.applyOrientation(this) // TV 가 아니면 세로가 기본
        binding = ActivitySettingsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        // Initialize database repository
        musicRepository = MusicRepository(this)
        updateController = UpdateController(this)
        
        setupUI()
        checkWebServerStatus()
        setupMainMenu()
    }
    
    private fun setupUI() {
        // RecyclerView 설정
        binding.settingsRecyclerView.layoutManager = LinearLayoutManager(this)
        
        // 뒤로 가기 버튼
        binding.backButton.setOnClickListener {
            if (binding.detailPanelLayout.visibility == View.VISIBLE) {
                hideDetailPanel()
            } else {
                handleBackPress()
            }
        }
        
        // 상세 패널 버튼들
        binding.cancelButton.setOnClickListener {
            hideDetailPanel()
        }
        
        binding.applyButton.setOnClickListener {
            // 현재 표시된 상세 패널에 따라 적용 로직 실행
            hideDetailPanel()
        }
        
        // 로그 지우기 버튼
        binding.clearLogButton.setOnClickListener {
            clearWebServerLog()
        }
    }
    
    private fun setupMainMenu() {
        currentItems.clear()
        
        // 웹서버 상태를 메뉴 생성 전에 다시 확인
        checkWebServerStatus()
        
        // 파일 관리 섹션
        val pdfCount = getAllPdfFiles().size
        currentItems.add(SettingsItem(
            id = "file_management",
            icon = "📂",
            title = "파일 관리",
            subtitle = "저장된 PDF 파일: ${pdfCount}개",
            arrow = "▶"
        ))
        
        // 웹서버 섹션 — ScoreMate 에 연결된 TV 에서는 쓰지 않는다 (#063)
        val webStatus = if (scoreMateClient.isLinked) {
            "ScoreMate 연결 중에는 쓸 수 없음"
        } else if (isWebServerRunning) {
            val port = preferences.getInt("web_server_port", 8080)
            val ipAddress = NetworkUtils.getLocalIpAddress()
            "실행 중 ($ipAddress:$port)"
        } else {
            "중지됨"
        }
        currentItems.add(SettingsItem(
            id = "web_server",
            icon = "🌐",
            title = "웹서버",
            subtitle = webStatus,
            arrow = "▶"
        ))
        
        // 협업 모드 섹션
        currentItems.add(SettingsItem(
            id = "collaboration",
            icon = "🎼",
            title = "협업 모드",
            subtitle = "합주 설정 관리",
            arrow = "▶"
        ))
        
        // ScoreMate 섹션 (P05 C1)
        currentItems.add(SettingsItem(
            id = "scoremate",
            icon = "☁️",
            title = "ScoreMate",
            subtitle = scoreMateSummary(),
            arrow = "▶"
        ))

        // 애니메이션/사운드 섹션
        currentItems.add(SettingsItem(
            id = "animation_sound",
            icon = "🎵",
            title = "애니메이션 & 사운드",
            subtitle = "페이지 전환 효과 설정",
            arrow = "▶"
        ))
        
        // 🎤 녹음 기록 (P10) — 태블릿에서만 (TV 는 녹음하지 않는다 — 연주 듣고 넘기기도 태블릿 전용)
        if (!com.mrgq.pdfviewer.utils.DeviceForm.isTv(this) &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
        ) {
            val (recCount, recBytes) = recordingsSummary()
            currentItems.add(SettingsItem(
                id = "recordings",
                icon = "🎤",
                title = "녹음 기록",
                subtitle = if (recCount == 0) "없음" else "${recCount}개 · ${megabytes(recBytes)}",
                arrow = "▶"
            ))
        }

        // 표시 모드 섹션 (비동기로 카운트 로드)
        CoroutineScope(Dispatchers.IO).launch {
            val displayModeCount = try {
                musicRepository.getUserPreferenceCount()
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error loading display mode count", e)
                0
            }
            
            withContext(Dispatchers.Main) {
                // 중복 방지를 위해 기존 항목이 있는지 확인
                if (currentItems.none { it.id == "display_mode" }) {
                    currentItems.add(SettingsItem(
                        id = "display_mode",
                        icon = "🔧",
                        title = "표시 모드",
                        subtitle = "저장된 설정: ${displayModeCount}개",
                        arrow = "▶"
                    ))
                }
                
                // 정보 섹션도 중복 확인
                if (currentItems.none { it.id == "info" }) {
                    currentItems.add(SettingsItem(
                        id = "info",
                        icon = "📊",
                        title = "앱 정보",
                        subtitle = "v${BuildConfig.VERSION_NAME}",
                        arrow = "▶"
                    ))
                }
                
                updateAdapter()
            }
        }
    }
    
    private fun updateAdapter() {
        settingsAdapter = SettingsAdapter(currentItems) { item ->
            handleItemClick(item)
        }
        binding.settingsRecyclerView.adapter = settingsAdapter
    }
    
    private fun handleItemClick(item: SettingsItem) {
        // 웹서버 설정 화면에서 다른 메뉴로 이동할 때 웹서버가 실행 중이면 확인
        if (binding.detailPanelLayout.visibility == View.VISIBLE && 
            binding.detailTitle.text == "웹서버" && 
            isWebServerRunning && 
            item.id != "web_server") {
            showWebServerDetailExitConfirmDialog {
                // 확인 후 다음 메뉴로 이동
                when (item.id) {
                    "file_management" -> showFileManagementPanel()
                    "collaboration" -> showCollaborationPanel()
                    "scoremate" -> showScoreMatePanel()
                    "animation_sound" -> showAnimationSoundPanel()
                    "recordings" -> showRecordingsPanel()
                    "display_mode" -> showDisplayModePanel()
                    "info" -> showInfoPanel()
                }
            }
        } else {
            when (item.id) {
                "file_management" -> showFileManagementPanel()
                "web_server" -> showWebServerPanel()
                "collaboration" -> showCollaborationPanel()
                "scoremate" -> showScoreMatePanel()
                "animation_sound" -> showAnimationSoundPanel()
                "recordings" -> showRecordingsPanel()
                "display_mode" -> showDisplayModePanel()
                "info" -> showInfoPanel()
            }
        }
    }
    
    private fun showFileManagementPanel() {
        val items = listOf(
            SettingsItem(
                id = "delete_all_pdf",
                icon = "🗑️",
                title = "모든 PDF 파일 삭제",
                subtitle = "저장된 모든 PDF 파일을 삭제합니다",
                type = SettingsType.ACTION
            )
        )
        
        showDetailPanel("파일 관리", items)
    }
    
    private fun showWebServerPanel() {
        // ScoreMate 에 연결된 TV 는 서재가 ScoreMate 악보뿐이라 웹서버로 올린 파일이 보이지 않는다 → 쓰지 않는다 (#063, 사용자 결정)
        if (scoreMateClient.isLinked) {
            showDetailPanel("웹서버", listOf(
                SettingsItem(
                    id = "web_server_disabled",
                    icon = "☁️",
                    title = "ScoreMate 에 연결된 TV 에서는 쓸 수 없습니다",
                    subtitle = "악보는 ScoreMate 웹에 올리고 동기화하세요. 웹서버를 쓰려면 설정 → ScoreMate 에서 연결을 해제하세요",
                    type = SettingsType.INFO
                )
            ))
            return
        }
        val port = preferences.getInt("web_server_port", 8080)
        val ipAddress = NetworkUtils.getLocalIpAddress()
        val status = if (isWebServerRunning) "실행 중 ($ipAddress:$port)" else "중지됨"
        
        val items = mutableListOf(
            SettingsItem(
                id = "web_server_toggle",
                icon = if (isWebServerRunning) "⏹️" else "▶️",
                title = if (isWebServerRunning) "웹서버 중지" else "웹서버 시작",
                subtitle = "현재 상태: $status",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "web_server_port",
                icon = "🔧",
                title = "포트 설정",
                subtitle = "현재 포트: $port",
                type = SettingsType.INPUT
            )
        )
        
        // 웹서버가 실행 중이면 로그 정보 아이템 추가
        if (isWebServerRunning) {
            items.add(
                SettingsItem(
                    id = "web_server_log_info",
                    icon = "🌐",
                    title = "활동 로그",
                    subtitle = "실시간 웹서버 활동이 아래에 표시됩니다",
                    type = SettingsType.INFO
                )
            )
        }
        
        showDetailPanel("웹서버", items)
        
        // 웹서버가 실행 중이면 로그 섹션 표시
        if (isWebServerRunning) {
            showWebServerLogSection()
        }
    }
    
    private fun showCollaborationPanel() {
        val inputBlockTime = preferences.getLong("input_block_duration", 500L)
        val syncEnabled = preferences.getBoolean("sync_page_turn_enabled", false)
        val syncLeadMs = preferences.getLong("sync_turn_lead_ms", 2000L)

        val items = listOf(
            SettingsItem(
                id = "collaboration_info",
                icon = "ℹ️",
                title = "협업 모드 정보",
                subtitle = "메인 화면에서 협업 모드를 시작할 수 있습니다",
                type = SettingsType.INFO
            ),
            SettingsItem(
                id = "ensemble_sound_toggle",
                icon = "🥁",
                title = "합주 메트로놈 소리 (연주자)",
                subtitle = if (preferences.getBoolean(PdfViewerActivity.PREF_ENSEMBLE_SOUND, false)) {
                    "켜짐 — 연주자 기기도 지휘자 메트로놈에 맞춰 소리를 냅니다"
                } else {
                    "꺼짐 — 연주자 기기는 박 표시 · 현재 마디 · 넘김만 따라갑니다"
                },
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "sync_turn_toggle",
                icon = "🎯",
                title = "동기 페이지 넘김",
                subtitle = if (syncEnabled) "현재: ON · 예약 후 모든 기기 동시 넘김" else "현재: OFF · 신호 즉시 넘김(기존)",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "sync_turn_lead",
                icon = "⏲️",
                title = "예약 시간",
                subtitle = "현재: ${syncLeadMs}ms · 누른 뒤 넘기까지 여유",
                type = SettingsType.ACTION,
                enabled = syncEnabled
            ),
            SettingsItem(
                id = "input_block_time",
                icon = "⏱️",
                title = "입력 차단 시간",
                subtitle = "현재: ${inputBlockTime}ms",
                type = SettingsType.ACTION
            ),
            SettingsItem(
                id = "message_queue_stats",
                icon = "📊",
                title = "메시지 큐 통계",
                subtitle = "비활성화됨 (성능 최적화)",
                type = SettingsType.INFO
            )
        )
        
        showDetailPanel("협업 모드", items)
    }
    
    // ── ScoreMate (P05 C1) ──────────────────────────────────────────────────

    private fun scoreMateSummary(): String =
        if (scoreMateClient.isLinked) "연결됨 — ${scoreMateStore.deviceName ?: "이 $device"}" else "연결 안 됨 — 악보 서버에 이 $device 연결"

    private fun showScoreMatePanel() {
        val linked = scoreMateClient.isLinked
        val server = scoreMateStore.server.removePrefix("https://")
        val items = mutableListOf(
            SettingsItem(
                id = "scoremate_status",
                icon = if (linked) "✅" else "⚪",
                title = if (linked) "연결됨" else "연결 안 됨",
                subtitle = if (linked) "${scoreMateStore.deviceName ?: "이 $device"} · $server" else "휴대폰으로 QR 을 찍어 ScoreMate 계정에 이 $device 를 연결합니다",
                type = SettingsType.INFO
            )
        )
        if (linked) {
            items += SettingsItem(
                id = "scoremate_check",
                icon = "🔍",
                title = "연결 확인",
                subtitle = "서버에서 이 $device 정보 받기",
                type = SettingsType.ACTION
            )
            items += SettingsItem(
                id = "scoremate_unlink",
                icon = "⛔",
                title = "연결 해제",
                subtitle = "이 $device 의 ScoreMate 연결을 끊습니다",
                type = SettingsType.ACTION
            )
        } else {
            items += SettingsItem(
                id = "scoremate_link",
                icon = "🔗",
                title = "이 $device 연결",
                subtitle = "화면의 QR 코드 · 연결 코드로 휴대폰에서 연결",
                type = SettingsType.ACTION
            )
            items += SettingsItem(
                id = "scoremate_server",
                icon = "🌐",
                title = "서버 주소",
                subtitle = server,
                type = SettingsType.INPUT
            )
        }
        if (linked) {
            items += SettingsItem(
                id = "scoremate_sync",
                icon = "📥",
                title = "지금 동기화",
                subtitle = "앙상블 · 내 악보를 받습니다 (앱을 켤 때도 저절로). 받은 악보는 파일 목록에 ☁️",
                type = SettingsType.ACTION
            )
            items += SettingsItem(
                id = "scoremate_unhide",
                icon = "♻️",
                title = "숨긴 악보 다시 받기",
                subtitle = "TV 에서 지운 서버 악보를 다음 동기화에 다시 받습니다",
                type = SettingsType.ACTION
            )
        }
        showDetailPanel("ScoreMate", items)
    }

    private fun linkScoreMate() {
        DeviceLinkDialog(this, scoreMateClient, scoreMateStore) {
            // 연결되면 서재가 ScoreMate 악보로 바뀌고 웹서버는 쓰지 않는다 (#063)
            if (isWebServerRunning) stopWebServer()
            Toast.makeText(this, "ScoreMate 에 연결되었습니다 — 파일 목록에는 ScoreMate 악보가 보입니다", Toast.LENGTH_LONG).show()
            setupMainMenu() // 뒤의 메인 메뉴 요약도
            showScoreMatePanel()
        }.show()
    }

    private fun checkScoreMate() {
        lifecycleScope.launch {
            try {
                val info = scoreMateClient.me()
                if (info.name.isNotBlank()) scoreMateStore.deviceName = info.name
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("ScoreMate 연결 확인")
                    .setMessage("이 $device: ${info.name}\n서버: ${scoreMateStore.server}\n마지막 접속: ${info.lastSeenAt ?: "-"}")
                    .setPositiveButton("확인", null)
                    .show()
            } catch (e: ScoreMateException) {
                Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show()
            }
            showScoreMatePanel()
        }
    }

    private fun syncScoreMateNow() {
        Toast.makeText(this, "ScoreMate 동기화 중…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val report = try {
                scoreMateSync.sync()
            } catch (e: ScoreMateException) {
                Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show()
                showScoreMatePanel()
                return@launch
            }
            Toast.makeText(
                this@SettingsActivity,
                com.mrgq.pdfviewer.scoremate.ScoreMateSyncText.summary(report) ?: "ScoreMate: 바뀐 악보가 없습니다",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun unhideScoreMate() {
        lifecycleScope.launch {
            val count = scoreMateSync.unhideAll()
            if (count == 0) {
                Toast.makeText(this@SettingsActivity, "숨긴 악보가 없습니다", Toast.LENGTH_SHORT).show()
            } else {
                syncScoreMateNow()
            }
        }
    }

    private fun confirmUnlinkScoreMate() {
        AlertDialog.Builder(this)
            .setTitle("ScoreMate 연결 해제")
            .setMessage(
                "이 $device 의 ScoreMate 연결을 끊습니다. 다시 쓰려면 휴대폰으로 다시 연결해야 합니다.\n\n" +
                    "받아 둔 악보는 어떻게 할까요? 남기면 이 기기 파일로 옮겨져 연결 해제 뒤의 파일 목록에 보입니다."
            )
            .setPositiveButton("해제 · 악보 남기기") { _, _ -> unlinkScoreMate(deleteFiles = false) }
            .setNeutralButton("해제 · 악보 지우기") { _, _ -> unlinkScoreMate(deleteFiles = true) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun unlinkScoreMate(deleteFiles: Boolean) {
        lifecycleScope.launch {
            scoreMateSync.forgetAll(deleteFiles)
            val notified = scoreMateClient.unlink()
            Toast.makeText(
                this@SettingsActivity,
                if (notified) "ScoreMate 연결을 해제했습니다" else "이 $device 에서 연결을 해제했습니다 (서버에 닿지 못함 — 웹의 TV 화면에서도 해제하세요)",
                Toast.LENGTH_LONG
            ).show()
            setupMainMenu()
            showScoreMatePanel()
        }
    }

    private fun showScoreMateServerDialog() {
        val editText = EditText(this).apply {
            setText(scoreMateStore.server)
            hint = ScoreMateProtocol.DEFAULT_SERVER
        }
        AlertDialog.Builder(this)
            .setTitle("ScoreMate 서버 주소")
            .setMessage("보통은 바꿀 필요가 없습니다 (개발 서버용). 비우면 기본값.")
            .setView(editText)
            .setPositiveButton("저장") { _, _ ->
                val input = editText.text.toString()
                val server = if (input.isBlank()) ScoreMateProtocol.DEFAULT_SERVER else ScoreMateProtocol.normalizeServer(input)
                if (server == null) {
                    Toast.makeText(this, "서버 주소를 해석할 수 없습니다", Toast.LENGTH_SHORT).show()
                } else {
                    scoreMateStore.server = server
                    showScoreMatePanel()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAnimationSoundPanel() {
        val animationEnabled = preferences.getBoolean("page_turn_animation_enabled", true)
        val soundEnabled = preferences.getBoolean("page_turn_sound_enabled", true)
        val volume = preferences.getFloat("page_turn_volume", 0.25f)
        val showPageInfo = preferences.getBoolean("show_page_info", true)
        
        val items = listOf(
            SettingsItem(
                id = "animation_toggle",
                icon = "🎬",
                title = "페이지 전환 애니메이션",
                subtitle = if (animationEnabled) "활성화됨" else "비활성화됨",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "sound_toggle",
                icon = "🔊",
                title = "페이지 넘기기 사운드",
                subtitle = if (soundEnabled) "활성화됨" else "비활성화됨",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "volume_setting",
                icon = "🎚️",
                title = "사운드 볼륨",
                subtitle = "${(volume * 100).toInt()}%",
                type = SettingsType.INPUT,
                enabled = soundEnabled
            ),
            SettingsItem(
                id = "page_info_toggle",
                icon = "📄",
                title = getString(R.string.settings_show_page_info),
                subtitle = if (showPageInfo) "표시함" else "숨김",
                type = SettingsType.TOGGLE
            )
        )
        
        showDetailPanel("애니메이션 & 사운드", items)
    }
    
    private fun showDisplayModePanel() {
        val items = listOf(
            SettingsItem(
                id = "view_display_modes",
                icon = "👁️",
                title = "설정 목록 보기",
                subtitle = "저장된 파일별 표시 모드를 확인합니다",
                type = SettingsType.ACTION
            ),
            SettingsItem(
                id = "reset_display_modes",
                icon = "🔄",
                title = "설정 초기화",
                subtitle = "모든 파일의 표시 모드를 초기화합니다",
                type = SettingsType.ACTION
            )
        )
        
        showDetailPanel("표시 모드", items)
    }
    
    private fun showInfoPanel() {
        val items = listOfNotNull(
            SettingsItem(
                id = "app_version",
                icon = "📱",
                title = "앱 버전",
                subtitle = "v${BuildConfig.VERSION_NAME}",
                type = SettingsType.INFO
            ),
            SettingsItem(
                id = "update_on_start_toggle",
                icon = "🔔",
                title = "자동 업데이트 확인",
                subtitle = if (UpdateController.isCheckOnStartup(this)) "켜짐 — 파일 목록에서 10분마다 확인해 새 버전이 있으면 알려 줍니다" else "꺼짐",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "update_prerelease_toggle",
                icon = "🧪",
                title = "사전 릴리스(beta) 받기",
                subtitle = if (UpdateController.isPrereleaseEnabled(this)) "켜짐 — 시험 중인 판도 받습니다. 확인 전 기능이 포함될 수 있습니다"
                    else "꺼짐 — 정식 릴리스만 받습니다",
                type = SettingsType.TOGGLE
            ),
            // 🎙 음성 명령 (P12 2단계) — 태블릿 · 휴대폰. 기기의 음성 인식(보통 Google)을 쓴다
            SettingsItem(
                id = "voice_commands_toggle",
                icon = "🎙",
                title = "음성 명령 (시험)",
                subtitle = when {
                    !com.mrgq.pdfviewer.voice.VoiceListener.isAvailable(this) -> "이 기기에는 음성 인식 서비스가 없습니다 (Google 앱 등)"
                    preferences.getBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, false) ->
                        "켜짐 — 악보 화면 오른쪽 아래 🎙 를 누른 채로 \"57마디부터 템포 72\" · \"다음 쪽\" · \"첼로 파트\". 기기의 음성 인식(대개 인터넷)을 씁니다"
                    else -> "꺼짐 — 켜면 악보 화면에 🎙 단추가 생깁니다"
                },
                type = SettingsType.TOGGLE
            ).takeIf { !com.mrgq.pdfviewer.utils.DeviceForm.isTv(this) },
            // 👂 계속 듣기 (#085) — 🎙 를 누르지 않고 호출어 "메이트" 뒤의 말을 명령으로
            SettingsItem(
                id = "voice_always_toggle",
                icon = "👂",
                title = "계속 듣기 (시험)",
                subtitle = when {
                    !preferences.getBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, false) -> "위의 🎙 음성 명령을 먼저 켜세요"
                    preferences.getBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, false) ->
                        "켜짐 — 악보 화면에서 늘 듣다가 \"메이트\" 뒤의 말만 실행(\"메이트, 57마디부터\" · \"Mate, 1페이지 시작\" · \"메이트, 멈춰\"). " +
                            "연주 중에도 듣습니다. 듣는 소리는 계속 기기의 음성 인식(대개 Google, 인터넷)으로 갑니다"
                    else -> "꺼짐 — 켜면 🎙 를 누르지 않고 \"메이트, …\"로 명령합니다"
                },
                type = SettingsType.TOGGLE
            ).takeIf { !com.mrgq.pdfviewer.utils.DeviceForm.isTv(this) },
            SettingsItem(
                id = "voice_text_input_toggle",
                icon = "⌨️",
                title = "글자로 명령 시험",
                subtitle = if (preferences.getBoolean(PdfViewerActivity.PREF_VOICE_TEXT_INPUT, false))
                    "켜짐 — 악보 화면 메트로놈 메뉴에 ⌨️ 글자로 명령(\"57마디부터 템포 72\") — 음성 명령 준비용"
                    else "꺼짐 — 음성 명령(준비 중)의 규칙을 글자로 시험해 보는 창",
                type = SettingsType.TOGGLE
            ),
            SettingsItem(
                id = "check_update",
                icon = "🔄",
                title = "업데이트 확인",
                subtitle = "GitHub 에서 새 버전 찾기",
                type = SettingsType.ACTION
            ),
            SettingsItem(
                id = "app_info",
                icon = "ℹ️",
                title = "앱 정보",
                subtitle = "MRGQ PDF Viewer — TV · 태블릿 · 휴대폰을 위한 스마트 악보 리더",
                type = SettingsType.INFO
            )
        )
        
        showDetailPanel("앱 정보", items)
    }
    
    // ── 🎤 녹음 기록 (P10 — 마이크 추적 기록 · 예전 연습 녹음) ─────────────────────────
    private var recordingEntries: List<com.mrgq.pdfviewer.recording.RecordingLibrary.Entry> = emptyList()
    private var recordingPlayer: android.media.MediaPlayer? = null

    private fun recordingsSummary(): Pair<Int, Long> {
        val list = com.mrgq.pdfviewer.recording.RecordingLibrary.dir(this).listFiles()?.filter { it.isFile } ?: emptyList()
        return list.count { it.name.endsWith(".wav") } to list.sumOf { it.length() }
    }

    private fun megabytes(bytes: Long) = String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)

    private fun clock(sec: Double): String {
        val s = sec.toInt()
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun showRecordingsPanel() {
        stopRecordingPlayback()
        val dir = com.mrgq.pdfviewer.recording.RecordingLibrary.dir(this)
        recordingEntries = com.mrgq.pdfviewer.recording.RecordingLibrary.list(dir)
        val items = mutableListOf(
            SettingsItem(
                id = "rec_folder",
                icon = "📁",
                title = "저장 위치",
                subtitle = "내부 저장소/Android/data/$packageName/files/recordings · ${recordingEntries.size}개 · ${megabytes(recordingEntries.sumOf { it.bytes })}",
                type = SettingsType.INFO
            )
        )
        if (recordingEntries.isEmpty()) {
            items += SettingsItem(
                id = "rec_empty",
                icon = "ℹ️",
                title = "녹음이 없습니다",
                subtitle = "악보 화면 ↑ 메뉴의 🎤 연주 듣고 넘기기가 소리와 추적 기록을 남깁니다",
                type = SettingsType.INFO
            )
        }
        val dateFormat = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA)
        recordingEntries.forEachIndexed { i, e ->
            val when_ = e.recordedAt?.let { dateFormat.format(it) } ?: ""
            val s = e.summary
            val detail = buildList {
                add(clock(e.durationSec))
                add(megabytes(e.bytes))
                if (s != null && e.isFollow) {
                    s.startMeasurePos?.let { add("${it.toInt() + 1}마디부터") }
                    if (s.pageTurns > 0) add("넘김 ${s.pageTurns}번")
                    s.lastPage?.let { add("${it + 1}쪽까지") }
                }
                if (!e.isFollow) add("연습 녹음")
            }.joinToString(" · ")
            items += SettingsItem(
                id = "rec:$i",
                icon = if (e.isFollow) "🎤" else "🎙️",
                title = "${e.title}  $when_",
                subtitle = detail,
                arrow = "▶",
                type = SettingsType.ACTION
            )
        }
        if (recordingEntries.isNotEmpty()) {
            items += SettingsItem(
                id = "rec_delete_all",
                icon = "🗑️",
                title = "모두 지우기",
                subtitle = "녹음 ${recordingEntries.size}개 (${megabytes(recordingEntries.sumOf { it.bytes })}) 를 지웁니다",
                type = SettingsType.ACTION
            )
        }
        showDetailPanel("녹음 기록", items)
    }

    private fun showRecordingDialog(entry: com.mrgq.pdfviewer.recording.RecordingLibrary.Entry) {
        val s = entry.summary
        val lines = buildList {
            entry.recordedAt?.let { add("녹음: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA).format(it)) }
            add("길이: ${clock(entry.durationSec)} · ${megabytes(entry.bytes)}")
            if (s != null && entry.isFollow) {
                s.quarterBpm?.let { add("기준 빠르기: ♩=$it") }
                if (s.startSec != null) add("연주 시작: ${clock(s.startSec)} — ${s.startMeasurePos?.let { "${it.toInt() + 1}마디" } ?: "?"}")
                add("쪽 넘김 ${s.pageTurns}번 · 반 쪽 ${s.halfTurns}번" + (s.lastPage?.let { " · ${it + 1}쪽까지" } ?: ""))
                s.lastMeasurePos?.let { add("마지막 위치: ${it.toInt() + 1}마디") }
                add("재위치 ${s.relocations}번" + if (s.manualAnchors > 0) " · 손으로 넘김 ${s.manualAnchors}번" else "")
            }
            add("")
            add(entry.wav.name)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("▶ 재생", null)
            .setNeutralButton("📤 공유", null)
            .setNegativeButton("🗑️ 지우기", null)
            .setOnDismissListener { stopRecordingPlayback() }
            .create()
        dialog.setOnShowListener {
            val play = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            play.setOnClickListener {
                if (recordingPlayer != null) {
                    stopRecordingPlayback()
                    play.text = "▶ 재생"
                } else if (startRecordingPlayback(entry) { play.text = "▶ 재생" }) {
                    play.text = "■ 정지"
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { shareRecording(entry) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage("이 녹음을 지울까요?\n${entry.wav.name}")
                    .setPositiveButton("지우기") { _, _ ->
                        dialog.dismiss()
                        com.mrgq.pdfviewer.recording.RecordingLibrary.delete(entry)
                        Toast.makeText(this, "지웠습니다", Toast.LENGTH_SHORT).show()
                        showRecordingsPanel()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun startRecordingPlayback(entry: com.mrgq.pdfviewer.recording.RecordingLibrary.Entry, onEnd: () -> Unit): Boolean {
        stopRecordingPlayback()
        return try {
            recordingPlayer = android.media.MediaPlayer().apply {
                setDataSource(entry.wav.path)
                setOnCompletionListener {
                    stopRecordingPlayback()
                    onEnd()
                }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.w("SettingsActivity", "녹음 재생 실패", e)
            Toast.makeText(this, "재생할 수 없습니다", Toast.LENGTH_SHORT).show()
            stopRecordingPlayback()
            false
        }
    }

    private fun stopRecordingPlayback() {
        recordingPlayer?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // 이미 멈춤
            }
            it.release()
        }
        recordingPlayer = null
    }

    /** 안드로이드 공유 창으로 WAV (+ JSON) — 휴대폰 · PC 로 옮길 때 */
    private fun shareRecording(entry: com.mrgq.pdfviewer.recording.RecordingLibrary.Entry) {
        try {
            val authority = "$packageName.fileprovider"
            val uris = ArrayList<android.net.Uri>()
            uris += androidx.core.content.FileProvider.getUriForFile(this, authority, entry.wav)
            entry.json?.let { uris += androidx.core.content.FileProvider.getUriForFile(this, authority, it) }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                putExtra(android.content.Intent.EXTRA_SUBJECT, entry.wav.nameWithoutExtension)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "녹음 보내기"))
        } catch (e: Exception) {
            Log.w("SettingsActivity", "녹음 공유 실패", e)
            Toast.makeText(this, "공유할 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteAllRecordings() {
        val total = recordingEntries.sumOf { it.bytes }
        AlertDialog.Builder(this)
            .setTitle("녹음 모두 지우기")
            .setMessage("녹음 ${recordingEntries.size}개 (${megabytes(total)}) 를 모두 지웁니다. 되돌릴 수 없습니다.")
            .setPositiveButton("모두 지우기") { _, _ ->
                stopRecordingPlayback()
                recordingEntries.forEach { com.mrgq.pdfviewer.recording.RecordingLibrary.delete(it) }
                Toast.makeText(this, "모두 지웠습니다", Toast.LENGTH_SHORT).show()
                showRecordingsPanel()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDetailPanel(title: String, items: List<SettingsItem>) {
        binding.detailTitle.text = title
        
        val detailAdapter = SettingsAdapter(items) { item ->
            handleDetailItemClick(item)
        }
        binding.detailRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.detailRecyclerView.adapter = detailAdapter
        
        binding.settingsRecyclerView.visibility = View.GONE
        binding.detailPanelLayout.visibility = View.VISIBLE
    }
    
    private fun hideDetailPanel() {
        // 웹서버 설정 화면에서 벗어날 때 웹서버가 실행 중이면 확인
        if (binding.detailTitle.text == "웹서버" && isWebServerRunning) {
            showWebServerDetailExitConfirmDialog()
        } else {
            binding.detailPanelLayout.visibility = View.GONE
            binding.settingsRecyclerView.visibility = View.VISIBLE
            // 로그 섹션도 숨김
            hideWebServerLogSection()
        }
    }
    
    private fun handleDetailItemClick(item: SettingsItem) {
        if (item.id.startsWith("rec:")) {
            recordingEntries.getOrNull(item.id.removePrefix("rec:").toIntOrNull() ?: -1)?.let { showRecordingDialog(it) }
            return
        }
        when (item.id) {
            "rec_delete_all" -> confirmDeleteAllRecordings()
            "delete_all_pdf" -> showDeleteAllPdfDialog()
            "web_server_toggle" -> toggleWebServer()
            "web_server_port" -> showPortSettingDialog()
            "animation_toggle" -> togglePageTurnAnimation()
            "sound_toggle" -> togglePageTurnSound()
            "volume_setting" -> showVolumeSettingDialog()
            "page_info_toggle" -> togglePageInfo()
            "animation_speed" -> showAnimationSpeedDialog()
            "view_display_modes" -> showDisplayModeListDialog()
            "reset_display_modes" -> showResetDisplayModeDialog()
            "input_block_time" -> showInputBlockTimeDialog()
            "sync_turn_toggle" -> toggleSyncPageTurn()
            "ensemble_sound_toggle" -> toggleEnsembleSound()
            "sync_turn_lead" -> showSyncTurnLeadDialog()
            "message_queue_stats" -> showMessageQueueDisabledDialog()
            "check_update" -> updateController.checkForUpdate()
            "scoremate_link" -> linkScoreMate()
            "scoremate_check" -> checkScoreMate()
            "scoremate_unlink" -> confirmUnlinkScoreMate()
            "scoremate_sync" -> syncScoreMateNow()
            "scoremate_unhide" -> unhideScoreMate()
            "scoremate_server" -> showScoreMateServerDialog()
            "update_on_start_toggle" -> {
                UpdateController.setCheckOnStartup(this, !UpdateController.isCheckOnStartup(this))
                showInfoPanel()
            }
            "voice_commands_toggle" -> {
                if (!com.mrgq.pdfviewer.voice.VoiceListener.isAvailable(this)) return
                val enable = !preferences.getBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, false)
                val editor = preferences.edit().putBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, enable)
                // 끄면 👂 계속 듣기도 함께 끈다 — 다시 켰을 때 모르는 사이 듣기 시작하지 않게
                if (!enable) editor.putBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, false)
                editor.apply()
                showInfoPanel()
            }
            "voice_always_toggle" -> {
                if (!preferences.getBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, false)) return
                val enable = !preferences.getBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, false)
                preferences.edit().putBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, enable).apply()
                showInfoPanel()
            }
            "voice_text_input_toggle" -> {
                val enable = !preferences.getBoolean(PdfViewerActivity.PREF_VOICE_TEXT_INPUT, false)
                preferences.edit().putBoolean(PdfViewerActivity.PREF_VOICE_TEXT_INPUT, enable).apply()
                showInfoPanel()
            }
            "update_prerelease_toggle" -> {
                val enable = !UpdateController.isPrereleaseEnabled(this)
                UpdateController.setPrereleaseEnabled(this, enable)
                if (enable) Toast.makeText(this, "사전 릴리스도 받습니다 — 확인 전 기능이 포함될 수 있습니다", Toast.LENGTH_LONG).show()
                showInfoPanel()
            }
        }
    }
    
    private fun showPortSettingDialog() {
        val currentPort = preferences.getInt("web_server_port", 8080)
        val editText = EditText(this)
        editText.setText(currentPort.toString())
        editText.hint = "포트 번호 (1024-65535)"
        
        AlertDialog.Builder(this)
            .setTitle("웹서버 포트 설정")
            .setView(editText)
            .setPositiveButton("저장") { _, _ ->
                val portText = editText.text.toString().trim()
                if (portText.isNotEmpty()) {
                    try {
                        val port = portText.toInt()
                        if (port in 1024..65535) {
                            preferences.edit().putInt("web_server_port", port).apply()
                            Toast.makeText(this, "포트 설정이 저장되었습니다: $port", Toast.LENGTH_SHORT).show()
                            hideDetailPanel()
                            setupMainMenu()
                        } else {
                            Toast.makeText(this, "포트 번호는 1024-65535 범위여야 합니다", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, "유효한 포트 번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "포트 번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun getAllPdfFiles(): List<File> {
        val pdfFiles = mutableListOf<File>()
        
        // Check app's external files directory
        val appPdfDir = File(getExternalFilesDir(null), "PDFs")
        if (appPdfDir.exists() && appPdfDir.isDirectory) {
            appPdfDir.listFiles { file ->
                file.isFile && file.extension.equals("pdf", ignoreCase = true)
            }?.let { files ->
                pdfFiles.addAll(files)
            }
        }
        
        return pdfFiles
    }
    
    private fun showDeleteAllPdfDialog() {
        val pdfFiles = getAllPdfFiles()
        
        if (pdfFiles.isEmpty()) {
            Toast.makeText(this, "삭제할 PDF 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("모든 PDF 파일 삭제")
            .setMessage("모든 PDF 파일(${pdfFiles.size}개)을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleteAllPdfFiles(pdfFiles)
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun deleteAllPdfFiles(pdfFiles: List<File>) {
        CoroutineScope(Dispatchers.IO).launch {
            var deletedCount = 0
            var failedCount = 0
            
            for (file in pdfFiles) {
                try {
                    if (file.delete()) {
                        com.mrgq.pdfviewer.notes.ScoreNotesFile.fileOf(file).delete() // 메모도 함께 (P11)
                        deletedCount++
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    failedCount++
                }
            }
            
            withContext(Dispatchers.Main) {
                if (failedCount == 0) {
                    Toast.makeText(this@SettingsActivity, "${deletedCount}개 파일이 삭제되었습니다", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "${deletedCount}개 파일 삭제 완료, ${failedCount}개 파일 삭제 실패", Toast.LENGTH_LONG).show()
                }
                
                hideDetailPanel()
                setupMainMenu()
            }
        }
    }
    
    // 애니메이션/사운드 설정 함수들
    private fun togglePageTurnAnimation() {
        val currentEnabled = preferences.getBoolean("page_turn_animation_enabled", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("page_turn_animation_enabled", newEnabled).apply()
        
        val message = if (newEnabled) "페이지 전환 애니메이션이 활성화되었습니다" else "페이지 전환 애니메이션이 비활성화되었습니다"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        // 상세 패널 새로고침
        showAnimationSoundPanel()
    }
    
    private fun togglePageTurnSound() {
        val currentEnabled = preferences.getBoolean("page_turn_sound_enabled", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("page_turn_sound_enabled", newEnabled).apply()
        
        val message = if (newEnabled) "페이지 넘기기 사운드가 활성화되었습니다" else "페이지 넘기기 사운드가 비활성화되었습니다"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        // 상세 패널 새로고침
        showAnimationSoundPanel()
    }
    
    private fun togglePageInfo() {
        val currentEnabled = preferences.getBoolean("show_page_info", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("show_page_info", newEnabled).apply()
        
        val message = if (newEnabled) "페이지 정보가 표시됩니다" else "페이지 정보가 숨겨집니다"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        // 상세 패널 새로고침
        showAnimationSoundPanel()
    }
    
    private fun showVolumeSettingDialog() {
        val currentVolume = preferences.getFloat("page_turn_volume", 0.25f)
        val currentPercent = (currentVolume * 100).toInt()
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_volume_slider, null)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.volumeSeekBar)
        val valueText = dialogView.findViewById<TextView>(R.id.volumeValueText)
        
        seekBar.progress = currentPercent
        valueText.text = "${currentPercent}%"
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    valueText.text = "${progress}%"
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ ->
                val volumePercent = seekBar.progress
                val volume = volumePercent / 100.0f
                preferences.edit().putFloat("page_turn_volume", volume).apply()
                Toast.makeText(this, "볼륨이 ${volumePercent}%로 설정되었습니다", Toast.LENGTH_SHORT).show()
                // 상세 패널 새로고침
                showAnimationSoundPanel()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    // 웹서버 관리 함수들
    private fun checkWebServerStatus() {
        val serverStatus = webServerManager.isServerRunning()
        Log.d("SettingsActivity", "웹서버 상태 확인 - 이전: $isWebServerRunning, 현재: $serverStatus")
        isWebServerRunning = serverStatus
    }
    
    private fun updateWebServerStatus() {
        // 메인 메뉴에서 웹서버 상태 업데이트
        val webServerItem = currentItems.find { it.id == "web_server" }
        if (webServerItem != null && ::settingsAdapter.isInitialized) {
            val index = currentItems.indexOf(webServerItem)
            val status = if (isWebServerRunning) {
                val port = preferences.getInt("web_server_port", 8080)
                val ipAddress = NetworkUtils.getLocalIpAddress()
                "실행 중 ($ipAddress:$port)"
            } else {
                "중지됨"
            }
            
            currentItems[index] = webServerItem.copy(subtitle = status)
            settingsAdapter.notifyItemChanged(index)
        }
    }
    
    private fun toggleWebServer() {
        if (!isWebServerRunning && scoreMateClient.isLinked) {
            Toast.makeText(this, "ScoreMate 에 연결된 TV 에서는 웹서버를 쓸 수 없습니다", Toast.LENGTH_LONG).show()
            return
        }
        if (isWebServerRunning) {
            stopWebServer()
        } else {
            startWebServer()
        }
    }
    
    private fun startWebServer() {
        webServerManager.startServer(this) { success ->
            runOnUiThread {
                if (success) {
                    isWebServerRunning = true
                    val currentPort = preferences.getInt("web_server_port", 8080)
                    val serverAddress = webServerManager.getServerAddress()
                    
                    // 웹서버 로그 콜백 설정
                    webServerManager.setLogCallback { logMessage ->
                        runOnUiThread {
                            addWebServerLog(logMessage)
                        }
                    }
                    
                    // 로그에 시작 메시지 추가
                    addWebServerLog("✅ 웹서버 시작됨 - http://$serverAddress:$currentPort")
                    addWebServerLog("📁 업로드 대기 중...")
                    
                    // 웹서버 패널 업데이트 (설정으로 돌아가지 않고 패널에 머물기)
                    updateWebServerPanel()
                    showWebServerLogSection()
                    
                    Toast.makeText(
                        this,
                        "웹서버가 시작되었습니다\nhttp://$serverAddress:$currentPort",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "웹서버 시작 실패", Toast.LENGTH_SHORT).show()
                    hideDetailPanel()
                    setupMainMenu()
                }
            }
        }
    }
    
    private fun stopWebServer() {
        // 로그 콜백 해제
        webServerManager.clearLogCallback()
        
        webServerManager.stopServer()
        isWebServerRunning = false
        addWebServerLog("⏹️ 웹서버 중지됨")
        Toast.makeText(this, "웹서버가 중지되었습니다", Toast.LENGTH_SHORT).show()
        hideDetailPanel()
        setupMainMenu()
    }
    
    // Display mode management functions
    private fun showResetDisplayModeDialog() {
        AlertDialog.Builder(this)
            .setTitle("표시 모드 초기화")
            .setMessage("모든 파일의 표시 모드 설정을 초기화하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("초기화") { _, _ ->
                resetAllDisplayModes()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun resetAllDisplayModes() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                musicRepository.deleteAllUserPreferences()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "모든 표시 모드 설정이 초기화되었습니다", Toast.LENGTH_SHORT).show()
                    hideDetailPanel()
                    setupMainMenu()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error resetting display modes", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "설정 초기화 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showDisplayModeListDialog() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = musicRepository.getAllUserPreferences()
                val pdfFiles = musicRepository.getAllPdfFiles()
                
                val preferenceList = mutableListOf<Pair<String, DisplayMode>>()
                preferences.collect { prefs ->
                    preferenceList.clear()
                    pdfFiles.collect { files ->
                        val fileMap = files.associateBy { it.id }
                        prefs.forEach { pref ->
                            val fileName = fileMap[pref.pdfFileId]?.filename ?: "Unknown"
                            preferenceList.add(fileName to pref.displayMode)
                        }
                        
                        withContext(Dispatchers.Main) {
                            if (preferenceList.isEmpty()) {
                                Toast.makeText(this@SettingsActivity, "저장된 표시 모드 설정이 없습니다", Toast.LENGTH_SHORT).show()
                            } else {
                                showDisplayModeList(preferenceList)
                            }
                        }
                        return@collect
                    }
                    return@collect
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error loading display mode list", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "설정 목록을 불러오는 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showDisplayModeList(preferences: List<Pair<String, DisplayMode>>) {
        val displayText = StringBuilder()
        preferences.forEach { (fileName, mode) ->
            val modeText = when (mode) {
                DisplayMode.AUTO -> "자동"
                DisplayMode.SINGLE -> "한 페이지"
                DisplayMode.DOUBLE -> "두 페이지"
            }
            displayText.append("• $fileName: $modeText\n")
        }
        
        AlertDialog.Builder(this)
            .setTitle("저장된 표시 모드 설정")
            .setMessage(displayText.toString().trimEnd())
            .setPositiveButton("확인", null)
            .show()
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (binding.detailPanelLayout.visibility == View.VISIBLE) {
                    hideDetailPanel()
                } else {
                    handleBackPress()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun getAnimationSpeedText(): String {
        val duration = preferences.getLong("page_animation_duration", 350L)
        return when (duration) {
            0L -> "즉시"
            200L -> "빠르게"
            350L -> "보통"
            500L -> "느리게"
            800L -> "매우 느리게"
            else -> "${duration}ms"
        }
    }
    
    private fun showAnimationSpeedDialog() {
        val currentDuration = preferences.getLong("page_animation_duration", 350L)
        val speeds = arrayOf("즉시 (0ms)", "빠르게 (200ms)", "보통 (350ms)", "느리게 (500ms)", "매우 느리게 (800ms)")
        val durations = longArrayOf(0L, 200L, 350L, 500L, 800L)
        
        var selectedIndex = durations.indexOf(currentDuration)
        if (selectedIndex == -1) selectedIndex = 2 // 기본값 "보통"
        
        AlertDialog.Builder(this)
            .setTitle("페이지 넘기기 애니메이션 속도")
            .setSingleChoiceItems(speeds, selectedIndex) { dialog, which ->
                val newDuration = durations[which]
                preferences.edit().putLong("page_animation_duration", newDuration).apply()
                
                val message = "애니메이션 속도가 변경되었습니다: ${speeds[which]}"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                
                dialog.dismiss()
                hideDetailPanel()
                setupMainMenu()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    override fun onPause() {
        stopRecordingPlayback()
        super.onPause()
        com.mrgq.pdfviewer.ensemble.VersionNotice.detach()
    }

    override fun onResume() {
        super.onResume()
        com.mrgq.pdfviewer.ensemble.VersionNotice.attach(this) // 합주 상대와 버전이 다르면 대화상자로 (#061)
        updateController.onResume()
        checkWebServerStatus()
        // 메인 화면으로 돌아올 때 전체 메뉴를 다시 로드하여 웹서버 상태 반영
        if (binding.detailPanelLayout.visibility == android.view.View.GONE) {
            setupMainMenu()
        } else {
            updateWebServerStatus()
        }
    }
    
    /**
     * Handle back press without web server confirmation
     * (웹서버는 이미 웹서버 설정 화면에서 벗어날 때 확인했으므로)
     */
    private fun handleBackPress() {
        finish()
    }
    
    /**
     * Show confirmation dialog when exiting with web server running
     */
    private fun showWebServerExitConfirmDialog() {
        val port = preferences.getInt("web_server_port", 8080)
        val ipAddress = NetworkUtils.getLocalIpAddress()
        
        AlertDialog.Builder(this)
            .setTitle("웹서버 실행 중")
            .setMessage("웹서버가 실행 중입니다 ($ipAddress:$port)\n\n설정을 나가면 웹서버가 중지됩니다.\n계속하시겠습니까?")
            .setPositiveButton("나가기") { _, _ ->
                // Stop web server and exit
                Log.d("SettingsActivity", "User confirmed exit, stopping web server")
                webServerManager.stopServer()
                isWebServerRunning = false
                finish()
            }
            .setNegativeButton("머물기", null)
            .setCancelable(true)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        updateController.dispose()
        // Clean up web server when leaving settings (if not already stopped by user confirmation)
        if (isWebServerRunning) {
            Log.d("SettingsActivity", "Activity destroyed, stopping web server for proper cleanup")
            webServerManager.clearLogCallback()
            webServerManager.stopServer()
            isWebServerRunning = false
        }
    }
    
    /**
     * Show input block time setting dialog
     */
    private fun showInputBlockTimeDialog() {
        val currentTime = preferences.getLong("input_block_duration", 500L)
        val editText = EditText(this).apply {
            setText(currentTime.toString())
            hint = "밀리초 (100-2000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_input_block_time_title))
            .setMessage(getString(R.string.settings_input_block_time_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                try {
                    val newTime = editText.text.toString().toLongOrNull()
                    if (newTime != null && newTime in 100..2000) {
                        preferences.edit().putLong("input_block_duration", newTime).apply()
                        Toast.makeText(this, "입력 차단 시간이 ${newTime}ms로 변경되었습니다", Toast.LENGTH_SHORT).show()
                        hideDetailPanel()
                        setupMainMenu()
                    } else {
                        Toast.makeText(this, "100-2000 사이의 값을 입력하세요", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "올바른 숫자를 입력하세요", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    
    /**
     * 동기 페이지 넘김 ON/OFF (Phase 0). OFF = 기존처럼 신호 즉시 넘김.
     * ON = 지휘자가 누르면 예약 시간 후 모든 기기가 동시에 넘김.
     */
    private fun toggleEnsembleSound() {
        val enabled = !preferences.getBoolean(PdfViewerActivity.PREF_ENSEMBLE_SOUND, false)
        preferences.edit().putBoolean(PdfViewerActivity.PREF_ENSEMBLE_SOUND, enabled).apply()
        Toast.makeText(this, if (enabled) "연주자 기기도 메트로놈 소리를 냅니다" else "연주자 기기는 메트로놈 소리를 내지 않습니다", Toast.LENGTH_SHORT).show()
        showCollaborationPanel()
    }

    private fun toggleSyncPageTurn() {
        val newEnabled = !preferences.getBoolean("sync_page_turn_enabled", false)
        preferences.edit().putBoolean("sync_page_turn_enabled", newEnabled).apply()
        val msg = if (newEnabled) "동기 페이지 넘김 ON (예약 후 동시 넘김)" else "동기 페이지 넘김 OFF (즉시 넘김)"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        showCollaborationPanel()  // 패널 새로고침
    }

    /**
     * 예약 시간(lead, ms) 설정 다이얼로그. 지휘자가 누른 뒤 실제로 넘기까지의 시간이며,
     * 모든 기기가 이 절대 시각에 동시에 넘긴다.
     */
    private fun showSyncTurnLeadDialog() {
        val currentMs = preferences.getLong("sync_turn_lead_ms", 2000L)
        val editText = EditText(this).apply {
            setText(currentMs.toString())
            hint = "밀리초 (500-5000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(this)
            .setTitle("예약 시간 설정")
            .setMessage("지휘자가 페이지 넘김을 누른 뒤 실제로 넘어가기까지의 시간(ms)입니다.\n모든 기기가 이 시각에 동시에 넘깁니다. (권장 1000~2500)")
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                val v = editText.text.toString().toLongOrNull()
                if (v != null && v in 500..5000) {
                    preferences.edit().putLong("sync_turn_lead_ms", v).apply()
                    Toast.makeText(this, "예약 시간이 ${v}ms로 변경되었습니다", Toast.LENGTH_SHORT).show()
                    showCollaborationPanel()
                } else {
                    Toast.makeText(this, "500-5000 사이의 값을 입력하세요", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Show message queue disabled dialog
     */
    private fun showMessageQueueDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("메시지 큐 시스템")
            .setMessage("메시지 큐 시스템은 성능 최적화를 위해 비활성화되었습니다.\n\n" +
                        "협업 메시지는 이제 직접 처리되어 더 빠른 반응 속도를 제공합니다.")
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
    
    /**
     * Add a log message to the web server log
     */
    private fun addWebServerLog(message: String) {
        webServerLogs.add(message)
        // 로그가 너무 많아지면 오래된 것부터 제거 (최대 100개)
        if (webServerLogs.size > 100) {
            webServerLogs.removeAt(0)
        }
        updateWebServerLogDisplay()
    }
    
    /**
     * Update the web server log display
     */
    private fun updateWebServerLogDisplay() {
        if (isWebServerLogVisible) {
            binding.webServerLogText.text = webServerLogs.joinToString("\n")
            // 스크롤을 맨 아래로 이동
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
    
    /**
     * Show the web server log section
     */
    private fun showWebServerLogSection() {
        isWebServerLogVisible = true
        binding.webServerLogSection.visibility = android.view.View.VISIBLE
        updateWebServerLogDisplay()
    }
    
    /**
     * Hide the web server log section
     */
    private fun hideWebServerLogSection() {
        isWebServerLogVisible = false
        binding.webServerLogSection.visibility = android.view.View.GONE
    }
    
    /**
     * Update the web server panel with current status
     */
    private fun updateWebServerPanel() {
        if (binding.detailPanelLayout.visibility == android.view.View.VISIBLE) {
            // 웹서버 패널이 열려있으면 새로고침
            showWebServerPanel()
        }
    }
    
    /**
     * Show confirmation dialog when exiting web server detail panel
     */
    private fun showWebServerDetailExitConfirmDialog(onConfirm: (() -> Unit)? = null) {
        val port = preferences.getInt("web_server_port", 8080)
        val ipAddress = NetworkUtils.getLocalIpAddress()
        
        AlertDialog.Builder(this)
            .setTitle("웹서버 실행 중")
            .setMessage("웹서버가 실행 중입니다 ($ipAddress:$port)\n\n웹서버 설정을 나가면 웹서버가 중지됩니다.\n계속하시겠습니까?")
            .setPositiveButton("나가기") { _, _ ->
                // Stop web server and exit detail panel
                Log.d("SettingsActivity", "User confirmed exit from web server panel, stopping web server")
                webServerManager.clearLogCallback()
                webServerManager.stopServer()
                isWebServerRunning = false
                
                // Actually hide the panel
                binding.detailPanelLayout.visibility = View.GONE
                binding.settingsRecyclerView.visibility = View.VISIBLE
                hideWebServerLogSection()
                
                // Refresh main menu to show updated web server status
                setupMainMenu()
                
                // Execute callback if provided
                onConfirm?.invoke()
            }
            .setNegativeButton("머물기", null)
            .setCancelable(true)
            .show()
    }
    
    private fun clearWebServerLog() {
        webServerLogs.clear()
        updateWebServerLogDisplay()
    }
}