package com.mrgq.pdfviewer

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mrgq.pdfviewer.adapter.PdfFileAdapter
import com.mrgq.pdfviewer.databinding.ActivityMainBinding
import com.mrgq.pdfviewer.model.PdfFile
import com.mrgq.pdfviewer.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private companion object {
        /** 파일 목록이 뜬 뒤 확인한다 — 시작 화면 전환과 겹치지 않게 */
        const val STARTUP_UPDATE_CHECK_DELAY_MS = 1500L
        /** 파일 목록이 떠 있는 동안 자동 확인을 두드리는 간격 — 실제 확인은 UpdateController 가 10분마다 한 번 */
        const val UPDATE_CHECK_TICK_MS = 60_000L


        /** 마지막으로 고른 세트리스트 id (#064). [ALL_SCORES] = 고른 적 없음(첫 세트리스트) */
        const val PREF_SETLIST = "scoremate_setlist"
        const val ALL_SCORES = -1L
        /** 키를 누른 뒤 이 안에 생긴 포커스 이동만 세트리스트 선택으로 본다 */
        const val TAB_FOCUS_KEY_WINDOW_MS = 500L

        /** 이 프로세스에서 ScoreMate heartbeat 를 보냈나 — 앱을 켤 때 한 번 */
        var scoreMateHeartbeatSent = false
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfAdapter: PdfFileAdapter
    private var currentSortBy = PdfFileSorter.BY_NAME
    private var isFileManagementMode = false // 파일 관리 모드 상태
    private val musicRepository by lazy { MusicRepository(applicationContext) }
    // 앱 시작 시 새 버전 확인 (#054 후속). 설치 허용 설정에서 돌아오면 onResume 에서 이어 간다
    private val updateController by lazy { com.mrgq.pdfviewer.update.UpdateController(this) }
    
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Note: Global collaboration manager는 Application에서 이미 초기화됨
        
        setupRecyclerView()
        loadPdfFiles()
        setupCollaborationButton()
        setupSortButtons()
        setupLibraryHeader()
        setupSettingsButton()
        setupFileManagementButton()
        setupCollaborationCallbacks()
        
        // Set version number
        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"
        
        // Add fade-in animation for UI elements
        addFadeInAnimations()
        
        // Handle file request from SettingsActivity
        handleFileRequest()

    }
    
    /**
     * 새 버전 자동 확인 — 파일 목록이 떠 있는 동안 10분마다 (UpdateController.checkIfDue). 화면이 뜬 뒤 조용히,
     * 그 뒤로는 [UPDATE_CHECK_TICK_MS] 마다 두드리고 onPause 에서 멈춘다. 합주 중 · 대화상자가 떠 있을 때는 방해하지 않는다
     */
    private fun scheduleAutoUpdateCheck() {
        sendScoreMateHeartbeat()
        if (!com.mrgq.pdfviewer.update.UpdateController.isCheckOnStartup(this)) return
        binding.root.removeCallbacks(autoUpdateCheck)
        binding.root.postDelayed(autoUpdateCheck, STARTUP_UPDATE_CHECK_DELAY_MS)
    }

    /**
     * ScoreMate 에 연결된 TV 면 앱을 켤 때 한 번 알린다 (P05 C1) — 웹 "TV" 목록에 앱 버전 · 모델 · 마지막 접속.
     * 조용히: 네트워크 오류는 무시, 웹에서 해제됐으면(401) 토큰을 지우고 한 번 알린다. 합주 중에는 하지 않는다
     */
    private fun sendScoreMateHeartbeat() {
        if (scoreMateHeartbeatSent) return
        val store = com.mrgq.pdfviewer.scoremate.ScoreMateStore(this)
        val client = com.mrgq.pdfviewer.scoremate.ScoreMateClient(store)
        if (!client.isLinked || GlobalCollaborationManager.getInstance().getCurrentMode() != CollaborationMode.NONE) return
        scoreMateHeartbeatSent = true
        lifecycleScope.launch {
            try {
                client.heartbeat(BuildConfig.VERSION_NAME, android.os.Build.MODEL)?.let { info ->
                    if (info.name.isNotBlank()) store.deviceName = info.name
                }
            } catch (e: com.mrgq.pdfviewer.scoremate.ScoreMateUnlinkedException) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                return@launch
            } catch (e: com.mrgq.pdfviewer.scoremate.ScoreMateException) {
                Log.i("MainActivity", "ScoreMate heartbeat 실패 (무시): ${e.message}")
            }
            // 이어서 악보 동기화 (P05 C2) — 파일 목록 화면에서만, 합주 중에는 하지 않는다
            runScoreMateSync(quiet = true)
        }
    }

    /**
     * ScoreMate 악보 동기화. 바뀐 게 있으면 목록을 다시 읽는다.
     * [quiet] 면 네트워크 오류 · 변경 없음은 알리지 않는다 (앱 시작 시)
     */
    private suspend fun runScoreMateSync(quiet: Boolean) {
        if (GlobalCollaborationManager.getInstance().getCurrentMode() != CollaborationMode.NONE) return
        val report = try {
            scoreMateSync().sync()
        } catch (e: com.mrgq.pdfviewer.scoremate.ScoreMateException) {
            Log.i("MainActivity", "ScoreMate 동기화 실패: ${e.message}")
            if (!quiet || e is com.mrgq.pdfviewer.scoremate.ScoreMateUnlinkedException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            }
            return
        }
        Log.i("MainActivity", "ScoreMate 동기화: $report")
        if (report.changed) loadPdfFiles()
        val summary = com.mrgq.pdfviewer.scoremate.ScoreMateSyncText.summary(report)
        if (summary != null && (report.changed || !quiet || report.errors.isNotEmpty())) {
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        } else if (summary == null && !quiet) {
            Toast.makeText(this, "ScoreMate: 바뀐 악보가 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private val scoreMateLocal by lazy { com.mrgq.pdfviewer.repository.ScoreMateLocal(this) }

    private fun scoreMateSync(): com.mrgq.pdfviewer.scoremate.ScoreMateSync {
        val store = com.mrgq.pdfviewer.scoremate.ScoreMateStore(this)
        return com.mrgq.pdfviewer.scoremate.ScoreMateSync(
            com.mrgq.pdfviewer.scoremate.ScoreMateClient(store), store, scoreMateLocal, scoreMateLocal,
            File(getExternalFilesDir(null), "PDFs"),
        )
    }

    private fun cloudLabel(score: com.mrgq.pdfviewer.scoremate.SyncedScore): String =
        listOfNotNull(score.ensembleName ?: "내 악보", score.partName.takeIf { it.isNotBlank() }, "판 ${score.versionNumber}")
            .joinToString(" · ")

    private val autoUpdateCheck: Runnable = object : Runnable {
        override fun run() {
            if (!isFinishing && hasWindowFocus() && GlobalCollaborationManager.getInstance().getCurrentMode() == CollaborationMode.NONE) {
                updateController.checkIfDue()
            }
            binding.root.postDelayed(this, UPDATE_CHECK_TICK_MS)
        }
    }

    private fun setupRecyclerView() {
        pdfAdapter = PdfFileAdapter(
            onItemClick = { pdfFile, position ->
                openPdfFile(pdfFile, position)
            },
            onDeleteClick = { pdfFile ->
                showDeleteConfirmationDialog(pdfFile)
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pdfAdapter
            setHasFixedSize(true)
        }
    }
    
    
    
    override fun onResume() {
        super.onResume()
        com.mrgq.pdfviewer.ensemble.VersionNotice.attach(this) // 합주 상대와 버전이 다르면 대화상자로 (#061)
        updateController.onResume()
        scheduleAutoUpdateCheck()
        
        // ====================[ 핵심 수정 사항 ]====================
        // 액티비티가 다시 활성화될 때마다 협업 콜백을 재등록합니다.
        // 이를 통해 PdfViewerActivity에서 돌아왔을 때 콜백 유실을 방지하고,
        // 지휘자의 파일 변경 메시지를 안정적으로 수신할 수 있습니다.
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        if (globalCollaborationManager.getCurrentMode() != CollaborationMode.NONE) {
            Log.d("MainActivity", "onResume: Re-registering collaboration callbacks")
            setupCollaborationCallbacks()
        }
        // ==========================================================
        
        Log.d("MainActivity", "onResume - 협업 상태 업데이트")
        updateCollaborationStatus()
        
        // Always refresh file list when returning to MainActivity
        // This ensures files deleted/added in Settings are reflected
        Log.d("MainActivity", "onResume - 파일 목록 새로고침")
        loadPdfFiles()
        
        // Clear any refresh flag
        val preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        preferences.edit().putBoolean("refresh_file_list", false).apply()
    }
    
    private fun loadPdfFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            val store = com.mrgq.pdfviewer.scoremate.ScoreMateStore(this@MainActivity)
            val linked = store.tokens != null
            val pdfFiles = getCurrentPdfFiles(linked)
            val lists = if (linked) com.mrgq.pdfviewer.scoremate.ScoreMateSetlists.parse(store.setlistsBody) else emptyList()
            val rows = if (linked) scoreMateLocal.all() else emptyList()

            withContext(Dispatchers.Main) {
                allPdfFiles = pdfFiles
                scoreMateLinked = linked
                setlists = lists
                syncedRows = rows
                showLibrary()
            }
        }
    }

    // ── 서재 (#063, 사용자 결정): ScoreMate 에 연결된 TV 는 ScoreMate 악보만, 아니면 로컬 파일(PDFs/)만 ──────────
    // 한 번에 한 서재 — 같은 악보가 두 벌 생기지 않고, 어디서 온 파일인지 따질 필요가 없다. 연결 중에는 웹서버도 끈다

    /** 지금 서재의 파일 */
    private var allPdfFiles: List<PdfFile> = emptyList()
    private var scoreMateLinked = false
    private val ensembleCacheDir by lazy { File(cacheDir, "ensemble") }
    private val preferences by lazy { getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE) }

    private fun setupLibraryHeader() {
        binding.scoreMateSyncBtn.setOnClickListener {
            Toast.makeText(this, "ScoreMate 동기화 중…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { runScoreMateSync(quiet = false) }
        }
        selectedSetlistId = preferences.getLong(PREF_SETLIST, ALL_SCORES).takeIf { it != ALL_SCORES }
    }

    // ── 세트리스트 (#064): 연결된 TV 에서 받은 곡목을 골라 곡 순서대로 본다 ─────────────────────
    private var setlists: List<com.mrgq.pdfviewer.scoremate.Setlist> = emptyList()
    private var syncedRows: List<com.mrgq.pdfviewer.scoremate.SyncedScore> = emptyList()
    /** 고른 세트리스트 — null 이거나 없어졌으면 첫 세트리스트 */
    private var selectedSetlistId: Long? = null
    /** 세트리스트 줄에 지금 그려 둔 것 (id 목록) — 같으면 다시 만들지 않는다(포커스를 잃지 않게) */
    private var renderedSetlistIds: List<Long?> = emptyList()
    /** 마지막 리모컨 키 시각 — 포커스 이동이 사용자 조작인지 가린다 */
    private var lastKeyAtMs = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) lastKeyAtMs = android.os.SystemClock.uptimeMillis()
        return super.dispatchKeyEvent(event)
    }

    private fun selectSetlist(id: Long?) {
        if (id == selectedSetlistId) return
        selectedSetlistId = id
        preferences.edit().putLong(PREF_SETLIST, id ?: ALL_SCORES).apply()
        showLibrary()
    }

    /** 세트리스트 줄 — [곡목 …]. 연결된 TV 는 세트리스트만 본다("모든 악보" 없음 — 곡목에 없는 악보는 받지 않는다, 서버 0.7.0). 포커스만 옮겨도 바뀐다(리모컨 키 뒤 500ms 안의 이동만 — 저절로 놓인 포커스는 무시) */
    private fun renderSetlistTabs(current: com.mrgq.pdfviewer.scoremate.Setlist?) {
        val show = scoreMateLinked && setlists.isNotEmpty()
        // 숨길 때도 자리는 남긴다(INVISIBLE) — 오른쪽 끝 ☁️ 동기화 버튼을 밀어 두는 칸이다
        binding.setlistScroll.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (!show) {
            renderedSetlistIds = emptyList()
            binding.setlistTabs.removeAllViews()
            return
        }
        val ids: List<Long?> = setlists.map { it.id }
        if (ids != renderedSetlistIds) {
            binding.setlistTabs.removeAllViews()
            ids.forEach { id ->
                binding.setlistTabs.addView(android.widget.TextView(this).apply {
                    tag = id
                    textSize = 17f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(36, 14, 36, 14)
                    setBackgroundResource(R.drawable.setlist_tab_background)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { selectSetlist(id) }
                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus && android.os.SystemClock.uptimeMillis() - lastKeyAtMs < TAB_FOCUS_KEY_WINDOW_MS) selectSetlist(id)
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = 12 }
                })
            }
            renderedSetlistIds = ids
        }
        for (i in 0 until binding.setlistTabs.childCount) {
            val view = binding.setlistTabs.getChildAt(i) as android.widget.TextView
            val id = view.tag as Long?
            val setlist = setlists.firstOrNull { it.id == id }
            view.text = if (setlist == null) "" else "${setlist.title} (${setlist.items.size})"
            view.isSelected = id == current?.id
        }
    }

    private fun showLibrary() {
        // 연결된 TV 는 세트리스트로 본다. 고른 것이 없거나 없어졌으면(곡목 해제) 첫 세트리스트
        val current = if (scoreMateLinked) {
            setlists.firstOrNull { it.id == selectedSetlistId } ?: setlists.firstOrNull()
        } else null
        renderSetlistTabs(current)
        binding.scoreMateSyncBtn.visibility = if (scoreMateLinked) View.VISIBLE else View.GONE
        // 연결된 TV 는 정렬(늘 곡 순서) · 파일관리(서버가 관리 — 웹에서 곡목 고르기)가 필요 없다 → 그 줄을 통째로 숨긴다.
        // ☁️ 동기화는 세트리스트 줄 오른쪽 끝에 있다
        binding.controlStrip.visibility = if (scoreMateLinked) View.GONE else View.VISIBLE
        if (scoreMateLinked && isFileManagementMode) {
            isFileManagementMode = false
            updateFileManagementUI()
            pdfAdapter.setFileManagementMode(false)
        }

        val shown: List<PdfFile>
        if (current != null) {
            // 곡 순서대로 — 정렬 버튼은 쓰지 않는다. 이 TV 에 아직 없는 곡(받는 중 · 숨김)은 빼고 개수로 알린다
            val entries = com.mrgq.pdfviewer.scoremate.ScoreMateSetlists.entries(current, syncedRows)
            val byPath = allPdfFiles.associateBy { it.path }
            shown = entries.mapNotNull { entry ->
                entry.path?.let { byPath[it] }?.copy(setlistPosition = entry.item.position, setlistNotes = entry.item.notes)
            }
            val missing = entries.size - shown.size
            // 세트리스트 이름은 줄에 이미 보인다 — 여기는 아직 받지 않은 곡이 있을 때만 알린다
            binding.librarySource.text = "☁️ ScoreMate" + if (missing > 0) " · ${entries.size}곡 중 ${shown.size}곡" else ""
            binding.emptyTitle.text = "이 세트리스트의 악보를 아직 받지 않았습니다"
            binding.emptyHint.text = "☁️ 동기화 를 누르세요"
        } else if (scoreMateLinked) {
            // 받은 세트리스트가 없다 — 곡목에 없는 악보는 보이지 않는다
            shown = emptyList()
            binding.librarySource.text = "☁️ ScoreMate"
            binding.emptyTitle.text = "받은 세트리스트가 없습니다"
            binding.emptyHint.text = "웹에서 이 TV 로 보낼 세트리스트를 고른 뒤 ☁️ 동기화 를 누르세요"
        } else {
            shown = allPdfFiles
            binding.librarySource.text = "이 기기 파일 ${allPdfFiles.size}"
            binding.emptyTitle.text = "PDF 파일이 없습니다"
            binding.emptyHint.text = "설정에서 웹서버를 통해 파일을 업로드하세요"
        }
        pdfAdapter.submitList(shown)
        binding.emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }
    
    private fun setupCollaborationButton() {
        binding.collaborationButton.setOnClickListener {
            // Show collaboration options dialog
            showCollaborationDialog()
        }
        
        // Update collaboration status on startup
        updateCollaborationStatus()
    }
    
    private fun showCollaborationDialog() {
        val globalManager = GlobalCollaborationManager.getInstance()
        val currentMode = globalManager.getCurrentMode()
        
        val options = when (currentMode) {
            CollaborationMode.NONE -> arrayOf("지휘자 모드 시작", "연주자 모드 시작", "취소")
            CollaborationMode.CONDUCTOR -> arrayOf("지휘자 모드 종료", "취소")
            CollaborationMode.PERFORMER -> arrayOf("연주자 모드 종료", "취소")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("합주 모드")
            .setItems(options) { _, which ->
                when (currentMode) {
                    CollaborationMode.NONE -> {
                        when (which) {
                            0 -> startConductorMode()
                            1 -> showPerformerDialog()
                        }
                    }
                    CollaborationMode.CONDUCTOR -> {
                        if (which == 0) stopCollaborationMode()
                    }
                    CollaborationMode.PERFORMER -> {
                        if (which == 0) stopCollaborationMode()
                    }
                }
            }
            .show()
    }
    
    /**
     * Clean, consistent flow for conductor mode:
     * 1. Activate conductor mode (this clears callbacks)
     * 2. Setup callbacks after activation 
     * 3. Update status
     */
    private fun startConductorMode() {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        Log.d("MainActivity", "🎯 Starting conductor mode")
        
        // STEP 1: Activate conductor mode FIRST (this clears callbacks)
        val success = globalManager.activateConductorMode()
        
        if (success) {
            Toast.makeText(this, "지휘자 모드가 시작되었습니다", Toast.LENGTH_SHORT).show()
            
            // STEP 2: Setup ALL callbacks AFTER mode activation (so they don't get cleared)
            Log.d("MainActivity", "🎯 Setting up conductor callbacks")
            setupCollaborationCallbacks()
            
            // STEP 3: Update collaboration status
            updateCollaborationStatus()
            
            Log.d("MainActivity", "🎯 Conductor mode ready - waiting for performers")
        } else {
            Toast.makeText(this, "지휘자 모드 시작 실패", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showPerformerDialog() {
        // 기본값으로 자동 발견 모드로 바로 시작
        startPerformerModeWithAutoDiscovery()
        
        // 자동 발견 실패 시 수동 연결 옵션 제공을 위한 타임아웃 설정
        binding.collaborationStatus.postDelayed({
            // 만약 아직 연결되지 않았다면 수동 연결 옵션 제공
            val globalManager = GlobalCollaborationManager.getInstance()
            if (globalManager.getCurrentMode() == CollaborationMode.PERFORMER && !globalManager.isClientConnected()) {
                showManualConnectionOption()
            }
        }, 15000) // 15초 후 수동 연결 옵션 제공
    }
    
    private fun showManualConnectionOption() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("지휘자 연결")
            .setMessage("자동 발견에 실패했습니다.\n수동으로 지휘자 IP를 입력하시겠습니까?")
            .setPositiveButton("수동 입력") { _, _ ->
                showManualConnectionDialog()
            }
            .setNegativeButton("계속 자동 발견", null)
            .setNeutralButton("연주자 모드 종료") { _, _ ->
                stopCollaborationMode()
            }
            .show()
    }
    
    /**
     * Clean, linear flow for performer mode with discovery:
     * 1. Setup all callbacks first
     * 2. Activate performer mode
     * 3. Start discovery
     */
    private fun startPerformerModeWithAutoDiscovery() {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        Log.d("MainActivity", "🎯 Starting performer mode with auto-discovery")
        
        // STEP 1: Activate performer mode FIRST (this clears callbacks)
        val success = globalManager.activatePerformerMode()
        
        if (success) {
            updateCollaborationStatus()
            Toast.makeText(this, "연주자 모드 시작 - 지휘자 자동 검색 중...", Toast.LENGTH_SHORT).show()
            
            // STEP 2: Setup ALL callbacks AFTER mode activation (so they don't get cleared)
            Log.d("MainActivity", "🎯 Setting up collaboration callbacks")
            setupCollaborationCallbacks()
            
            // STEP 3: Setup discovery-specific callbacks
            Log.d("MainActivity", "🎯 Setting up discovery callbacks")
            
            globalManager.setOnConductorDiscovered { conductorInfo ->
                runOnUiThread {
                    Log.d("MainActivity", "🎯 Conductor discovered in UI: ${conductorInfo.name} at ${conductorInfo.ipAddress}")
                    
                    // Auto-connect to discovered conductor
                    val connected = globalManager.connectToDiscoveredConductor(conductorInfo)
                    if (connected) {
                        Toast.makeText(this, "지휘자 발견 - 연결 중...", Toast.LENGTH_SHORT).show()
                        // Stop discovery after successful connection attempt
                        globalManager.stopConductorDiscovery()
                    } else {
                        Toast.makeText(this, "지휘자 연결 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            globalManager.setOnDiscoveryTimeout {
                runOnUiThread {
                    Log.d("MainActivity", "🎯 Discovery timeout in UI")
                    Toast.makeText(this, "지휘자를 찾을 수 없습니다. 수동 연결을 시도해보세요.", Toast.LENGTH_LONG).show()
                }
            }
            
            // STEP 4: Start discovery manually with proper callback setup
            Log.d("MainActivity", "🎯 Starting conductor discovery")
            val discoveryStarted = globalManager.startConductorDiscovery()
            
            if (!discoveryStarted) {
                Toast.makeText(this, "자동 검색 시작 실패", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "연주자 모드 시작 실패", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showConductorFoundDialog(conductorInfo: com.mrgq.pdfviewer.ConductorDiscovery.ConductorInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("지휘자 발견")
            .setMessage("${conductorInfo.name}\nIP: ${conductorInfo.ipAddress}\n\n연결하시겠습니까?")
            .setPositiveButton("연결") { _, _ ->
                val globalManager = GlobalCollaborationManager.getInstance()
                val connected = globalManager.connectToDiscoveredConductor(conductorInfo)
                if (connected) {
                    Toast.makeText(this, "지휘자에 연결 중...", Toast.LENGTH_SHORT).show()
                    // 상태 업데이트는 콜백에서 처리되도록 함
                } else {
                    Toast.makeText(this, "연결 실패", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun showManualConnectionDialog() {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        // 연주자 모드가 아니면 먼저 시작
        if (globalManager.getCurrentMode() != CollaborationMode.PERFORMER) {
            val success = globalManager.activatePerformerMode()
            if (!success) {
                Toast.makeText(this, "연주자 모드 시작 실패", Toast.LENGTH_SHORT).show()
                return
            }
            updateCollaborationStatus()
        }
        
        val input = android.widget.EditText(this)
        input.hint = "192.168.1.100"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("수동 연결")
            .setMessage("지휘자의 IP 주소를 입력하세요:")
            .setView(input)
            .setPositiveButton("연결") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    connectToManualConductor(ip)
                } else {
                    Toast.makeText(this, "IP 주소를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun connectToManualConductor(ip: String) {
        val globalManager = GlobalCollaborationManager.getInstance()
        
        // Connect to conductor (performer mode should already be active)
        val deviceName = android.os.Build.MODEL ?: "Android TV"
        val connected = globalManager.connectToConductor(ip, 9090, "$deviceName (연주자)")
        
        if (connected) {
            Toast.makeText(this, "지휘자에 연결 중... ($ip)", Toast.LENGTH_SHORT).show()
            // 상태 업데이트는 콜백에서 처리되도록 함
        } else {
            Toast.makeText(this, "연결 실패. IP 주소와 지휘자 상태를 확인해주세요.", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopCollaborationMode() {
        val globalManager = GlobalCollaborationManager.getInstance()
        globalManager.deactivateCollaborationMode()
        Toast.makeText(this, "합주 모드가 종료되었습니다", Toast.LENGTH_SHORT).show()
        updateCollaborationStatus()
    }
    
    private fun openPdfFile(pdfFile: PdfFile, position: Int) {
        val currentPdfFiles = pdfAdapter.currentList
        
        // Use the stable file path to find the ACTUAL current index.
        // This is the crucial fix for the race condition.
        val actualIndex = currentPdfFiles.indexOfFirst { it.path == pdfFile.path }
        
        // Safety check: if file was deleted in the meantime, abort.
        if (actualIndex == -1) {
            Toast.makeText(this, getString(R.string.error_file_not_found_or_moved), Toast.LENGTH_SHORT).show()
            return
        }
        
        val filePathList = currentPdfFiles.map { it.path }
        val fileNameList = currentPdfFiles.map { it.name }
        
        Log.d("MainActivity", "Opening file: ${pdfFile.name} at actual index $actualIndex (clicked position was $position)")
        Log.d("MainActivity", "File list size: ${currentPdfFiles.size}")
        Log.d("MainActivity", "Actual file at index $actualIndex: ${currentPdfFiles[actualIndex].name}")
        
        // 지휘자 모드에서 파일 변경 브로드캐스트
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        if (globalCollaborationManager.getCurrentMode() == CollaborationMode.CONDUCTOR) {
            Log.d("MainActivity", "🎵 지휘자 모드: 파일 선택 브로드캐스트 - ${pdfFile.name}")
            globalCollaborationManager.addFileToServer(pdfFile.name, pdfFile.path)
            // 내용 해시도 — 연주자가 내용으로 찾는다(서버 · 계정 무관, 판까지 맞다 — #063). 로컬 파일은 한 번 계산해 기억
            val sha256 = com.mrgq.pdfviewer.ensemble.EnsembleFiles.sha256Of(File(pdfFile.path), pdfFile.sha256)
            globalCollaborationManager.broadcastFileChange(pdfFile.name, 1, sha256) // 첫 페이지로
        }
        
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            // Use the reliable, just-in-time calculated index.
            putExtra(PdfViewerActivity.EXTRA_CURRENT_INDEX, actualIndex)
            putStringArrayListExtra(PdfViewerActivity.EXTRA_FILE_PATH_LIST, ArrayList(filePathList))
            putStringArrayListExtra(PdfViewerActivity.EXTRA_FILE_NAME_LIST, ArrayList(fileNameList))
        }
        startActivity(intent)
    }
    
    private suspend fun getCurrentPdfFiles(linked: Boolean): List<PdfFile> {
        val pdfFiles = mutableListOf<PdfFile>()
        
        // Load from app's external files directory (uploaded via web server)
        val appPdfDir = File(getExternalFilesDir(null), "PDFs")
        // ScoreMate 에서 받은 악보 (PDFs/ScoreMate/<앙상블>/) — 경로 → 표시 이름 (P05 C2)
        val synced = try {
            scoreMateLocal.all().filter { !it.hidden }.associateBy { it.filePath }
        } catch (e: Exception) {
            emptyMap()
        }
        if (appPdfDir.exists() && appPdfDir.isDirectory) {
            PdfLibrary.listPdfFiles(appPdfDir, scoreMate = linked).forEach { file ->
                // 페이지 수·문서 정보는 DB 에 캐시된다. 처음 보거나 바뀐 파일만 분석한다 (PdfFileSync).
                val record = try {
                    musicRepository.syncPdfFile(file)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error syncing PDF record for ${file.name}", e)
                    null
                }
                pdfFiles.add(PdfFile(
                    name = file.name,
                    path = file.absolutePath,
                    lastModified = file.lastModified(),
                    size = file.length(),
                    pageCount = record?.totalPages ?: 0,
                    title = record?.title,
                    author = record?.author,
                    cloudLabel = synced[file.path]?.let { cloudLabel(it) }
                        ?: PdfLibrary.scoreMateGroupOf(appPdfDir, file),
                    sha256 = synced[file.path]?.sha256,
                    serverTitle = synced[file.path]?.title,
                    composer = synced[file.path]?.composer,
                    partName = synced[file.path]?.partName,
                    arranger = synced[file.path]?.arranger,
                ))
            }
        }
        
        // 정렬은 PdfFileSorter 가 담당한다. 목록 순서는 표시 문제가 아니라 **정합성 문제**다 —
        // PdfViewerActivity 에 인덱스로 파일을 넘기므로 순서가 흔들리면 다른 파일이 열린다(#030~#032).
        // 자연 정렬(악보2 < 악보10) + 동률 없는 전순서로 그 흔들림을 없앤다.
        val sorted = PdfFileSorter.sort(pdfFiles, currentSortBy)
        pdfFiles.clear()
        pdfFiles.addAll(sorted)
        
        Log.d("MainActivity", "=== LOADED ${pdfFiles.size} PDF FILES FROM APP DIRECTORY ===")
        pdfFiles.forEachIndexed { index, file ->
            Log.d("MainActivity", "[$index] NAME: '${file.name}' PATH: '${file.path}' PAGES: ${file.pageCount}")
        }
        Log.d("MainActivity", "=== END FILE LIST ===")
        
        return pdfFiles
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // RecyclerView will handle these automatically
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MENU -> {
                // Menu key reserved for future use
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onPause() {
        super.onPause()
        com.mrgq.pdfviewer.ensemble.VersionNotice.detach()
        binding.root.removeCallbacks(autoUpdateCheck)
        // Note: 웹서버 관리는 이제 설정 화면에서 담당
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
        handleFileRequest()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        updateController.dispose()
        
        // Clear collaboration callbacks to prevent memory leaks
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        globalCollaborationManager.setOnServerClientConnected { _, _ -> }
        globalCollaborationManager.setOnServerClientDisconnected { _ -> }
        globalCollaborationManager.setOnClientConnectionStatusChanged { _ -> }
        globalCollaborationManager.setOnFileChangeReceived { _, _, _ -> }
        
        // Force stop all collaboration modes to prevent port conflicts
        Log.d("MainActivity", "Forcing collaboration mode cleanup on app destruction")
        globalCollaborationManager.deactivateCollaborationMode()
    }
    
    fun refreshFileList() {
        loadPdfFiles()
    }
    
    private fun setupCollaborationCallbacks() {
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        
        // Set up file change callback for performer mode
        globalCollaborationManager.setOnFileChangeReceived { fileName, page, sha256 ->
            runOnUiThread {
                handleRemoteFileChange(fileName, page, sha256)
            }
        }
        
        // Set up connection status callbacks
        globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
            runOnUiThread {
                Log.d("MainActivity", "🎼 지휘자 모드: 연주자 연결됨 - $deviceName")
                updateCollaborationStatus()
                Toast.makeText(this, "연주자 '$deviceName'이 연결되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
        
        globalCollaborationManager.setOnServerClientDisconnected { clientId ->
            runOnUiThread {
                Log.d("MainActivity", "🎼 지휘자 모드: 연주자 연결 해제됨")
                updateCollaborationStatus()
                Toast.makeText(this, "연주자 연결이 해제되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
        
        globalCollaborationManager.setOnClientConnectionStatusChanged { isConnected ->
            runOnUiThread {
                Log.d("MainActivity", "🎼 연주자 모드: 연결 상태 콜백 - $isConnected")
                
                // 콜백 직후 실제 상태도 확인
                val actualConnected = globalCollaborationManager.isClientConnected()
                val conductorAddress = globalCollaborationManager.getConductorAddress()
                Log.d("MainActivity", "🎼 실제 연결 상태: $actualConnected, 지휘자 주소: $conductorAddress")
                
                // 즉시 상태 업데이트
                updateCollaborationStatus()
                
                // 추가로 약간 지연 후에도 상태 업데이트 (안전장치)
                binding.collaborationStatus.postDelayed({
                    Log.d("MainActivity", "🎼 200ms 후 추가 상태 업데이트 실행")
                    updateCollaborationStatus()
                }, 200)
                
                // 연결 성공 시에만 토스트 메시지 표시
                if (isConnected) {
                    Toast.makeText(this, "지휘자에 연결되었습니다", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "지휘자와의 연결이 끊어졌습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun handleFileRequest() {
        // Check if we were called with a specific file request
        val requestedFile = intent.getStringExtra("requested_file")
        if (requestedFile != null) {
            Log.d("MainActivity", "🎼 연주자 모드: SettingsActivity로부터 파일 요청 받음 - $requestedFile")
            
            // Wait for file list to load, then try to open the requested file
            binding.recyclerView.post {
                val currentFiles = allPdfFiles
                if (currentFiles.isNotEmpty()) {
                    val fileIndex = currentFiles.indexOfFirst { it.name == requestedFile }
                    if (fileIndex >= 0) {
                        val pdfFile = currentFiles[fileIndex]
                        openPdfFile(pdfFile, fileIndex)
                    } else {
                        // File not found, try to download
                        val requestedPage = intent.getIntExtra("requested_page", 1)
                        handleRemoteFileChange(requestedFile, requestedPage)
                    }
                } else {
                    // File list not loaded yet, try again after a delay
                    binding.recyclerView.postDelayed({
                        val requestedPage = intent.getIntExtra("requested_page", 1)
                        handleRemoteFileChange(requestedFile, requestedPage)
                    }, 1000)
                }
            }
            
            // Clear the intent extras to prevent re-processing
            intent.removeExtra("requested_file")
            intent.removeExtra("requested_page")
        }
    }
    
    /**
     * 연주자: 지휘자가 파일을 바꿨다 (#063). 연결된 TV 는 내용 해시로 내 ScoreMate 에서, 없으면 캐시에서 찾고 없으면 캐시로 받는다.
     * 연결하지 않은 TV 는 이름으로 찾고 없으면 `PDFs/` 에 받는다 (EnsembleFiles)
     */
    private fun handleRemoteFileChange(fileName: String, page: Int = 1, sha256: String? = null) {
        Log.d("MainActivity", "🎼 연주자 모드: 파일 '$fileName' 변경 요청 (페이지 $page, sha256=${sha256?.take(12)})")
        lifecycleScope.launch {
            val linked = scoreMateLinked || com.mrgq.pdfviewer.scoremate.ScoreMateStore(this@MainActivity).tokens != null
            val synced = if (linked) withContext(Dispatchers.IO) { scoreMateLocal.all() } else emptyList()
            val resolution = com.mrgq.pdfviewer.ensemble.EnsembleFiles.resolve(
                fileName = fileName,
                sha256 = sha256,
                linked = linked,
                listed = allPdfFiles.map { it.name to it.path },
                synced = synced,
                pdfRoot = File(getExternalFilesDir(null), "PDFs"),
                cacheDir = ensembleCacheDir,
            )
            when (resolution) {
                is com.mrgq.pdfviewer.ensemble.EnsembleFiles.Resolution.Open -> openRemoteFile(resolution.path, page)
                is com.mrgq.pdfviewer.ensemble.EnsembleFiles.Resolution.Download -> {
                    val conductorAddress = GlobalCollaborationManager.getInstance().getConductorAddress()
                    if (conductorAddress.isNotEmpty()) {
                        showDownloadDialog(fileName, conductorAddress, resolution.target, resolution.cached, page)
                    } else {
                        Toast.makeText(this@MainActivity, "요청된 파일을 찾을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** 지휘자가 고른 파일을 연다. 서재에 없는 파일(캐시)이면 목록 끝에 붙여 넘긴다 — 이전/다음은 서재 안에서 */
    private fun openRemoteFile(path: String, page: Int) {
        val files = pdfAdapter.currentList
        val paths = files.map { it.path }.toMutableList()
        val names = files.map { it.name }.toMutableList()
        var index = paths.indexOf(path)
        if (index < 0) {
            paths += path
            names += File(path).name
            index = paths.lastIndex
            if (path.startsWith(ensembleCacheDir.path)) com.mrgq.pdfviewer.ensemble.EnsembleFiles.touch(File(path))
        }
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            putExtra("current_index", index)
            putExtra("target_page", page)
            putStringArrayListExtra("file_path_list", ArrayList(paths))
            putStringArrayListExtra("file_name_list", ArrayList(names))
        }
        startActivity(intent)
    }

    private fun showDownloadDialog(fileName: String, conductorAddress: String, target: File, cached: Boolean, page: Int) {
        val where = if (cached) "\n(이 기기의 ScoreMate 에 없는 악보 — 합주용으로만 받아 두고 목록에는 넣지 않습니다)" else ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("파일 다운로드")
            .setMessage("'$fileName' 파일이 없습니다.\n지휘자로부터 다운로드하시겠습니까?$where")
            .setPositiveButton("다운로드") { _, _ ->
                downloadFileFromConductor(fileName, conductorAddress, target, cached, page)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 지휘자 파일 서버에서 [target] 으로 받는다 (`.part` → 이름 바꾸기). 캐시면 상한을 넘은 오래된 파일을 지운다 */
    private fun downloadFileFromConductor(fileName: String, conductorAddress: String, target: File, cached: Boolean, page: Int) {
        val ipAddress = conductorAddress.split(":")[0]
        val fileServerUrl = "http://$ipAddress:8090"
        Log.d("MainActivity", "🎼 연주자 모드: 파일 다운로드 시작 - $fileServerUrl → $target")

        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("다운로드 중...")
            .setMessage("$fileName\n0%")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val part = File(target.path + ".part")
            try {
                target.parentFile?.mkdirs()
                val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                val connection = java.net.URL("$fileServerUrl/download/$encodedFileName").openConnection()
                connection.connect()
                val fileLength = connection.contentLength
                connection.getInputStream().use { input ->
                    java.io.FileOutputStream(part).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                withContext(Dispatchers.Main) { progressDialog.setMessage("$fileName\n$progress%") }
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) throw java.io.IOException("받은 파일을 저장하지 못했습니다")
                }
                if (cached) com.mrgq.pdfviewer.ensemble.EnsembleFiles.trimCache(ensembleCacheDir, keep = target)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Log.d("MainActivity", "🎼 연주자 모드: 파일 다운로드 완료 - $target")
                    Toast.makeText(this@MainActivity, "파일 다운로드 완료: $fileName", Toast.LENGTH_SHORT).show()
                    if (!cached) loadPdfFiles() // 로컬 서재에 들어간다
                    openRemoteFile(target.path, page)
                }
            } catch (e: Exception) {
                part.delete()
                Log.e("MainActivity", "🎼 연주자 모드: 파일 다운로드 오류", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "다운로드 오류: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupSortButtons() {
        binding.sortByNameBtn.setOnClickListener {
            currentSortBy = PdfFileSorter.BY_NAME
            updateSortButtonStates()
            loadPdfFiles()
        }
        
        binding.sortByTimeBtn.setOnClickListener {
            currentSortBy = PdfFileSorter.BY_TIME
            updateSortButtonStates()
            loadPdfFiles()
        }
        
        // Set initial button states
        updateSortButtonStates()
    }
    
    private fun updateSortButtonStates() {
        // Update sort buttons with clear visual distinction for TV
        val selectedColor = ContextCompat.getColor(this, R.color.tv_primary)
        val unselectedColor = ContextCompat.getColor(this, R.color.tv_text_secondary)
        
        if (currentSortBy == PdfFileSorter.BY_NAME) {
            binding.sortByNameBtn.setTextColor(selectedColor)
            binding.sortByNameBtn.alpha = 1.0f
            binding.sortByTimeBtn.setTextColor(unselectedColor)
            binding.sortByTimeBtn.alpha = 0.8f
        } else {
            binding.sortByNameBtn.setTextColor(unselectedColor)
            binding.sortByNameBtn.alpha = 0.8f
            binding.sortByTimeBtn.setTextColor(selectedColor)
            binding.sortByTimeBtn.alpha = 1.0f
        }
    }
    
    private fun showDeleteConfirmationDialog(pdfFile: PdfFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("파일 삭제")
            .setMessage("${pdfFile.name} 파일을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                deletePdfFile(pdfFile)
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    private fun deletePdfFile(pdfFile: PdfFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(pdfFile.path)
                val deleted = file.delete()
                // ScoreMate 에서 받은 악보면 숨긴다 — 다음 동기화에 다시 받지 않게 (설정 → ScoreMate → 숨긴 악보 다시 받기)
                if (deleted && pdfFile.cloudLabel != null) {
                    scoreMateSync().markHidden(file.path)
                }
                
                withContext(Dispatchers.Main) {
                    if (deleted) {
                        Toast.makeText(this@MainActivity, "파일이 삭제되었습니다", Toast.LENGTH_SHORT).show()
                        loadPdfFiles()
                    } else {
                        Toast.makeText(this@MainActivity, "파일 삭제 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "삭제 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun setupSettingsButton() {
        binding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun setupFileManagementButton() {
        binding.fileManageBtn.setOnClickListener {
            isFileManagementMode = !isFileManagementMode
            updateFileManagementUI()
            pdfAdapter.setFileManagementMode(isFileManagementMode)
        }
        
        // 초기 상태 설정
        updateFileManagementUI()
    }
    
    private fun updateFileManagementUI() {
        if (isFileManagementMode) {
            binding.fileManageBtn.text = "완료"
            binding.fileManageBtn.alpha = 1.0f
        } else {
            binding.fileManageBtn.text = "파일관리"
            binding.fileManageBtn.alpha = 0.7f
        }
    }
    
    private fun updateCollaborationStatus() {
        val globalCollaborationManager = GlobalCollaborationManager.getInstance()
        val currentMode = globalCollaborationManager.getCurrentMode()
        
        when (currentMode) {
            CollaborationMode.CONDUCTOR -> {
                val clientCount = globalCollaborationManager.getConnectedClientCount()
                binding.collaborationStatus.text = "🎼 지휘자 모드 활성 (연결된 연주자: ${clientCount}명)"
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
                binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_secondary)) // 녹색으로 변경
            }
            CollaborationMode.PERFORMER -> {
                val isConnected = globalCollaborationManager.isClientConnected()
                val conductorAddress = globalCollaborationManager.getConductorAddress()
                val conductorIp = if (conductorAddress.contains(":")) {
                    conductorAddress.split(":")[0]
                } else conductorAddress
                
                Log.d("MainActivity", "🎼 updateCollaborationStatus - 연주자 모드")
                Log.d("MainActivity", "🎼   isConnected: $isConnected")
                Log.d("MainActivity", "🎼   conductorAddress: '$conductorAddress'")
                Log.d("MainActivity", "🎼   conductorIp: '$conductorIp'")
                
                // 더 관대한 연결 상태 판단: isConnected가 true이면 연결된 것으로 간주
                if (isConnected) {
                    val displayIp = if (conductorIp.isNotEmpty()) conductorIp else "지휘자"
                    binding.collaborationStatus.text = "🎵 연주자 모드 (지휘자: $displayIp)"
                    binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_secondary)) // 녹색으로 변경
                    Log.d("MainActivity", "🎼 UI 업데이트: 연결됨 상태로 표시 - '$displayIp'")
                } else {
                    binding.collaborationStatus.text = "🎵 연주자 모드 (연결 끊김)"
                    binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_error))
                    Log.d("MainActivity", "🎼 UI 업데이트: 연결 끊김 상태로 표시")
                }
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
            }
            CollaborationMode.NONE -> {
                binding.collaborationStatus.text = "합주 모드: 비활성"
                binding.collaborationStatus.visibility = android.view.View.VISIBLE
                binding.collaborationStatus.setTextColor(ContextCompat.getColor(this, R.color.tv_text_secondary))
            }
        }
        
        Log.d("MainActivity", "협업 상태 업데이트: $currentMode")
    }
    
    private fun addFadeInAnimations() {
        // Initially hide all animated elements
        binding.headerSection.alpha = 0f
        binding.controlStrip.alpha = 0f
        binding.recyclerView.alpha = 0f
        
        // Animate elements sequentially
        binding.headerSection.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(100)
            .start()
        
        binding.controlStrip.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(200)
            .start()
        
        binding.recyclerView.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(300)
            .start()
    }
}