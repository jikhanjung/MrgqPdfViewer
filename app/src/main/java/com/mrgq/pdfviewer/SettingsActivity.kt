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

    /**
     * **두 칸** — 왼쪽 카테고리 · 오른쪽 그 항목. TV(#106)와 넓은 태블릿(세로 폭 [TWO_PANE_MIN_WIDTH_DP] 이상, #107 — iPad 설정처럼).
     * TV 는 왼쪽에서 포커스를 옮기면 오른쪽이 바로 바뀌고, → · OK 로 들어가고 ← · 뒤로로 나온다. 태블릿은 카테고리를 탭하면 오른쪽이 바뀐다.
     * 휴대폰 · 좁은 태블릿은 한 칸(목록 → 상세가 자리를 대신)
     */
    private val isTv by lazy { com.mrgq.pdfviewer.utils.DeviceForm.isTv(this) }
    private val twoPane by lazy {
        isTv || (!com.mrgq.pdfviewer.utils.DeviceForm.isPhone(this) && resources.configuration.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP)
    }
    /** 두 칸: 오른쪽에 보이는 카테고리 */
    private var paneCategory: String? = null
    /** 다시 만들어지기 전에 보던 카테고리 (P18 — 언어를 바꾸면 액티비티가 다시 만들어진다) */
    private var restoredCategory: String? = null
    /** 왼쪽에서 포커스를 빨리 훑을 때 오른쪽을 매번 만들지 않게 */
    private val panePreview = android.os.Handler(android.os.Looper.getMainLooper())
    /** 오른쪽(상세)에 지금 있는 줄 — 다시 그린 뒤 같은 줄로 포커스를 돌려놓는다 */
    private var detailItems: List<SettingsItem> = emptyList()
    /** 오른쪽(상세)에 지금 보이는 카테고리 id — 화면 글(제목) 대신 이것으로 비교한다 (P18) */
    private var detailCategory: String? = null
    
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
        // 볼륨 키 = 미디어 소리(녹음 재생 · 넘김 소리) — 아무것도 안 울릴 때 벨소리를 바꾸지 않게 (#111)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        binding = ActivitySettingsNewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 언어를 바꿔 다시 만들어졌으면 보던 칸으로 (P18)
        restoredCategory = savedInstanceState?.getString(STATE_PANE_CATEGORY)
        
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        
        // Initialize database repository
        musicRepository = MusicRepository(this)
        updateController = UpdateController(this)
        
        setupUI()
        if (twoPane) applyTwoPaneLayout()
        checkWebServerStatus()
        setupMainMenu()
    }

    /**
     * 두 칸: 목록은 경계선 왼쪽, 상세는 오른쪽 — 둘 다 늘 보인다. 상세의 취소 · 적용 단추는 쓰지 않는다(닫을 칸이 없다).
     * TV 는 경계를 36%(XML), 태블릿은 왼쪽을 [TABLET_LIST_WIDTH_DP] 로 — 오른쪽 줄 설명이 길다
     */
    private fun applyTwoPaneLayout() {
        val set = androidx.constraintlayout.widget.ConstraintSet()
        set.clone(binding.root)
        if (!isTv) set.setGuidelineBegin(R.id.paneGuide, (resources.displayMetrics.density * TABLET_LIST_WIDTH_DP).toInt())
        set.connect(R.id.settingsRecyclerView, androidx.constraintlayout.widget.ConstraintSet.END, R.id.paneGuide, androidx.constraintlayout.widget.ConstraintSet.START)
        set.connect(R.id.detailPanelLayout, androidx.constraintlayout.widget.ConstraintSet.START, R.id.paneGuide, androidx.constraintlayout.widget.ConstraintSet.END)
        val gap = (resources.displayMetrics.density * 16).toInt()
        set.setMargin(R.id.detailPanelLayout, androidx.constraintlayout.widget.ConstraintSet.START, gap)
        set.setMargin(R.id.detailPanelLayout, androidx.constraintlayout.widget.ConstraintSet.TOP, (resources.displayMetrics.density * 24).toInt())
        set.applyTo(binding.root)
        binding.detailButtons.visibility = View.GONE
    }

    /** 두 칸: 왼쪽 [id] 카테고리를 오른쪽에 보인다 (들어가지는 않는다) */
    private fun showCategory(id: String) {
        paneCategory = id
        if (::settingsAdapter.isInitialized) settingsAdapter.select(binding.settingsRecyclerView, id)
        showPanel(id)
    }

    /** [id] 카테고리의 항목을 상세 칸에 */
    private fun showPanel(id: String) {
        when (id) {
            "file_management" -> showFileManagementPanel()
            "web_server" -> showWebServerPanel()
            "collaboration" -> showCollaborationPanel()
            "scoremate" -> showScoreMatePanel()
            "voice" -> showVoicePanel()
            "animation_sound" -> showAnimationSoundPanel()
            "recordings" -> showRecordingsPanel()
            "display_mode" -> showDisplayModePanel()
            "language" -> showLanguagePanel()
            "info" -> showInfoPanel()
        }
    }

    /** 두 칸: 왼쪽에서 포커스가 온 카테고리를 잠깐 뒤 오른쪽에 (빨리 훑으면 마지막 것만) */
    private fun onCategoryFocused(item: SettingsItem) {
        panePreview.removeCallbacksAndMessages(null)
        if (item.id == paneCategory) return
        panePreview.postDelayed({ if (item.id != paneCategory) showCategory(item.id) }, PANE_PREVIEW_DELAY_MS)
    }

    /** 두 칸: 왼쪽의 지금 카테고리로 포커스 */
    private fun focusCategory() {
        val list = binding.settingsRecyclerView
        val position = currentItems.indexOfFirst { it.id == paneCategory }.takeIf { it >= 0 } ?: 0
        list.scrollToPosition(position)
        list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
    }

    /** 두 칸: 오른쪽 첫 줄로 들어간다 */
    private fun focusDetail() {
        panePreview.removeCallbacksAndMessages(null)
        binding.detailPanelLayout.scrollTo(0, 0)
        // 첫 줄 — 묶음 제목(포커스 안 됨)은 건너뛴다
        binding.detailRecyclerView.post {
            val list = binding.detailRecyclerView
            (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
        }
    }

    private fun detailHasFocus(): Boolean = binding.detailPanelLayout.hasFocus()

    /** 두 칸의 좌우: 왼쪽에서 → = 오른쪽으로 들어감, 오른쪽에서 ← = 왼쪽 지금 카테고리로 (가까운 줄이 아니라) */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (twoPane && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (binding.settingsRecyclerView.hasFocus()) {
                    // 미리 보기를 기다리지 않고 지금 포커스의 카테고리로 들어간다
                    val list = binding.settingsRecyclerView
                    val focused = list.focusedChild?.let { currentItems.getOrNull(list.getChildAdapterPosition(it))?.id }
                    if (focused != null && focused != paneCategory) showCategory(focused)
                    focusDetail()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (detailHasFocus()) {
                    focusCategory()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    
    private fun setupUI() {
        // 태블릿 · 휴대폰: 왼쪽 위 ← · "설정" (#107) — TV 는 오른쪽 "돌아가기" 그대로
        if (!isTv) {
            binding.backArrow.visibility = View.VISIBLE
            binding.headerIcon.visibility = View.GONE
            binding.backButton.visibility = View.GONE
            binding.backArrow.setOnClickListener { binding.backButton.performClick() }
        }
        // RecyclerView 설정
        binding.settingsRecyclerView.layoutManager = LinearLayoutManager(this)
        
        // 뒤로 가기 버튼
        binding.backButton.setOnClickListener {
            if (twoPane) {
                handleBackPress()
            } else if (binding.detailPanelLayout.visibility == View.VISIBLE) {
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
        
        // 카테고리는 제목만 — 설명 · 요약 줄은 두지 않는다(영어에서 줄바꿈이 많았다, #127)
        // 파일 관리 섹션
        currentItems.add(SettingsItem(
            id = "file_management",
            icon = "📂",
            title = getString(R.string.settings_cat_files),
            arrow = "▶"
        ))
        
        // 웹서버 섹션 — ScoreMate 에 연결된 TV 에서는 쓰지 않는다 (#063)
        currentItems.add(SettingsItem(
            id = "web_server",
            icon = "🌐",
            title = getString(R.string.settings_cat_web),
            arrow = "▶"
        ))
        
        // 협업 모드 섹션
        currentItems.add(SettingsItem(
            id = "collaboration",
            icon = "",
            iconRes = R.drawable.ic_ensemble,
            title = getString(R.string.settings_cat_collab),
            arrow = "▶"
        ))
        
        // ScoreMate 섹션 (P05 C1)
        currentItems.add(SettingsItem(
            id = "scoremate",
            icon = "☁️",
            title = "ScoreMate",
            arrow = "▶"
        ))

        // 애니메이션/사운드 섹션
        currentItems.add(SettingsItem(
            id = "animation_sound",
            icon = "🎵",
            title = getString(R.string.settings_cat_animation),
            arrow = "▶"
        ))
        
        // 🎙 음성 명령 · 👂 계속 듣기 — 태블릿 · 휴대폰 (P12). 예전엔 앱 정보 안에 있었다 (#123)
        if (!isTv) {
            currentItems.add(SettingsItem(
                id = "voice",
                icon = "🎙",
                title = getString(R.string.settings_cat_voice),
                arrow = "▶"
            ))
        }

        // 🎤 녹음 기록 (P10) — 태블릿에서만 (TV 는 녹음하지 않는다 — 연주 듣고 넘기기도 태블릿 전용)
        if (!com.mrgq.pdfviewer.utils.DeviceForm.isTv(this) &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
        ) {
            currentItems.add(SettingsItem(
                id = "recordings",
                icon = "🎤",
                title = getString(R.string.settings_cat_recordings),
                arrow = "▶"
            ))
        }

        currentItems.add(SettingsItem(
            id = "display_mode",
            icon = "🔧",
            title = getString(R.string.settings_cat_display),
            arrow = "▶"
        ))
        // 언어 (P18) — 앱 정보 안에 있던 것을 따로 칸으로. 제목에 두 언어라 못 읽는 언어로 바뀌어도 찾기 쉽다
        currentItems.add(SettingsItem(
            id = "language",
            icon = "🔤",
            title = getString(R.string.settings_language_title),
            arrow = "▶"
        ))
        currentItems.add(SettingsItem(
            id = "info",
            icon = "📊",
            title = getString(R.string.settings_cat_info),
            arrow = "▶"
        ))
        updateAdapter()
    }
    
    private fun updateAdapter() {
        val listHadFocus = binding.settingsRecyclerView.hasFocus()
        settingsAdapter = SettingsAdapter(currentItems, selectable = twoPane, onItemFocus = if (isTv) ::onCategoryFocused else null) { item ->
            handleItemClick(item)
        }
        if (twoPane) settingsAdapter.select(binding.settingsRecyclerView, paneCategory)
        binding.settingsRecyclerView.adapter = settingsAdapter
        if (twoPane) {
            // 처음엔 첫 카테고리를 오른쪽에. 목록을 다시 만들면(요약이 바뀜) 왼쪽 포커스를 지금 카테고리로 돌려놓는다
            if (paneCategory == null) {
                (restoredCategory?.takeIf { id -> currentItems.any { it.id == id } } ?: currentItems.firstOrNull()?.id)?.let { showCategory(it) }
            }
            if (listHadFocus || currentFocus == null) focusCategory()
        }
    }
    
    private fun handleItemClick(item: SettingsItem) {
        // 두 칸: 오른쪽에 보이고 그리로 들어간다. 웹서버 칸을 떠나면 웹서버는 멈춘다(showDetailPanel)
        if (twoPane) {
            if (item.id != paneCategory) showCategory(item.id)
            focusDetail()
            return
        }
        showPanel(item.id)
    }
    
    private fun showFileManagementPanel() {
        val items = listOf(
            SettingsItem(
                id = "delete_all_pdf",
                icon = "🗑️",
                title = getString(R.string.settings_delete_all_pdf),
                type = SettingsType.ACTION
            )
        )
        
        showDetailPanel("file_management", getString(R.string.settings_cat_files), items)
    }
    
    private fun showWebServerPanel() {
        // ScoreMate 에 연결된 TV 는 서재가 ScoreMate 악보뿐이라 웹서버로 올린 파일이 보이지 않는다 → 쓰지 않는다 (#063, 사용자 결정)
        if (scoreMateClient.isLinked) {
            showDetailPanel("web_server", getString(R.string.settings_cat_web), listOf(
                SettingsItem(
                    id = "web_server_disabled",
                    icon = "☁️",
                    title = getString(R.string.settings_web_unavailable_linked),
                    type = SettingsType.INFO
                )
            ))
            return
        }
        val port = preferences.getInt("web_server_port", 8080)
        
        // 켜면 오른쪽 값에 브라우저로 열 주소 — 이 칸을 떠나면 멈춘다(showDetailPanel)
        val items = listOf(
            SettingsItem(
                id = "web_server_toggle",
                icon = "▶️",
                title = getString(R.string.settings_cat_web),
                value = if (isWebServerRunning) "${NetworkUtils.getLocalIpAddress()}:$port" else "",
                type = SettingsType.TOGGLE,
                checked = isWebServerRunning
            ),
            SettingsItem(
                id = "web_server_port",
                icon = "🔧",
                title = getString(R.string.settings_web_port),
                value = port.toString(),
                type = SettingsType.INPUT
            )
        )
        
        showDetailPanel("web_server", getString(R.string.settings_cat_web), items)
        
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
            header("ensemble_header", getString(R.string.settings_ensemble_header)),
            SettingsItem(
                id = "ensemble_sound_toggle",
                icon = "🥁",
                title = getString(R.string.settings_ensemble_sound),
                type = SettingsType.TOGGLE,
                checked = preferences.getBoolean(PdfViewerActivity.PREF_ENSEMBLE_SOUND, false)
            ),
            header("sync_turn_header", getString(R.string.settings_sync_turn_header)),
            SettingsItem(
                id = "sync_turn_toggle",
                icon = "🎯",
                title = getString(R.string.settings_sync_turn),
                type = SettingsType.TOGGLE,
                checked = syncEnabled
            ),
            SettingsItem(
                id = "sync_turn_lead",
                icon = "⏲️",
                title = getString(R.string.settings_sync_lead),
                value = getString(R.string.settings_ms, syncLeadMs),
                type = SettingsType.ACTION,
                enabled = syncEnabled
            ),
            header("diagnostics_header", getString(R.string.settings_advanced_header)),
            SettingsItem(
                id = "input_block_time",
                icon = "⏱️",
                title = getString(R.string.settings_input_block_time),
                value = getString(R.string.settings_ms, inputBlockTime),
                type = SettingsType.ACTION
            ),
            SettingsItem(
                id = "message_queue_stats",
                icon = "📊",
                title = getString(R.string.settings_message_queue_stats),
                value = getString(R.string.settings_off),
                type = SettingsType.INFO
            )
        )
        
        showDetailPanel("collaboration", getString(R.string.settings_cat_collab), items)
    }
    
    // ── ScoreMate (P05 C1) ──────────────────────────────────────────────────

    private fun showScoreMatePanel() {
        val linked = scoreMateClient.isLinked
        val server = scoreMateStore.server.removePrefix("https://")
        val items = mutableListOf(
            header("scoremate_link_header", getString(R.string.settings_scoremate_link_header)),
            SettingsItem(
                id = "scoremate_status",
                icon = if (linked) "✅" else "⚪",
                title = getString(if (linked) R.string.settings_scoremate_linked else R.string.settings_scoremate_unlinked),
                value = if (linked) scoreMateStore.deviceName ?: "" else "",
                type = SettingsType.INFO
            )
        )
        if (linked) {
            items += SettingsItem(
                id = "scoremate_check",
                icon = "🔍",
                title = getString(R.string.settings_scoremate_check),
                type = SettingsType.ACTION
            )
            items += SettingsItem(
                id = "scoremate_unlink",
                icon = "⛔",
                title = getString(R.string.settings_scoremate_unlink),
                type = SettingsType.ACTION
            )
        } else {
            items += SettingsItem(
                id = "scoremate_link",
                icon = "🔗",
                title = getString(R.string.settings_scoremate_link, device),
                type = SettingsType.ACTION
            )
            items += SettingsItem(
                id = "scoremate_server",
                icon = "🌐",
                title = getString(R.string.settings_scoremate_server),
                value = server,
                type = SettingsType.INPUT
            )
        }
        if (linked) {
            items += header("scoremate_sync_header", getString(R.string.settings_scoremate_scores_header))
            items += SettingsItem(
                id = "scoremate_sync",
                icon = "📥",
                title = getString(R.string.settings_scoremate_sync),
                type = SettingsType.ACTION
            )
            items += SettingsItem(
                id = "scoremate_unhide",
                icon = "♻️",
                title = getString(R.string.settings_scoremate_unhide),
                type = SettingsType.ACTION
            )
        }
        showDetailPanel("scoremate", "ScoreMate", items)
    }

    private fun linkScoreMate() {
        DeviceLinkDialog(this, scoreMateClient, scoreMateStore) {
            // 연결되면 서재가 ScoreMate 악보로 바뀌고 웹서버는 쓰지 않는다 (#063)
            if (isWebServerRunning) stopWebServer()
            Toast.makeText(this, getString(R.string.settings_scoremate_linked_toast), Toast.LENGTH_LONG).show()
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
                    .setTitle(getString(R.string.settings_scoremate_check_title))
                    .setMessage(getString(R.string.settings_scoremate_check_message, device, info.name, scoreMateStore.server, info.lastSeenAt ?: "-"))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            } catch (e: ScoreMateException) {
                Toast.makeText(this@SettingsActivity, e.text.resolve(this@SettingsActivity), Toast.LENGTH_LONG).show()
            }
            showScoreMatePanel()
        }
    }

    private fun syncScoreMateNow() {
        Toast.makeText(this, getString(R.string.settings_scoremate_syncing), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val report = try {
                scoreMateSync.sync()
            } catch (e: ScoreMateException) {
                Toast.makeText(this@SettingsActivity, e.text.resolve(this@SettingsActivity), Toast.LENGTH_LONG).show()
                showScoreMatePanel()
                return@launch
            }
            Toast.makeText(
                this@SettingsActivity,
                com.mrgq.pdfviewer.scoremate.ScoreMateSyncText.summary(this@SettingsActivity, report) ?: getString(R.string.settings_scoremate_no_changes),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun unhideScoreMate() {
        lifecycleScope.launch {
            val count = scoreMateSync.unhideAll()
            if (count == 0) {
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_scoremate_no_hidden), Toast.LENGTH_SHORT).show()
            } else {
                syncScoreMateNow()
            }
        }
    }

    private fun confirmUnlinkScoreMate() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_scoremate_unlink_title))
            .setMessage(getString(R.string.settings_scoremate_unlink_message, device))
            .setPositiveButton(getString(R.string.settings_scoremate_unlink_keep)) { _, _ -> unlinkScoreMate(deleteFiles = false) }
            .setNeutralButton(getString(R.string.settings_scoremate_unlink_delete)) { _, _ -> unlinkScoreMate(deleteFiles = true) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun unlinkScoreMate(deleteFiles: Boolean) {
        lifecycleScope.launch {
            scoreMateSync.forgetAll(deleteFiles)
            val notified = scoreMateClient.unlink()
            Toast.makeText(
                this@SettingsActivity,
                if (notified) getString(R.string.settings_scoremate_unlinked_toast) else getString(R.string.settings_scoremate_unlinked_local_toast, device),
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
            .setTitle(getString(R.string.settings_scoremate_server_title))
            .setMessage(getString(R.string.settings_scoremate_server_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val input = editText.text.toString()
                val server = if (input.isBlank()) ScoreMateProtocol.DEFAULT_SERVER else ScoreMateProtocol.normalizeServer(input)
                if (server == null) {
                    Toast.makeText(this, getString(R.string.settings_scoremate_server_invalid), Toast.LENGTH_SHORT).show()
                } else {
                    scoreMateStore.server = server
                    showScoreMatePanel()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
                title = getString(R.string.settings_page_turn_animation),
                type = SettingsType.TOGGLE,
                checked = animationEnabled
            ),
            SettingsItem(
                id = "sound_toggle",
                icon = "🔊",
                title = getString(R.string.settings_page_turn_sound),
                type = SettingsType.TOGGLE,
                checked = soundEnabled
            ),
            SettingsItem(
                id = "volume_setting",
                icon = "🎚️",
                title = getString(R.string.settings_sound_volume),
                value = "${(volume * 100).toInt()}%",
                type = SettingsType.INPUT,
                enabled = soundEnabled
            ),
            SettingsItem(
                id = "page_info_toggle",
                icon = "📄",
                title = getString(R.string.settings_show_page_info),
                type = SettingsType.TOGGLE,
                checked = showPageInfo
            )
        )
        
        showDetailPanel("animation_sound", getString(R.string.settings_cat_animation), items)
    }
    
    private fun showDisplayModePanel() {
        val items = listOfNotNull(
            // 📱 휴대폰 악보 화면의 회전 모드 (#090) — 휴대폰만
            SettingsItem(
                id = "phone_orientation",
                icon = "📱",
                title = getString(R.string.settings_phone_orientation),
                value = phoneOrientationLabel(),
                type = SettingsType.ACTION
            ).takeIf { com.mrgq.pdfviewer.utils.DeviceForm.isPhone(this) },
            SettingsItem(
                id = "view_display_modes",
                icon = "👁️",
                title = getString(R.string.settings_view_display_modes),
                type = SettingsType.ACTION
            ),
            SettingsItem(
                id = "reset_display_modes",
                icon = "🔄",
                title = getString(R.string.settings_reset_display_modes),
                type = SettingsType.ACTION
            )
        )
        
        showDetailPanel("display_mode", getString(R.string.settings_cat_display), items)
    }
    
    private fun wakeWords(): List<String> =
        com.mrgq.pdfviewer.voice.WakeWord.parseSetting(preferences.getString(PdfViewerActivity.PREF_VOICE_WAKE_WORDS, null))

    /**
     * 호출어 바꾸기 (#091) — 쉼표로 여럿(STT 가 달리 적는 표기도 함께: "메이트, 매이트, mate"). 비우거나 "기본값"이면 메이트.
     * 두 글자 미만이거나 그 자체가 명령으로 읽히는 말은 받지 않는다
     */
    private fun showWakeWordsDialog() {
        val density = resources.displayMetrics.density
        val custom = preferences.getString(PdfViewerActivity.PREF_VOICE_WAKE_WORDS, null)
        val input = android.widget.EditText(this).apply {
            setText(if (custom.isNullOrBlank()) "" else custom)
            hint = com.mrgq.pdfviewer.voice.WakeWord.toSetting(com.mrgq.pdfviewer.voice.VoiceLexicon.WAKE_WORDS)
            setSingleLine(true)
        }
        val help = android.widget.TextView(this).apply {
            text = getString(R.string.settings_wake_help)
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(input)
            addView(help)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_wake_title))
            .setView(box)
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val words = com.mrgq.pdfviewer.voice.WakeWord.parseSetting(input.text.toString())
                val problem = words.firstNotNullOfOrNull { com.mrgq.pdfviewer.voice.WakeWord.problemText(it) }?.resolve(this)
                if (problem != null) {
                    Toast.makeText(this, getString(R.string.settings_wake_not_saved, problem), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val editor = preferences.edit()
                if (input.text.isBlank()) editor.remove(PdfViewerActivity.PREF_VOICE_WAKE_WORDS)
                else editor.putString(PdfViewerActivity.PREF_VOICE_WAKE_WORDS, com.mrgq.pdfviewer.voice.WakeWord.toSetting(words))
                editor.apply()
                showVoicePanel()
            }
            .setNeutralButton(getString(R.string.settings_default)) { _, _ ->
                preferences.edit().remove(PdfViewerActivity.PREF_VOICE_WAKE_WORDS).apply()
                showVoicePanel()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun phoneOrientationMode(): String =
        preferences.getString(PdfViewerActivity.PREF_PHONE_ORIENTATION, PdfViewerActivity.PHONE_ORIENTATION_AUTO)
            ?: PdfViewerActivity.PHONE_ORIENTATION_AUTO

    private fun phoneOrientationLabel(): String = getString(when (phoneOrientationMode()) {
        PdfViewerActivity.PHONE_ORIENTATION_LANDSCAPE -> R.string.settings_orientation_landscape
        PdfViewerActivity.PHONE_ORIENTATION_PORTRAIT -> R.string.settings_orientation_portrait
        else -> R.string.settings_orientation_auto
    })

    /** 회전 모드 고르기 (#090): 기기 회전 · 가로 · 세로 */
    private fun showPhoneOrientationDialog() {
        val modes = listOf(
            PdfViewerActivity.PHONE_ORIENTATION_AUTO to getString(R.string.settings_orientation_auto),
            PdfViewerActivity.PHONE_ORIENTATION_LANDSCAPE to getString(R.string.settings_orientation_landscape),
            PdfViewerActivity.PHONE_ORIENTATION_PORTRAIT to getString(R.string.settings_orientation_portrait),
        )
        val current = modes.indexOfFirst { it.first == phoneOrientationMode() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_phone_orientation))
            .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), current) { dialog, which ->
                preferences.edit().putString(PdfViewerActivity.PREF_PHONE_ORIENTATION, modes[which].first).apply()
                dialog.dismiss()
                showDisplayModePanel()
            }
            .setNegativeButton(getString(R.string.settings_close), null)
            .show()
    }

    /** 언어 칸 (P18) — 예전엔 앱 정보 맨 위에 있었다 */
    private fun showLanguagePanel() {
        val language = com.mrgq.pdfviewer.utils.AppLanguage
        val items = listOf(
            SettingsItem(
                id = "app_language",
                icon = "🔤",
                title = getString(R.string.settings_language_title),
                value = language.current().let { choice ->
                    if (choice == com.mrgq.pdfviewer.utils.AppLanguage.Choice.SYSTEM) {
                        getString(R.string.settings_language_system_with, language.locale(this).getDisplayLanguage(language.locale(this)))
                    } else {
                        language.label(this, choice)
                    }
                },
                type = SettingsType.ACTION
            ),
        )
        showDetailPanel("language", getString(R.string.settings_language_title), items)
    }

    private fun showInfoPanel() {
        val items = listOfNotNull(
            header("update_header", getString(R.string.settings_update_header)),
            SettingsItem(
                id = "app_version",
                icon = "📱",
                title = getString(R.string.settings_app_version),
                value = "v${BuildConfig.VERSION_NAME}",
                type = SettingsType.INFO
            ),
            SettingsItem(
                id = "check_update",
                icon = "🔄",
                title = getString(R.string.settings_check_update),
                type = SettingsType.BUTTON
            ),
            SettingsItem(
                id = "update_on_start_toggle",
                icon = "🔔",
                title = getString(R.string.settings_auto_update),
                type = SettingsType.TOGGLE,
                checked = UpdateController.isCheckOnStartup(this)
            ),
            SettingsItem(
                id = "update_prerelease_toggle",
                icon = "🧪",
                title = getString(R.string.settings_prerelease),
                type = SettingsType.TOGGLE,
                checked = UpdateController.isPrereleaseEnabled(this)
            )
        )
        
        showDetailPanel("info", getString(R.string.settings_cat_info), items)
    }
    
    /** 언어 고르기 (P18) — 고르면 AppCompat 이 액티비티를 다시 만든다(지금 칸은 [onSaveInstanceState] 로 지킨다) */
    private fun showLanguageDialog() {
        val language = com.mrgq.pdfviewer.utils.AppLanguage
        val choices = com.mrgq.pdfviewer.utils.AppLanguage.Choice.values()
        val current = choices.indexOf(language.current())
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language_dialog_title)
            .setSingleChoiceItems(choices.map { language.label(this, it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) language.set(choices[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 음성 명령 언어 (P18 8단계) — 인식 언어 · 문법. 앱 언어와 따로 둘 수 있다(한국어 화면 + 영어로 말하기) */
    private fun showVoiceLanguageDialog() {
        val voice = com.mrgq.pdfviewer.voice.VoiceLanguage
        val choices = com.mrgq.pdfviewer.voice.VoiceLanguageSetting.values()
        val current = choices.indexOf(voice.setting(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_language_title)
            .setSingleChoiceItems(choices.map { voice.label(this, it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                voice.setSetting(this, choices[which])
                showVoicePanel()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        paneCategory?.let { outState.putString(STATE_PANE_CATEGORY, it) }
    }

    /** 🎙 음성 명령 · 👂 계속 듣기 (#123 — 설정 맨 위 칸으로) */
    private fun showVoicePanel() {
        val items = listOfNotNull(
            header("voice_press_header", getString(R.string.settings_voice_press_header)),
            // 🎙 음성 명령 (P12 2단계) — 태블릿 · 휴대폰. 기기의 음성 인식(보통 Google)을 쓴다
            SettingsItem(
                id = "voice_commands_toggle",
                icon = "🎙",
                title = getString(R.string.settings_cat_voice),
                // 인식 서비스가 없으면 누를 때 설치 안내(showRecognizerInstallHelp)
                value = if (com.mrgq.pdfviewer.voice.VoiceListener.isAvailable(this)) "" else getString(R.string.settings_voice_unavailable),
                type = SettingsType.TOGGLE,
                checked = preferences.getBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, false)
            ),
            // 음성 명령 언어 (P18 8단계) — 앱 언어 따름(기본) · 한국어 · English
            SettingsItem(
                id = "voice_language",
                icon = "🌐",
                title = getString(R.string.voice_language_title),
                value = com.mrgq.pdfviewer.voice.VoiceLanguage.summary(this),
                type = SettingsType.ACTION
            ),
            header("voice_always_header", getString(R.string.settings_voice_always_header)),
            // 👂 계속 듣기 (#085) — 🎙 를 누르지 않고 호출어 "메이트" 뒤의 말을 명령으로
            SettingsItem(
                id = "voice_always_toggle",
                icon = "👂",
                title = getString(R.string.settings_voice_always),
                type = SettingsType.TOGGLE,
                checked = PdfViewerActivity.VOICE_ALWAYS_AVAILABLE && preferences.getBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, false),
                enabled = PdfViewerActivity.VOICE_ALWAYS_AVAILABLE
            ),
            // 🗣 계속 듣기의 호출어 (#091)
            SettingsItem(
                id = "voice_wake_words",
                icon = "🗣",
                title = getString(R.string.settings_wake_words),
                value = wakeWords().joinToString(" · "),
                type = SettingsType.ACTION
            ).takeIf { PdfViewerActivity.VOICE_ALWAYS_AVAILABLE },
        )
        showDetailPanel("voice", getString(R.string.settings_cat_voice), items)
    }

    /** 오른쪽 칸의 묶음 제목 (#122) */
    private fun header(id: String, title: String) = SettingsItem(id = id, icon = "", title = title, type = SettingsType.HEADER)

    // ── 🎤 녹음 기록 (P10 — 마이크 추적 기록 · 예전 연습 녹음) ─────────────────────────
    private var recordingEntries: List<com.mrgq.pdfviewer.recording.RecordingLibrary.Entry> = emptyList()
    private var recordingPlayer: android.media.MediaPlayer? = null

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
        val items = mutableListOf<SettingsItem>()
        if (recordingEntries.isEmpty()) {
            items += SettingsItem(
                id = "rec_empty",
                icon = "ℹ️",
                title = getString(R.string.settings_rec_empty),
                type = SettingsType.INFO
            )
        }
        val dateFormat = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA)
        recordingEntries.forEachIndexed { i, e ->
            val when_ = e.recordedAt?.let { dateFormat.format(it) } ?: ""
            // 줄은 곡 이름 + 날짜 · 길이만 — 나머지(시작 마디 · 넘김)는 눌러서 여는 대화상자에
            items += SettingsItem(
                id = "rec:$i",
                icon = if (e.isFollow) "🎤" else "🎙️",
                title = e.title,
                value = "$when_ · ${clock(e.durationSec)}",
                type = SettingsType.ACTION
            )
        }
        if (recordingEntries.isNotEmpty()) {
            items += SettingsItem(
                id = "rec_delete_all",
                icon = "🗑️",
                title = getString(R.string.settings_rec_delete_all),
                value = megabytes(recordingEntries.sumOf { it.bytes }),
                type = SettingsType.ACTION
            )
        }
        showDetailPanel("recordings", getString(R.string.settings_cat_recordings), items)
    }

    private fun showRecordingDialog(entry: com.mrgq.pdfviewer.recording.RecordingLibrary.Entry) {
        val s = entry.summary
        val lines = buildList {
            entry.recordedAt?.let { add(getString(R.string.settings_rec_recorded, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA).format(it))) }
            add(getString(R.string.settings_rec_length, clock(entry.durationSec), megabytes(entry.bytes)))
            if (s != null && entry.isFollow) {
                s.quarterBpm?.let { add(getString(R.string.settings_rec_tempo, it)) }
                if (s.startSec != null) add(getString(R.string.settings_rec_start, clock(s.startSec), s.startMeasurePos?.let { getString(R.string.settings_rec_measure, it.toInt() + 1) } ?: "?"))
                add(getString(R.string.settings_rec_turns_detail, s.pageTurns, s.halfTurns) + (s.lastPage?.let { " · " + getString(R.string.settings_rec_to_page, it + 1) } ?: ""))
                s.lastMeasurePos?.let { add(getString(R.string.settings_rec_last_position, it.toInt() + 1)) }
                add(getString(R.string.settings_rec_relocations, s.relocations) + if (s.manualAnchors > 0) " · " + getString(R.string.settings_rec_manual_turns, s.manualAnchors) else "")
            }
            add("")
            add(entry.wav.name)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(getString(R.string.settings_rec_play), null)
            .setNeutralButton(getString(R.string.settings_rec_share), null)
            .setNegativeButton(getString(R.string.settings_rec_delete), null)
            .setOnDismissListener { stopRecordingPlayback() }
            .create()
        dialog.setOnShowListener {
            val play = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            play.setOnClickListener {
                if (recordingPlayer != null) {
                    stopRecordingPlayback()
                    play.text = getString(R.string.settings_rec_play)
                } else if (startRecordingPlayback(entry) { play.text = getString(R.string.settings_rec_play) }) {
                    play.text = getString(R.string.settings_rec_stop)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { shareRecording(entry) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.settings_rec_delete_confirm, entry.wav.name))
                    .setPositiveButton(getString(R.string.settings_delete)) { _, _ ->
                        dialog.dismiss()
                        com.mrgq.pdfviewer.recording.RecordingLibrary.delete(entry)
                        Toast.makeText(this, getString(R.string.settings_rec_deleted), Toast.LENGTH_SHORT).show()
                        showRecordingsPanel()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
        dialog.show()
    }

    private fun startRecordingPlayback(entry: com.mrgq.pdfviewer.recording.RecordingLibrary.Entry, onEnd: () -> Unit): Boolean {
        stopRecordingPlayback()
        // 미디어 소리가 0 이면 재생돼도 들리지 않는다 — 기기 설정이라 알리기만 (태블릿 실제 사례, #111)
        val audio = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
        if (audio != null && audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
            Toast.makeText(this, getString(R.string.settings_rec_media_volume_zero), Toast.LENGTH_LONG).show()
        }
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
            Toast.makeText(this, getString(R.string.settings_rec_cannot_play), Toast.LENGTH_SHORT).show()
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
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.settings_rec_send)))
        } catch (e: Exception) {
            Log.w("SettingsActivity", "녹음 공유 실패", e)
            Toast.makeText(this, getString(R.string.settings_rec_cannot_share, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteAllRecordings() {
        val total = recordingEntries.sumOf { it.bytes }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_rec_delete_all_title))
            .setMessage(getString(R.string.settings_rec_delete_all_confirm, recordingEntries.size, megabytes(total)))
            .setPositiveButton(getString(R.string.settings_rec_delete_all)) { _, _ ->
                stopRecordingPlayback()
                recordingEntries.forEach { com.mrgq.pdfviewer.recording.RecordingLibrary.delete(it) }
                Toast.makeText(this, getString(R.string.settings_rec_all_deleted), Toast.LENGTH_SHORT).show()
                showRecordingsPanel()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** [category] = 카테고리 id("web_server" 등) — 지금 칸을 화면 글(제목)로 비교하지 않는다(P18 — 언어마다 다르다) */
    private fun showDetailPanel(category: String, title: String, items: List<SettingsItem>) {
        // 같은 칸을 다시 그릴 때(켜기 · 끄기 뒤) 포커스가 있던 줄 — 새 목록에서 같은 줄로 돌려놓는다(리모컨 포커스가 맨 위로 튀지 않게)
        val focusedId = binding.detailRecyclerView.focusedChild
            ?.let { detailItems.getOrNull(binding.detailRecyclerView.getChildAdapterPosition(it))?.id }
            ?.takeIf { category == detailCategory }
        // 웹서버 칸을 떠나면 웹서버를 멈춘다 — 묻지 않는다 (#127, 사용자 결정)
        if (detailCategory == "web_server" && category != "web_server") stopWebServerNow()
        detailCategory = category
        binding.detailTitle.text = title
        detailItems = items
        // 웹서버 칸이 아니면 로그는 숨긴다 (웹서버 칸은 이 뒤에 다시 보인다)
        hideWebServerLogSection()

        val detailAdapter = SettingsAdapter(items, detail = true) { item ->
            handleDetailItemClick(item)
        }
        binding.detailRecyclerView.layoutManager = LinearLayoutManager(this)
        // 줄 사이 구분줄 (#129) — 한 번만 붙인다
        if (binding.detailRecyclerView.itemDecorationCount == 0) binding.detailRecyclerView.addItemDecoration(SettingsAdapter.Divider(this))
        binding.detailRecyclerView.adapter = detailAdapter
        
        if (!twoPane) binding.settingsRecyclerView.visibility = View.GONE
        binding.detailPanelLayout.visibility = View.VISIBLE
        val again = items.indexOfFirst { it.id == focusedId }
        if (again >= 0) binding.detailRecyclerView.post { binding.detailRecyclerView.layoutManager?.findViewByPosition(again)?.requestFocus() }
    }
    
    private fun hideDetailPanel() {
        // 두 칸: 닫을 칸이 없다 — 지금 카테고리를 새 값으로 다시 그리고 왼쪽으로 (설정 대화상자를 마친 뒤 등)
        if (twoPane) {
            paneCategory?.let { showCategory(it) }
            focusCategory()
            return
        }
        // 웹서버 칸을 떠나면 웹서버를 멈춘다 (#127)
        if (detailCategory == "web_server") stopWebServerNow()
        detailCategory = null
        binding.detailPanelLayout.visibility = View.GONE
        binding.settingsRecyclerView.visibility = View.VISIBLE
        hideWebServerLogSection()
    }
    
    /**
     * 음성 인식 서비스가 없는 기기(주로 중국판 롬 태블릿 — Google 앱만으로는 인식 서비스가 꺼져 있다, #084): 깔 앱과 권한을 알리고 스토어로.
     * Play 스토어가 없는 기기는 웹 페이지로
     */
    private fun showRecognizerInstallHelp() {
        val pkg = com.mrgq.pdfviewer.voice.VoiceListener.RECOGNIZER_PACKAGE
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_recognizer_missing_title))
            .setMessage(getString(R.string.settings_recognizer_missing_message))
            .setPositiveButton(getString(R.string.settings_recognizer_open_store)) { _, _ ->
                val market = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))
                val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                try {
                    startActivity(market)
                } catch (e: android.content.ActivityNotFoundException) {
                    try {
                        startActivity(web)
                    } catch (e2: android.content.ActivityNotFoundException) {
                        Toast.makeText(this, getString(R.string.settings_recognizer_no_store, pkg), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.settings_close), null)
            .show()
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
            "phone_orientation" -> showPhoneOrientationDialog()
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
                if (!com.mrgq.pdfviewer.voice.VoiceListener.isAvailable(this)) return showRecognizerInstallHelp()
                val enable = !preferences.getBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, false)
                val editor = preferences.edit().putBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, enable)
                // 끄면 👂 계속 듣기도 함께 끈다 — 다시 켰을 때 모르는 사이 듣기 시작하지 않게
                if (!enable) editor.putBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, false)
                editor.apply()
                showVoicePanel()
            }
            "voice_always_toggle" -> {
                if (!PdfViewerActivity.VOICE_ALWAYS_AVAILABLE) return
                if (!preferences.getBoolean(PdfViewerActivity.PREF_VOICE_COMMANDS, false)) return
                val enable = !preferences.getBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, false)
                preferences.edit().putBoolean(PdfViewerActivity.PREF_VOICE_ALWAYS, enable).apply()
                showVoicePanel()
            }
            "voice_wake_words" -> showWakeWordsDialog()
            "voice_language" -> showVoiceLanguageDialog()
            "app_language" -> showLanguageDialog()
            "update_prerelease_toggle" -> {
                val enable = !UpdateController.isPrereleaseEnabled(this)
                UpdateController.setPrereleaseEnabled(this, enable)
                if (enable) Toast.makeText(this, getString(R.string.settings_prerelease_on_toast), Toast.LENGTH_LONG).show()
                showInfoPanel()
            }
        }
    }
    
    private fun showPortSettingDialog() {
        val currentPort = preferences.getInt("web_server_port", 8080)
        val editText = EditText(this)
        editText.setText(currentPort.toString())
        editText.hint = getString(R.string.settings_web_port_hint)
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_web_port_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val portText = editText.text.toString().trim()
                if (portText.isNotEmpty()) {
                    try {
                        val port = portText.toInt()
                        if (port in 1024..65535) {
                            preferences.edit().putInt("web_server_port", port).apply()
                            Toast.makeText(this, getString(R.string.settings_web_port_saved, port), Toast.LENGTH_SHORT).show()
                            hideDetailPanel()
                            setupMainMenu()
                        } else {
                            Toast.makeText(this, getString(R.string.settings_web_port_range), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, getString(R.string.settings_web_port_invalid), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, getString(R.string.settings_web_port_empty), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
            Toast.makeText(this, getString(R.string.settings_delete_all_pdf_none), Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_delete_all_pdf))
            .setMessage(getString(R.string.settings_delete_all_pdf_confirm, pdfFiles.size))
            .setPositiveButton(getString(R.string.settings_delete_button)) { _, _ ->
                deleteAllPdfFiles(pdfFiles)
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_delete_all_pdf_done, deletedCount), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_delete_all_pdf_partial, deletedCount, failedCount), Toast.LENGTH_LONG).show()
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
        
        val message = getString(if (newEnabled) R.string.settings_animation_on_toast else R.string.settings_animation_off_toast)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        // 상세 패널 새로고침
        showAnimationSoundPanel()
    }
    
    private fun togglePageTurnSound() {
        val currentEnabled = preferences.getBoolean("page_turn_sound_enabled", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("page_turn_sound_enabled", newEnabled).apply()
        
        val message = getString(if (newEnabled) R.string.settings_sound_on_toast else R.string.settings_sound_off_toast)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        // 상세 패널 새로고침
        showAnimationSoundPanel()
    }
    
    private fun togglePageInfo() {
        val currentEnabled = preferences.getBoolean("show_page_info", true)
        val newEnabled = !currentEnabled
        
        preferences.edit().putBoolean("show_page_info", newEnabled).apply()
        
        val message = getString(if (newEnabled) R.string.settings_page_info_on_toast else R.string.settings_page_info_off_toast)
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
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                val volumePercent = seekBar.progress
                val volume = volumePercent / 100.0f
                preferences.edit().putFloat("page_turn_volume", volume).apply()
                Toast.makeText(this, getString(R.string.settings_volume_saved, volumePercent), Toast.LENGTH_SHORT).show()
                // 상세 패널 새로고침
                showAnimationSoundPanel()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    // 웹서버 관리 함수들
    private fun checkWebServerStatus() {
        val serverStatus = webServerManager.isServerRunning()
        Log.d("SettingsActivity", "웹서버 상태 확인 - 이전: $isWebServerRunning, 현재: $serverStatus")
        isWebServerRunning = serverStatus
    }
    
    private fun toggleWebServer() {
        if (!isWebServerRunning && scoreMateClient.isLinked) {
            Toast.makeText(this, getString(R.string.settings_web_linked_toast), Toast.LENGTH_LONG).show()
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
                    addWebServerLog(getString(R.string.settings_web_log_started, serverAddress, currentPort))
                    addWebServerLog(getString(R.string.settings_web_log_waiting))
                    
                    // 웹서버 패널 업데이트 (설정으로 돌아가지 않고 패널에 머물기)
                    updateWebServerPanel()
                    showWebServerLogSection()
                    
                    Toast.makeText(
                        this,
                        getString(R.string.settings_web_started_toast, serverAddress, currentPort),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, getString(R.string.settings_web_start_failed), Toast.LENGTH_SHORT).show()
                    hideDetailPanel()
                    setupMainMenu()
                }
            }
        }
    }
    
    private fun stopWebServer() {
        stopWebServerNow()
        updateWebServerPanel()
    }

    /** 웹서버를 멈춘다(돌고 있을 때만) — 칸은 다시 그리지 않는다: 웹서버 칸을 떠날 때도 쓴다 */
    private fun stopWebServerNow() {
        if (!isWebServerRunning) return
        webServerManager.clearLogCallback()
        webServerManager.stopServer()
        isWebServerRunning = false
        addWebServerLog(getString(R.string.settings_web_log_stopped))
        Toast.makeText(this, getString(R.string.settings_web_stopped_toast), Toast.LENGTH_SHORT).show()
    }
    
    // Display mode management functions
    private fun showResetDisplayModeDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_reset_display_modes_title))
            .setMessage(getString(R.string.settings_reset_display_modes_confirm))
            .setPositiveButton(getString(R.string.settings_reset_button)) { _, _ ->
                resetAllDisplayModes()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun resetAllDisplayModes() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                musicRepository.deleteAllUserPreferences()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_reset_display_modes_done), Toast.LENGTH_SHORT).show()
                    hideDetailPanel()
                    setupMainMenu()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error resetting display modes", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_reset_display_modes_error), Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(this@SettingsActivity, getString(R.string.settings_display_modes_empty), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_display_modes_load_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showDisplayModeList(preferences: List<Pair<String, DisplayMode>>) {
        val displayText = StringBuilder()
        preferences.forEach { (fileName, mode) ->
            val modeText = getString(when (mode) {
                DisplayMode.AUTO -> R.string.settings_display_mode_auto
                DisplayMode.SINGLE -> R.string.settings_display_mode_single
                DisplayMode.DOUBLE -> R.string.settings_display_mode_double
            })
            displayText.append("• $fileName: $modeText\n")
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_display_modes_title))
            .setMessage(displayText.toString().trimEnd())
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                when {
                    // 두 칸: 오른쪽에 있으면 왼쪽으로, 왼쪽이면 설정을 나간다
                    twoPane && detailHasFocus() -> focusCategory()
                    twoPane -> handleBackPress()
                    binding.detailPanelLayout.visibility == View.VISIBLE -> hideDetailPanel()
                    else -> handleBackPress()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun getAnimationSpeedText(): String {
        val duration = preferences.getLong("page_animation_duration", 350L)
        return when (duration) {
            0L -> getString(R.string.settings_speed_instant)
            200L -> getString(R.string.settings_speed_fast)
            350L -> getString(R.string.settings_speed_normal)
            500L -> getString(R.string.settings_speed_slow)
            800L -> getString(R.string.settings_speed_very_slow)
            else -> "${duration}ms"
        }
    }
    
    private fun showAnimationSpeedDialog() {
        val currentDuration = preferences.getLong("page_animation_duration", 350L)
        val durations = longArrayOf(0L, 200L, 350L, 500L, 800L)
        val speedNames = intArrayOf(
            R.string.settings_speed_instant, R.string.settings_speed_fast, R.string.settings_speed_normal,
            R.string.settings_speed_slow, R.string.settings_speed_very_slow,
        )
        val speeds = Array(durations.size) { getString(R.string.settings_speed_with_ms, getString(speedNames[it]), durations[it]) }
        
        var selectedIndex = durations.indexOf(currentDuration)
        if (selectedIndex == -1) selectedIndex = 2 // 기본값 "보통"
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_speed_title))
            .setSingleChoiceItems(speeds, selectedIndex) { dialog, which ->
                val newDuration = durations[which]
                preferences.edit().putLong("page_animation_duration", newDuration).apply()
                
                val message = getString(R.string.settings_speed_saved, speeds[which])
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                
                dialog.dismiss()
                hideDetailPanel()
                setupMainMenu()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 설정 화면에서도 새 버전 자동 확인 (#124) — 파일 목록과 같은 10분 시계(`UpdateController.checkIfDue`)를 1분마다 두드린다.
     * 대화상자가 떠 있거나 합주 중이면 건너뛰고, onPause 에서 멈춘다. 자동 확인을 꺼 두었으면 하지 않는다
     */
    private val autoUpdateCheck: Runnable = object : Runnable {
        override fun run() {
            if (!isFinishing && hasWindowFocus() && UpdateController.isCheckOnStartup(this@SettingsActivity) &&
                GlobalCollaborationManager.getInstance().getCurrentMode() == CollaborationMode.NONE
            ) {
                updateController.checkIfDue()
            }
            binding.root.postDelayed(this, UPDATE_CHECK_TICK_MS)
        }
    }

    override fun onPause() {
        binding.root.removeCallbacks(autoUpdateCheck)
        stopRecordingPlayback()
        super.onPause()
        com.mrgq.pdfviewer.ensemble.VersionNotice.detach()
    }

    override fun onResume() {
        super.onResume()
        com.mrgq.pdfviewer.ensemble.VersionNotice.attach(this) // 합주 상대와 버전이 다르면 대화상자로 (#061)
        updateController.onResume()
        binding.root.removeCallbacks(autoUpdateCheck)
        binding.root.postDelayed(autoUpdateCheck, UPDATE_CHECK_FIRST_DELAY_MS)
        checkWebServerStatus()
        // 두 칸은 상세 칸이 늘 보이므로 늘 다시 읽는다
        if (twoPane || binding.detailPanelLayout.visibility == android.view.View.GONE) setupMainMenu()
    }
    
    /** 설정을 나간다 — 웹서버가 돌고 있으면 멈춘다(묻지 않는다, #127) */
    private fun handleBackPress() {
        stopWebServerNow()
        finish()
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
            hint = getString(R.string.settings_input_block_hint)
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
                        Toast.makeText(this, getString(R.string.settings_input_block_saved, newTime), Toast.LENGTH_SHORT).show()
                        hideDetailPanel()
                        setupMainMenu()
                    } else {
                        Toast.makeText(this, getString(R.string.settings_input_block_range), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.settings_invalid_number), Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, getString(if (enabled) R.string.settings_ensemble_sound_on_toast else R.string.settings_ensemble_sound_off_toast), Toast.LENGTH_SHORT).show()
        showCollaborationPanel()
    }

    private fun toggleSyncPageTurn() {
        val newEnabled = !preferences.getBoolean("sync_page_turn_enabled", false)
        preferences.edit().putBoolean("sync_page_turn_enabled", newEnabled).apply()
        val msg = getString(if (newEnabled) R.string.settings_sync_turn_on_toast else R.string.settings_sync_turn_off_toast)
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
            hint = getString(R.string.settings_sync_lead_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_sync_lead_title))
            .setMessage(getString(R.string.settings_sync_lead_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                val v = editText.text.toString().toLongOrNull()
                if (v != null && v in 500..5000) {
                    preferences.edit().putLong("sync_turn_lead_ms", v).apply()
                    Toast.makeText(this, getString(R.string.settings_sync_lead_saved, v), Toast.LENGTH_SHORT).show()
                    showCollaborationPanel()
                } else {
                    Toast.makeText(this, getString(R.string.settings_sync_lead_range), Toast.LENGTH_SHORT).show()
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
            .setTitle(getString(R.string.settings_message_queue_title))
            .setMessage(getString(R.string.settings_message_queue_message))
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
        // 두 칸은 상세 칸이 늘 보이므로 웹서버 칸일 때만
        if (binding.detailPanelLayout.visibility == android.view.View.VISIBLE && detailCategory == "web_server") {
            // 웹서버 패널이 열려있으면 새로고침
            showWebServerPanel()
        }
    }
    
    private fun clearWebServerLog() {
        webServerLogs.clear()
        updateWebServerLogDisplay()
    }

    companion object {
        private const val STATE_PANE_CATEGORY = "pane_category"
        /** 설정 화면에서 자동 업데이트 확인을 두드리는 간격 — 실제 확인은 UpdateController 가 10분마다 한 번(파일 목록과 같은 시계, #124) */
        private const val UPDATE_CHECK_TICK_MS = 60_000L
        /** 화면이 뜬 뒤 첫 두드림 */
        private const val UPDATE_CHECK_FIRST_DELAY_MS = 1500L
        /** 두 칸: 왼쪽 포커스가 이만큼 머물면 오른쪽을 그 카테고리로 */
        private const val PANE_PREVIEW_DELAY_MS = 150L
        /** 이만큼 넓은 태블릿은 두 칸 (샤오신패드 12.7 세로 ≈ 791dp, iPad 세로 ≈ 820pt) */
        private const val TWO_PANE_MIN_WIDTH_DP = 700
        /** 태블릿 두 칸의 왼쪽 카테고리 폭 */
        private const val TABLET_LIST_WIDTH_DP = 280
    }
}
