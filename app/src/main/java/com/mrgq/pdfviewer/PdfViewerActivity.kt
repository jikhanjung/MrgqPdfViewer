package com.mrgq.pdfviewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.mrgq.pdfviewer.databinding.ActivityPdfViewerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import android.content.SharedPreferences
import android.util.DisplayMetrics
import java.io.File
import com.mrgq.pdfviewer.database.entity.DisplayMode
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.UserPreference
import com.mrgq.pdfviewer.repository.MusicRepository
import com.mrgq.pdfviewer.utils.PdfAnalyzer
import com.mrgq.pdfviewer.database.entity.ScoreMeasure
import com.mrgq.pdfviewer.score.ScoreOverlayGeometry
import com.mrgq.pdfviewer.score.PartLayout
import com.mrgq.pdfviewer.score.PartPdfBuilder
import com.mrgq.pdfviewer.score.ScoreParts
import com.mrgq.pdfviewer.score.PartStaves
import com.mrgq.pdfviewer.menu.Menu
import com.mrgq.pdfviewer.menu.MenuAction
import com.mrgq.pdfviewer.menu.ViewerMenus
import com.mrgq.pdfviewer.voice.CommandOutcome
import com.mrgq.pdfviewer.voice.CommandParser
import com.mrgq.pdfviewer.voice.PartMatcher
import com.mrgq.pdfviewer.voice.PartNames
import com.mrgq.pdfviewer.voice.PartRef
import com.mrgq.pdfviewer.voice.ViewerCommands
import com.mrgq.pdfviewer.voice.VoiceLexicon
import com.mrgq.pdfviewer.voice.VoiceListener
import com.mrgq.pdfviewer.voice.VoiceCommandRunner
import com.mrgq.pdfviewer.voice.WakeWord
import com.mrgq.pdfviewer.metronome.Accent
import com.mrgq.pdfviewer.metronome.MetronomeClock
import com.mrgq.pdfviewer.metronome.MetronomeEngine
import com.mrgq.pdfviewer.metronome.Beat
import com.mrgq.pdfviewer.metronome.ScoreFollower
import com.mrgq.pdfviewer.metronome.SectionSpan
import com.mrgq.pdfviewer.metronome.TempoRelation
import com.mrgq.pdfviewer.metronome.TempoSection
import com.mrgq.pdfviewer.metronome.TempoSectionSetting
import com.mrgq.pdfviewer.metronome.TempoSections
import com.mrgq.pdfviewer.metronome.TimeSignature
import com.mrgq.pdfviewer.score.ScoreOverlayView
import com.mrgq.pdfviewer.ensemble.BeatTimeline
import com.mrgq.pdfviewer.ensemble.EnsembleRun
import com.mrgq.pdfviewer.ensemble.EnsembleSchedule
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import android.os.Handler
import android.os.Looper

class PdfViewerActivity : AppCompatActivity() {
    
    companion object {
        /** 휴대폰 보기: 쪽의 마지막 조각 ([pendingChunk]) */
        private const val LAST_CHUNK = Int.MAX_VALUE
        /** 태블릿: 이만큼(dp) 넘게 가로로 밀면 쪽 넘김 */
        private const val SWIPE_MIN_DP = 60f
        private const val REQUEST_MIC_FOLLOW = 7302
        /** 차례 넘김: 지휘자가 쪽에 들어선 뒤 다 친 쪽을 바꾸기까지 — 지휘자 넘김은 최악 3.7초 일찍이었다(P08 §7-2) */
        private const val ROLL_DELAY_MS = 5000L
        /** 듣기 시작 펼침(#088): 쪽이 아직 그려지지 않았으면 이 간격으로 이만큼 다시 해 본다(약 3초) */
        private const val ROLL_START_RETRY_MS = 200L
        private const val ROLL_START_ATTEMPTS = 15
        // 악보 메모 (P11) — 보이기는 전역, 펜 색 · 굵기는 마지막 고른 것
        private const val PREF_SHOW_NOTES = "show_score_notes"
        private const val PREF_NOTE_COLOR = "note_pen_color"
        private const val PREF_NOTE_WIDTH = "note_pen_width"
        private const val PREF_NOTE_TEXT_SIZE = "note_text_size"
        private val NOTE_WIDTH_LABELS = listOf("╌", "─", "━")
        private val NOTE_TEXT_SIZE_LABELS = listOf("가", "가", "가")
        private val NOTE_TEXT_SIZE_SP = floatArrayOf(13f, 18f, 24f)
        /** 획 줄이기 허용 오차 (pt) — 오선 두께(≈ 0.5pt)보다 작게 */
        private const val STROKE_TOLERANCE_PT = 0.25f
        /** 메모 모드 확대 한도 — 쪽은 화면 크기로 렌더하므로 더 키우면 흐릿하다 */
        private const val NOTE_ZOOM_MAX = 4f
        /** 다 그은 뒤 붙은 보표 표시를 남겨 두는 시간 */
        private const val STAFF_HINT_MS = 1200L
        // Intent extra keys
        const val EXTRA_CURRENT_INDEX = "current_index"
        const val EXTRA_FILE_PATH_LIST = "file_path_list"
        const val EXTRA_FILE_NAME_LIST = "file_name_list"

        // 메트로놈 클릭음 설정 (전역). 템포·박자는 파일별로 DB 에 저장한다
        private const val PREF_METRONOME_SOUND = "metronome_sound_enabled"
        private const val PREF_METRONOME_VOLUME = "metronome_volume"
        /** 악보 연동 예비박 마디 수 — 기본 2, 1 도 고를 수 있다 (전역, #060) */
        private const val PREF_METRONOME_COUNT_IN_BARS = "metronome_count_in_bars"
        /** 반주 (MusicXML, P07 6단계) 켜기 — 전역, 기본 켜짐 (MusicXML 이 있는 악보에서만 뜻이 있다) */
        private const val PREF_ACCOMPANIMENT = "metronome_accompaniment"
        /** 반주 소리 크기 — 메트로놈 클릭과 따로 (사용자 요청 2026-09-28), 0~1 */
        private const val PREF_ACCOMPANIMENT_VOLUME = "metronome_accompaniment_volume"
        private const val DEFAULT_COUNT_IN_BARS = 2
        /** 👂 계속 듣기 (#085) — 🎙 를 누르지 않고 "메이트, …" */
        const val PREF_VOICE_ALWAYS = "voice_always_listen"
        /** 👂 계속 듣기를 쓸 수 있나 — 잠시 꺼 둠(#093, 효용이 낮아 다듬을 때까지). 켜 둔 기기도 듣지 않는다 */
        const val VOICE_ALWAYS_AVAILABLE = false
        /** 👂 계속 듣기의 호출어 (#091) — 쉼표로 여럿, 없으면 기본(메이트 · 매이트 · mate) */
        const val PREF_VOICE_WAKE_WORDS = "voice_wake_words"
        /** 휴대폰 악보 화면의 회전 모드 (#090) — 설정 → 표시 모드. 기본 기기 회전 */
        const val PREF_PHONE_ORIENTATION = "phone_orientation"
        const val PHONE_ORIENTATION_AUTO = "auto"
        const val PHONE_ORIENTATION_LANDSCAPE = "landscape"
        const val PHONE_ORIENTATION_PORTRAIT = "portrait"
        /** 파트 보기를 바꾼 명령이 파일이 다시 열리기를 기다리는 한도 */
        private const val FILE_RELOAD_TIMEOUT_MS = 15_000L
        /** 설정 → 앱 정보 → 🎙 음성 명령 (P12 2단계) — 켜면 악보 화면 오른쪽 아래 🎙 단추 */
        const val PREF_VOICE_COMMANDS = "voice_commands"
        private const val REQUEST_VOICE = 7303
        /** 🎙 결과를 화면에 남겨 두는 시간 */
        private const val VOICE_STATUS_MS = 5_000L
        /** 하단 시트 메뉴의 최대 폭 — 태블릿에서 화면 끝까지 늘지 않게 (P17 1단계) */
        private const val MENU_SHEET_MAX_WIDTH_DP = 600
        /** 손을 뗀 뒤에도 이만큼 더 듣는다 — 바로 떼면 끝 음절이 잘린다("50 마디" → "50 마", #084) */
        private const val VOICE_RELEASE_TAIL_MS = 600L
        /** 🎙 를 이보다 짧게 눌렀다 떼면 말하려던 게 아니라 정지 단추로 쓴 것 — 조용히 접는다 (사용자 요청 2026-10-10) */
        private const val VOICE_TAP_MS = 400L
        /** 👂 한 번 듣기가 끝나면 이만큼 뒤에 다시 듣는다 — 오류가 이어지면 두 배씩, [ALWAYS_BACKOFF_MAX_MS] 까지 */
        private const val ALWAYS_RESTART_MS = 250L
        private const val ALWAYS_BACKOFF_MAX_MS = 10_000L
        /** "메이트"만 들린 뒤 이 시간 안의 말은 호출어 없이 받는다 */
        private const val WAKE_WINDOW_MS = 6_000L
        /** "3쪽 시작" — 넘김이 끝나기를 이만큼까지 기다린다 */
        private const val PAGE_SETTLE_TIMEOUT_MS = 2_000L
        private const val VOICE_LOG_MAX_BYTES = 1_000_000L

        /** 악보 연동 자동 넘김: 페이지 마지막 마디가 끝나기 몇 박 전에 넘길지 */
        private const val TURN_LEAD_BEATS = 2
        /** 겹박자를 분모 음표로 세는데 이보다 빠르면 점음표로 세기를 권한다 (#052) — 8분음표 180 = 점4분음표 60 */
        private const val COMPOUND_FAST_BPM = 180
        /** 일시정지 중 포커스가 돌아온 뒤 선택 메뉴를 띄우기까지 — 메뉴 → 설정 대화상자 전환 사이의 틈을 넘긴다 */
        private const val PAUSED_MENU_DELAY_MS = 300L

        /** 합주 메트로놈 (#055): 연주자 기기도 소리를 낼지 (기본 끔 — 소리 기준은 지휘자 기기 하나, 사용자 결정) */
        const val PREF_ENSEMBLE_SOUND = "ensemble_metronome_sound"
        /** 지휘자가 시작을 누른 뒤 첫 박까지 — 연주자에게 알리고 악보를 준비할 시간. 연주자가 없으면 짧게 */
        private const val ENSEMBLE_START_LEAD_NS = 1_500_000_000L
        private const val ENSEMBLE_SOLO_LEAD_NS = 500_000_000L
        /** 연주 중 상태를 다시 보내는 간격 — 유실 · 늦게 연 연주자 대비 */
        private const val ENSEMBLE_REBROADCAST_MS = 5_000L
    }
    
    private lateinit var binding: ActivityPdfViewerBinding
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var pageIndex = 0
    private var pageCount = 0
    private lateinit var pdfFilePath: String
    private lateinit var pdfFileName: String
    private var currentFileIndex = 0
    private var filePathList: List<String> = emptyList()
    private var fileNameList: List<String> = emptyList()
    
    // Navigation guide state
    private var isNavigationGuideVisible = false
    private var navigationGuideType = ""  // "end" or "start"
    
    // Two-page mode
    private var isTwoPageMode = false
    /**
     * 휴대폰 보기 (사용자 요청 2026-10-06): 가로 화면에 한 쪽을 **조각**으로 나눠 차례로 본다.
     * 악보 분석(마디)이 있으면 조각 = 시스템 하나, 화면 높이가 허락하면 둘. 없으면 위 절반 · 아래 절반.
     * 쪽은 화면 폭에 맞춰 한 장으로 렌더하고(쪽 캐시 · 마디 박스 좌표 그대로) 보이는 조각은 행렬로 고른다 —
     * 조각이 화면보다 높으면 그만큼 줄인다. → 는 다음 조각 → 다음 쪽 첫 조각, ← 는 거꾸로
     */
    private val phoneView by lazy { com.mrgq.pdfviewer.utils.DeviceForm.isPhone(this) }
    /** 지금 쪽의 조각들 (표시 비트맵 y 범위) · 지금 보이는 조각 */
    private var phoneChunks: List<ClosedFloatingPointRange<Float>> = emptyList()
    private var chunkIndex = 0
    /** 다음 showPage 가 보일 조각 — ← 로 앞 쪽에 가면 그 쪽의 마지막 조각([LAST_CHUNK]) */
    private var pendingChunk: Int? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var preferences: SharedPreferences
    
    // Collaboration
    private var collaborationMode = CollaborationMode.NONE
    private val globalCollaborationManager = GlobalCollaborationManager.getInstance()
    
    // Input blocking for synchronization
    private var lastSyncTime = 0L
    private fun getInputBlockDuration(): Long {
        return preferences.getLong("input_block_duration", 500L) // Default 0.5 seconds
    }
    
    // Page caching for instant page switching
    private var pageCache: PageCache? = null
    
    // PDF Renderer synchronization to prevent concurrency issues
    private val renderMutex = Mutex()
    
    // Rendering state management
    private var isRenderingInProgress = false
    private var lastRenderTime = 0L
    
    // Database repository
    private lateinit var musicRepository: MusicRepository
    
    // Sound effects
    private var soundPool: SoundPool? = null
    private var pageTurnSoundId: Int = 0
    private var soundsLoaded = false
    private var currentPdfFileId: String? = null

    // 파트보 보기 (P07): 보여 주는 보표 순번 · 이름 — null 이면 전체 악보. 이때 렌더러는 파트 PDF(앱 캐시)를 연다.
    // pdfFilePath · currentPdfFileId 는 원본 그대로다 (설정 · 합주 · 분석은 원본 기준)
    private var partViewStaves: Set<Int>? = null
    private var partViewName: String? = null
    private var partViewLayout: PartLayout? = null

    // 반주 연습 (P07 5 · 6단계): 이 파일 옆 `.musicxml` 을 읽은 것. 없거나 읽지 못하면 null
    private var musicXml: com.mrgq.pdfviewer.score.MusicXmlScore? = null
    private var musicXmlFileId: String? = null

    // 악보 분석 확인용 마디 박스 오버레이 (PDF 표시 옵션에서 켬, 전역 설정)
    private var scoreMeasures: List<ScoreMeasure> = emptyList()
    private var scoreMeasuresFileId: String? = null
    private var scoreLoadingFileId: String? = null
    private fun isScoreOverlayEnabled(): Boolean = preferences.getBoolean("score_overlay_enabled", false)

    // 메트로놈 (PDF 표시 옵션에서 켬). 실행 중에는 매 프레임 재생 위치로 박 표시를 갱신한다
    private val metronome = MetronomeEngine()
    private var metronomeFileId: String? = null
    private val metronomeTicker = object : Runnable {
        override fun run() {
            if (!metronome.isRunning) return
            val beat = metronome.currentBeat()
            if (followState == FollowState.PLAYING) updateFollow(beat)
            if (!metronome.isRunning) return // 악보 끝에서 멈췄다
            // 박자는 들리는 박의 것 — 바꾼 박자·악보의 박자 바뀜이 소리와 같은 박에서 보인다
            binding.metronomeBeat.update(
                beat,
                beat?.bpm ?: metronome.bpm,
                beat?.timeSignature ?: metronome.timeSignature,
                beat?.dotted ?: metronome.dottedBeat,
            )
            binding.metronomeBeat.postOnAnimation(this)
        }
    }

    // 메트로놈 악보 연동 (#050): 시작 마디 고르기 → 예비박(기본 두 마디, #060) → 현재 마디 표시 + 자동 넘김
    // PAUSED (#053): 연주 중 메뉴(↑ 메트로놈 메뉴, OK 길게 PDF 표시 옵션)를 띄우면 멈추고, 메뉴를 모두 닫으면 다음 동작을 고른다
    private enum class FollowState { OFF, SELECTING, PLAYING, PAUSED }
    private var followState = FollowState.OFF
    /** 마지막으로 멈춘 곳 (파일 ID, 마디 번호) — 다음에 마디를 고를 때 커서를 거기 둔다 */
    private var lastFollowPosition: Pair<String, Int>? = null
    private var metronomeMenuShowing = false
    /** 메트로놈 설정 대화상자를 준비하는 중 (설정 읽기·악보 분석) — 그 사이 포커스가 돌아와도 선택 메뉴를 띄우지 않는다 */
    private var metronomeDialogPending = false
    private val pausedMenuCheck = Runnable {
        if (hasWindowFocus() && followState == FollowState.PAUSED && !metronomeMenuShowing &&
            !metronomeDialogPending && !isFinishing && ensembleRole != EnsembleRole.FOLLOWING
        ) {
            showMetronomeMenu()
        }
    }
    /** 시작할 수 있는 마디 (박자를 아는 마디부터) */
    private var followMeasures: List<ScoreMeasure> = emptyList()
    private var followFileId: String? = null
    private var cursorIndex = 0
    private var follower: ScoreFollower? = null
    private var followMeasure: ScoreMeasure? = null
    private var followInCountIn = false
    private var turnRequestedTo = -1
    /** 따라가는 연주의 시작 마디 — 연주 중 빠르기를 바꿀 때 같은 시작으로 다시 만든다 (#057) */
    private var followStartMeasure: Int? = null
    /** 지금 [follower] 를 만든 빠르기 (첫 구간 템포, 첫 구간 점음표, 둘째 구간부터의 설정) — 지휘자가 방송하는 것도 이것 */
    private var followTempo: Triple<Int, Boolean, List<TempoSectionSetting>>? = null
    /** 세는 단위를 바꿔 다음 시작부터 적용된다는 안내를 이번 연주에서 이미 보였나 */
    private var followTempoDeferredNotice = false

    // ScoreMate (#063) — onCreate 에서 읽는다
    private var syncedScores: List<com.mrgq.pdfviewer.scoremate.SyncedScore> = emptyList()
    private var scoreMateLinked = false
    /** 지휘자: 연 파일의 내용 해시 — ScoreMate 악보는 받을 때 검증한 값, 로컬 파일은 한 번 계산 (#063) */
    private fun sha256Of(path: String): String? =
        com.mrgq.pdfviewer.ensemble.EnsembleFiles.sha256Of(File(path), syncedScores.firstOrNull { it.filePath == path }?.sha256)

    // 구간별 빠르기 (#057): 이 파일의 둘째 구간부터의 설정. 대화상자에서 바꾸면 바로 여기에, 닫을 때 DB 에
    private var tempoSectionSettings: List<TempoSectionSetting> = emptyList()
    private var tempoSectionSettingsFileId: String? = null

    // 합주 메트로놈 (#055): 지휘자는 시간표를 방송하고, 연주자는 같은 시간표를 자기 시계로 읽어 따라간다
    private enum class EnsembleRole { NONE, CONDUCTING, FOLLOWING }
    private var ensembleRole = EnsembleRole.NONE
    private var ensembleSchedule: EnsembleSchedule? = null
    /** 지휘자: 지금 연주자들에게 알리고 있는 상태 (일시정지 중에도 남는다) */
    private var conductorRun: EnsembleRun? = null
    private var conductorRunCounter = 0
    /** 연주자: 지휘자에게서 받은 최신 상태 — 빠져 있어도 계속 받는다 (다시 합류용) */
    private var performerRun: EnsembleRun? = null
    /** 연주자가 스스로 빠진 연주 — 지휘자가 새로 시작하면 다시 따라간다 */
    private var performerDetachedRunId: String? = null
    /** 악보 준비(비동기) 중인 연주 — 반복 수신으로 두 번 시작하지 않게 */
    private var performerJoiningRunId: String? = null
    private var clockWaitNoticeShown = false
    private val ensembleRebroadcast = object : Runnable {
        override fun run() {
            val run = conductorRun ?: return
            globalCollaborationManager.broadcastMetronomeRun(run)
            binding.root.postDelayed(this, ENSEMBLE_REBROADCAST_MS)
        }
    }
    private var turnRequestedAtMs = 0L
    
    // Current display settings
    private var currentTopClipping: Float = 0f
    private var currentBottomClipping: Float = 0f
    private var currentCenterPadding: Float = 0f  // Changed to Float for percentage (0.0 - 0.15)
    private var currentDisplayMode: DisplayMode = DisplayMode.AUTO
    
    // Flag to force direct rendering (bypass cache) after settings change
    private var forceDirectRendering: Boolean = false
    
    // Long press handling for OK button
    private var isLongPressing = false
    private val longPressHandler = Handler(Looper.getMainLooper())

    // Phase 0: 합주 동기 페이지 넘김 (예약 넘김)
    private val syncTurnHandler = Handler(Looper.getMainLooper())
    private var pendingSyncTurn: Runnable? = null
    private var suppressBroadcastUntil = 0L   // 예약 넘김 실행 중 재브로드캐스트 억제 창
    /** 동기 예약 넘김 사용 여부 (지휘자 기준). 기본 false = 기존처럼 즉시 넘김 */
    private fun isSyncTurnEnabled(): Boolean = preferences.getBoolean("sync_page_turn_enabled", false)
    /** 예약 lead time(ms). 신호 전달 후 실제 넘기까지 여유 */
    private fun syncTurnLeadMs(): Long = preferences.getLong("sync_turn_lead_ms", 2000L)
    private val longPressRunnable = Runnable {
        if (isLongPressing) {
            // 악보 연동 중이면 메뉴를 보는 동안 멈춘다 — 메뉴를 모두 닫으면 이어서 · 마디 골라 다시 · 정지를 고른다 (#053)
            pauseFollowing()
            showPdfDisplayOptions()
        }
    }
    private val longPressDelay = 800L // 800ms for long press
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 가 아니면 세로가 기본. 휴대폰은 설정의 회전 모드 — 기본은 기기 회전(가로면 시스템 1 ~ 2개씩, 세로면 보통 한 쪽 전체, #090)
        if (phoneView) requestedOrientation = phoneOrientationRequest()
        else com.mrgq.pdfviewer.utils.DeviceForm.applyOrientation(this)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 세로 화면(태블릿): 한 쪽이 폭에 맞고 위아래가 빈다 — 박 표시를 가운데 위로 옮겨 악보 왼쪽 위를 가리지 않게
        if (isPortraitScreen()) {
            (binding.metronomeBeat.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).endToEnd =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        
        // Keep screen on while viewing PDF
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Initialize preferences
        preferences = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)

        // ScoreMate (#063): 지휘자는 연 파일의 악보 id 를 file_change 에 싣고, 연주자는 그 id 로 자기 ScoreMate 에서 찾는다
        lifecycleScope.launch {
            scoreMateLinked = com.mrgq.pdfviewer.scoremate.ScoreMateStore(this@PdfViewerActivity).tokens != null
            syncedScores = withContext(Dispatchers.IO) {
                com.mrgq.pdfviewer.repository.ScoreMateLocal(this@PdfViewerActivity).all()
            }
            // 쪽 정보의 제목(서버 제목)이 이것을 쓴다 — 첫 쪽이 먼저 그려졌으면 다시
            if (pageCount > 0) updatePageInfo()
        }


        // Initialize database repository
        musicRepository = MusicRepository(this)
        
        // Initialize sound effects
        initializeSoundPool()
        
        // Get screen dimensions.
        // getRealMetrics: 시스템 데코 제외 없는 실제 논리 해상도. 4K 기기에서 물리 모드와
        // 논리 해상도가 다를 수 있으므로 (SurfaceFlinger 업스케일) 둘 다 로그로 남긴다.
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        // 감마 기본값은 실제 화면 높이로 (아래에서 휴대폰은 렌더 높이를 넉넉히 바꾼다)
        // 화면을 돌려도 액티비티를 다시 만들지 않는다(매니페스트 configChanges) — 돌기 전 첫 onCreate 에서 읽은 크기일 수
        // 있으니 기기 방향으로 정한다: 태블릿 = 세로, 휴대폰 = 가로. 다시 만들면 앞 인스턴스가 PDF 를 읽다가 닫혀
        // "Document already closed" 로 뷰어가 닫혔다 (휴대폰, 2026-10-06)
        val tv = com.mrgq.pdfviewer.utils.DeviceForm.isTv(this)
        if (!tv && !phoneView) {
            screenWidth = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
            screenHeight = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
        val physicalHeight = if (phoneView) minOf(screenWidth, screenHeight) else screenHeight
        if (phoneView) {
            // 회전 모드(#090): 가로 · 세로 고정이면 그 방향의 폭(돌기 전 첫 onCreate 일 수 있다), 기기 회전이면 지금 폭.
            // 렌더 높이는 넉넉히(폭 × 3) 잡아 쪽이 화면 폭에 맞게. 열고 나서 돌면 onConfigurationChanged 가 새 폭으로 다시 그린다
            screenWidth = phoneRenderWidth(displayMetrics.widthPixels, displayMetrics.heightPixels)
            screenHeight = screenWidth * 3
        }

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val display = windowManager.defaultDisplay
            val mode = display.mode
            val supported = display.supportedModes.joinToString {
                "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}"
            }
            Log.i("PdfViewerActivity", "=== DISPLAY INFO === app=${screenWidth}x${screenHeight} " +
                    "(${displayMetrics.densityDpi}dpi), physicalMode=${mode.physicalWidth}x${mode.physicalHeight}" +
                    "@${mode.refreshRate.toInt()}Hz, supportedModes=[$supported]")
        } else {
            Log.i("PdfViewerActivity", "=== DISPLAY INFO === app=${screenWidth}x${screenHeight}")
        }

        // 렌더 선명도 — 렌더 경로가 읽으므로 렌더 시작 전에 적용.
        // 조정 메뉴(선 선명도)는 2026-09-13 에 없앴다. 측정으로 정한 기본값만 쓴다:
        // oversample 1× 는 PDFium 의 device-pixel 스냅으로 오선이 순수 검정이 되고, 감마는 1× 에서 자동으로 꺼진다.
        // 예전에 메뉴로 저장한 값이 남아 있으면 되돌릴 방법이 없으니 지운다.
        preferences.edit().remove(PageCache.PREF_OVERSAMPLE).remove(InkGamma.PREF_KEY).apply()
        PageCache.oversampleFactor = PageCache.DEFAULT_OVERSAMPLE_FACTOR
        InkGamma.gamma = InkGamma.defaultFor(physicalHeight, PageCache.oversampleFactor)
        Log.i("PdfViewerActivity",
            "렌더 선명도: oversample=${PageCache.oversampleFactor}×, 감마=${InkGamma.gamma} (${screenHeight}p 기본값)")

        currentFileIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, 0)
        filePathList = intent.getStringArrayListExtra(EXTRA_FILE_PATH_LIST) ?: emptyList()
        fileNameList = intent.getStringArrayListExtra(EXTRA_FILE_NAME_LIST) ?: emptyList()
        
        // Check if there's a target page from collaboration
        val targetPage = intent.getIntExtra("target_page", -1)
        Log.d("PdfViewerActivity", "Target page from intent: $targetPage")
        
        // 받은 파일 목록 로그
        Log.d("PdfViewerActivity", "=== RECEIVED FILE LIST ===")
        filePathList.forEachIndexed { index, path ->
            val name = if (index < fileNameList.size) fileNameList[index] else "Unknown"
            Log.d("PdfViewerActivity", "[$index] NAME: '$name' PATH: '$path'")
        }
        Log.d("PdfViewerActivity", "Current file index: $currentFileIndex")
        
        // 인덱스에 해당하는 파일을 로드
        if (currentFileIndex >= 0 && currentFileIndex < filePathList.size) {
            pdfFilePath = filePathList[currentFileIndex]
            pdfFileName = fileNameList[currentFileIndex]
            Log.d("PdfViewerActivity", "SELECTED FILE: '$pdfFileName' at '$pdfFilePath'")
        } else {
            Toast.makeText(this, "잘못된 파일 인덱스입니다", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        if (pdfFilePath.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_loading_pdf), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupUI()
        initializeCollaboration()
        loadPdf()
    }
    
    private fun setupUI() {
        binding.pdfView.isFocusable = true
        binding.pdfView.isFocusableInTouchMode = true
        binding.pdfView.requestFocus()
        
        // 페이지 정보 표시 설정 확인
        val showPageInfo = preferences.getBoolean("show_page_info", true)
        if (!showPageInfo) {
            binding.pageInfo.visibility = View.GONE
        } else {
            // Hide page info after a few seconds
            binding.pageInfo.postDelayed({
                binding.pageInfo.animate().alpha(0f).duration = 500
            }, 3000)
        }
    }
    
    private fun loadPdf() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading PDF: $pdfFilePath")
                val file = File(pdfFilePath)
                
                Log.d("PdfViewerActivity", "File exists: ${file.exists()}")
                Log.d("PdfViewerActivity", "File can read: ${file.canRead()}")
                Log.d("PdfViewerActivity", "File size: ${file.length()}")
                
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 찾을 수 없습니다: $pdfFileName", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
                
                Log.d("PdfViewerActivity", "Initial load - creating ParcelFileDescriptor...")
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                Log.d("PdfViewerActivity", "Initial load - ParcelFileDescriptor created successfully")
                
                Log.d("PdfViewerActivity", "Initial load - creating PdfRenderer...")
                pdfRenderer = PdfRenderer(fileDescriptor)
                Log.d("PdfViewerActivity", "Initial load - PdfRenderer created successfully")
                
                pageCount = pdfRenderer?.pageCount ?: 0
                Log.d("PdfViewerActivity", "Initial load - PDF page count: $pageCount")
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Add file to server if in conductor mode
                        if (collaborationMode == CollaborationMode.CONDUCTOR) {
                            Log.d("PdfViewerActivity", "🎵 지휘자 모드: 파일을 서버에 추가 중...")
                            globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
                        }
                        
                        // Initialize page cache with proper scale calculation
                        pageCache?.destroy() // Clean up previous cache
                        
                        // Calculate proper scale based on first page
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        
                        // PageCache에 설정 콜백 등록
                        registerSettingsCallback()
                        
                        Log.d("PdfViewerActivity", "PageCache 초기화 완료 (calculated scale: $calculatedScale)")
                        
                        // Check if we should use two-page mode, then show target page or first page
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            // Re-register settings provider after cache operations and settings load
                            registerSettingsCallback()
                            
                            Log.d("PdfViewerActivity", "=== 최종 콜백 등록 완료 ===")
                            Log.d("PdfViewerActivity", "최종 설정 상태: 위 ${currentTopClipping * 100}%, 아래 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
                            
                            // Navigate to target page if specified, otherwise first page
                            val targetPage = intent.getIntExtra("target_page", -1)
                            val initialPageIndex = if (targetPage > 0) {
                                incomingPageIndex(targetPage) // 1부터 → 0부터 (파트 보기면 원본 쪽 → 파트 쪽)
                            } else {
                                0
                            }
                            
                            Log.d("PdfViewerActivity", "Initial page navigation: targetPage=$targetPage, initialPageIndex=$initialPageIndex")
                            showPage(initialPageIndex)
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "PDF 파일에 페이지가 없습니다", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error loading PDF", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "PDF 열기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    private fun checkAndSetTwoPageMode(onComplete: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // First, ensure PDF file is in database
                ensurePdfFileInDatabase()
                
                Log.d("PdfViewerActivity", "=== checkAndSetTwoPageMode: PDF 파일 DB 등록 완료 ===")
                Log.d("PdfViewerActivity", "currentPdfFileId: $currentPdfFileId")
                
                // Load display settings after ensuring file is in database
                loadDisplaySettingsSync()

                // 파트보 보기를 고른 파일이면 렌더러를 파트 PDF 로 바꾼다 (P07) — 쪽 비율로 정하는 아래 두 쪽 모드 판단도 파트 쪽 기준
                // MusicXML 을 먼저 — 파트 보기 이름을 PDF 가 못 읽었으면 MusicXML 의 파트 이름으로 채운다
                loadMusicXml()
                applyPartViewSelection()
                
                Log.d("PdfViewerActivity", "=== checkAndSetTwoPageMode: 설정 로드 완료 ===")
                Log.d("PdfViewerActivity", "로드된 설정: 위 ${currentTopClipping * 100}%, 아래 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px")
                
                // Force cache invalidation to apply loaded settings
                withContext(Dispatchers.Main) {
                    pageCache?.clear()
                    Log.d("PdfViewerActivity", "=== 설정 로드 후 캐시 클리어 완료 ===")
                }
                
                // 세로 화면(태블릿)은 늘 한 쪽 — 저장된 두 쪽 설정은 건드리지 않는다(가로에서 열면 그대로) (사용자 요청 2026-09-28)
                if (isPortraitScreen() || phoneView) { // 휴대폰도 한 쪽 — 조각씩 본다(세로면 보통 한 쪽 전체)
                    withContext(Dispatchers.Main) {
                        isTwoPageMode = false
                        onComplete()
                    }
                    return@launch
                }

                // Use already loaded currentDisplayMode instead of querying database again
                Log.d("PdfViewerActivity", "=== checkAndSetTwoPageMode: currentDisplayMode 사용 ===")
                Log.d("PdfViewerActivity", "currentDisplayMode: $currentDisplayMode")
                Log.d("PdfViewerActivity", "파일: $pdfFileName")
                Log.d("PdfViewerActivity", "파일 ID: $currentPdfFileId")
                
                if (currentDisplayMode != DisplayMode.AUTO) {
                    // File-specific setting exists (SINGLE or DOUBLE)
                    Log.d("PdfViewerActivity", "=== 저장된 설정 발견됨 ===")
                    Log.d("PdfViewerActivity", "저장된 DisplayMode: $currentDisplayMode")
                    
                    withContext(Dispatchers.Main) {
                        isTwoPageMode = when (currentDisplayMode) {
                            DisplayMode.DOUBLE -> {
                                Log.d("PdfViewerActivity", "✅ 저장된 설정으로 두 페이지 모드 적용")
                                true
                            }
                            DisplayMode.SINGLE -> {
                                Log.d("PdfViewerActivity", "✅ 저장된 설정으로 단일 페이지 모드 적용")
                                false
                            }
                            DisplayMode.AUTO -> false // Won't reach here due to if condition
                        }
                        Log.d("PdfViewerActivity", "=== 저장된 설정 적용 완료: isTwoPageMode=$isTwoPageMode ===")
                        Log.d("PdfViewerActivity", "Using saved display mode: $currentDisplayMode for $pdfFileName")
                        onComplete()
                    }
                    return@launch
                }
                
                // Get first page to check aspect ratio
                val firstPage = pdfRenderer?.openPage(0)
                firstPage?.let { page ->
                    val pdfWidth = page.width
                    val pdfHeight = page.height
                    page.close()
                    
                    val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
                    val pdfAspectRatio = pdfWidth.toFloat() / pdfHeight.toFloat()
                    
                    Log.d("PdfViewerActivity", "Screen aspect ratio: $screenAspectRatio")
                    Log.d("PdfViewerActivity", "PDF aspect ratio: $pdfAspectRatio")
                    
                    withContext(Dispatchers.Main) {
                        // Check if aspect ratios are compatible (difference < 0.3)
                        val aspectRatioDiff = kotlin.math.abs(screenAspectRatio - pdfAspectRatio)
                        
                        if (aspectRatioDiff < 0.3f) {
                            // Aspect ratios are similar, use single page mode - NO SAVING
                            isTwoPageMode = false
                            Log.d("PdfViewerActivity", "Aspect ratios match (diff: $aspectRatioDiff), using single page mode (no saving)")
                            onComplete()
                        } else if (screenAspectRatio > 1.0f && pdfAspectRatio < 1.0f) {
                            // Screen is landscape and PDF is portrait - automatically use two page mode
                            Log.d("PdfViewerActivity", "Landscape screen + Portrait PDF, automatically setting two page mode")
                            isTwoPageMode = true
                            saveDisplayModePreference(DisplayMode.DOUBLE)
                            Log.d("PdfViewerActivity", "✅ Auto-enabled two page mode and saved preference for $pdfFileName")
                            onComplete()
                        } else {
                            // Other cases (portrait screen, landscape PDF, etc.) - use single page - NO SAVING
                            isTwoPageMode = false
                            Log.d("PdfViewerActivity", "Other aspect ratio case, using single page mode (no saving)")
                            onComplete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error checking aspect ratio", e)
                withContext(Dispatchers.Main) {
                    isTwoPageMode = false
                    onComplete()
                }
            }
        }
    }
    
    private suspend fun ensurePdfFileInDatabase() {
        try {
            val file = File(pdfFilePath)
            if (!file.exists()) return
            
            // 레코드가 없거나 파일이 바뀌었으면 분석해 저장한다(문서 정보 포함).
            // 기존 레코드는 update 로 갱신된다 — insert(REPLACE) 는 표시 설정을 지운다 (PdfFileSync)
            val pdfFile = musicRepository.syncPdfFile(file)
            if (pdfFile != null) {
                currentPdfFileId = pdfFile.id
                runOnUiThread {
                    onPdfFileChangedForMetronome(pdfFile.id)
                    prefetchVoiceStaffNames()
                }
                Log.d("PdfViewerActivity", "PDF file record ready: ${pdfFile.id}")
            } else {
                Log.e("PdfViewerActivity", "Failed to analyze PDF file: $pdfFilePath")
            }
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Error ensuring PDF file in database", e)
        }
    }
    
    private fun getFileKey(filePath: String): String {
        // Create a unique key for the file based on path and size
        return try {
            val file = File(filePath)
            "${file.name}_${file.length()}"
        } catch (e: Exception) {
            filePath.hashCode().toString()
        }
    }
    
    private fun saveDisplayModePreference(displayMode: DisplayMode) {
        Log.d("PdfViewerActivity", "=== saveDisplayModePreference 호출됨 ===")
        Log.d("PdfViewerActivity", "저장할 DisplayMode: $displayMode")
        Log.d("PdfViewerActivity", "현재 파일 ID: $currentPdfFileId")
        Log.d("PdfViewerActivity", "현재 파일명: $pdfFileName")
        
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    musicRepository.setDisplayModeForFile(fileId, displayMode)
                    Log.d("PdfViewerActivity", "=== DisplayMode 저장 성공 ===")
                    Log.d("PdfViewerActivity", "저장된 DisplayMode: $displayMode for file: $fileId")
                    
                    // 저장 후 즉시 확인
                    val savedPrefs = musicRepository.getUserPreference(fileId)
                    Log.d("PdfViewerActivity", "저장 후 즉시 확인: $savedPrefs")
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "=== DisplayMode 저장 실패 ===", e)
                }
            }
        } ?: run {
            Log.e("PdfViewerActivity", "=== currentPdfFileId가 null이어서 저장 실패 ===")
        }
    }
    
    private fun saveLastPageNumber(pageNumber: Int) {
        if (partViewStaves != null) return // 파트 PDF 의 쪽 번호는 원본 쪽 번호가 아니다
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    musicRepository.setLastPageForFile(fileId, pageNumber)
                    // Don't log every page change to avoid spam
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "Error saving last page number", e)
                }
            }
        }
    }
    
    private fun showTwoPageModeDialog(onComplete: () -> Unit) {
        // Create custom dialog with checkbox
        val dialogView = layoutInflater.inflate(android.R.layout.select_dialog_multichoice, null)
        
        // Create a simple custom layout
        val linearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        
        // Message text
        val messageText = android.widget.TextView(this).apply {
            text = "세로 PDF를 가로 화면에서 보고 있습니다.\n'$pdfFileName' 파일을 어떻게 표시하시겠습니까?"
            textSize = 16f
            setPadding(0, 0, 0, 30)
        }
        
        // Checkbox for "remember choice"
        val rememberCheckbox = android.widget.CheckBox(this).apply {
            text = "이 선택을 기억하기"
            isChecked = true
            textSize = 14f
            setPadding(0, 20, 0, 0)
        }
        
        linearLayout.addView(messageText)
        linearLayout.addView(rememberCheckbox)
        
        AlertDialog.Builder(this)
            .setTitle("페이지 표시 모드")
            .setView(linearLayout)
            .setPositiveButton("두 페이지씩 보기") { _, _ ->
                isTwoPageMode = true
                if (rememberCheckbox.isChecked) {
                    saveDisplayModePreference(DisplayMode.DOUBLE)
                    Log.d("PdfViewerActivity", "User selected two page mode (saved) for $pdfFileName")
                } else {
                    Log.d("PdfViewerActivity", "User selected two page mode (temp) for $pdfFileName")
                }
                onComplete()
            }
            .setNegativeButton("한 페이지씩 보기") { _, _ ->
                isTwoPageMode = false
                if (rememberCheckbox.isChecked) {
                    saveDisplayModePreference(DisplayMode.SINGLE)
                    Log.d("PdfViewerActivity", "User selected single page mode (saved) for $pdfFileName")
                } else {
                    Log.d("PdfViewerActivity", "User selected single page mode (temp) for $pdfFileName")
                }
                onComplete()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showPage(index: Int) {
        if (index < 0 || index >= pageCount) return
        flushNotePen() // 긋던 획은 지금 쪽에 마저 (P11)
        rollSpread = null
        halfPageShown = false
        cancelPendingRoll()
        
        // Throttle rapid page changes to reduce rendering load
        val currentTime = System.currentTimeMillis()
        if (isRenderingInProgress && currentTime - lastRenderTime < 100) {
            Log.d("PdfViewerActivity", "⏭️ Skipping rapid page change request for index $index (throttling)")
            return
        }
        
        Log.d("PdfViewerActivity", "showPage called: index=$index, isTwoPageMode=$isTwoPageMode, pageCount=$pageCount")
        lastRenderTime = currentTime
        if (phoneView) {
            // 같은 쪽을 다시 그리면(설정 변경 등) 보던 조각 그대로, 다른 쪽이면 첫 조각부터
            chunkIndex = pendingChunk ?: if (index == pageIndex) chunkIndex else 0
            pendingChunk = null
        }
        
        // Check cache first for instant display
        val cachedBitmap = if (isTwoPageMode) {
            if (index + 1 < pageCount) {
                // Two page mode - check if both pages are cached with correct scale
                val page1 = pageCache?.getPageImmediate(index)
                val page2 = pageCache?.getPageImmediate(index + 1)
                if (page1 != null && page2 != null) {
                    Log.d("PdfViewerActivity", "⚡ 페이지 $index, ${index + 1} 캐시에서 즉시 표시 (두 페이지 모드)")
                    combineTwoPagesUnified(page1, page2)
                } else {
                    null
                }
            } else {
                // Last page is odd - show on left side with empty right
                val page1 = pageCache?.getPageImmediate(index)
                if (page1 != null) {
                    Log.d("PdfViewerActivity", "⚡ 마지막 페이지 $index 캐시에서 왼쪽에 표시 (두 페이지 모드)")
                    combineTwoPagesUnified(page1, null)
                } else {
                    null
                }
            }
        } else {
            pageCache?.getPageImmediate(index)
        }
        
        if (cachedBitmap != null) {
            // Cache hit - instant display!
            if (!isTwoPageMode) {
                Log.d("PdfViewerActivity", "⚡ 페이지 $index 캐시에서 즉시 표시")
            }
            binding.pdfView.setImageBitmap(cachedBitmap)
            setImageViewMatrix(cachedBitmap)
            pageIndex = index
            updatePageInfo()
            binding.loadingProgress.visibility = View.GONE
            
            // Save last page number to database
            saveLastPageNumber(index + 1)
            
            // Show page info briefly if enabled
            if (preferences.getBoolean("show_page_info", true)) {
                binding.pageInfo.animate().alpha(1f).duration = 200
                binding.pageInfo.postDelayed({
                    binding.pageInfo.animate().alpha(0f).duration = 500
                }, 2000)
            }
            
            // Start prerendering around this page
            pageCache?.prerenderAround(index)
            
            // 협업 모드 브로드캐스트
            broadcastCollaborationPageChange(index)
            
            return
        }
        
        // Cache miss - fallback to traditional rendering with loading indicator
        Log.d("PdfViewerActivity", "⏳ 페이지 $index 캐시 미스 - 기존 방식으로 렌더링")
        binding.loadingProgress.visibility = View.VISIBLE
        isRenderingInProgress = true
        
        CoroutineScope(Dispatchers.IO).launch {
            val renderResult = renderWithRetry(index, maxRetries = 2)
            
            withContext(Dispatchers.Main) {
                binding.loadingProgress.visibility = View.GONE
                isRenderingInProgress = false
                
                if (renderResult != null) {
                    binding.pdfView.setImageBitmap(renderResult)
                    setImageViewMatrix(renderResult)
                    pageIndex = index
                    updatePageInfo()
                    
                    // Save last page number to database
                    saveLastPageNumber(index + 1)
                    
                    // Show page info briefly
                    binding.pageInfo.animate().alpha(1f).duration = 200
                    binding.pageInfo.postDelayed({
                        binding.pageInfo.animate().alpha(0f).duration = 500
                    }, 2000)
                    
                    // Start prerendering around this page
                    pageCache?.prerenderAround(index)
                    
                    // 협업 모드 브로드캐스트
                    broadcastCollaborationPageChange(index)
                } else {
                    // Only log error after all retries failed - no user notification needed
                    Log.e("PdfViewerActivity", "Failed to render page $index after retries")
                }
            }
        }
    }
    
    /**
     * Render page with retry logic for handling concurrency issues
     */
    private suspend fun renderWithRetry(index: Int, maxRetries: Int = 2): Bitmap? {
        repeat(maxRetries) { attempt ->
            try {
                try {
                    currentPage?.close()
                } catch (e: Exception) {
                    Log.w("PdfViewerActivity", "Current page already closed or error closing in renderWithRetry: ${e.message}")
                }
                
                val bitmap = if (isTwoPageMode) {
                    if (index + 1 < pageCount) {
                        Log.d("PdfViewerActivity", "=== 두 페이지 모드 렌더링: $index and ${index + 1} (attempt ${attempt + 1}) ===")
                        Log.d("PdfViewerActivity", "forceDirectRendering: $forceDirectRendering")
                        // For two-page mode, always use direct rendering to preserve aspect ratio
                        if (forceDirectRendering) {
                            forceDirectRendering = false // 플래그 리셋
                        }
                        renderTwoPagesUnified(index)
                    } else {
                        Log.d("PdfViewerActivity", "=== 마지막 페이지 왼쪽 표시 렌더링: $index (attempt ${attempt + 1}) ===")
                        Log.d("PdfViewerActivity", "forceDirectRendering: $forceDirectRendering")
                        if (forceDirectRendering) {
                            forceDirectRendering = false // 플래그 리셋
                        }
                        renderTwoPagesUnified(index, true)
                    }
                } else {
                    Log.d("PdfViewerActivity", "=== 단일 페이지 모드 렌더링: $index (attempt ${attempt + 1}) ===")
                    Log.d("PdfViewerActivity", "forceDirectRendering: $forceDirectRendering")
                    
                    if (forceDirectRendering) {
                        Log.d("PdfViewerActivity", "설정 변경으로 인한 강제 직접 렌더링 - 캐시 완전 우회")
                        forceDirectRendering = false // 플래그 리셋
                        renderSinglePage(index)
                    } else {
                        Log.d("PdfViewerActivity", "일반 렌더링 - PageCache 자동 설정 관리 사용")
                        // PageCache will automatically handle settings changes and cache invalidation
                        pageCache?.getPageImmediate(index) ?: renderSinglePage(index)
                    }
                }
                
                Log.d("PdfViewerActivity", "✅ Successfully rendered page $index on attempt ${attempt + 1}")
                return bitmap
                
            } catch (e: Exception) {
                if (attempt < maxRetries - 1) {
                    Log.w("PdfViewerActivity", "⚠️ Rendering attempt ${attempt + 1}/$maxRetries failed for page $index, retrying...", e)
                    kotlinx.coroutines.delay(50) // Short delay before retry
                } else {
                    Log.e("PdfViewerActivity", "❌ All rendering attempts failed for page $index", e)
                }
            }
        }
        return null
    }
    
    /**
     * 통합된 두 페이지 결합 함수.
     *
     * 입력 비트맵은 이미 화면 좌표계에서 "화면 절반 - 중앙 여백/2" 영역에 fit 된 크기로 와있다고
     * 가정 (renderPageAtTwoPageTarget / PageCache.renderPageToTargetBitmap 이 보장). 따라서
     * 이 함수는 추가 스케일링 없이 두 비트맵을 좌/우 영역 가운데에 배치만 한다.
     *
     * 결과는 화면 크기 비트맵 (screenWidth × pageHeight) — ImageView 가 안전하게 draw 가능.
     */
    private fun combineTwoPagesUnified(leftBitmap: Bitmap, rightBitmap: Bitmap? = null): Bitmap {
        val finalWidth = screenWidth.coerceAtLeast(1)
        // 크롭 이후 입력 비트맵 높이가 화면 높이보다 작을 수 있다.
        // finalHeight 는 입력 비트맵 높이에 맞춘다 (위아래 letterbox 방지).
        val pageHeight = maxOf(leftBitmap.height, rightBitmap?.height ?: 0)
        val finalHeight = pageHeight.coerceAtLeast(1)

        val finalBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val finalCanvas = Canvas(finalBitmap)
        finalCanvas.drawColor(android.graphics.Color.WHITE)

        // 배치 좌표는 TwoPageOffsets 가 계산한다 (정수 — 소수점 blit 은 Canvas 재샘플링을
        // 일으켜 oversample 1× 로 얻은 device-pixel 스냅을 무효화한다, #042).
        val offsets = TwoPageOffsets.compute(
            canvasWidth = finalWidth,
            canvasHeight = finalHeight,
            centerPadding = currentCenterPadding,
            leftWidth = leftBitmap.width,
            leftHeight = leftBitmap.height,
            rightWidth = rightBitmap?.width ?: 0,
            rightHeight = rightBitmap?.height ?: 0,
        )
        val centerPadPx = (screenWidth * currentCenterPadding).toInt()

        finalCanvas.drawBitmap(leftBitmap, offsets.leftX.toFloat(), offsets.leftY.toFloat(), null)
        if (rightBitmap != null) {
            finalCanvas.drawBitmap(rightBitmap, offsets.rightX.toFloat(), offsets.rightY.toFloat(), null)
        }

        Log.d("PdfViewerActivity", "=== UNIFIED TWO PAGE COMBINE === Inputs: ${leftBitmap.width}x${leftBitmap.height} + ${rightBitmap?.let{"${it.width}x${it.height}"} ?: "none"} -> Final: ${finalWidth}x${finalHeight}, centerPad=$centerPadPx")

        return finalBitmap
    }
    
    private suspend fun renderSinglePage(index: Int): Bitmap {
        return renderMutex.withLock {
            Log.d("PdfViewerActivity", "🔒 Acquired render lock for single page $index")
            try {
                currentPage = pdfRenderer?.openPage(index)
                val page = currentPage ?: throw Exception("Failed to open page $index")
                renderPageAtSinglePageTarget(page)
            } finally {
                Log.d("PdfViewerActivity", "🔓 Released render lock for single page $index")
            }
        }
    }

    /**
     * 단일 페이지 모드용 직접 렌더 (캐시 미스 / forceDirectRendering 경로).
     *
     * 좌표는 [PageGeometry], 래스터화는 [PageRenderer] — 프리렌더(PageCache)와 **같은 함수**다.
     */
    private fun renderPageAtSinglePageTarget(page: PdfRenderer.Page): Bitmap {
        notePageSize(page)
        val geometry = PageGeometry.compute(
            pdfWidth = page.width,
            pdfHeight = page.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topClipping = currentTopClipping,
            bottomClipping = currentBottomClipping,
            twoPageMode = false,
        )
        Log.d("PdfViewerActivity", "=== SINGLE PAGE RENDER === pdf=${page.width}x${page.height} " +
                "-> render=${geometry.renderWidth}x${geometry.renderHeight} " +
                "-> display=${geometry.displayWidth}x${geometry.displayHeight}")
        return PageRenderer.render(page, geometry)
    }

    /**
     * 두 페이지 모드용 직접 렌더 (캐시 미스 경로).
     *
     * renderPageAtSinglePageTarget 와 동일한 oversample → downscale 정책. 화면 절반 - 중앙
     * 여백/2 영역에 fit 되는 크기로 다운스케일된 비트맵을 반환. combineTwoPagesUnified 가
     * 추가 스케일 없이 좌/우 영역 가운데에 배치한다.
     */
    private fun renderPageAtTwoPageTarget(page: PdfRenderer.Page): Bitmap {
        notePageSize(page)
        val geometry = PageGeometry.compute(
            pdfWidth = page.width,
            pdfHeight = page.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topClipping = currentTopClipping,
            bottomClipping = currentBottomClipping,
            centerPadding = currentCenterPadding,
            twoPageMode = true,
        )
        return PageRenderer.render(page, geometry)
    }

    /**
     * 통합된 두 페이지 렌더링 함수 - 처음부터 렌더링하는 모든 두 페이지 모드를 처리
     * @param leftPageIndex 왼쪽 페이지 인덱스
     * @param isLastOddPage 마지막 홀수 페이지 모드 (오른쪽 빈 공간)
     * @return 결합된 고해상도 비트맵
     */
    private suspend fun renderTwoPagesUnified(leftPageIndex: Int, isLastOddPage: Boolean = false): Bitmap {
        return renderMutex.withLock {
            Log.d("PdfViewerActivity", "🔒 Acquired render lock for two pages $leftPageIndex${if (isLastOddPage) " (last odd page)" else " and ${leftPageIndex + 1}"}")
            try {
                Log.d("PdfViewerActivity", "Starting renderTwoPagesUnified for page $leftPageIndex${if (isLastOddPage) " (last odd page)" else " and ${leftPageIndex + 1}"}")
                
                // Open left page
                val leftPage = try {
                    pdfRenderer?.openPage(leftPageIndex)
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "Failed to open left page $leftPageIndex", e)
                    return@withLock renderSinglePageInternal(leftPageIndex)
                }
                
                if (leftPage == null) {
                    Log.e("PdfViewerActivity", "Left page is null")
                    return@withLock renderSinglePageInternal(leftPageIndex)
                }
                
                var leftBitmap: Bitmap? = null
                var rightBitmap: Bitmap? = null

                try {
                    // 좌측 페이지를 두 페이지 모드 목표 크기로 단일 단계 Matrix 렌더
                    leftBitmap = renderPageAtTwoPageTarget(leftPage)
                    leftPage.close()

                    // 우측 페이지 처리
                    rightBitmap = if (isLastOddPage) {
                        null
                    } else {
                        val rightPage = try {
                            pdfRenderer?.openPage(leftPageIndex + 1)
                        } catch (e: Exception) {
                            Log.e("PdfViewerActivity", "Failed to open right page ${leftPageIndex + 1}", e)
                            null
                        }

                        if (rightPage != null) {
                            try {
                                val bm = renderPageAtTwoPageTarget(rightPage)
                                rightPage.close()
                                bm
                            } catch (e: Exception) {
                                Log.e("PdfViewerActivity", "Error rendering right page", e)
                                try { rightPage.close() } catch (ex: Exception) { }
                                null
                            }
                        } else {
                            null
                        }
                    }

                    val result = combineTwoPagesUnified(leftBitmap, rightBitmap)

                    leftBitmap.recycle()
                    rightBitmap?.recycle()

                    result

                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "Error in renderTwoPagesUnified", e)
                    try {
                        leftPage.close()
                    } catch (closeError: Exception) {
                        Log.w("PdfViewerActivity", "Left page already closed or error closing: ${closeError.message}")
                    }
                    leftBitmap?.recycle()
                    rightBitmap?.recycle()
                    renderSinglePageInternal(leftPageIndex)
                }
            } finally {
                Log.d("PdfViewerActivity", "🔓 Released render lock for two pages $leftPageIndex")
            }
        }
    }
    
    /**
     * Internal single page rendering without mutex (for use within mutex-protected context)
     */
    private suspend fun renderSinglePageInternal(index: Int): Bitmap {
        Log.d("PdfViewerActivity", "Internal single page rendering for $index (within mutex)")
        currentPage = pdfRenderer?.openPage(index)
        val page = currentPage ?: throw Exception("Failed to open page $index")
        return renderPageAtSinglePageTarget(page)
    }
    
    /**
     * 로그/PageCache.updateSettings 호환용 스케일 값.
     *
     * 실제 렌더 스케일은 renderPageAtSinglePageTarget / renderPageAtTwoPageTarget /
     * PageCache.renderPageToTargetBitmap 가 각자 계산한다(visibleFraction, centerPadding 반영).
     * 이 함수의 반환값은 더 이상 렌더에 직접 사용되지 않지만, 호출부 호환을 위해 유지.
     */
    private fun calculateOptimalScale(pageWidth: Int, pageHeight: Int, forTwoPageMode: Boolean = false): Float {
        // 공식은 PageGeometry 하나만 쓴다 (이 함수는 호출부 호환용 얇은 래퍼).
        return PageGeometry.compute(
            pdfWidth = pageWidth,
            pdfHeight = pageHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topClipping = currentTopClipping,
            bottomClipping = currentBottomClipping,
            centerPadding = currentCenterPadding,
            twoPageMode = forTwoPageMode,
        ).renderScale
    }
    
    private fun setImageViewMatrix(bitmap: Bitmap) {
        val imageMatrix = android.graphics.Matrix()
        
        // Calculate scale to fit the image in the view while preserving aspect ratio
        val viewWidth = binding.pdfView.width
        val viewHeight = binding.pdfView.height
        
        if (viewWidth == 0 || viewHeight == 0) {
            // View not yet measured, set later
            binding.pdfView.post {
                setImageViewMatrix(bitmap)
            }
            return
        }
        
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        
        // Calculate original aspect ratio
        val originalAspectRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        
        val scaleX = viewWidth.toFloat() / bitmapWidth.toFloat()
        val scaleY = viewHeight.toFloat() / bitmapHeight.toFloat()
        
        // Use the smaller scale to ensure the whole image fits
        // 1.0 에 극히 가까우면 정확히 1.0 으로 스냅한다. 0.999… 스케일은 비트맵 전체를
        // 재샘플링시켜 device-pixel 에 스냅된 오선을 뭉갠다 (oversample 1× 정책의 전제).
        if (phoneView) return setPhoneChunkMatrix(bitmap, viewWidth, viewHeight)
        val rawScale = minOf(scaleX, scaleY)
        val scale = if (kotlin.math.abs(rawScale - 1f) < 0.005f) 1f else rawScale

        // Calculate translation to center the image
        val scaledWidth = bitmapWidth * scale
        val scaledHeight = bitmapHeight * scale
        // 정수 좌표로 반올림 — 소수점 translate 도 재샘플링을 유발한다.
        val dx = ((viewWidth - scaledWidth) / 2f).toInt().toFloat()
        val dy = ((viewHeight - scaledHeight) / 2f).toInt().toFloat()
        
        // Calculate final displayed aspect ratio
        val finalAspectRatio = scaledWidth / scaledHeight
        
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        
        binding.pdfView.imageMatrix = imageMatrix
        // showPage 는 이 함수 다음에 pageIndex 를 갱신하므로 한 박자 뒤에 그린다
        binding.pdfView.post { refreshScoreOverlay() }
        
        Log.d("PdfViewerActivity", "=== ASPECT RATIO CHECK ===")
        Log.d("PdfViewerActivity", "Original bitmap: ${bitmapWidth}x${bitmapHeight}, aspect ratio: $originalAspectRatio")
        Log.d("PdfViewerActivity", "Final displayed: ${scaledWidth}x${scaledHeight}, aspect ratio: $finalAspectRatio")
        Log.d("PdfViewerActivity", "Aspect ratio preserved: ${kotlin.math.abs(originalAspectRatio - finalAspectRatio) < 0.001f}")
        Log.d("PdfViewerActivity", "ImageView matrix: scale=$scale, translate=($dx, $dy)")
        Log.d("PdfViewerActivity", "========================")
    }
    
    private fun updatePageInfo() {
        // ScoreMate 악보면 파일 이름(제목 + " (파트)" + .pdf) 대신 서버 제목 — 목록과 같게 (사용자 요청 2026-09-28)
        val shownName = syncedScores.firstOrNull { it.filePath == pdfFilePath }?.title?.takeIf { it.isNotBlank() }
            ?: fileNameList.getOrNull(currentFileIndex) ?: pdfFileName
        val fileInfo = if (fileNameList.isNotEmpty()) {
            "[${currentFileIndex + 1}/${filePathList.size}] $shownName - "
        } else {
            "$shownName - "
        }
        
        val twoPages = isTwoPageMode && pageIndex + 1 < pageCount
        val pageInfo = if (twoPages) {
            // Two page mode: show "1-2 / 10" format
            "${pageIndex + 1}-${pageIndex + 2} / $pageCount"
        } else {
            // Single page mode: show "1 / 10" format
            "${pageIndex + 1}${if (phoneView && phoneChunks.size > 1) " (${if (phoneWindowEnd > chunkIndex) "${chunkIndex + 1}-${phoneWindowEnd + 1}" else "${chunkIndex + 1}"}/${phoneChunks.size})" else ""} / $pageCount"
        } + partSourceInfo(if (twoPages) pageIndex..pageIndex + 1 else pageIndex..pageIndex)
        
        // Add cache info for debugging (only show if cache exists)
        val cacheInfo = pageCache?.let { cache ->
            " [${cache.getCacheInfo()}]"
        } ?: ""
        
        binding.pageInfo.text = "$fileInfo$pageInfo$cacheInfo"
    }
    
    /** 파트 보기면 " · 보표 2 (총보 11~15쪽)" — 지금 가상 쪽에 담긴 원본 쪽 (P07) */
    private fun partSourceInfo(pages: IntRange): String {
        if (partViewStaves == null) return ""
        val source = partViewLayout?.sourcePages(pages)?.let { r ->
            if (r.first == r.last) " (총보 ${r.first + 1}쪽)" else " (총보 ${r.first + 1}~${r.last + 1}쪽)"
        }.orEmpty()
        return " · ${partViewName.orEmpty()}$source"
    }

    private fun loadNextFile() {
        if (currentFileIndex < filePathList.size - 1) {
            currentFileIndex++
            loadFile(filePathList[currentFileIndex], fileNameList[currentFileIndex])
        }
    }
    
    private fun loadPreviousFile() {
        if (currentFileIndex > 0) {
            currentFileIndex--
            loadFile(filePathList[currentFileIndex], fileNameList[currentFileIndex], true)
        }
    }
    
    private fun loadFileWithTargetPage(filePath: String, fileName: String, targetPage: Int, originalMode: CollaborationMode) {
        stopMicFollow()
        conductorMeasure = null
        // Close current PDF
        Log.d("PdfViewerActivity", "Closing current PDF resources for collaboration file change...")
        try {
            currentPage?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Current page already closed or error closing: ${e.message}")
        }
        currentPage = null
        
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "PdfRenderer already closed or error closing: ${e.message}")
        }
        pdfRenderer = null
        
        pdfFilePath = filePath
        pdfFileName = fileName
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading file for collaboration: $filePath")
                val file = File(pdfFilePath)
                
                if (!file.exists() || !file.canRead()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 읽을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
                        collaborationMode = originalMode
                        finish()
                    }
                    return@launch
                }
                
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)
                pageCount = pdfRenderer?.pageCount ?: 0
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Initialize page cache for new file
                        pageCache?.destroy()
                        
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        
                        // Register settings callback immediately after PageCache creation
                        registerSettingsCallback()
                        
                        Log.d("PdfViewerActivity", "PageCache 재초기화 완료 for collaboration file change")
                        
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for collaboration file change, two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            // Navigate to target page (convert from 1-based to 0-based)
                            val targetIndex = incomingPageIndex(targetPage)
                            showPage(targetIndex)
                            
                            // Restore collaboration mode
                            collaborationMode = originalMode
                            
                            Log.d("PdfViewerActivity", "🎼 연주자 모드: 파일 '$fileName' 로드 완료, 페이지 $targetPage 로 이동 완료")
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "빈 PDF 파일입니다: $fileName", Toast.LENGTH_SHORT).show()
                        collaborationMode = originalMode
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error loading file for collaboration", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "파일 로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    collaborationMode = originalMode
                    finish()
                }
            }
        }
    }
    
    private fun loadFile(filePath: String, fileName: String, goToLastPage: Boolean = false) {
        stopMicFollow() // 곡이 바뀌면 추적도 끝 (P10)
        conductorMeasure = null
        // Close current PDF
        Log.d("PdfViewerActivity", "Closing current PDF resources...")
        try {
            currentPage?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Current page already closed or error closing: ${e.message}")
        }
        currentPage = null
        Log.d("PdfViewerActivity", "Current page closed")
        
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "PdfRenderer already closed or error closing: ${e.message}")
        }
        pdfRenderer = null
        Log.d("PdfViewerActivity", "PdfRenderer closed")
        
        pdfFilePath = filePath
        pdfFileName = fileName
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading new file: $filePath")
                val file = File(pdfFilePath)
                
                Log.d("PdfViewerActivity", "New file exists: ${file.exists()}")
                Log.d("PdfViewerActivity", "New file path: ${file.absolutePath}")
                
                // 파일 존재 확인
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 찾을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
                        // 파일 목록을 다시 로드하고 현재 액티비티 종료
                        finish()
                    }
                    return@launch
                }
                
                if (!file.canRead()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 읽을 수 없습니다: $fileName", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@launch
                }
                
                Log.d("PdfViewerActivity", "File permissions OK, creating ParcelFileDescriptor...")
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                Log.d("PdfViewerActivity", "ParcelFileDescriptor created successfully")
                
                Log.d("PdfViewerActivity", "Creating PdfRenderer...")
                pdfRenderer = PdfRenderer(fileDescriptor)
                Log.d("PdfViewerActivity", "PdfRenderer created successfully")
                
                pageCount = pdfRenderer?.pageCount ?: 0
                Log.d("PdfViewerActivity", "Page count retrieved: $pageCount")
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Initialize page cache for new file with proper scale calculation
                        pageCache?.destroy() // Clean up previous cache
                        
                        // Calculate proper scale based on first page
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        
                        // Register settings callback immediately after PageCache creation
                        registerSettingsCallback()
                        
                        Log.d("PdfViewerActivity", "PageCache 재초기화 완료 for $fileName (scale: $calculatedScale)")
                        
                        // Check two-page mode for this new file, then show the page
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for loadFile, two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            val targetPage = if (goToLastPage) {
                                // 두 페이지 모드에서 마지막 페이지 계산
                                if (isTwoPageMode) {
                                    // 짝수 페이지: 마지막 두 페이지 표시 (예: 8페이지 파일이면 7,8페이지를 보여주려면 인덱스 6)  
                                    // 홀수 페이지: 마지막 페이지만 왼쪽에 표시 (예: 7페이지 파일이면 7페이지만 보여주려면 인덱스 6)
                                    if (pageCount % 2 == 0) {
                                        // 짝수 페이지: 마지막 두 페이지를 표시하기 위해 마지막에서 두 번째 페이지로 이동
                                        pageCount - 2
                                    } else {
                                        // 홀수 페이지: 마지막 페이지를 왼쪽에 표시
                                        pageCount - 1
                                    }
                                } else {
                                    // 단일 페이지 모드: 항상 마지막 페이지
                                    pageCount - 1
                                }
                            } else 0
                            showPage(targetPage)
                            // 파트 보기를 바꾼 명령이 이어지는 명령(마디로 가기)을 실행하려고 기다린다
                            fileShownSignal?.complete(Unit)
                            fileShownSignal = null

                            // Broadcast file change if in conductor mode
                            if (collaborationMode == CollaborationMode.CONDUCTOR) {
                                // Add file to server first
                                globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
                                // Then broadcast the change with the target page number
                                val actualPageNumber = outgoingPage(targetPage) // 1부터 · 파트 보기면 원본 쪽으로
                                globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber, sha256Of(pdfFilePath))
                            }
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "빈 PDF 파일입니다: $fileName", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Exception in loadFile for $fileName", e)
                Log.e("PdfViewerActivity", "Exception type: ${e::class.java.simpleName}")
                Log.e("PdfViewerActivity", "Exception message: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "파일 열기 실패: $fileName - ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 블루투스 넘김 페달 · 키보드 (태블릿 1단계): 쪽 넘김 키를 리모컨 ← → 와 같게
        pageTurnKeyAsDpad(keyCode)?.let { return onKeyDown(it, event) }
        // 메모 모드: 뒤로 = 메모 끝 (뷰어를 나가지 않는다, P11)
        if (notePen.editMode && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            exitNoteMode()
            return true
        }
        // 메트로놈 악보 연동: 시작 마디 고르는 중에는 리모컨이 커서를 움직인다
        if (followState == FollowState.SELECTING) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { moveCursorLine(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursorLine(1); return true }
                // 떼는 순간 시작한다 (onKeyUp). 길게 누르기 메뉴는 띄우지 않는다
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { cancelMeasureSelection(); return true }
            }
        } else if ((followState == FollowState.PLAYING || followState == FollowState.PAUSED) &&
            (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            // 연주 중 뒤로: 뷰어를 나가지 않고 메트로놈만 멈춘다. 합주 연주자면 이 기기만 빠진다 (#055)
            if (ensembleRole == EnsembleRole.FOLLOWING) {
                detachFromEnsemble()
            } else {
                stopMetronome()
                Toast.makeText(this, "메트로놈 정지", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        // ↑: 메트로놈 메뉴 (#053) — 자주 쓰므로 PDF 표시 옵션과 따로. 악보 연동 중이면 일시정지하고 연다
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event?.repeatCount == 0) showMetronomeMenu()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Check if input is blocked due to synchronization
                if (isInputBlocked()) {
                    showInputBlockedMessage()
                    return true
                }
                turnBack()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Check if input is blocked due to synchronization
                if (isInputBlocked()) {
                    showInputBlockedMessage()
                    return true
                }
                turnForward()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // 지휘자 모드에서 뒤로가기 시 연주자에게 알림
                if (collaborationMode == CollaborationMode.CONDUCTOR) {
                    Log.d("PdfViewerActivity", "🎵 지휘자 모드: 뒤로가기 브로드캐스트")
                    globalCollaborationManager.broadcastBackToList()
                }
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    // Start long press detection
                    isLongPressing = true
                    longPressHandler.postDelayed(longPressRunnable, longPressDelay)
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                // 메뉴 키로 페이지 정보 표시/숨김 토글
                if (binding.pageInfo.alpha > 0.5f) {
                    binding.pageInfo.animate().alpha(0f).duration = 200
                } else {
                    binding.pageInfo.animate().alpha(1f).duration = 200
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    /** ← · 페달 · 터치 · 명령 층의 "이전 쪽" — 휴대폰은 같은 쪽 앞 화면부터, 첫 쪽이면 안내(안내 중이면 이전 파일로) */
    private fun turnBack() {
        if (isNavigationGuideVisible) {
            if (navigationGuideType == "start" && currentFileIndex > 0) {
                // 첫 페이지 안내에서 왼쪽 키 -> 이전 파일로 이동
                hideNavigationGuide()
                loadPreviousFile()
            }
            // 안내가 표시된 상태에서는 일반 페이지 이동 차단
        } else if (phoneView && chunkIndex > 0) {
            showChunk(phoneStartEndingAt(chunkIndex - 1)) // 휴대폰: 같은 쪽 앞 화면
        } else if (pageIndex > 0) {
            if (phoneView) pendingChunk = LAST_CHUNK // 앞 쪽의 마지막 조각으로
            val nextPageIndex = if (isTwoPageMode) pageIndex - 2 else pageIndex - 1
            turnTo(maxOf(0, nextPageIndex), -1)
        } else {
            // 첫 페이지에서 안내 표시
            showStartOfFileGuide()
        }
    }

    /** → · 페달 · 터치 · 명령 층의 "다음 쪽" — 휴대폰은 같은 쪽 다음 화면부터, 마지막 쪽이면 안내(안내 중이면 다음 파일로) */
    private fun turnForward() {
        if (isNavigationGuideVisible) {
            if (navigationGuideType == "end" && currentFileIndex < filePathList.size - 1) {
                // 마지막 페이지 안내에서 오른쪽 키 -> 다음 파일로 이동
                hideNavigationGuide()
                loadNextFile()
            }
            // 안내가 표시된 상태에서는 일반 페이지 이동 차단
        } else if (phoneView && phoneWindowEnd < phoneChunks.lastIndex) {
            showChunk(phoneWindowEnd + 1) // 휴대폰: 같은 쪽 다음 화면
        } else {
            val nextPageIndex = if (isTwoPageMode) pageIndex + 2 else pageIndex + 1
            if (nextPageIndex < pageCount) {
                turnTo(nextPageIndex, 1)
            } else {
                // 마지막 페이지에서 안내 표시
                showEndOfFileGuide()
            }
        }
    }

    /** 손으로 넘기기 — 지휘자 동기 넘김이 켜져 있으면 예약, 마이크 추적이면 다시 맞춘다 */
    private fun turnTo(target: Int, direction: Int) {
        if (collaborationMode == CollaborationMode.CONDUCTOR && isSyncTurnEnabled()) {
            conductorScheduledTurn(target, direction)
        } else {
            showPageWithAnimation(target, direction)
        }
        anchorMicFollow(target)
    }

    /**
     * 넘김 페달이 보내는 키 → 리모컨 ← →. 페달은 보통 PageUp/PageDown · 미디어 이전/다음 중 하나를 보낸다 (←→ 를 보내는 것은 그대로 된다).
     * ↑ ↓ 를 보내는 페달은 ↑ 이 메트로놈 메뉴라 맞지 않는다 — 페달 모드를 바꿔 쓴다
     */
    private fun pageTurnKeyAsDpad(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MOVE_END -> KeyEvent.KEYCODE_DPAD_RIGHT
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MOVE_HOME -> KeyEvent.KEYCODE_DPAD_LEFT
        else -> null
    }

    /**
     * 태블릿 터치 (1단계 — 리모컨 동작을 그대로 부른다, 2단계 — 밀기 · 마디 탭):
     *  - 왼쪽 · 오른쪽 1/3 탭 = ← →(쪽 넘김, 마디 고르는 중이면 커서). 끝 · 처음 안내가 떠 있으면 다음 · 이전 파일
     *  - 왼쪽으로 밀기 = 다음 쪽, 오른쪽으로 밀기 = 이전 쪽
     *  - 시작 마디를 고르는 중에 **마디를 탭**하면 그 마디로, 이미 고른 마디를 다시 탭하면 시작
     *  - 가운데 탭 = OK 짧게(쪽 정보 · 안내 닫기, 마디 고르는 중이면 시작)
     *  - 가운데 두 번 탭 = ↑(메트로놈 메뉴)
     *  - 길게 누르기 = OK 길게(PDF 표시 옵션, 악보 연동 중이면 일시정지)
     * 대화상자는 다른 창이라 여기로 오지 않는다. TV 에는 터치가 없어 영향이 없다
     */
    private val touchGestures by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            private fun zone(e: android.view.MotionEvent): Int {
                val third = binding.root.width / 3f
                return when {
                    e.x < third -> -1
                    e.x > third * 2 -> 1
                    else -> 0
                }
            }
            private fun press(keyCode: Int) {
                onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            /** 이번 탭을 마디 고르기가 처리했다 — 가운데 탭(OK)으로 또 처리하지 않게 */
            private var tapTaken = false
            override fun onDown(e: android.view.MotionEvent): Boolean {
                tapTaken = false
                return true
            }
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // 휴대폰: 세로로 끌면 악보가 따라 움직이고, 손을 떼면(dispatchTouchEvent) 가까운 시스템에 맞춘다
                if (!phoneView) return false
                val start = e1 ?: return false
                if (phoneDrag == null && kotlin.math.abs(e2.y - start.y) < kotlin.math.abs(e2.x - start.x) * 1.5f) return false
                phoneDragBy(-distanceY)
                return true
            }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                if (phoneDrag != null) return false // 세로 끌기는 손을 뗄 때 맞춘다
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val minDistance = resources.displayMetrics.density * SWIPE_MIN_DP
                if (kotlin.math.abs(dx) < minDistance || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) return false
                press(if (dx < 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                return true
            }
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                // 시작 마디 고르기의 마디 탭은 두 번 탭(↑ 메뉴)이 아닌 것이 확인된 뒤에 (onSingleTapConfirmed) — 커서 마디를
                // 두 번 탭해 메뉴를 열려다 첫 탭에 시작하지 않게 (#087)
                if (followState == FollowState.SELECTING && followMeasureAt(e.x, e.y) >= 0) {
                    tapTaken = true
                    return true
                }
                when (zone(e)) {
                    -1 -> press(KeyEvent.KEYCODE_DPAD_LEFT)
                    1 -> press(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
                return true
            }
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (followState == FollowState.SELECTING) {
                    val index = followMeasureAt(e.x, e.y)
                    if (index >= 0) {
                        if (index == cursorIndex) startFollowing() else moveCursor(index - cursorIndex)
                        return true
                    }
                }
                if (zone(e) == 0 && !tapTaken) {
                    // OK 짧게 — 누르고 떼기
                    press(KeyEvent.KEYCODE_DPAD_CENTER)
                    longPressHandler.removeCallbacks(longPressRunnable)
                    onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                }
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                // 시작 마디를 고르는 중에도 두 번 탭 = ↑ 메뉴(🎤 고른 마디부터 듣기 · 시작 · 취소, #087). 리모컨의 ↑ 는 여전히 윗줄로 —
                // 터치 기기는 줄을 탭으로 고르고, 듣기는 TV 에 없다
                if (followState == FollowState.SELECTING) {
                    showMetronomeMenu()
                    return true
                }
                if (zone(e) == 0) press(KeyEvent.KEYCODE_DPAD_UP)
                return true
            }
            override fun onLongPress(e: android.view.MotionEvent) {
                if (followState == FollowState.SELECTING) return
                // 🎤 듣는 중: 마디를 길게 누르면 그 마디부터 다시 맞춘다 (P10 §10, #087). 마디 밖이면 전처럼 PDF 표시 옵션
                if (micFollower != null && anchorMicFollowAt(e.x, e.y)) return
                isLongPressing = true
                longPressRunnable.run()
                isLongPressing = false
            }
        })
    }

    /** 이번 터치가 메모 도구 줄 · 🎙 단추 · 🎤 시작 단추에서 시작했다 — 단추만 받고 몸짓 · 메모로 보내지 않는다 */
    private var touchOnNoteToolbar = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            fun View.hit() = visibility == View.VISIBLE && ev.x >= left && ev.x <= right && ev.y >= top && ev.y <= bottom
            touchOnNoteToolbar = binding.noteToolbar.hit() || binding.voiceButton.hit() || binding.micStartButton.hit()
        }
        if (touchOnNoteToolbar) return super.dispatchTouchEvent(ev)
        // 메모 모드의 두 손가락 = 확대 · 이동 (긋던 획은 버린다)
        if (handleNotePinch(ev)) return true
        // 악보 메모 (P11): 펜은 늘, 손가락은 메모 모드에서 — 먹은 것은 넘김 몸짓으로 보내지 않는다
        if (notePen.handle(ev)) return true
        // 악보 화면에는 눌리는 뷰가 없다 — 모든 터치를 몸짓으로 (안내 카드도 옆 탭 = ← → 가 다음 · 이전 파일로 간다)
        super.dispatchTouchEvent(ev)
        touchGestures.onTouchEvent(ev)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) phoneDragEnd()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_VOICE) {
            val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            showVoiceStatus(if (granted) "🎙 이제 단추를 누른 채로 말하세요" else "🎙 마이크 권한이 없어 들을 수 없어요", VOICE_STATUS_MS)
            if (granted) scheduleAlwaysListening()
            return
        }
        if (requestCode != REQUEST_MIC_FOLLOW) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startMicFollow()
        else {
            micStartAt = null
            Toast.makeText(this, "마이크 권한이 없어 들을 수 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    // ── 악보 메모 (P11) — 펜 · 손가락으로 선 긋기. PDF 옆 `.notes.json` 곁 파일 ─────────────────────────────
    /**
     * 메모 겹 하나 — 개인(`.notes.json`) · 지휘자(`.conductor.notes.json`, 앙상블 악보, P11 §6). 겹마다 메모 · 되돌리기 · 곁 파일.
     * 지휘자 메모는 모두에게 보이고(개인 메모 아래, 다른 모양), 쓸 수 있는 사람(owner · leader)만 도구 줄에서 그 겹을 고른다
     */
    private class NoteLayer(val conductor: Boolean) {
        var notes = com.mrgq.pdfviewer.notes.ScoreNotes()
        var ready = false
        /** 곁 파일을 읽지 못했다 — 덮어쓰지 않는다 */
        var broken = false
        var unknown: List<com.google.gson.JsonObject> = emptyList()
        /** 지휘자 메모를 쓸 수 있나 (동기화가 곁 파일에 적은 것) */
        var writable = false

        fun reset() {
            notes = com.mrgq.pdfviewer.notes.ScoreNotes()
            ready = false
            broken = false
            unknown = emptyList()
            writable = false
        }

        fun fileOf(pdf: File): File =
            if (conductor) com.mrgq.pdfviewer.notes.ScoreNotesFile.conductorFileOf(pdf) else com.mrgq.pdfviewer.notes.ScoreNotesFile.fileOf(pdf)
    }

    private val personalNotes = NoteLayer(conductor = false)
    private val conductorNotes = NoteLayer(conductor = true)
    /** 지휘자 메모에 쓰는 중 (도구 줄에서 고름 — 쓸 수 있을 때만) */
    private var conductorActive = false
    /** 지금 쓰는 겹 */
    private val activeLayer: NoteLayer get() = if (conductorActive && conductorNotes.writable) conductorNotes else personalNotes
    /** 지금 쓰는 겹의 메모 — 편집은 늘 이 겹에만 */
    private val notes: com.mrgq.pdfviewer.notes.ScoreNotes get() = activeLayer.notes
    /** 어느 PDF 의 메모인가. 파일이 바뀌면 새로 연다 (되돌리기도 새로) */
    private var notesPath: String? = null
    /** 지우개로 문지르는 중인 것 — 화면에서 바로 빼고, 손을 떼면 한 번에 지운다(되돌리기 한 번) */
    private val notesErasing = LinkedHashMap<String, com.mrgq.pdfviewer.notes.ScoreNote>()
    /** 붙을 보표를 정하는 보표 띠 (P11 §3.6) — 악보 분석 */
    private var noteStaves: List<com.mrgq.pdfviewer.database.entity.ScoreStaff> = emptyList()
    private var noteStavesFileId: String? = null
    private val noteHintClear = Runnable { binding.noteLayer.staffHint(null, null, true) }

    /** 직접 렌더한 쪽 크기 — 렌더러가 바뀌면 비운다 (프리렌더 쪽은 [PageCache.pageSizeOf]) */
    private val pageSizeBook = HashMap<Int, Pair<Int, Int>>()
    private var pageSizeOwner: PdfRenderer? = null

    private fun notePageSize(page: PdfRenderer.Page) = synchronized(pageSizeBook) {
        if (pageSizeOwner !== pdfRenderer) {
            pageSizeBook.clear()
            pageSizeOwner = pdfRenderer
        }
        pageSizeBook[page.index] = page.width to page.height
    }

    private fun pageSizeOf(index: Int): Pair<Int, Int>? =
        synchronized(pageSizeBook) { if (pageSizeOwner === pdfRenderer) pageSizeBook[index] else null }
            ?: pageCache?.pageSizeOf(index)

    private fun notesVisible() = preferences.getBoolean(PREF_SHOW_NOTES, true)

    /** 메모를 쓸 수 있는 기기 — 터치가 있는 태블릿 · 휴대폰 (TV 는 보기만) */
    private fun notesWritable() = !com.mrgq.pdfviewer.utils.DeviceForm.isTv(this)

    /** 메모를 이 화면에 그릴 수 없는 까닭 — null 이면 그릴 수 있다 */
    private fun notesBlockedReason(): String? = when {
        partViewLayout != null -> "파트 보기에서는 아직 메모를 쓸 수 없습니다 — 전체 악보에서"
        activeLayer.broken -> "메모 파일을 읽지 못해 쓰지 않습니다"
        else -> null
    }

    /** 지금 화면에 놓인 쪽들 (쪽 pt ↔ 표시 비트맵 px). 파트 보기 · 반 쪽 넘김은 원본 쪽이 아니라 비운다 */
    private fun notePlacements(): List<com.mrgq.pdfviewer.score.PagePlacement> {
        if (partViewLayout != null || halfPageShown || pdfRenderer == null) return emptyList()
        val spread = rollSpread
        val left = spread?.left ?: pageIndex
        val right = if (isTwoPageMode) (if (spread != null) spread.right else (pageIndex + 1).takeIf { it < pageCount }) else null
        return ScoreOverlayGeometry.placements(
            left, right, isTwoPageMode, ::pageSizeOf, screenWidth, screenHeight,
            currentTopClipping, currentBottomClipping, currentCenterPadding,
        )
    }

    /** 표시 비트맵 px → 화면(창) 좌표 행렬 — 마디 탭과 같다 */
    private fun bitmapToWindow(): android.graphics.Matrix {
        val view = binding.pdfView
        return android.graphics.Matrix(view.imageMatrix).apply {
            postTranslate((view.left + view.paddingLeft).toFloat(), (view.top + view.paddingTop).toFloat())
        }
    }

    private fun windowToBitmap(x: Float, y: Float): Pair<Float, Float>? {
        val inverse = android.graphics.Matrix()
        if (!bitmapToWindow().invert(inverse)) return null
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        return p[0] to p[1]
    }

    /** 파일이 바뀌었으면 곁 파일을 연다 (메모 줄 스레드에서 — 앞서 저장 중인 것이 끝난 뒤에 읽는다) */
    private fun ensureNotesLoaded() {
        if (!::pdfFilePath.isInitialized) return
        val path = pdfFilePath
        if (path.isEmpty() || notesPath == path) return
        notePen.cancel()
        commitPendingNote(refresh = false) // 그리던 묶음은 앞 파일에 (다시 그리기는 이 함수를 부르므로 하지 않는다)
        notesPath = path
        conductorActive = false
        notesErasing.clear()
        val pdf = File(path)
        for (layer in listOf(personalNotes, conductorNotes)) {
            layer.reset()
            com.mrgq.pdfviewer.notes.ScoreNotesFile.openAsync(layer.fileOf(pdf), pdf) { opened ->
                runOnUiThread {
                    if (notesPath != path) return@runOnUiThread
                    layer.notes = com.mrgq.pdfviewer.notes.ScoreNotes(opened.loaded.notes)
                    layer.unknown = opened.loaded.unknown
                    layer.broken = opened.broken
                    layer.writable = layer.conductor && opened.loaded.sync?.writable == true
                    layer.ready = true
                    if (!layer.conductor) {
                        if (opened.broken) toast("메모 파일을 읽지 못했습니다 — 이 악보에는 메모를 쓰지 않습니다")
                        if (opened.newEdition) toast("악보가 새 판입니다 — 메모 자리가 어긋날 수 있습니다")
                    }
                    updateNoteToolbar()
                    refreshNotes()
                }
            }
        }
    }

    /** 붙을 보표를 정할 보표 띠 — 쓸 수 있는 기기에서만, 처음 그을 때 (악보 분석은 DB 캐시) */
    private fun ensureNoteStaves() {
        val fileId = currentPdfFileId ?: return
        if (noteStavesFileId == fileId || partViewLayout != null) return
        noteStavesFileId = fileId
        noteStaves = emptyList()
        val file = File(pdfFilePath)
        lifecycleScope.launch {
            val staves = withContext(Dispatchers.IO) {
                try { musicRepository.getOrAnalyzeScoreStaves(fileId, file) } catch (e: Exception) { null }
            }
            if (noteStavesFileId == fileId) noteStaves = staves.orEmpty()
        }
    }

    /** 메모 층을 지금 화면에 맞춰 다시 그린다 — 마디 박스와 같은 때 ([refreshScoreOverlay]) */
    private fun refreshNotes() {
        val layer = binding.noteLayer
        if (!notesVisible() || isAnimating) {
            layer.clear()
            return
        }
        ensureNotesLoaded()
        // 지금 쓰는 겹에만 지우는 중 · 옮기는 중 · 그리는 중 묶음이 있다
        fun shown(l: NoteLayer, page: Int): List<com.mrgq.pdfviewer.notes.ScoreNote> =
            if (l !== activeLayer) l.notes.onPage(page)
            else l.notes.onPage(page).filter { it.id !in notesErasing }.map { n ->
                // 옮기는 중이면 그 메모 대신 옮긴 모습
                if (n.id == noteMoving?.id) noteMovePreview ?: n else n
            } + listOfNotNull(notePending?.takeIf { it.page == page && it.strokes.isNotEmpty() }) // 그리는 중인 묶음
        layer.show(notePlacements(), { page -> shown(personalNotes, page) }, { page -> shown(conductorNotes, page) }, binding.pdfView.imageMatrix)
    }

    /** 개인 ↔ 지휘자 메모 (쓸 수 있을 때만) — 그리던 묶음은 앞 겹에 확정 */
    private fun toggleConductorLayer() {
        if (!conductorNotes.writable) return
        commitPendingNote()
        notePen.cancel()
        conductorActive = !conductorActive
        toast(if (conductorActive) "지휘자 메모에 씁니다 — 앙상블 모두에게 보입니다" else "내 메모에 씁니다")
        refreshNotes()
        updateNoteToolbar()
    }

    // ── 2단계: 글자 · 옮기기 ──
    /** 옮기기로 잡은 메모와 지금 끈 모습 (아직 저장 전) */
    private var noteMoving: com.mrgq.pdfviewer.notes.ScoreNote? = null
    private var noteMovePreview: com.mrgq.pdfviewer.notes.ScoreNote? = null

    /** 쪽 [page] 의 (x, y) pt 에서 [radiusPt] 안의 메모 — 나중에 그린 것(위에 보이는 것)부터 */
    private fun noteAt(page: Int, x: Float, y: Float, radiusPt: Float, textOnly: Boolean = false): com.mrgq.pdfviewer.notes.ScoreNote? =
        notes.onPage(page).asReversed().firstOrNull { n ->
            when (n) {
                is com.mrgq.pdfviewer.notes.ScoreNote.Text ->
                    com.mrgq.pdfviewer.notes.NoteGeometry.hitsText(n, com.mrgq.pdfviewer.notes.ScoreNotesView.textWidthPt(n), x, y, radiusPt)
                is com.mrgq.pdfviewer.notes.ScoreNote.Ink -> !textOnly && com.mrgq.pdfviewer.notes.NoteGeometry.hits(n, x, y, radiusPt)
            }
        }

    /** 메모가 붙을 보표 — 차지하는 사각형의 위 · 아래로 (§3.6) */
    private fun noteAttachment(note: com.mrgq.pdfviewer.notes.ScoreNote): com.mrgq.pdfviewer.notes.NoteStaff.Attachment? {
        val b = com.mrgq.pdfviewer.notes.ScoreNotesView.boundsPt(note)
        return com.mrgq.pdfviewer.notes.NoteStaff.of(noteStaves, note.page, b.top, b.bottom)
    }

    /** 글자 넣기 · 고치기 — [existing] 이 있으면 고치기(비우면 지우기). 새 글자는 탭한 곳이 첫 줄 가운데 높이에 오게 */
    private fun showNoteTextDialog(page: Int, x: Float, y: Float, existing: com.mrgq.pdfviewer.notes.ScoreNote.Text?) {
        ensureNoteStaves()
        val input = android.widget.EditText(this).apply {
            setText(existing?.text.orEmpty())
            setSelection(text.length)
            hint = "예: rit. · 숨 · 4 · 활 바꿈"
            minLines = 1
            maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        fun commit() {
            val text = input.text.toString().trimEnd()
            if (existing != null) {
                if (text.isEmpty()) notes.remove(listOf(existing))
                else if (text != existing.text) {
                    val edited = existing.copy(id = notes.nextId(), text = text, author = null) // 고친 사람이 새 작성자 (서버가 찍음)
                    notes.replace(existing, edited.copy(staff = noteAttachment(edited)?.staff?.staffIndex))
                } else return
            } else {
                if (text.isEmpty()) return
                val size = notePen.textSizePt
                val note = com.mrgq.pdfviewer.notes.ScoreNote.Text(notes.nextId(), page, notePen.color, size, x, y - size * 0.6f, text)
                notes.add(note.copy(staff = noteAttachment(note)?.staff?.staffIndex))
            }
            saveNotes()
            refreshNotes()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing != null) "글자 고치기" else "글자 넣기")
            .setView(frame)
            .setPositiveButton("확인") { _, _ -> commit() }
            .setNegativeButton("취소", null)
            .apply {
                if (existing != null) setNeutralButton("지우기") { _, _ ->
                    notes.remove(listOf(existing))
                    saveNotes()
                    refreshNotes()
                }
            }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun saveNotes() {
        val path = notesPath ?: return
        val layer = activeLayer
        if (layer.broken) return
        val pdf = File(path)
        com.mrgq.pdfviewer.notes.ScoreNotesFile.saveAsync(layer.fileOf(pdf), pdf, layer.notes.notes.toList(), layer.unknown) { e ->
            Log.e("PdfViewerActivity", "메모 저장 실패: $path", e)
            runOnUiThread { toast("메모를 저장하지 못했습니다") }
        }
        updateNoteToolbar()
    }

    /** 보표 띠 (pt) → 표시 비트맵 px 사각형 — 쪽 폭 전체, 오선 위 · 아래로 한 칸씩 */
    private fun staffBandRect(page: Int, staff: com.mrgq.pdfviewer.database.entity.ScoreStaff): android.graphics.RectF? {
        val p = notePlacements().firstOrNull { it.pageIndex == page } ?: return null
        val space = (staff.bottomPt - staff.topPt) / 4
        return android.graphics.RectF(p.left, p.toBitmapY(staff.topPt - space), p.right, p.toBitmapY(staff.bottomPt + space))
    }

    /** 보표 이름 — 그 보표 번호에 이름이 적힌 시스템이 하나라도 있으면 그것 (보통 첫 시스템만 이름이 있다) */
    private fun staffName(staff: com.mrgq.pdfviewer.database.entity.ScoreStaff): String {
        val named = noteStaves.firstOrNull { it.staffIndex == staff.staffIndex && !it.label.isNullOrBlank() }
        return com.mrgq.pdfviewer.notes.NoteStaff.labelOf(named ?: staff)
    }

    private fun showStaffHint(page: Int, attachment: com.mrgq.pdfviewer.notes.NoteStaff.Attachment?) {
        binding.noteLayer.removeCallbacks(noteHintClear)
        if (attachment == null) {
            binding.noteLayer.staffHint(null, if (noteStaves.isEmpty()) null else "전체 악보", true)
            return
        }
        binding.noteLayer.staffHint(staffBandRect(page, attachment.staff), staffName(attachment.staff), attachment.sure)
    }

    private val notePen: com.mrgq.pdfviewer.notes.NotePen by lazy {
        com.mrgq.pdfviewer.notes.NotePen(object : com.mrgq.pdfviewer.notes.NotePen.Host {
            override fun canDraw() = notesWritable() && notesVisible() && activeLayer.ready && notesBlockedReason() == null &&
                !halfPageShown && !isAnimating && ::pdfFilePath.isInitialized && notesPath == pdfFilePath

            override fun pageAt(x: Float, y: Float): Int? {
                val (bx, by) = windowToBitmap(x, y) ?: return null
                return notePlacements().firstOrNull { it.contains(bx, by) }?.pageIndex
            }

            override fun toPage(page: Int, x: Float, y: Float): Pair<Float, Float>? {
                val p = notePlacements().firstOrNull { it.pageIndex == page } ?: return null
                val (bx, by) = windowToBitmap(x, y) ?: return null
                return p.toPageX(bx) to p.toPageY(by)
            }

            override fun pxPerPt(page: Int): Float {
                val p = notePlacements().firstOrNull { it.pageIndex == page } ?: return 1f
                val v = FloatArray(9)
                binding.pdfView.imageMatrix.getValues(v)
                return p.fitScale * v[android.graphics.Matrix.MSCALE_X]
            }

            override fun screenWidth() = binding.root.width

            override fun onStroke(page: Int, points: List<Float>, color: Int, widthPt: Float) {
                ensureNoteStaves()
                val simplified = com.mrgq.pdfviewer.notes.NoteGeometry.simplify(points, STROKE_TOLERANCE_PT)
                // 그리는 중인 묶음에 더한다 — 다른 쪽 · 색 · 굵기면 앞 묶음을 확정하고 새로
                val pending = notePending?.takeIf { it.page == page && it.color == color && it.widthPt == widthPt && it.strokes.isNotEmpty() }
                if (pending == null) commitPendingNote()
                val base = pending ?: com.mrgq.pdfviewer.notes.ScoreNote.Ink("", page, color, widthPt, emptyList())
                notePending = base.copy(strokes = base.strokes + listOf(simplified))
                notePendingRedo.clear()
                // 메모 모드 밖의 펜 — 도구 줄을 띄워 확인을 누를 수 있게
                if (!notePen.editMode) enterNoteMode()
                showStaffHint(page, notePending?.let { noteAttachment(it) })
                refreshNotes()
                updateNoteToolbar()
            }

            override fun onErase(page: Int, x: Float, y: Float, radiusPt: Float) {
                commitPendingNote()
                val hit = notes.onPage(page).filter {
                    it.id !in notesErasing && when (it) {
                        is com.mrgq.pdfviewer.notes.ScoreNote.Ink -> com.mrgq.pdfviewer.notes.NoteGeometry.hits(it, x, y, radiusPt)
                        is com.mrgq.pdfviewer.notes.ScoreNote.Text -> com.mrgq.pdfviewer.notes.NoteGeometry.hitsText(
                            it, com.mrgq.pdfviewer.notes.ScoreNotesView.textWidthPt(it), x, y, radiusPt)
                    }
                }
                if (hit.isEmpty()) return
                hit.forEach { notesErasing[it.id] = it }
                refreshNotes()
            }

            override fun onEraseEnd() {
                if (notesErasing.isEmpty()) return
                notes.remove(notesErasing.values.toList())
                notesErasing.clear()
                saveNotes()
                refreshNotes()
            }

            override fun onLive(page: Int, points: List<Float>, color: Int, widthPx: Float, topPt: Float, bottomPt: Float) {
                ensureNoteStaves()
                binding.noteLayer.liveStroke(points, color, widthPx)
                // 붙을 보표는 묶음 전체(그리는 중인 획들 + 지금 획)로
                val group = notePending?.takeIf { it.page == page && it.strokes.isNotEmpty() }
                    ?.let { com.mrgq.pdfviewer.notes.ScoreNotesView.boundsPt(it) }
                val top = minOf(topPt, group?.top ?: topPt)
                val bottom = maxOf(bottomPt, group?.bottom ?: bottomPt)
                showStaffHint(page, com.mrgq.pdfviewer.notes.NoteStaff.of(noteStaves, page, top, bottom))
            }

            override fun onLiveEnd() {
                binding.noteLayer.endLiveStroke()
                // 묶음을 그리는 중이면 보표 표시를 남겨 둔다 (확인 때 지운다)
                if (notePending?.strokes.isNullOrEmpty()) binding.noteLayer.postDelayed(noteHintClear, STAFF_HINT_MS)
            }

            override fun onTextTap(page: Int, x: Float, y: Float) {
                val radius = com.mrgq.pdfviewer.notes.NotePen.TAP_SLOP_PX / pxPerPt(page)
                val existing = noteAt(page, x, y, radius, textOnly = true) as? com.mrgq.pdfviewer.notes.ScoreNote.Text
                showNoteTextDialog(page, x, y, existing)
            }

            override fun onMoveStart(page: Int, x: Float, y: Float): Boolean {
                ensureNoteStaves()
                commitPendingNote()
                val picked = noteAt(page, x, y, com.mrgq.pdfviewer.notes.NotePen.PICK_PX / pxPerPt(page)) ?: return false
                picked.author?.let { toast("$it 님 메모") } // 지휘자 메모 — 누가 썼는지
                noteMoving = picked
                noteMovePreview = picked
                binding.noteLayer.select(notePlacements().firstOrNull { it.pageIndex == page }, picked)
                return true
            }

            override fun onMoveBy(page: Int, dx: Float, dy: Float) {
                val picked = noteMoving ?: return
                val preview = picked.movedBy(dx, dy, picked.id, picked.staff)
                noteMovePreview = preview
                binding.noteLayer.select(notePlacements().firstOrNull { it.pageIndex == page }, preview)
                showStaffHint(page, noteAttachment(preview))
                refreshNotes()
            }

            override fun onMoveEnd(commit: Boolean) {
                val picked = noteMoving
                val preview = noteMovePreview
                noteMoving = null
                noteMovePreview = null
                binding.noteLayer.select(null, null)
                binding.noteLayer.postDelayed(noteHintClear, STAFF_HINT_MS)
                if (commit && picked != null && preview != null && preview != picked) {
                    // 메모는 불변 — 옛 것을 지우고 새 id 로 (P11 §6)
                    notes.replace(picked, preview.movedBy(0f, 0f, notes.nextId(), noteAttachment(preview)?.staff?.staffIndex))
                    saveNotes()
                }
                refreshNotes()
            }

            override fun onEraserCursor(x: Float, y: Float, radiusPx: Float) {
                // 창 좌표 → 메모 층 좌표 (같은 자리 · 크기)
                binding.noteLayer.eraserAt(x - binding.noteLayer.left, y - binding.noteLayer.top, radiusPx)
            }
        })
    }

    /** 쪽 · 조각이 바뀌기 직전 — 긋던 획은 지금 화면 기준으로 마저 저장한다 */
    private fun flushNotePen() {
        notePen.flush()
        commitPendingNote() // 쪽이 바뀌면 그리던 묶음은 확정
    }

    /** 그리는 중인 묶음 (확인 전, 저장 전) — 확인을 누르면 메모 하나(사용자 요청 2026-10-07) */
    private var notePending: com.mrgq.pdfviewer.notes.ScoreNote.Ink? = null
    /** 묶음 안에서 되돌린 획 — 다시(↷) */
    private val notePendingRedo = ArrayDeque<List<Float>>()

    private fun hasPendingNote() = !notePending?.strokes.isNullOrEmpty()

    /** 그리는 중인 묶음을 메모 하나로 확정 — 붙는 보표는 묶음 전체로 */
    private fun commitPendingNote(refresh: Boolean = true) {
        val pending = notePending
        notePending = null
        notePendingRedo.clear()
        if (pending == null || pending.strokes.isEmpty()) return
        notes.add(pending.copy(id = notes.nextId(), staff = noteAttachment(pending)?.staff?.staffIndex))
        saveNotes()
        if (!refresh) return
        refreshNotes()
        binding.noteLayer.postDelayed(noteHintClear, STAFF_HINT_MS)
    }

    private fun undoNote() {
        val pending = notePending
        if (pending != null && pending.strokes.isNotEmpty()) {
            notePendingRedo.addLast(pending.strokes.last())
            notePending = pending.copy(strokes = pending.strokes.dropLast(1))
            if (!hasPendingNote()) binding.noteLayer.postDelayed(noteHintClear, 0)
        } else if (notes.undo()) saveNotes()
        refreshNotes()
        updateNoteToolbar()
    }

    private fun redoNote() {
        val pending = notePending
        if (pending != null && notePendingRedo.isNotEmpty()) {
            notePending = pending.copy(strokes = pending.strokes + listOf(notePendingRedo.removeLast()))
        } else if (notes.redo()) saveNotes()
        refreshNotes()
        updateNoteToolbar()
    }

    // ── 메모 모드 · 도구 줄 ──
    private fun toggleNoteMode() {
        if (notePen.editMode) exitNoteMode() else enterNoteMode()
    }

    private fun enterNoteMode() {
        notesBlockedReason()?.let { return toast(it) }
        if (!notesVisible()) preferences.edit().putBoolean(PREF_SHOW_NOTES, true).apply()
        if (halfPageShown) showPage(pageIndex) // 반 쪽 넘김 화면은 온전한 쪽으로
        ensureNoteStaves()
        notePen.editMode = true
        notePen.tool = com.mrgq.pdfviewer.notes.NotePen.Tool.PEN
        buildNoteToolbar()
        binding.noteToolbar.visibility = View.VISIBLE
        refreshNotes()
    }

    private fun exitNoteMode() {
        notePen.cancel()
        commitPendingNote()
        notePen.editMode = false
        binding.noteToolbar.visibility = View.GONE
        resetNoteZoom()
    }

    // ── 메모 모드 확대 · 이동 — 두 손가락으로 집기 · 끌기. 한 손가락 · 펜은 그대로 긋는다 ──
    // 범위는 쪽 단위: 태블릿은 쪽 맞춤 ~ 4배, 휴대폰은 조각이 아니라 쪽 전체 — 쪽이 다 들어가게 줄이기 ~ 폭 맞춤 4배,
    // 쪽 어디로든 끌 수 있다 (사용자 요청 2026-10-08)
    /** 확대 중인 표시 행렬 (비트맵 → 뷰) */
    private val noteZoom = android.graphics.Matrix()
    /** 확대하기 전의 표시 행렬 (쪽 맞춤 · 휴대폰은 지금 조각) */
    private var noteZoomBase: android.graphics.Matrix? = null
    /** 마지막으로 놓은 확대 행렬 — 넘김 · 조각 이동이 행렬을 새로 놓았으면 이것과 달라 확대가 풀린 것으로 본다 */
    private var noteZoomApplied: android.graphics.Matrix? = null
    private var notePinching = false
    private var pinchSpan = 0f
    private var pinchX = 0f
    private var pinchY = 0f

    /** 두 손가락이면 확대 · 이동으로 먹는다 — 이 터치가 끝날 때까지 */
    private fun handleNotePinch(ev: android.view.MotionEvent): Boolean {
        if (!notePinching) {
            if (ev.actionMasked != android.view.MotionEvent.ACTION_POINTER_DOWN || ev.pointerCount != 2 || !notePen.editMode) return false
            if ((0 until 2).any { ev.getToolType(it) != android.view.MotionEvent.TOOL_TYPE_FINGER }) return false
            if (isAnimating || pdfRenderer == null) return false
            notePen.cancel()
            // 첫 손가락이 넘김 몸짓으로 갔을 수 있다 — 탭 · 끌기로 끝나지 않게 거둔다
            val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
            touchGestures.onTouchEvent(cancel)
            cancel.recycle()
            phoneDragEnd()
            val current = binding.pdfView.imageMatrix
            if (noteZoomApplied == null || current != noteZoomApplied) {
                noteZoomBase = android.graphics.Matrix(current)
                noteZoom.set(current)
            }
            notePinching = true
            pinchFocus(ev)
            return true
        }
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> if (ev.pointerCount >= 2) {
                val lastSpan = pinchSpan
                val lastX = pinchX
                val lastY = pinchY
                pinchFocus(ev)
                val f = if (lastSpan > 0f) pinchSpan / lastSpan else 1f
                noteZoom.postScale(f, f, pinchX, pinchY)
                noteZoom.postTranslate(pinchX - lastX, pinchY - lastY)
                applyNoteZoom()
            }
            // 손가락 수가 바뀌면 중심을 다시 잡는다 (튀지 않게)
            android.view.MotionEvent.ACTION_POINTER_DOWN, android.view.MotionEvent.ACTION_POINTER_UP -> pinchFocus(ev, ev.actionIndex.takeIf { ev.actionMasked == android.view.MotionEvent.ACTION_POINTER_UP })
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                notePinching = false
                notePen.handle(ev) // 펜 쪽 상태를 끝낸다 (버린 획)
            }
        }
        return true
    }

    /** 손가락들의 가운데 · 퍼짐 (화면 좌표 그대로 — 뷰가 창을 꽉 채운다) */
    private fun pinchFocus(ev: android.view.MotionEvent, skip: Int? = null) {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) if (i != skip) {
            sx += ev.getX(i)
            sy += ev.getY(i)
            n++
        }
        if (n == 0) return
        pinchX = sx / n
        pinchY = sy / n
        var d = 0f
        for (i in 0 until ev.pointerCount) if (i != skip) d += kotlin.math.hypot(ev.getX(i) - pinchX, ev.getY(i) - pinchY)
        pinchSpan = if (n >= 2) d / n else 0f
    }

    /**
     * 확대를 쪽 범위로 가두고 표시 행렬에 놓는다 — 배율은 [NOTE_ZOOM_MAX] 까지, 쪽이 화면보다 작은 쪽(축)은 가운데,
     * 크면 쪽 밖의 빈 곳이 보이지 않게. 태블릿은 쪽 맞춤보다 작아지면 확대를 푼다
     */
    private fun applyNoteZoom() {
        val base = noteZoomBase ?: return
        val drawable = binding.pdfView.drawable ?: return
        val bw = drawable.intrinsicWidth.toFloat()
        val bh = drawable.intrinsicHeight.toFloat()
        val w = binding.pdfView.width.toFloat()
        val h = binding.pdfView.height.toFloat()
        if (bw <= 0f || bh <= 0f || w <= 0f || h <= 0f) return
        val v = FloatArray(9)
        base.getValues(v)
        val baseScale = v[android.graphics.Matrix.MSCALE_X]
        val (minScale, maxScale) =
            if (phoneView) minOf(w / bw, h / bh) to w / bw * NOTE_ZOOM_MAX
            else baseScale to baseScale * NOTE_ZOOM_MAX
        noteZoom.getValues(v)
        val scale = v[android.graphics.Matrix.MSCALE_X]
        if (!phoneView && scale <= minScale * 1.001f) return resetNoteZoom()
        val target = scale.coerceIn(minScale, maxScale)
        if (target != scale) noteZoom.postScale(target / scale, target / scale, pinchX, pinchY)
        noteZoom.getValues(v)
        fun fit(t: Float, content: Float, room: Float) = if (content <= room) (room - content) / 2f else t.coerceIn(room - content, 0f)
        v[android.graphics.Matrix.MTRANS_X] = fit(v[android.graphics.Matrix.MTRANS_X], bw * target, w)
        v[android.graphics.Matrix.MTRANS_Y] = fit(v[android.graphics.Matrix.MTRANS_Y], bh * target, h)
        noteZoom.setValues(v)
        binding.pdfView.imageMatrix = noteZoom
        noteZoomApplied = android.graphics.Matrix(noteZoom)
        refreshScoreOverlay()
    }

    /**
     * 확대를 푼다 — 아직 화면에 있을 때만 (넘김이 이미 새 행렬을 놓았으면 그대로). 태블릿은 쪽 맞춤으로,
     * 휴대폰은 지금 화면 맨 위에 가장 가까운 시스템 조각으로 (끌기를 놓을 때와 같게)
     */
    private fun resetNoteZoom() {
        val base = noteZoomBase
        val applied = noteZoomApplied
        noteZoomBase = null
        noteZoomApplied = null
        if (base == null || applied == null || binding.pdfView.imageMatrix != applied) return
        if (phoneView && phoneChunks.isNotEmpty()) {
            val v = FloatArray(9)
            applied.getValues(v)
            val topY = -v[android.graphics.Matrix.MTRANS_Y] / v[android.graphics.Matrix.MSCALE_Y]
            val nearest = phoneChunks.indices.minByOrNull { kotlin.math.abs(phoneChunks[it].start - topY) } ?: 0
            showChunk(minOf(nearest, phoneStartEndingAt(phoneChunks.lastIndex)))
            return
        }
        binding.pdfView.imageMatrix = base
        refreshScoreOverlay()
    }

    private val noteToolButtons = mutableListOf<Pair<android.widget.TextView, () -> Boolean>>()
    private var noteUndoButton: android.widget.TextView? = null
    private var noteRedoButton: android.widget.TextView? = null
    private var noteDoneButton: android.widget.TextView? = null
    private var noteLayerButton: android.widget.TextView? = null

    private fun buildNoteToolbar() {
        val bar = binding.noteToolbar
        if (bar.childCount > 0) return updateNoteToolbar()
        val pen = notePen
        pen.color = preferences.getInt(PREF_NOTE_COLOR, pen.color)
        pen.widthPt = preferences.getFloat(PREF_NOTE_WIDTH, pen.widthPt)
        pen.textSizePt = preferences.getFloat(PREF_NOTE_TEXT_SIZE, pen.textSizePt)
        val size = (resources.displayMetrics.density * 44).toInt()
        fun button(label: String, color: Int = android.graphics.Color.WHITE, textSp: Float = 20f, selected: (() -> Boolean)? = null, onClick: () -> Unit): android.widget.TextView {
            val b = android.widget.TextView(this).apply {
                text = label
                setTextColor(color)
                textSize = textSp
                gravity = android.view.Gravity.CENTER
                minWidth = size
                minimumHeight = size
                setPadding(size / 6, 0, size / 6, 0)
                isFocusable = false
                setOnClickListener {
                    onClick()
                    updateNoteToolbar()
                }
            }
            bar.addView(b, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, size))
            if (selected != null) noteToolButtons += b to selected
            return b
        }
        button("✏️", selected = { pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.PEN }) {
            if (pen.tool != com.mrgq.pdfviewer.notes.NotePen.Tool.PEN) commitPendingNote()
            pen.tool = com.mrgq.pdfviewer.notes.NotePen.Tool.PEN
        }
        button("🧽", selected = { pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.ERASER }) {
            if (pen.tool != com.mrgq.pdfviewer.notes.NotePen.Tool.ERASER) commitPendingNote()
            pen.tool = com.mrgq.pdfviewer.notes.NotePen.Tool.ERASER
        }
        button("🅰", selected = { pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT }) {
            if (pen.tool != com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT) commitPendingNote()
            pen.tool = com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT
        }
        button("✋", selected = { pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.MOVE }) {
            if (pen.tool != com.mrgq.pdfviewer.notes.NotePen.Tool.MOVE) commitPendingNote()
            pen.tool = com.mrgq.pdfviewer.notes.NotePen.Tool.MOVE
        }
        for (c in com.mrgq.pdfviewer.notes.NotePen.COLORS) {
            // 검정은 어두운 줄에서 안 보이니 테두리 색으로
            button("●", color = if (c == com.mrgq.pdfviewer.notes.NotePen.COLORS[0]) 0xFFBDBDBD.toInt() else c, textSp = 24f,
                selected = { pen.color == c && (pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.PEN || pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT) }) {
                if (pen.color != c) commitPendingNote() // 묶음은 한 색
                pen.color = c
                // 글자 도구면 글자 색, 아니면 펜으로
                if (pen.tool != com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT) pen.tool = com.mrgq.pdfviewer.notes.NotePen.Tool.PEN
                preferences.edit().putInt(PREF_NOTE_COLOR, c).apply()
            }
        }
        // 굵기 단추 — 글자 도구면 글자 크기(작게 · 보통 · 크게)
        noteSizeButtons.clear()
        com.mrgq.pdfviewer.notes.NotePen.WIDTHS.forEachIndexed { i, w ->
            val size = com.mrgq.pdfviewer.notes.NotePen.TEXT_SIZES[i]
            noteSizeButtons += button(NOTE_WIDTH_LABELS[i], textSp = 22f, selected = {
                if (pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT) pen.textSizePt == size else pen.widthPt == w
            }) {
                if (pen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT) {
                    pen.textSizePt = size
                    preferences.edit().putFloat(PREF_NOTE_TEXT_SIZE, size).apply()
                } else {
                    if (pen.widthPt != w) commitPendingNote() // 묶음은 한 굵기
                    pen.widthPt = w
                    pen.tool = com.mrgq.pdfviewer.notes.NotePen.Tool.PEN
                    preferences.edit().putFloat(PREF_NOTE_WIDTH, w).apply()
                }
            }
        }
        // 지휘자 메모 겹 — 이 악보에 쓸 수 있을 때만 보인다 (updateNoteToolbar)
        noteLayerButton = button("👤", textSp = 18f) { toggleConductorLayer() }
        noteUndoButton = button("↶") { undoNote() }
        noteRedoButton = button("↷") { redoNote() }
        // 그리는 중이면 "확인" = 묶음을 메모 하나로, 아니면 "끝" = 메모 모드 닫기
        noteDoneButton = button("끝", textSp = 18f) { if (hasPendingNote()) commitPendingNote() else exitNoteMode() }
        updateNoteToolbar()
    }

    private val noteSizeButtons = mutableListOf<android.widget.TextView>()

    private fun updateNoteToolbar() {
        val text = notePen.tool == com.mrgq.pdfviewer.notes.NotePen.Tool.TEXT
        noteSizeButtons.forEachIndexed { i, b ->
            b.text = if (text) NOTE_TEXT_SIZE_LABELS[i] else NOTE_WIDTH_LABELS[i]
            b.textSize = if (text) NOTE_TEXT_SIZE_SP[i] else 22f
        }
        for ((b, selected) in noteToolButtons) b.setBackgroundColor(if (selected()) 0x5590CAF9 else 0)
        noteUndoButton?.alpha = if (notes.canUndo || hasPendingNote()) 1f else 0.35f
        noteRedoButton?.alpha = if (notes.canRedo || notePendingRedo.isNotEmpty()) 1f else 0.35f
        noteDoneButton?.text = if (hasPendingNote()) "확인" else "끝"
        noteLayerButton?.visibility = if (conductorNotes.writable) View.VISIBLE else View.GONE
        noteLayerButton?.text = if (activeLayer.conductor) "🎼 지휘자" else "👤 내 메모"
        noteLayerButton?.setBackgroundColor(if (activeLayer.conductor) 0x997E57C2.toInt() else 0)
        noteDoneButton?.setBackgroundColor(if (hasPendingNote()) 0xCC43A047.toInt() else 0)
    }

    private fun toggleNotesVisible(announce: Boolean = true) {
        val show = !notesVisible()
        preferences.edit().putBoolean(PREF_SHOW_NOTES, show).apply()
        if (!show) exitNoteMode()
        refreshNotes()
        if (announce) toast(if (show) "메모 보이기" else "메모 숨김")
    }

    // ── 마이크로 연주를 듣고 넘기기 — 태블릿 지휘자 (P10) ─────────────────────────────────
    private var micFollower: com.mrgq.pdfviewer.follow.MicScoreFollower? = null
    /** MusicXML 마디 순서 → 악보(PDF) 마디 — 추적을 시작할 때 만든다 */
    private var micMeasures: List<ScoreMeasure?> = emptyList()
    /** 추정한 지금 마디 — 노란 테두리 */
    private var micFocus: ScoreMeasure? = null
    /** 듣기를 켜고 연주 시작을 기다리는 마디 — 시작을 찾을 때까지 그 마디만 연하게 (#089). 시작하거나 손으로 짚으면 null */
    private var micWaitingMeasure: ScoreMeasure? = null
    /** 다음 page_change 는 마이크 추적이 넘긴 것 — 연주자는 차례 넘김 (P10 §3.2) */
    private var micRollBroadcast = false
    /** 연주자에게 마지막으로 알린 시스템 (쪽 × 1000 + 시스템) */
    private var micSentSystem: Int? = null

    /** 이 기기에서 마이크 추적을 쓸 수 있나 — 태블릿(TV 아님)에 마이크 */
    /** 마이크 추적의 지금 시스템 — 그 시스템 마디 박스들을 합친 사각형 (표시 비트맵 픽셀). 시작을 기다리는 동안은 그 마디 하나 */
    private fun micSystemFrame(): android.graphics.RectF? {
        micWaitingMeasure?.let { m ->
            val box = overlayBoxes(listOf(m)).firstOrNull() ?: return null
            return android.graphics.RectF(box.left, box.top, box.right, box.bottom)
        }
        val focus = micFocus ?: return null
        val boxes = overlayBoxes(micMeasures.filter { it != null && it.pageIndex == focus.pageIndex && it.systemIndex == focus.systemIndex }.filterNotNull())
        if (boxes.isEmpty()) return null
        return android.graphics.RectF(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    private fun micFollowCapable(): Boolean =
        !com.mrgq.pdfviewer.utils.DeviceForm.isTv(this) &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)

    private fun toggleMicFollow() {
        if (micFollower != null) return stopMicFollow(announce = true)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC_FOLLOW)
            return
        }
        startMicFollow()
    }

    /** 다음 [startMicFollow] 가 시작할 마디 — 시작 마디 고르기의 "🎤 여기서부터 듣기"(#087). null = 지금 쪽 첫 마디 */
    private var micStartAt: ScoreMeasure? = null
    /** [startMicFollow] 가 마디 · 악보 크로마를 준비하는 중 — 그사이 👂 계속 듣기가 마이크를 다시 잡지 않게 (#087) */
    private var micFollowStarting = false

    /** 연주 듣고 넘기기를 시작할 수 없는 까닭 — 시작할 수 있으면 null (P10 §3.1). 마디 · MusicXML 맞춤은 시작하면서 본다 */
    private fun micFollowBlocker(): String? = when {
        currentPdfFileId == null -> "악보를 아직 여는 중입니다"
        collaborationMode == CollaborationMode.PERFORMER -> "연주자 기기에서는 쓸 수 없습니다 — 지휘자가 넘깁니다"
        partViewLayout != null -> "전체 악보에서만 쓸 수 있습니다 (파트 보기를 끄세요)"
        musicXml == null || musicXmlFileId != currentPdfFileId -> "이 곡의 MusicXML 이 없어 들을 수 없습니다"
        else -> null
    }

    /**
     * [micStartAt](고른 마디) 또는 지금 쪽 첫 마디부터 듣기 시작. 조건: 전체 악보 · 맞는 MusicXML · 악보 분석 마디 (P10 §3.1)
     */
    private fun startMicFollow() {
        val at = micStartAt.also { micStartAt = null }
        micFollowBlocker()?.let { return toast(it) }
        val fileId = currentPdfFileId ?: return
        val score = musicXml ?: return
        stopMetronome()
        // 👂 계속 듣기가 마이크를 쥐고 있으면 놓는다 — 준비하는 동안 다시 잡지 않고, 추적이 열리면 refreshVoiceButton 이 다시 걸지 않는다,
        // 못 열면 다시 건다
        micFollowStarting = true
        stopAlwaysListening()
        val file = File(pdfFilePath)
        lifecycleScope.launch {
            val measures = withContext(Dispatchers.IO) { musicRepository.getOrAnalyzeScoreMeasures(fileId, file) }
            if (measures.isNullOrEmpty() || currentPdfFileId != fileId) return@launch toast("악보에서 마디를 찾지 못해 들을 수 없습니다")
            val match = com.mrgq.pdfviewer.metronome.MusicXmlMatch.check(score, measures)
            if (!match.ok) return@launch toast("MusicXML 이 악보와 맞지 않습니다: ${match.reason}")
            val byNumber = measures.associateBy { it.measureNumber }
            val mapped = score.measures.indices.map { byNumber[match.measureNumberOf(it)] }
            var lastPage = 0
            val pageOf = IntArray(mapped.size) { i -> (mapped[i]?.pageIndex ?: lastPage).also { lastPage = it } }
            val lowerHalf = BooleanArray(mapped.size) { i -> mapped[i]?.let { it.topPt >= it.pageHeightPt / 2 } ?: false }
            val here = if (isTwoPageMode) pairStart(pageIndex) else pageIndex
            val startMeasure = if (at != null) {
                // 고른 마디 — 같은 쪽의 같은 번호(MusicXML 순서에서 처음 나오는 것)
                mapped.indexOfFirst { it != null && it.measureNumber == at.measureNumber && it.pageIndex == at.pageIndex }
                    .takeIf { it >= 0 } ?: return@launch toast("${at.measureNumber}마디를 MusicXML 에서 찾지 못했습니다")
            } else {
                pageOf.indexOfFirst { it >= here }.takeIf { it >= 0 } ?: return@launch toast("이 쪽에 마디가 없습니다")
            }
            // 기준 빠르기 = 이 곡 메트로놈 템포 (박 → 4분음표)
            val meter = metronome.timeSignature
            val beatQ = 4.0 / meter.denominator * (if (metronome.dottedBeat) 3 else 1)
            val quarterBpm = metronome.bpm * beatQ
            val chroma = withContext(Dispatchers.Default) {
                com.mrgq.pdfviewer.follow.ScoreChroma.build(score, quarterBpm, com.mrgq.pdfviewer.follow.MicScoreFollower.FRAME_SEC)
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val base = pdfFileName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val wav = File(File(getExternalFilesDir(null), "recordings"), "${base}_${stamp}_follow.wav")
            val info = mapOf(
                "pdf" to pdfFileName,
                "quarter_bpm" to "%.1f".format(java.util.Locale.US, quarterBpm),
                "start_measure" to (mapped[startMeasure]?.measureNumber ?: 0).toString(),
                "start_page" to (pageOf[startMeasure] + 1).toString(),
                "ensemble" to collaborationMode.name,
                "device" to android.os.Build.MODEL,
                "app_version" to BuildConfig.VERSION_NAME,
            )
            val follower = com.mrgq.pdfviewer.follow.MicScoreFollower(
                chroma, pageOf, lowerHalf, startMeasure, pageOf[startMeasure], wav, info, micListener,
            )
            if (!follower.start()) return@launch toast("마이크를 열지 못했습니다")
            micFollower = follower
            // 두 쪽 연주자: 시작 쪽이 펼침 오른쪽이면 왼쪽 자리를 바로 다음 쪽으로 — 듣기 시작 신호 (#088)
            if (collaborationMode == CollaborationMode.CONDUCTOR) {
                globalCollaborationManager.broadcastPageChange(pageOf[startMeasure] + 1, pdfFileName, roll = true, rollStart = true)
            }
            micMeasures = mapped
            binding.micStatus.text = "🎤 대기"
            binding.micStatus.visibility = View.VISIBLE
            micWaitingMeasure = mapped[startMeasure] // 시작을 찾을 때까지 이 마디만 연하게 (#089)
            refreshVoiceButton()
            refreshScoreOverlay()
            Toast.makeText(this@PdfViewerActivity, "🎤 ${mapped[startMeasure]?.measureNumber}번 마디부터 듣습니다 — 연주를 시작하세요", Toast.LENGTH_LONG).show()
        }.invokeOnCompletion {
            binding.voiceButton.post {
                micFollowStarting = false
                if (micFollower == null) scheduleAlwaysListening() // 못 열었으면 👂 다시
            }
        }
    }

    private fun stopMicFollow(announce: Boolean = false) {
        val f = micFollower ?: return
        micFollower = null
        f.stop()
        micFocus = null
        micWaitingMeasure = null
        if (micSentSystem != null && collaborationMode == CollaborationMode.CONDUCTOR) {
            globalCollaborationManager.broadcastFollowPosition(pdfFileName, 0) // 연주자 표시 지움
        }
        micSentSystem = null
        binding.micStatus.visibility = View.GONE
        refreshVoiceButton()
        refreshScoreOverlay()
        if (announce) Toast.makeText(this, "🎤 듣기 멈춤 — 기록은 recordings 에", Toast.LENGTH_SHORT).show()
    }

    private val micListener = object : com.mrgq.pdfviewer.follow.MicScoreFollower.Listener {
        override fun onListening() {
            if (micFollower == null) return
            binding.micStatus.text = "🎤 듣는 중"
            micWaitingMeasure = null // 시작을 찾았다 — 이제 지금 시스템 표시 (#089)
            refreshScoreOverlay()
        }

        override fun onPosition(measurePos: Double) {
            if (micFollower == null) return
            val m = micMeasures.getOrNull(measurePos.toInt())
            micFocus = m
            // 합주 지휘자면 시스템이 바뀔 때 연주자에게 (P10) — 연주자도 지금 시스템을 연하게 표시
            val system = m?.let { it.pageIndex * 1000 + it.systemIndex }
            if (m != null && system != micSentSystem && collaborationMode == CollaborationMode.CONDUCTOR) {
                micSentSystem = system
                globalCollaborationManager.broadcastFollowPosition(pdfFileName, m.measureNumber)
            }
            binding.micStatus.text = "🎤 ${m?.measureNumber ?: "?"}"
            refreshScoreOverlay()
        }

        override fun onTurn(turn: com.mrgq.pdfviewer.follow.PageTurnDecider.Turn) {
            if (micFollower == null) return
            when (turn) {
                is com.mrgq.pdfviewer.follow.PageTurnDecider.Turn.Half -> showHalfPage(turn.page, turn.nextPage)
                is com.mrgq.pdfviewer.follow.PageTurnDecider.Turn.Page -> {
                    val target = if (isTwoPageMode) pairStart(turn.page) else turn.page
                    if (target == pageIndex || target !in 0 until pageCount) return
                    micRollBroadcast = true
                    showPageWithAnimation(target, if (target > pageIndex) 1 else -1)
                }
            }
        }

        override fun onError(message: String) {
            toast("🎤 $message")
            stopMicFollow()
        }
    }

    /** 🎤 듣는 중 (x, y) 의 마디를 길게 눌렀다 — 그 마디부터 다시 맞춘다 (P10 §10, #087). 마디가 아니면 false */
    private fun anchorMicFollowAt(x: Float, y: Float): Boolean {
        val f = micFollower ?: return false
        val m = measureAt(micMeasures.filterNotNull().distinct(), x, y) ?: return false
        val index = micMeasures.indexOfFirst { it != null && it.measureNumber == m.measureNumber && it.pageIndex == m.pageIndex }
        if (index < 0) return false
        f.anchor(m.pageIndex, index)
        micWaitingMeasure = null // 손으로 짚은 곳이 곧 시작
        binding.pdfView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        toast("🎤 ${m.measureNumber}마디부터 다시 맞춥니다")
        return true
    }

    /** 시작 마디를 고르는 중 "🎤 N마디부터 듣기" — 고르기를 접고 그 마디부터 연주 듣고 넘기기 (#087) */
    private fun startMicFollowFromSelection() {
        val at = followMeasures.getOrNull(cursorIndex)?.takeIf { followState == FollowState.SELECTING } ?: return
        cancelMeasureSelection()
        micStartAt = at
        toggleMicFollow()
    }

    /** 시작 마디를 고르는 동안만 "🎤 N마디부터 듣기" — 마이크가 있는 기기, 연주자가 아니고 듣는 중이 아닐 때 */
    private fun refreshMicStartButton() {
        val at = followMeasures.getOrNull(cursorIndex)
        val show = followState == FollowState.SELECTING && at != null && micFollowCapable() && micFollower == null &&
            collaborationMode != CollaborationMode.PERFORMER
        binding.micStartButton.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        binding.micStartButton.text = "🎤 ${at!!.measureNumber}마디부터 듣기"
        binding.micStartButton.setOnClickListener { startMicFollowFromSelection() }
    }

    /** 손으로 [page] 쪽으로 넘겼다 — 추적도 그 쪽 첫 마디에서 다시 (P10 §2) */
    private fun anchorMicFollow(page: Int) {
        val f = micFollower ?: return
        val first = micMeasures.indexOfFirst { it != null && it.pageIndex >= page }
        if (first >= 0) {
            f.anchor(page, first)
            micWaitingMeasure = null // 손으로 넘긴 쪽이 곧 시작 (#089)
        }
    }

    /**
     * 반 쪽 넘김 (P10 §3.3.1) — 위는 다음 쪽 [next] 의 위 절반, 아래는 지금 쪽 [page] 의 아래 절반(치는 중). 자르는 선은
     * 지금 쪽의 위 · 아래 절반 시스템 사이, 다음 쪽은 그 선 위에서 시스템이 잘리지 않는 가장 아래 틈까지만(남는 곳은 비움)
     */
    private fun showHalfPage(page: Int, next: Int) {
        // 휴대폰은 지금 시스템 조각을 따라가므로 반 쪽 넘김이 필요 없다 — 쪽 끝에서 넘긴다
        if (phoneView || isTwoPageMode || pageIndex != page || next >= pageCount || isAnimating) return
        val bottom = pageCache?.getPageImmediate(page) ?: return
        val top = pageCache?.getPageImmediate(next) ?: return
        if (top.width != bottom.width || top.height != bottom.height) return
        val measures = micMeasures.filterNotNull()
        fun systems(p: Int) = ScoreOverlayGeometry.boxes(
            measures = measures.filter { it.pageIndex == p },
            leftPageIndex = p, twoPageMode = false, pageCount = pageCount,
            screenWidth = screenWidth, screenHeight = screenHeight,
            topClipping = currentTopClipping, bottomClipping = currentBottomClipping, centerPadding = currentCenterPadding,
        ).groupBy { it.systemIndex }.map { (_, b) -> b.minOf { it.top } to b.maxOf { it.bottom } }.sortedBy { it.first }
        val lowerSystems = measures.filter { it.pageIndex == page && it.topPt >= it.pageHeightPt / 2 }.map { it.systemIndex }.toSet()
        val here = ScoreOverlayGeometry.boxes(
            measures = measures.filter { it.pageIndex == page },
            leftPageIndex = page, twoPageMode = false, pageCount = pageCount,
            screenWidth = screenWidth, screenHeight = screenHeight,
            topClipping = currentTopClipping, bottomClipping = currentBottomClipping, centerPadding = currentCenterPadding,
        )
        val upperBottom = here.filter { it.systemIndex !in lowerSystems }.maxOfOrNull { it.bottom } ?: return
        val lowerTop = here.filter { it.systemIndex in lowerSystems }.minOfOrNull { it.top } ?: return
        val split = ((upperBottom + lowerTop) / 2).toInt().coerceIn(1, bottom.height - 1)
        val nextSystems = systems(next)
        val gaps = nextSystems.zipWithNext { a, b -> ((a.second + b.first) / 2).toInt() }
        val cut = (gaps.filter { it <= split }.maxOrNull() ?: nextSystems.firstOrNull()?.second?.toInt()?.takeIf { it <= split } ?: split)
            .coerceIn(1, split)
        val out = bottom.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        canvas.drawRect(0f, 0f, out.width.toFloat(), split.toFloat(), android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
        canvas.drawBitmap(top, android.graphics.Rect(0, 0, top.width, cut), android.graphics.Rect(0, 0, top.width, cut), null)
        canvas.drawRect(0f, split - 2f, out.width.toFloat(), split + 2f, android.graphics.Paint().apply { color = 0xFF9E9E9E.toInt() })
        flushNotePen()
        halfPageShown = true
        binding.pdfView.setImageBitmap(out)
        setImageViewMatrix(out)
        refreshScoreOverlay()
        Log.i("PdfViewerActivity", "🎤 반 쪽 넘김: 위 = ${next + 1}쪽(0 ~ $cut), 아래 = ${page + 1}쪽($split ~)")
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (followState == FollowState.SELECTING &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            startFollowing()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Cancel long press and handle short press
                longPressHandler.removeCallbacks(longPressRunnable)
                
                if (isLongPressing && event?.isCanceled != true) {
                    // This was a short press, not a long press
                    if (isNavigationGuideVisible) {
                        // 안내가 표시되어 있으면 숨기기
                        hideNavigationGuide()
                    } else {
                        // Toggle page info visibility
                        if (binding.pageInfo.alpha > 0.5f) {
                            binding.pageInfo.animate().alpha(0f).duration = 200
                        } else {
                            binding.pageInfo.animate().alpha(1f).duration = 200
                        }
                    }
                }
                
                isLongPressing = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
    
    // handleEndOfFile()과 handleStartOfFile() 메서드 삭제 - 더 이상 필요하지 않음
    
    private fun showEndOfFileGuide() {
        val hasNextFile = currentFileIndex < filePathList.size - 1
        
        // 왼쪽 네비게이션은 숨김 (더 이상 목록으로 돌아가기 없음)
        binding.leftNavigation.visibility = View.GONE
        
        // 오른쪽 네비게이션 설정 (다음 파일 또는 없음)
        if (hasNextFile) {
            binding.rightNavigation.visibility = View.VISIBLE
            binding.rightNavText.text = "다음 파일"
            binding.rightNavSubText.text = fileNameList[currentFileIndex + 1]
        } else {
            binding.rightNavigation.visibility = View.GONE
        }
        
        showNavigationGuide("end")
    }
    
    private fun showStartOfFileGuide() {
        val hasPreviousFile = currentFileIndex > 0
        
        // 왼쪽 네비게이션 설정 (이전 파일 또는 없음)
        if (hasPreviousFile) {
            binding.leftNavigation.visibility = View.VISIBLE
            binding.leftNavText.text = "이전 파일"
            binding.leftNavSubText.text = fileNameList[currentFileIndex - 1]
        } else {
            binding.leftNavigation.visibility = View.GONE
        }
        
        // 오른쪽 네비게이션은 숨김 (더 이상 목록으로 돌아가기 없음)
        binding.rightNavigation.visibility = View.GONE
        
        showNavigationGuide("start")
    }
    
    private fun showNavigationGuide(type: String) {
        isNavigationGuideVisible = true
        navigationGuideType = type
        binding.navigationGuide.visibility = View.VISIBLE
        binding.navigationGuide.animate().alpha(1f).duration = 300
        
        // 3초 후 자동으로 숨기기
        binding.navigationGuide.postDelayed({
            hideNavigationGuide()
        }, 3000)
    }
    
    private fun hideNavigationGuide() {
        if (isNavigationGuideVisible) {
            isNavigationGuideVisible = false
            navigationGuideType = ""
            binding.navigationGuide.animate().alpha(0f).withEndAction {
                binding.navigationGuide.visibility = View.GONE
                binding.leftNavigation.visibility = View.GONE
                binding.rightNavigation.visibility = View.GONE
            }.duration = 300
        }
    }
    
    private fun initializeCollaboration() {
        // Get current collaboration mode from global manager
        collaborationMode = globalCollaborationManager.getCurrentMode()
        
        Log.d("PdfViewerActivity", "Collaboration mode: $collaborationMode")
        
        when (collaborationMode) {
            CollaborationMode.CONDUCTOR -> {
                setupConductorCallbacks()
                updateCollaborationStatus()
            }
            CollaborationMode.PERFORMER -> {
                setupPerformerCallbacks()
                updateCollaborationStatus()
            }
            CollaborationMode.NONE -> {
                binding.collaborationStatus.visibility = View.GONE
            }
        }
    }
    
    private fun setupConductorCallbacks() {
        globalCollaborationManager.setOnServerClientConnected { clientId, deviceName ->
            runOnUiThread {
                Log.d("PdfViewerActivity", "🎵 지휘자 모드: 새 연주자 연결됨 - $deviceName")
                Toast.makeText(this@PdfViewerActivity, "$deviceName 연결됨", Toast.LENGTH_SHORT).show()
                updateCollaborationStatus()
                
                // Send current file and page to newly connected client
                Log.d("PdfViewerActivity", "🎵 지휘자 모드: 현재 상태를 새 연주자에게 전송 중...")
                // 합주 메트로놈이 도는 중이면 그 상태도 (#055) — 연주자는 시계를 맞춘 뒤 지금 박으로 합류한다
                conductorRun?.let { globalCollaborationManager.broadcastMetronomeRun(it) }
                // Add file to server so performers can download if needed
                globalCollaborationManager.addFileToServer(pdfFileName, pdfFilePath)
                
                val actualPageNumber = outgoingPage(pageIndex)
                globalCollaborationManager.broadcastFileChange(pdfFileName, actualPageNumber, sha256Of(pdfFilePath))
            }
        }
        
        globalCollaborationManager.setOnServerClientDisconnected { clientId ->
            runOnUiThread {
                Toast.makeText(this@PdfViewerActivity, "기기 연결 해제됨", Toast.LENGTH_SHORT).show()
                updateCollaborationStatus()
            }
        }
    }
    
    private fun setupPerformerCallbacks() {
        globalCollaborationManager.setOnPageChangeReceived { page, file, turnAt, roll, rollStart ->
            runOnUiThread {
                if (file != pdfFileName) return@runOnUiThread
                // 지휘자가 마이크로 듣고 넘긴 쪽 — 두 쪽 · 전체 악보면 차례 넘김 (P10 §3.3). 한 쪽 · 파트 보기는 지금처럼.
                // 듣기를 시작한 쪽이면(roll_start) 기다리지 않고 바로 시작 펼침으로 (#088)
                if (roll && turnAt == null && isTwoPageMode && partViewLayout == null &&
                    !(ensembleRole == EnsembleRole.FOLLOWING && metronome.isRunning)
                ) {
                    if (rollStart) handleRollStart(page) else handleRollPageChange(page)
                    return@runOnUiThread
                }
                // 합주 메트로놈을 따라가는 중에는 마디로 스스로 넘긴다 — 지휘자와 표시 모드가 달라도 맞게 (#055).
                // 지휘자가 일시정지한 동안에는 지휘자의 넘김을 따른다
                if (ensembleRole == EnsembleRole.FOLLOWING &&
                    (metronome.isRunning || performerRun?.state == EnsembleRun.State.SELECTING)
                ) {
                    Log.d("PdfViewerActivity", "🎼 합주 메트로놈 연동 중 — 지휘자 page_change($page) 무시")
                    return@runOnUiThread
                }
                // Phase 0: turn_at 이 있으면 그 절대 시각(벽시계)에 맞춰 예약, 없거나 이미 지났으면 즉시
                val delay = if (turnAt != null) turnAt - System.currentTimeMillis() else 0L
                pendingSyncTurn?.let { syncTurnHandler.removeCallbacks(it) }
                if (turnAt == null || delay <= 0L) {
                    if (turnAt != null) Log.w("PdfViewerActivity", "🎼 동기 넘김: turn_at 이미 지남(delay=${delay}ms) → 즉시 넘김")
                    handleRemotePageChange(page)
                } else {
                    Log.d("PdfViewerActivity", "🎼 동기 넘김 예약: page $page, ${delay}ms 후 (turn_at=$turnAt)")
                    val r = Runnable { handleRemotePageChange(page) }
                    pendingSyncTurn = r
                    syncTurnHandler.postDelayed(r, delay)
                }
            }
        }
        
        globalCollaborationManager.setOnEnsembleEnded {
            if (isDestroyed) return@setOnEnsembleEnded
            collaborationMode = CollaborationMode.NONE
            conductorMeasure = null
            cancelPendingRoll()
            if (metronome.isRunning || followState != FollowState.OFF) stopMetronome()
            updateCollaborationStatus()
            refreshScoreOverlay()
            Toast.makeText(this, "지휘자가 합주를 끝냈습니다 — 합주 모드 종료", Toast.LENGTH_LONG).show()
        }

        globalCollaborationManager.setOnFollowPositionReceived { file, measure ->
            runOnUiThread {
                if (isDestroyed || file != pdfFileName) return@runOnUiThread
                conductorMeasure = measure.takeIf { it > 0 }
                refreshScoreOverlay()
            }
        }

        globalCollaborationManager.setOnFileChangeReceived { file, page, sha256 ->
            runOnUiThread {
                handleRemoteFileChange(file, page, sha256)
            }
        }
        
        globalCollaborationManager.setOnClientConnectionStatusChanged { isConnected ->
            runOnUiThread {
                val status = if (isConnected) "연결됨" else "연결 끊김"
                Toast.makeText(this@PdfViewerActivity, "지휘자: $status", Toast.LENGTH_SHORT).show()
                updateCollaborationStatus()
            }
        }
        
        globalCollaborationManager.setOnBackToListReceived {
            runOnUiThread {
                Log.d("PdfViewerActivity", "🎼 연주자 모드: 뒤로가기 신호 수신, 파일 목록으로 돌아가기")
                finish()
            }
        }

        // 합주 메트로놈 (#055)
        globalCollaborationManager.setOnMetronomeRunReceived { run ->
            runOnUiThread { if (!isDestroyed) onEnsembleRunReceived(run) }
        }
        globalCollaborationManager.setOnClockSynced {
            // 시계를 맞추느라 기다리던 연주가 있으면 이제 따라간다
            runOnUiThread { if (!isDestroyed) performerRun?.let { onEnsembleRunReceived(it, retry = true) } }
        }
    }
    
    // ── 두 쪽 연주자의 차례 넘김 (P10 §3.3) ────────────────────────────────────────────
    /** 보통의 짝(1-2 · 3-4)이 아닌 화면(3 | 2 …). null 이면 보통 — [showPage] 가 비운다 */
    private var rollSpread: com.mrgq.pdfviewer.follow.RollingTurns.Spread? = null
    /** 반 쪽 넘김 화면(위 · 아래가 다른 쪽)인가 — 메모는 원본 쪽 한 장에만 그린다 (P11) */
    private var halfPageShown = false
    private var pendingRoll: Runnable? = null

    private fun cancelPendingRoll() {
        pendingRoll?.let { binding.pdfView.removeCallbacks(it) }
        pendingRoll = null
    }

    /** 지휘자가 [page](1부터) 쪽에 들어섰다 — 화면에 없으면 바로 그 짝, 있으면 [ROLL_DELAY_MS] 뒤 다 친 쪽 자리만 +2 */
    private fun handleRollPageChange(page: Int) {
        val p = page - 1
        if (p !in 0 until pageCount) return
        cancelPendingRoll()
        val current = rollSpread ?: com.mrgq.pdfviewer.follow.RollingTurns.Spread.pairOf(pairStart(pageIndex), pageCount)
        when (val action = com.mrgq.pdfviewer.follow.RollingTurns.onPage(current, p, pageCount)) {
            is com.mrgq.pdfviewer.follow.RollingTurns.Action.Immediate -> handleRemotePageChange(page)
            is com.mrgq.pdfviewer.follow.RollingTurns.Action.Delayed -> {
                Log.d("PdfViewerActivity", "🎼 차례 넘김 예약: 지휘자 ${page}쪽 → ${ROLL_DELAY_MS}ms 뒤 ${action.spread.left + 1} | ${action.spread.right?.plus(1)}")
                val r = Runnable {
                    pendingRoll = null
                    showSpread(action.spread, conductorPage = p)
                }
                pendingRoll = r
                binding.pdfView.postDelayed(r, ROLL_DELAY_MS)
            }
            com.mrgq.pdfviewer.follow.RollingTurns.Action.None -> Unit
        }
    }

    /**
     * 지휘자가 [page](1부터) 쪽에서 듣고 넘기기를 시작했다 (`roll_start`, #088) — 기다리지 않고 바로 시작 펼침으로.
     * 그 쪽이 오른쪽 자리면 왼쪽 자리(이미 지난 쪽)를 다음 쪽으로(3 | 4 에서 4 시작 → 5 | 4). 쪽이 아직 그려지지 않았으면 그려지기를 잠시 기다린다
     */
    private fun handleRollStart(page: Int) {
        val p = page - 1
        if (p !in 0 until pageCount) return
        cancelPendingRoll()
        val spread = com.mrgq.pdfviewer.follow.RollingTurns.startSpread(p, pageCount)
        if (spread.isPair) return handleRemotePageChange(page)
        Log.d("PdfViewerActivity", "🎼 듣기 시작 펼침: 지휘자 ${page}쪽 → ${spread.left + 1} | ${spread.right?.plus(1)}")
        pageCache?.prerenderAround(p)
        var attempts = 0
        val r = object : Runnable {
            override fun run() {
                if (showSpread(spread, conductorPage = p) || ++attempts >= ROLL_START_ATTEMPTS) {
                    pendingRoll = null
                    return
                }
                binding.pdfView.postDelayed(this, ROLL_START_RETRY_MS)
            }
        }
        pendingRoll = r
        r.run()
    }

    /** 두 쪽 화면에 [spread] 의 왼 · 오 쪽을 그린다. 보통의 짝이면 [showPage] 로. 쪽이 아직 캐시에 없거나 넘기는 중이면 false */
    private fun showSpread(spread: com.mrgq.pdfviewer.follow.RollingTurns.Spread, conductorPage: Int): Boolean {
        if (!isTwoPageMode || isAnimating) return false
        if (spread.isPair) {
            isHandlingRemotePageChange = true
            showPage(spread.left)
            isHandlingRemotePageChange = false
            return true
        }
        val left = pageCache?.getPageImmediate(spread.left) ?: return false
        val right = spread.right?.let { pageCache?.getPageImmediate(it) ?: return false }
        val combined = combineTwoPagesUnified(left, right)
        flushNotePen()
        halfPageShown = false
        binding.pdfView.setImageBitmap(combined)
        setImageViewMatrix(combined)
        rollSpread = spread
        pageIndex = pairStart(conductorPage)
        updatePageInfo()
        pageCache?.prerenderAround(pairStart(conductorPage) + 2)
        Log.d("PdfViewerActivity", "🎼 차례 넘김: ${spread.left + 1} | ${spread.right?.plus(1)}")
        return true
    }

    /** 연주자: 지휘자 마이크 추적의 지금 마디 번호 — 그 시스템을 연하게 (P10) */
    private var conductorMeasure: Int? = null

    /**
     * 연주자 화면에서 지휘자의 지금 시스템 (표시 비트맵 픽셀). 이 기기의 악보 분석 마디로 찾는다(같은 PDF → 같은 마디 번호).
     * 두 쪽 차례 넘김(3 | 2)이어도 맞게 — 그 쪽이 놓인 자리(왼 · 오)로 옮겨 계산
     */
    private fun conductorSystemFrame(): android.graphics.RectF? {
        val number = conductorMeasure ?: return null
        if (collaborationMode != CollaborationMode.PERFORMER || partViewLayout != null) return null
        val fileId = currentPdfFileId ?: return null
        if (scoreMeasuresFileId != fileId) {
            loadScoreMeasures(fileId, announce = false) // 끝나면 다시 그린다
            return null
        }
        val m = scoreMeasures.firstOrNull { it.measureNumber == number } ?: return null
        val system = scoreMeasures.filter { it.pageIndex == m.pageIndex && it.systemIndex == m.systemIndex }
        val boxes = if (!isTwoPageMode) {
            if (m.pageIndex != pageIndex) return null
            overlayBoxes(system)
        } else {
            val spread = rollSpread ?: com.mrgq.pdfviewer.follow.RollingTurns.Spread.pairOf(pairStart(pageIndex), pageCount)
            if (!spread.shows(m.pageIndex)) return null
            val slot = if (m.pageIndex == spread.left) 0 else 1
            ScoreOverlayGeometry.boxes(
                measures = system.map { it.copy(pageIndex = slot) },
                leftPageIndex = 0, twoPageMode = true, pageCount = if (spread.right != null) 2 else 1,
                screenWidth = screenWidth, screenHeight = screenHeight,
                topClipping = currentTopClipping, bottomClipping = currentBottomClipping, centerPadding = currentCenterPadding,
            )
        }
        if (boxes.isEmpty()) return null
        return android.graphics.RectF(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    private fun handleRemotePageChange(page: Int) {
        // Update sync time for input blocking
        updateSyncTime()
        
        // 0부터 — 파트 보기면 원본 쪽을 파트 쪽으로 (P07 4단계). 전체 악보면 전과 같이 범위 밖은 아래에서 무시.
        // 두 쪽 모드면 짝의 시작(0 · 2 · 4 …)으로 — 지휘자가 3 → 2 쪽으로 오면 1-2 를 보여야 한다(2-3 이 아니라)
        val targetIndex = pairStart(if (partViewLayout != null) incomingPageIndex(page) else page - 1)
        
        Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 변경 신호 수신됨 (current: ${pageIndex + 1}, target: $page, file: $pdfFileName)")
        
        if (targetIndex >= 0 && targetIndex < pageCount) {
            // Check if already on the same page or screen
            val isOnSamePage = if (isTwoPageMode) {
                // In two-page mode, check if target is on the same screen
                val currentScreenStart = (pageIndex / 2) * 2
                val targetScreenStart = (targetIndex / 2) * 2
                currentScreenStart == targetScreenStart
            } else {
                // In single page mode, simple comparison
                targetIndex == pageIndex
            }
            
            if (isOnSamePage) {
                val currentDisplayRange = if (isTwoPageMode) {
                    val screenStart = (pageIndex / 2) * 2
                    val screenEnd = minOf(screenStart + 1, pageCount - 1)
                    "${screenStart + 1}-${screenEnd + 1}"
                } else {
                    "${pageIndex + 1}"
                }
                Log.d("PdfViewerActivity", "🎼 연주자 모드: 이미 페이지 $page 가 포함된 화면에 있음. 페이지 전환 생략 (현재 표시: $currentDisplayRange, 두 페이지 모드: $isTwoPageMode)")
                return
            }
            
            // 재귀 방지를 위해 플래그 설정
            isHandlingRemotePageChange = true
            
            Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 로 이동 중...")
            
            // 연주자도 애니메이션 설정에 따라 애니메이션을 보여줌
            if (isPageTurnAnimationEnabled()) {
                val direction = if (targetIndex > pageIndex) 1 else -1
                Log.d("PdfViewerActivity", "🎼 연주자 모드: 애니메이션과 함께 페이지 전환 (방향: $direction)")
                showPageWithAnimation(targetIndex, direction)
            } else {
                Log.d("PdfViewerActivity", "🎼 연주자 모드: 즉시 페이지 전환 (애니메이션 비활성화)")
                showPage(targetIndex)
            }
            
            // 플래그 해제
            isHandlingRemotePageChange = false
            
            Log.d("PdfViewerActivity", "🎼 연주자 모드: 페이지 $page 로 이동 완료")
        } else {
            Log.w("PdfViewerActivity", "🎼 연주자 모드: 잘못된 페이지 번호 $page (총 $pageCount 페이지)")
        }
    }
    
    /**
     * 연주자: 지휘자가 파일을 바꿨다 (#063). 찾는 순서는 파일 목록 화면과 같다 (EnsembleFiles) — 연결된 TV 는 내용 해시로 내 ScoreMate,
     * 없으면 캐시, 없으면 캐시로 받는다. 연결하지 않은 TV 는 이름, 없으면 `PDFs/` 로 받는다. 찾은 파일이 지금 목록에 없으면 끝에 붙인다
     */
    private fun handleRemoteFileChange(file: String, targetPage: Int, sha256: String? = null) {
        // Update sync time for input blocking
        updateSyncTime()

        val resolution = com.mrgq.pdfviewer.ensemble.EnsembleFiles.resolve(
            fileName = file,
            sha256 = sha256,
            linked = scoreMateLinked,
            listed = fileNameList.zip(filePathList),
            synced = syncedScores,
            pdfRoot = File(getExternalFilesDir(null), "PDFs"),
            cacheDir = File(cacheDir, "ensemble"),
        )
        when (resolution) {
            is com.mrgq.pdfviewer.ensemble.EnsembleFiles.Resolution.Open -> {
                val index = filePathList.indexOf(resolution.path)
                if (index < 0) {
                    com.mrgq.pdfviewer.ensemble.EnsembleFiles.touch(File(resolution.path))
                    refreshFileListAndLoad(File(resolution.path).name, resolution.path, targetPage)
                    return
                }
                currentFileIndex = index
                pdfFilePath = filePathList[index]
                pdfFileName = fileNameList[index]

                // Temporarily disable collaboration to prevent loops
                val originalMode = collaborationMode
                collaborationMode = CollaborationMode.NONE
                Log.d("PdfViewerActivity", "🎼 연주자 모드: 파일 '$file' 로 변경 중... (목표 페이지: $targetPage)")
                loadFileWithTargetPage(pdfFilePath, pdfFileName, targetPage, originalMode)
            }
            is com.mrgq.pdfviewer.ensemble.EnsembleFiles.Resolution.Download -> {
                Log.w("PdfViewerActivity", "🎼 연주자 모드: 요청된 파일을 찾을 수 없습니다: $file (sha256=${sha256?.take(12)})")
                val conductorAddress = globalCollaborationManager.getConductorAddress()
                if (conductorAddress.isNotEmpty()) {
                    showDownloadDialog(file, conductorAddress, targetPage, resolution.target, resolution.cached)
                } else {
                    Toast.makeText(this, "요청된 파일을 찾을 수 없습니다: $file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun updateCollaborationStatus() {
        when (collaborationMode) {
            CollaborationMode.CONDUCTOR -> {
                val clientCount = globalCollaborationManager.getConnectedClientCount()
                binding.collaborationStatus.text = "지휘자: ${clientCount}명 연결"
                binding.collaborationStatus.visibility = View.VISIBLE
            }
            CollaborationMode.PERFORMER -> {
                val isConnected = globalCollaborationManager.isClientConnected()
                val status = if (isConnected) "연결됨" else "연결 끊김"
                binding.collaborationStatus.text = "연주자: $status"
                binding.collaborationStatus.visibility = View.VISIBLE
            }
            CollaborationMode.NONE -> {
                binding.collaborationStatus.visibility = View.GONE
            }
        }
    }
    
    private fun showDownloadDialog(fileName: String, conductorAddress: String, targetPage: Int, target: File, cached: Boolean) {
        val ipOnly = conductorAddress.split(":").firstOrNull() ?: conductorAddress
        val fileServerUrl = "http://$ipOnly:8090"
        
        AlertDialog.Builder(this)
            .setTitle("파일 다운로드")
            .setMessage(
                "'$fileName' 파일이 없습니다.\n지휘자로부터 다운로드하시겠습니까?" +
                    if (cached) "\n(이 기기의 ScoreMate 에 없는 악보 — 합주용으로만 받아 두고 목록에는 넣지 않습니다)" else ""
            )
            .setPositiveButton("다운로드") { _, _ ->
                downloadFileFromConductor(fileName, fileServerUrl, targetPage, target, cached)
            }
            .setNegativeButton("취소", null)
            .show()
    }
    
    /** [target] 으로 받는다 — 연결하지 않은 TV 는 PDFs/, 연결된 TV 에서 ScoreMate 에 없는 악보는 캐시 (#063) */
    private fun downloadFileFromConductor(fileName: String, serverUrl: String, targetPage: Int, target: File, cached: Boolean) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("다운로드 중...")
            .setMessage("$fileName\n0%")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        Log.d("PdfViewerActivity", "🎼 파일 다운로드 시작: $fileName (목표 페이지: $targetPage)")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                val downloadUrl = "$serverUrl/download/$encodedFileName"
                Log.d("PdfViewerActivity", "Downloading from: $downloadUrl")
                
                val url = java.net.URL(downloadUrl)
                val connection = url.openConnection()
                connection.connect()
                
                val fileLength = connection.contentLength
                val input = connection.getInputStream()
                // 예전에는 공용 Download/ 에 써서 목록에 나오지 않았다 (v0.1.8 에서 앱 전용 폴더로 옮길 때 빠진 경로, #062).
                // 받는 중에는 .part 로 — 끊긴 파일이 목록에 보이지 않게. 이름은 EnsembleFiles 가 경로로 쓸 수 있게 다듬었다
                target.parentFile?.mkdirs()
                val downloadPath = target
                val partPath = File(target.path + ".part")
                val output = java.io.FileOutputStream(partPath)
                
                val buffer = ByteArray(4096)
                var total: Long = 0
                var count: Int
                
                while (input.read(buffer).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            progressDialog.setMessage("$fileName\n$progress%")
                        }
                    }
                    output.write(buffer, 0, count)
                }
                
                output.flush()
                output.close()
                input.close()
                if (!partPath.renameTo(downloadPath)) {
                    downloadPath.delete()
                    if (!partPath.renameTo(downloadPath)) throw java.io.IOException("받은 파일을 저장하지 못했습니다")
                }
                if (cached) com.mrgq.pdfviewer.ensemble.EnsembleFiles.trimCache(downloadPath.parentFile!!, keep = downloadPath)
                
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PdfViewerActivity, "다운로드 완료: $fileName", Toast.LENGTH_SHORT).show()
                    
                    // Refresh file list and load the downloaded file with target page
                    refreshFileListAndLoad(downloadPath.name, downloadPath.absolutePath, targetPage)
                }
                
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Download failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PdfViewerActivity, "다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun refreshFileListAndLoad(fileName: String, filePath: String, targetPage: Int = 1) {
        // Add to current file lists
        fileNameList = fileNameList.toMutableList().apply { add(fileName) }
        filePathList = filePathList.toMutableList().apply { add(filePath) }
        currentFileIndex = fileNameList.size - 1
        
        // Update current file info
        pdfFileName = fileName
        pdfFilePath = filePath
        
        // Notify that file list should be refreshed when returning to MainActivity
        val sharedPrefs = getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("refresh_file_list", true).apply()
        
        // Close current PDF if open
        try {
            currentPage?.close()
            currentPage = null
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing current page: ${e.message}")
        }
        
        try {
            pdfRenderer?.close()
            pdfRenderer = null
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing PDF renderer: ${e.message}")
        }
        
        Log.d("PdfViewerActivity", "🎼 다운로드된 파일 로드 중, 목표 페이지: $targetPage")
        
        // Load the new file with target page
        loadPdfWithTargetPage(targetPage)
    }
    
    private fun loadPdfWithTargetPage(targetPage: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("PdfViewerActivity", "Loading PDF with target page: $pdfFilePath (target: $targetPage)")
                val file = File(pdfFilePath)
                
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "파일을 찾을 수 없습니다: $pdfFileName", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
                
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)
                pageCount = pdfRenderer?.pageCount ?: 0
                
                withContext(Dispatchers.Main) {
                    if (pageCount > 0) {
                        // Initialize page cache
                        pageCache?.destroy()
                        
                        val firstPage = pdfRenderer!!.openPage(0)
                        val calculatedScale = calculateOptimalScale(firstPage.width, firstPage.height)
                        firstPage.close()
                        
                        pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
                        Log.d("PdfViewerActivity", "PageCache 초기화 완료 for downloaded file")
                        
                        checkAndSetTwoPageMode {
                            // Recalculate scale based on the determined mode
                            val firstPage = pdfRenderer!!.openPage(0)
                            val finalScale = calculateOptimalScale(firstPage.width, firstPage.height, isTwoPageMode)
                            firstPage.close()
                            
                            Log.d("PdfViewerActivity", "Final scale for downloaded file, two-page mode $isTwoPageMode: $finalScale")
                            
                            // Clear cache and update settings to ensure clean state
                            pageCache?.clear()
                            pageCache?.updateSettings(isTwoPageMode, finalScale)
                            
                            // Navigate to target page
                            val targetIndex = incomingPageIndex(targetPage)
                            showPage(targetIndex)
                            
                            Log.d("PdfViewerActivity", "🎼 다운로드된 파일 로드 완료, 페이지 $targetPage 로 이동")
                        }
                    } else {
                        Toast.makeText(this@PdfViewerActivity, "PDF 파일에 페이지가 없습니다", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "Error loading downloaded PDF", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "PDF 열기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
    
    /**
     * PDF 표시 옵션 다이얼로그 표시 (OK 버튼 길게 누르기)
     */
    /**
     * 메트로놈 설정과 시작/정지.
     *
     * 템포·박자는 **이 파일에** 저장하고(user_preferences, v7) 클릭음 켜기·음량은 전역 설정이다.
     * 실행 중에 바꾸면 다음 박부터 적용된다. 설정은 대화상자를 닫을 때 저장한다.
     */
    private fun showMetronomeDialog() {
        // 연주자는 소리만 고른다 — 템포 · 박자 · 시작은 모두 지휘자를 따른다 (사용자 결정, #055)
        if (collaborationMode == CollaborationMode.PERFORMER) return showPerformerMetronomeSettings()
        val fileId = currentPdfFileId
        metronomeDialogPending = true
        lifecycleScope.launch {
            val settings = try {
                metronomeSettingsFor(fileId)
            } finally {
                metronomeDialogPending = false
            }
            if (currentPdfFileId != fileId) return@launch
            buildMetronomeDialog(fileId, settings)
        }
    }

    /**
     * 이 파일의 메트로놈 설정. [scoreMeter] 는 박자를 악보 박자표로 채웠을 때만 있다 — 대화상자에 "악보 박자표"로 표시.
     * [spans] 는 악보 박자로 나눈 구간 (#057) — 둘 이상이면 대화상자에 구간이 보인다. [sectionSettings] 는 둘째 구간부터의 설정.
     */
    private data class MetronomeSettings(
        val bpm: Int,
        val meter: TimeSignature,
        val dotted: Boolean,
        val scoreMeter: TimeSignature? = null,
        val spans: List<SectionSpan> = emptyList(),
        val sectionSettings: List<TempoSectionSetting> = emptyList(),
    )

    /**
     * 이 파일의 메트로놈 설정 (템포, 박자, 점음표 박, 구간). 실행·일시정지 중이면 템포 · 박자는 엔진의 지금 값을 쓴다.
     * 박자는 이 파일에서 고른 적이 있으면 그것, 아니면 악보 박자표로 채운다 (#051).
     * 분모 없이 박 수만 저장된 행(v0.2.0)도 "고른 적 없음"으로 본다 — 그때는 분모를 고를 수 없었다.
     * 구간을 알려면 악보가 필요하므로 분석 전인 파일은 여기서 분석한다 — 시작하면 어차피 필요하고 결과는 캐시된다.
     */
    private suspend fun metronomeSettingsFor(fileId: String?): MetronomeSettings {
        val saved = if (fileId != null) {
            withContext(Dispatchers.IO) { musicRepository.getUserPreference(fileId) }
        } else {
            null
        }
        if (fileId != null && tempoSectionSettingsFileId != fileId) {
            tempoSectionSettings = TempoSections.decode(saved?.metronomeSections)
            tempoSectionSettingsFileId = fileId
        }
        val spans = TempoSections.spans(scoreMeasuresNoticing(fileId).orEmpty())
        val sections = sectionSettingsFor(fileId)
        if (metronome.isRunning || followState == FollowState.PAUSED) {
            return MetronomeSettings(metronome.bpm, metronome.timeSignature, metronome.dottedBeat, null, spans, sections)
        }
        val bpm = saved?.metronomeBpm ?: MetronomeClock.DEFAULT_BPM
        val dotted = saved?.metronomeDottedBeat ?: false
        if (saved?.metronomeBeatUnit != null) {
            val meter = TimeSignature.of(saved.metronomeBeatsPerBar, saved.metronomeBeatUnit)
            return MetronomeSettings(bpm, meter, dotted, null, spans, sections)
        }
        val fromScore = spans.firstOrNull()?.meter
        val meter = fromScore ?: TimeSignature.of(saved?.metronomeBeatsPerBar, null)
        return MetronomeSettings(bpm, meter, dotted, fromScore, spans, sections)
    }

    /** 이 파일의 둘째 구간부터의 설정 (#057) — 읽은 적 없는 파일이면 모두 기본 */
    private fun sectionSettingsFor(fileId: String?): List<TempoSectionSetting> =
        if (fileId != null && fileId == tempoSectionSettingsFileId) tempoSectionSettings else emptyList()

    /** 악보 분석 결과. 분석 전인 파일이면 알리고 분석한다. 악보 분석이 안 되는 파일이면 null */
    private suspend fun scoreMeasuresNoticing(fileId: String?): List<ScoreMeasure>? {
        if (fileId == null) return null
        if (scoreMeasuresFileId != fileId) {
            val analyzed = withContext(Dispatchers.IO) { musicRepository.getPdfFileById(fileId)?.scoreAnalyzedAt != null }
            if (!analyzed) Toast.makeText(this, "악보 박자표 읽는 중…", Toast.LENGTH_SHORT).show()
        }
        return scoreMeasuresFor(fileId)
    }

    /**
     * 메트로놈 대화상자. 첫 화면은 박자(주요 박자 버튼) · 속도 · 소리 크기 세 가지만 두고, 나머지는 각 줄의 "상세…"로 뺀다.
     * 상세 버튼은 슬라이더 옆이 아니라 제목 줄에 있다 — 슬라이더가 좌우 키를 쓰므로 리모컨으로는 위/아래로만 닿는다.
     */
    private fun buildMetronomeDialog(fileId: String?, initial: MetronomeSettings) {
        var bpm = initial.bpm.coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM)
        var meter = initial.meter.coerced()
        var dotted = initial.dotted
        var soundOn = preferences.getBoolean(PREF_METRONOME_SOUND, true)
        val scoreMeter = initial.scoreMeter?.coerced()
        // 구간별 빠르기 (#057): 악보 박자가 바뀌는 곳마다 구간. 첫 화면이 첫 구간이고 둘째 구간부터는 목록에서 고른다
        val spans = initial.spans
        val sectionSettings = initial.sectionSettings.associateBy { it.startMeasure }.toMutableMap()
        fun sectionSetting(span: SectionSpan) = sectionSettings[span.startMeasure] ?: TempoSectionSetting(span.startMeasure)
        fun currentSectionSettings() = spans.drop(1).mapNotNull { sectionSettings[it.startMeasure] }
        fun resolvedSections() = TempoSections.resolve(spans, bpm, dotted, currentSectionSettings())
        fun rangeText(span: SectionSpan) = "${span.startMeasure}~${span.endMeasure}마디 (${span.measureCount}마디)"
        fun tempoText(section: TempoSection) = "${section.meter.beatNoteName(section.dotted)} = ${Math.round(section.bpm)}"
        fun relationName(relation: TempoRelation) = when (relation) {
            TempoRelation.NOTE -> "음표 길이 그대로"
            TempoRelation.BEAT -> "박 길이 그대로"
            TempoRelation.SET -> "직접 입력"
        }

        // 열려 있는 상세 대화상자도 같은 값을 보여 준다. 그리는 동안 슬라이더를 옮겨도 사용자 입력으로 보지 않는다
        val renderers = mutableListOf<() -> Unit>()
        var rendering = false
        fun render() {
            rendering = true
            try {
                renderers.toList().forEach { it() }
            } finally {
                rendering = false
            }
        }

        fun android.widget.SeekBar.onProgress(block: (Int) -> Unit) {
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!rendering) block(progress)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        fun label() = android.widget.TextView(this).apply {
            textSize = 16f
            setPadding(0, 10, 0, 10)
        }
        fun hintText(text: String) = android.widget.TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 20, 0, 0)
        }
        fun buttonRow() = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        fun column(views: List<View>) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
            views.forEach { addView(it) }
        }
        /** 제목 줄 — 왼쪽에 항목과 지금 값, 오른쪽에 "상세…" */
        fun header(title: android.widget.TextView, detail: () -> Unit) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(title, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.Button(this@PdfViewerActivity).apply {
                text = "상세…"
                isAllCaps = false
                setOnClickListener { detail() }
            })
        }
        fun detailDialog(title: String, views: List<View>, renderer: () -> Unit) {
            renderers += renderer
            render()
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(android.widget.ScrollView(this).apply { addView(column(views)) })
                .setPositiveButton("완료", null)
                .setOnDismissListener { renderers.remove(renderer) }
                .show()
        }

        // 첫 화면: 박자 · 속도 · 소리 크기
        val meterLabel = label()
        val primaryButtons = TimeSignature.PRIMARY.associateWith { android.widget.Button(this).apply { isAllCaps = false } }
        // 주요 박자가 아닌 박자(상세에서 고름 · 악보 박자표)면 그 박자를 옆에 보여 준다
        val otherMeterButton = android.widget.Button(this).apply { isAllCaps = false }
        val meterRow = buttonRow().apply {
            primaryButtons.values.forEach { addView(it) }
            addView(otherMeterButton)
        }
        val bpmLabel = label()
        val bpmSeek = android.widget.SeekBar(this).apply {
            max = MetronomeClock.MAX_BPM - MetronomeClock.MIN_BPM
            keyProgressIncrement = 1
            progress = bpm - MetronomeClock.MIN_BPM
        }
        val volumeLabel = label()
        val volumeSeek = android.widget.SeekBar(this).apply {
            max = 100
            progress = (preferences.getFloat(PREF_METRONOME_VOLUME, 0.6f) * 100).toInt()
        }
        // 예비박: 악보 연동에서 시작 마디 앞에 몇 마디를 셀지 (기본 2, 전역 — #060). 자주 바꾸므로 첫 화면에 (P13 2단계)
        val countInLabel = label()
        val countInButtons = listOf(1, 2).associateWith { android.widget.Button(this).apply { isAllCaps = false } }
        val countInRow = buttonRow().apply { countInButtons.values.forEach { addView(it) } }
        countInButtons.forEach { (bars, button) ->
            button.setOnClickListener {
                preferences.edit().putInt(PREF_METRONOME_COUNT_IN_BARS, bars).apply()
                render()
            }
        }
        // 구간이 둘 이상이면: 첫 화면이 어느 구간인지, 그리고 나머지 구간 목록 (누르면 그 구간 설정)
        val sectionHeader = label().apply { setTextColor(0xFF90CAF9.toInt()) }
        val otherSectionsLabel = label().apply { text = "다른 구간 — 박자가 바뀌는 곳마다 나뉩니다" }
        val sectionButtons = spans.drop(1).map {
            android.widget.Button(this).apply {
                isAllCaps = false
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            }
        }
        if (spans.size > 1) {
            renderers += {
                val resolved = resolvedSections()
                sectionHeader.text = "구간 1 / ${spans.size} · ${rangeText(spans[0])} · ${spans[0].meter} — 아래 설정은 이 구간"
                sectionButtons.forEachIndexed { i, button ->
                    val section = resolved[i + 1]
                    button.text = "구간 ${i + 2} · ${rangeText(section.span)} · ${section.meter}\n" +
                        "${relationName(section.relation)} · ${tempoText(section)}"
                }
            }
        }
        renderers += {
            val beatNote = meter.beatNoteName(dotted)
            val fromScore = if (meter == scoreMeter) " · 악보 박자표" else ""
            meterLabel.text = "박자: $meter — $beatNote ${meter.beatsPerBar(dotted)}박$fromScore"
            primaryButtons.forEach { (ts, button) -> button.text = if (ts == meter) "✓ $ts" else "$ts" }
            otherMeterButton.visibility = if (meter in TimeSignature.PRIMARY) View.GONE else View.VISIBLE
            otherMeterButton.text = "✓ $meter"
            bpmLabel.text = "속도: $beatNote = $bpm BPM"
            volumeLabel.text = if (soundOn) "소리 크기: ${volumeSeek.progress}%" else "소리 크기: 클릭음 꺼짐 (박 표시만)"
            val countIn = countInBarsSetting()
            countInLabel.text = "예비박: ${countIn}마디 — 악보에서 마디를 골라 시작할 때"
            countInButtons.forEach { (bars, button) -> button.text = (if (bars == countIn) "✓ " else "") + (if (bars == 1) "한 마디" else "두 마디") }
        }

        // 실행 중이면 바로 들린다 (다음 박부터)
        fun applyToEngine() {
            metronome.bpm = bpm
            metronome.timeSignature = meter
            metronome.dottedBeat = dotted
            metronome.soundEnabled = soundOn
            metronome.volume = volumeSeek.progress / 100f
            if (fileId != null && spans.size > 1) {
                tempoSectionSettings = currentSectionSettings()
                tempoSectionSettingsFileId = fileId
            }
            // 악보 연동 중이면 구간 템포도 다음 박부터 (#057)
            refreshFollowTempo()
            // 합주 지휘자가 연주 중에 바꾸면 다음 박부터 새 시간표를 알린다 (#055)
            retimeConductorRun()
        }
        fun selectDotted(value: Boolean) {
            if (value == dotted) return
            // 같은 빠르기로 환산 — 점음표 박 하나 = 분모 음표 세 박. 범위를 넘으면(점4분 81 이상 → 8분 240 초과) 잘린다
            bpm = (if (value) Math.round(bpm / 3.0).toInt() else bpm * 3)
                .coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM)
            dotted = value
            bpmSeek.progress = bpm - MetronomeClock.MIN_BPM
            render()
            applyToEngine()
        }
        fun selectMeter(selected: TimeSignature) {
            meter = selected.coerced()
            render()
            applyToEngine()
        }

        // 박자 상세: 다른 흔한 박자, 직접 고르기(분자 슬라이더 + 분모 버튼), 겹박자의 세는 단위 (#051, #052)
        fun showMeterDetail() {
            val accentLabel = label()
            val presetButtons = TimeSignature.COMMON.associateWith { android.widget.Button(this).apply { isAllCaps = false } }
            val presetRows = TimeSignature.COMMON.chunked(5).map { row ->
                buttonRow().apply { row.forEach { addView(presetButtons.getValue(it)) } }
            }
            val numeratorLabel = label()
            val numeratorSeek = android.widget.SeekBar(this).apply {
                max = MetronomeClock.MAX_BEATS - MetronomeClock.MIN_BEATS
                keyProgressIncrement = 1
                progress = meter.numerator - MetronomeClock.MIN_BEATS
            }
            val denominatorButtons = TimeSignature.DENOMINATORS.associateWith { android.widget.Button(this).apply { isAllCaps = false } }
            val denominatorRow = buttonRow().apply { denominatorButtons.values.forEach { addView(it) } }
            // 박 단위: 겹박자에서만 — 분모 음표(6/8 = 8분음표 6박)로 셀지 점음표(점4분음표 2박)로 셀지 (#052).
            // 곡 빠르기에 따라 달라서 자동으로 바꾸지 않고 파일마다 고른다. 바꿔도 빠르기는 그대로 (BPM 을 3배로 환산)
            val beatUnitLabel = label().apply { text = "세는 단위 — 바꿔도 빠르기는 그대로입니다" }
            val eighthButton = android.widget.Button(this).apply { isAllCaps = false }
            val dottedButton = android.widget.Button(this).apply { isAllCaps = false }
            val beatUnitRow = buttonRow().apply {
                addView(eighthButton)
                addView(dottedButton)
            }
            val beatUnitHint = android.widget.TextView(this).apply {
                textSize = 13f
                setTextColor(0xFFFFD54F.toInt())
                setPadding(0, 4, 0, 4)
            }
            val scoreHint = if (scoreMeter != null) "악보에서 읽은 박자표는 $scoreMeter 입니다. " else ""
            val hint = hintText(
                scoreHint + "박은 박자표 아래 숫자의 음표이고 BPM 도 그 음표 기준입니다(6/8 이면 8분음표). " +
                    "6/8 같은 겹박자는 점음표로 셀 수도 있습니다(6/8 이면 점4분음표 2박).\n" +
                    "악보에서 박자표를 읽을 수 있으면 처음엔 그 박자로 채워집니다. 시작을 누른 뒤 악보에서 시작 마디를 고르면 " +
                    "예비박 뒤(화면 가운데에 남은 마디 수) 현재 마디를 표시하며 페이지를 넘기고, 이때 마디 길이와 강박은 악보 박자표를 따릅니다."
            )

            numeratorSeek.onProgress { selectMeter(TimeSignature(MetronomeClock.MIN_BEATS + it, meter.denominator)) }
            presetButtons.forEach { (ts, button) -> button.setOnClickListener { selectMeter(ts) } }
            denominatorButtons.forEach { (d, button) -> button.setOnClickListener { selectMeter(TimeSignature(meter.numerator, d)) } }
            eighthButton.setOnClickListener { selectDotted(false) }
            dottedButton.setOnClickListener { selectDotted(true) }

            val views = listOf<View>(accentLabel) + presetRows +
                listOf(numeratorLabel, numeratorSeek, denominatorRow, beatUnitLabel, beatUnitRow, beatUnitHint, hint)
            detailDialog("박자 상세", views) {
                val beats = meter.beatsPerBar(dotted)
                val medium = (1 until beats).filter { meter.accentAt(it, dotted) == Accent.MEDIUM }
                val accents = if (medium.isEmpty()) {
                    "첫 박 강조"
                } else {
                    "1박 강 · ${medium.joinToString("·") { "${it + 1}" }}박 중간"
                }
                accentLabel.text = "$meter — ${meter.beatNoteName(dotted)} ${beats}박, $accents"
                presetButtons.forEach { (ts, button) -> button.text = if (ts == meter) "✓ $ts" else "$ts" }
                numeratorLabel.text = "직접 고르기 — 마디당 박 수(위 숫자) ${meter.numerator}, 박 단위(아래 숫자):"
                numeratorSeek.progress = meter.numerator - MetronomeClock.MIN_BEATS
                denominatorButtons.forEach { (d, button) -> button.text = if (d == meter.denominator) "✓ /$d" else "/$d" }

                val compound = meter.isCompound
                beatUnitLabel.visibility = if (compound) View.VISIBLE else View.GONE
                beatUnitRow.visibility = if (compound) View.VISIBLE else View.GONE
                eighthButton.text = (if (!dotted) "✓ " else "") + "${meter.beatNoteName(false)} ${meter.beatsPerBar(false)}박"
                dottedButton.text = (if (dotted) "✓ " else "") + "${meter.beatNoteName(true)} ${meter.beatsPerBar(true)}박"
                val fast = compound && !dotted && bpm > COMPOUND_FAST_BPM
                beatUnitHint.visibility = if (fast) View.VISIBLE else View.GONE
                beatUnitHint.text = "빠른 곡이면 ${meter.beatNoteName(true)}로 세는 편이 편할 수 있습니다 " +
                    "(${meter.beatNoteName(true)} = ${Math.round(bpm / 3.0)})"
            }
        }

        // 속도 상세: 리모컨으로 슬라이더를 한 칸씩 옮기기엔 범위가 넓어 큰 단위 버튼을 둔다
        fun showTempoDetail() {
            val tempoLabel = label()
            val steps = buttonRow()
            for (delta in listOf(-10, -1, 1, 10)) {
                steps.addView(android.widget.Button(this).apply {
                    text = if (delta > 0) "+$delta" else "$delta"
                    setOnClickListener {
                        bpmSeek.progress = (bpm + delta).coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM) - MetronomeClock.MIN_BPM
                    }
                })
            }
            val hint = hintText("BPM 은 1분에 박이 몇 번인지이고, 박은 박자 단위 음표입니다. 실행 중에 바꾸면 다음 박부터 적용됩니다.")
            detailDialog("속도 상세", listOf(tempoLabel, steps, hint)) {
                tempoLabel.text = "${meter.beatNoteName(dotted)} = $bpm BPM"
            }
        }

        // 소리 상세: 클릭음 끄기 (박 표시 · 악보 연동은 그대로)
        fun showSoundDetail() {
            val soundCheck = android.widget.CheckBox(this).apply {
                text = "클릭음"
                isChecked = soundOn
                setOnCheckedChangeListener { _, checked ->
                    soundOn = checked
                    render()
                    applyToEngine()
                }
            }
            val hint = hintText("끄면 소리 없이 박 표시 · 현재 마디 · 페이지 넘김만 합니다. 소리 설정은 모든 파일에 공통입니다.")
            detailDialog("소리 상세", listOf(soundCheck, hint)) {}
        }

        // 구간 화면 (둘째 구간부터, #057) — 첫 화면과 같은 짜임: 박자 · 속도 · 소리 크기, 줄마다 "상세…".
        // 박자는 악보가 정하므로 박자 줄에는 세는 단위(겹박자)만 두고, 속도는 앞 구간에서 이어받는 방법을 속도 상세에서 고른다
        fun showSectionDetail(index: Int) {
            val span = spans[index]
            fun setting() = sectionSetting(span)
            fun change(block: (TempoSectionSetting) -> TempoSectionSetting) {
                sectionSettings[span.startMeasure] = block(setting())
                render()
                applyToEngine()
            }
            fun currentBpm() = resolvedSections()[index].bpm
            // 세는 단위를 바꿔도 빠르기는 그대로 — 음표 길이 그대로는 저절로, 직접 입력은 3배로 환산 (#052 와 같다).
            // 박 길이 그대로는 한 박 = 앞 구간 한 박이 뜻이므로 박 길이가 유지된다
            fun selectSectionDotted(value: Boolean) = change { s ->
                when {
                    s.dotted == value -> s
                    s.relation == TempoRelation.SET -> s.copy(
                        dotted = value,
                        bpm = (if (value) Math.round(s.bpm / 3.0).toInt() else s.bpm * 3)
                            .coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM),
                    )
                    else -> s.copy(dotted = value)
                }
            }
            fun beatUnitButtons(): Triple<android.widget.LinearLayout, android.widget.Button, android.widget.Button> {
                val eighth = android.widget.Button(this).apply {
                    isAllCaps = false
                    setOnClickListener { selectSectionDotted(false) }
                }
                val dottedUnit = android.widget.Button(this).apply {
                    isAllCaps = false
                    setOnClickListener { selectSectionDotted(true) }
                }
                return Triple(buttonRow().apply { addView(eighth); addView(dottedUnit) }, eighth, dottedUnit)
            }
            fun renderBeatUnit(eighth: android.widget.Button, dottedUnit: android.widget.Button) {
                val d = setting().dotted
                eighth.text = (if (!d) "✓ " else "") + "${span.meter.beatNoteName(false)} ${span.meter.beatsPerBar(false)}박"
                dottedUnit.text = (if (d) "✓ " else "") + "${span.meter.beatNoteName(true)} ${span.meter.beatsPerBar(true)}박"
            }

            // 박자 상세: 강세 · 세는 단위 · 박자는 악보를 따른다는 설명
            fun showSectionMeterDetail() {
                val accentLabel = label()
                val beatUnitLabel = label().apply { text = "세는 단위 — 바꿔도 빠르기는 그대로입니다" }
                val (beatUnitRow, eighth, dottedUnit) = beatUnitButtons()
                val compound = span.meter.isCompound
                beatUnitLabel.visibility = if (compound) View.VISIBLE else View.GONE
                beatUnitRow.visibility = if (compound) View.VISIBLE else View.GONE
                val hint = hintText(
                    "이 구간의 박자는 악보 박자표(${span.meter})를 따릅니다. 박은 박자표 아래 숫자의 음표이고, " +
                        "6/8 같은 겹박자는 점음표로 셀 수도 있습니다(6/8 이면 점4분음표 2박)."
                )
                detailDialog("구간 ${index + 1} — 박자 상세", listOf(accentLabel, beatUnitLabel, beatUnitRow, hint)) {
                    val d = setting().dotted
                    val beats = span.meter.beatsPerBar(d)
                    val medium = (1 until beats).filter { span.meter.accentAt(it, d) == Accent.MEDIUM }
                    val accents = if (medium.isEmpty()) {
                        "첫 박 강조"
                    } else {
                        "1박 강 · ${medium.joinToString("·") { "${it + 1}" }}박 중간"
                    }
                    accentLabel.text = "${span.meter} — ${span.meter.beatNoteName(d)} ${beats}박, $accents"
                    renderBeatUnit(eighth, dottedUnit)
                }
            }

            // 속도 상세: 앞 구간에서 빠르기를 이어받는 방법 (선택지마다 결과 템포) · ±1 · ±10
            fun showSectionTempoDetail() {
                val previousLabel = android.widget.TextView(this).apply {
                    textSize = 13f
                    setPadding(0, 0, 0, 10)
                }
                val relationLabel = label().apply { text = "앞 구간에서 빠르기를 이어받는 방법" }
                val relationButtons = TempoRelation.values().associateWith { relation ->
                    android.widget.Button(this).apply {
                        isAllCaps = false
                        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                        setOnClickListener {
                            change { s ->
                                if (relation == TempoRelation.SET) {
                                    // 지금 빠르기에서 시작한다
                                    s.copy(
                                        relation = relation,
                                        bpm = Math.round(currentBpm()).toInt().coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM),
                                    )
                                } else {
                                    s.copy(relation = relation)
                                }
                            }
                        }
                    }
                }
                val relationColumn = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    relationButtons.values.forEach { addView(it) }
                }
                val tempoLabel = label()
                val steps = buttonRow()
                for (delta in listOf(-10, -1, 1, 10)) {
                    steps.addView(android.widget.Button(this).apply {
                        text = if (delta > 0) "+$delta" else "$delta"
                        setOnClickListener {
                            val target = (Math.round(currentBpm()).toInt() + delta).coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM)
                            change { it.copy(relation = TempoRelation.SET, bpm = target) }
                        }
                    })
                }
                val hint = hintText(
                    "'음표 길이 그대로'는 앞 구간의 같은 음표가 같은 길이(4/4 → 6/8 이면 8분음표 = 8분음표), " +
                        "'박 길이 그대로'는 앞 구간 한 박 = 이 구간 한 박입니다. 둘 다 앞 구간 빠르기를 바꾸면 함께 바뀝니다. " +
                        "±로 바꾸면 '직접 입력'이 됩니다."
                )
                detailDialog(
                    "구간 ${index + 1} — 속도 상세",
                    listOf(previousLabel, relationLabel, relationColumn, tempoLabel, steps, hint),
                ) {
                    val resolved = resolvedSections()
                    val previous = resolved[index - 1]
                    val s = setting()
                    previousLabel.text = "앞 구간 (구간 $index): ${rangeText(previous.span)} · ${previous.meter} · ${tempoText(previous)}"
                    relationButtons.forEach { (relation, button) ->
                        val mark = if (relation == s.relation) "✓ " else ""
                        button.text = if (relation == TempoRelation.SET) {
                            mark + relationName(relation)
                        } else {
                            val value = TempoSections.bpmFor(previous, span, s.dotted, relation, s.bpm)
                            "$mark${relationName(relation)} → ${span.meter.beatNoteName(s.dotted)} = ${Math.round(value)}"
                        }
                    }
                    tempoLabel.text = tempoText(resolved[index]) + " BPM"
                }
                relationButtons[setting().relation]?.requestFocus()
            }

            // 구간 화면
            val sectionTitle = label().apply { setTextColor(0xFF90CAF9.toInt()) }
            val meterRowLabel = label()
            val (beatUnitRow, eighth, dottedUnit) = beatUnitButtons()
            val meterNote = android.widget.TextView(this).apply {
                text = "박자는 악보 박자표를 따릅니다"
                textSize = 13f
                setTextColor(android.graphics.Color.GRAY)
                gravity = android.view.Gravity.CENTER
            }
            val compound = span.meter.isCompound
            beatUnitRow.visibility = if (compound) View.VISIBLE else View.GONE
            meterNote.visibility = if (compound) View.GONE else View.VISIBLE
            val tempoRowLabel = label()
            // 옮기면 "직접 입력"이 된다
            val tempoSeek = android.widget.SeekBar(this).apply {
                max = MetronomeClock.MAX_BPM - MetronomeClock.MIN_BPM
                keyProgressIncrement = 1
                onProgress { progress -> change { it.copy(relation = TempoRelation.SET, bpm = MetronomeClock.MIN_BPM + progress) } }
            }
            val warning = android.widget.TextView(this).apply {
                textSize = 13f
                setTextColor(0xFFFFD54F.toInt())
                setPadding(0, 4, 0, 4)
            }
            // 소리는 모든 구간 · 파일에 공통 — 첫 화면의 음량 슬라이더와 같은 값
            val volumeRowLabel = label()
            val sectionVolumeSeek = android.widget.SeekBar(this).apply {
                max = 100
                onProgress { volumeSeek.progress = it }
            }
            val views = listOf<View>(
                sectionTitle,
                header(meterRowLabel, ::showSectionMeterDetail), beatUnitRow, meterNote,
                header(tempoRowLabel, ::showSectionTempoDetail), tempoSeek, warning,
                header(volumeRowLabel, ::showSoundDetail), sectionVolumeSeek,
                hintText("이 구간의 빠르기 · 세는 단위는 이 파일에 저장되고, 악보 연동(시작 마디를 고르고 시작)에서 쓰입니다."),
            )
            detailDialog("메트로놈 — 구간 ${index + 1} / ${spans.size}", views) {
                val resolved = resolvedSections()
                val section = resolved[index]
                val s = setting()
                sectionTitle.text = "구간 ${index + 1} / ${spans.size} · ${rangeText(span)} · ${span.meter}"
                meterRowLabel.text = "박자: ${span.meter} — ${span.meter.beatNoteName(s.dotted)} ${span.meter.beatsPerBar(s.dotted)}박 · 악보 박자표"
                renderBeatUnit(eighth, dottedUnit)
                tempoRowLabel.text = "속도: ${tempoText(section)} BPM · ${relationName(section.relation)}"
                tempoSeek.progress = Math.round(section.bpm).toInt().coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM) -
                    MetronomeClock.MIN_BPM
                val tooFast = section.bpm > MetronomeClock.MAX_BPM
                val fastCompound = compound && !s.dotted && section.bpm > COMPOUND_FAST_BPM
                warning.visibility = if (tooFast || fastCompound) View.VISIBLE else View.GONE
                warning.text = when {
                    fastCompound -> "박이 빠릅니다 — ${span.meter.beatNoteName(true)}로 세면 " +
                        "${span.meter.beatNoteName(true)} = ${Math.round(section.bpm / 3.0)} 입니다"
                    tooFast -> "박이 1분에 ${Math.round(section.bpm)}번으로 빠릅니다 — 속도 상세에서 다른 이어받기를 고르세요"
                    else -> ""
                }
                volumeRowLabel.text = if (soundOn) "소리 크기: ${volumeSeek.progress}%" else "소리 크기: 클릭음 꺼짐 (박 표시만)"
                sectionVolumeSeek.progress = volumeSeek.progress
            }
            // 처음 포커스는 첫 화면처럼 박자 줄 — 겹박자면 세는 단위 버튼, 아니면 속도 슬라이더
            (if (compound) (if (setting().dotted) dottedUnit else eighth) else tempoSeek).requestFocus()
        }

        bpmSeek.onProgress { bpm = MetronomeClock.MIN_BPM + it; render(); applyToEngine() }
        volumeSeek.onProgress { render(); applyToEngine() }
        primaryButtons.forEach { (ts, button) -> button.setOnClickListener { selectMeter(ts) } }
        otherMeterButton.setOnClickListener { showMeterDetail() }
        sectionButtons.forEachIndexed { i, button -> button.setOnClickListener { showSectionDetail(i + 1) } }

        render()
        val sectionTop = if (spans.size > 1) listOf<View>(sectionHeader) else emptyList()
        val sectionList = if (spans.size > 1) listOf<View>(otherSectionsLabel) + sectionButtons else emptyList()
        val content = column(
            sectionTop + listOf(
                header(meterLabel, ::showMeterDetail), meterRow,
                header(bpmLabel, ::showTempoDetail), bpmSeek,
                header(volumeLabel, ::showSoundDetail), volumeSeek,
                countInLabel, countInRow,
            ) + sectionList + hintText(
                "템포 · 박자는 이 파일에, 소리 · 예비박은 모든 파일에 저장됩니다. " +
                    "악보 화면에서 ↑ 키로 연주 메뉴(일시정지 · 이어서 · 정지)를 열 수 있습니다."
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("메트로놈")
            .setView(android.widget.ScrollView(this).apply { addView(content) })
            .setPositiveButton(if (metronome.isRunning || followState != FollowState.OFF) "정지" else "시작") { _, _ ->
                if (metronome.isRunning || followState != FollowState.OFF) {
                    stopMetronome()
                } else {
                    applyToEngine()
                    startMetronomeFromDialog()
                }
            }
            .setNegativeButton("닫기", null)
            .setOnDismissListener {
                preferences.edit()
                    .putBoolean(PREF_METRONOME_SOUND, soundOn)
                    .putFloat(PREF_METRONOME_VOLUME, volumeSeek.progress / 100f)
                    .apply()
                if (fileId != null) {
                    val tempo = bpm
                    val chosen = meter
                    val countDotted = dotted
                    // 구간을 모르면(분석 실패 등) 저장된 구간 설정을 지우지 않는다
                    val chosenSections = if (spans.size > 1) currentSectionSettings() else null
                    lifecycleScope.launch(Dispatchers.IO) {
                        musicRepository.setMetronomeForFile(fileId, tempo, chosen.numerator, chosen.denominator, countDotted)
                        chosenSections?.let { musicRepository.setMetronomeSectionsForFile(fileId, it) }
                    }
                }
            }
            .create()
        // 처음 포커스는 지금 박자 버튼 — 상세 버튼이 아니라
        dialog.setOnShowListener { (primaryButtons[meter] ?: otherMeterButton).requestFocus() }
        dialog.show()
    }

    private fun startMetronome() {
        // 합주 지휘자면 시간표를 만들어 연주자들에게 알리고 자신도 그 시간표로 돈다 (#055)
        val withSound = if (isConductingEnsemble()) startConductorRun() else metronome.start()
        metronomeFileId = currentPdfFileId
        binding.metronomeBeat.visibility = View.VISIBLE
        binding.metronomeBeat.removeCallbacks(metronomeTicker)
        binding.metronomeBeat.postOnAnimation(metronomeTicker)
        if (!withSound) {
            Toast.makeText(this, "소리 장치를 열지 못해 박 표시만 합니다", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMetronome() {
        endEnsembleRun()
        metronome.stop()
        metronome.barPosition = null
        metronome.accompaniment = null
        binding.metronomeBeat.removeCallbacks(metronomeTicker)
        binding.metronomeBeat.visibility = View.GONE
        showCountIn(null)
        metronomeFileId = null
        if (followState != FollowState.OFF) {
            val fileId = followFileId
            val at = followMeasure
            if (fileId != null && at != null && followState != FollowState.SELECTING) {
                lastFollowPosition = fileId to at.measureNumber
            }
            followState = FollowState.OFF
            follower = null
            followMeasure = null
            followMeasures = emptyList()
            followStartMeasure = null
            followTempo = null
            refreshScoreOverlay()
        }
    }

    // ── 합주 메트로놈 (#055) ────────────────────────────────────────────────

    /** 합주 지휘자인가 — 연결된 연주자가 없어도 시간표로 돈다 (연주 중에 들어온 연주자도 합류할 수 있게) */
    private fun isConductingEnsemble(): Boolean =
        collaborationMode == CollaborationMode.CONDUCTOR && globalCollaborationManager.isServerRunning()

    /** 지휘자: 새 시간표로 시작하고 알린다. 첫 박(예비박 1)은 지금 + lead. @return 소리와 함께 시작했으면 true */
    private fun startConductorRun(): Boolean {
        val lead = if (globalCollaborationManager.getConnectedClientCount() > 0) ENSEMBLE_START_LEAD_NS else ENSEMBLE_SOLO_LEAD_NS
        val bpm = metronome.bpm.coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM)
        val timeline = BeatTimeline(anchorBeat = 0, anchorNs = System.nanoTime() + lead, bpm = bpm)
        val meter = metronome.timeSignature.coerced()
        // 구간별 빠르기가 있는 곡이면 박 시각을 구간 템포로 (#057). 연주자는 방송한 구간 설정으로 같은 시각을 만든다
        val following = followState == FollowState.PLAYING
        val times = if (following) follower?.beatTimes else null
        val schedule = EnsembleSchedule(timeline, meter, metronome.dottedBeat, times) { 0L }
        schedule.barPosition = metronome.barPosition
        ensembleSchedule = schedule
        ensembleRole = EnsembleRole.CONDUCTING
        conductorRun = EnsembleRun(
            runId = "r${System.currentTimeMillis()}-${++conductorRunCounter}",
            file = pdfFileName,
            state = EnsembleRun.State.PLAYING,
            timeline = timeline,
            timeSignature = meter,
            dotted = metronome.dottedBeat,
            startMeasure = if (following) followMeasure?.measureNumber else null,
            sections = if (following) followTempo?.third.orEmpty() else null,
            countInBars = if (following) follower?.countInBars ?: 1 else 1,
        )
        binding.root.removeCallbacks(ensembleRebroadcast)
        ensembleRebroadcast.run()
        Log.d("PdfViewerActivity", "🎵 합주 메트로놈 시작: ${conductorRun?.runId} bpm=$bpm $meter 시작마디=${conductorRun?.startMeasure}")
        return metronome.startScheduled(schedule, withSound = true)
    }

    /** 지휘자: 연주 중(일반 메트로놈) 템포 · 박자를 바꾸면 다음 박부터 새 시간표. 앞 박들의 시각은 그대로다 */
    private fun retimeConductorRun() {
        val run = conductorRun ?: return
        val schedule = ensembleSchedule ?: return
        if (run.state != EnsembleRun.State.PLAYING || ensembleRole != EnsembleRole.CONDUCTING) return
        val bpm = metronome.bpm.coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM)
        val oldTimes = schedule.times
        if (run.isFollowingScore && oldTimes != null) {
            // 구간별 빠르기 (#057): refreshFollowTempo 가 새 follower 를 만들었으면 다음 박부터 그 시각표로. 마디 나눔은 같다
            val scoreFollower = follower ?: return
            val newTimes = scoreFollower.beatTimes ?: return
            if (newTimes === oldTimes) return
            val from = maxOf(0L, schedule.beatAtLocal(System.nanoTime()) + 1)
            val timeline = run.timeline.retimed(followTempo?.first ?: bpm, from, oldTimes)
            schedule.barPosition = metronome.barPosition
            schedule.update(timeline, run.timeSignature, run.dotted, newTimes)
            conductorRun = run.copy(timeline = timeline, sections = followTempo?.third.orEmpty())
            conductorRun?.let { globalCollaborationManager.broadcastMetronomeRun(it) }
            return
        }
        val meter = metronome.timeSignature.coerced()
        val dotted = metronome.dottedBeat
        // 악보 연동 중에는 박자를 악보가 정한다 — 템포만 본다
        val meterChanged = !run.isFollowingScore && (meter != run.timeSignature || dotted != run.dotted)
        if (bpm == run.timeline.bpm && !meterChanged) return
        val from = maxOf(0L, schedule.beatAtLocal(System.nanoTime()) + 1)
        val timeline = run.timeline.retimed(bpm, from, if (meterChanged) from else run.timeline.barBeat)
        val newMeter = if (meterChanged) meter else run.timeSignature
        val newDotted = if (meterChanged) dotted else run.dotted
        schedule.update(timeline, newMeter, newDotted)
        conductorRun = run.copy(timeline = timeline, timeSignature = newMeter, dotted = newDotted)
        conductorRun?.let { globalCollaborationManager.broadcastMetronomeRun(it) }
    }

    /**
     * 실측용 (#055 §2): 현재 마디가 바뀌어 화면에 반영한 순간을 **지휘자 시계로** 남긴다. 두 기기 로그에서 같은 마디의
     * `shownAt` 차이 = 앱이 만든 표시 오차 (모니터 지연 제외). `late` = 박 시각보다 얼마나 늦게 반영했나.
     */
    private fun logEnsembleMeasure(beat: Beat, measureNumber: Int) {
        val schedule = ensembleSchedule ?: return
        val offset = when (ensembleRole) {
            EnsembleRole.CONDUCTING -> 0L
            EnsembleRole.FOLLOWING -> globalCollaborationManager.getClockOffsetNs() ?: return
            EnsembleRole.NONE -> return
        }
        val shownAt = System.nanoTime() + offset
        val late = (shownAt - schedule.timeOf(beat.index)) / 1_000_000.0
        Log.d("EnsembleSync", "role=$ensembleRole measure=$measureNumber beat=${beat.index} shownAt=$shownAt late=${"%.1f".format(late)}ms")
    }

    /** 합주 역할을 끝낸다. 지휘자면 연주자들에게 정지를 알린다 (stopMetronome 에서) */
    private fun endEnsembleRun() {
        if (ensembleRole == EnsembleRole.CONDUCTING) {
            binding.root.removeCallbacks(ensembleRebroadcast)
            conductorRun?.let { globalCollaborationManager.broadcastMetronomeRun(it.copy(state = EnsembleRun.State.STOPPED)) }
            conductorRun = null
        }
        ensembleRole = EnsembleRole.NONE
        ensembleSchedule = null
    }

    /** 연주자: 지휘자 상태를 받았다. [retry] = 같은 상태를 다시 적용해 본다 (시계 동기 완료 · 파일 열림 · 화면 복귀) */
    private fun onEnsembleRunReceived(run: EnsembleRun, retry: Boolean = false) {
        if (collaborationMode != CollaborationMode.PERFORMER) return
        val previous = performerRun
        performerRun = run
        // 새 연주가 시작되면 빠져 있던 연주자도 다시 따라간다 (지휘자의 시작은 모두에게 — 사용자 결정)
        if (performerDetachedRunId != null && performerDetachedRunId != run.runId) performerDetachedRunId = null

        if (run.file != pdfFileName) {
            if (ensembleRole == EnsembleRole.FOLLOWING) stopMetronome()
            return
        }
        when (run.state) {
            EnsembleRun.State.STOPPED -> {
                performerDetachedRunId = null
                if (ensembleRole == EnsembleRole.FOLLOWING) {
                    stopMetronome()
                    Toast.makeText(this, "지휘자가 메트로놈을 멈췄습니다", Toast.LENGTH_SHORT).show()
                }
            }
            EnsembleRun.State.PAUSED, EnsembleRun.State.SELECTING -> showEnsembleFocus(run)
            EnsembleRun.State.PLAYING -> {
                if (run.runId == performerDetachedRunId) return
                val following = ensembleRole == EnsembleRole.FOLLOWING && previous?.runId == run.runId && metronome.isRunning
                if (following) {
                    // 같은 연주 — 템포가 바뀌었으면 시간표만 갈아 끼운다
                    if (previous != run) updatePerformerSchedule(run)
                    return
                }
                if (!retry && performerJoiningRunId == run.runId) return
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
                joinEnsembleRun(run)
            }
        }
    }

    /** 연주자: 지휘자 연주에 합류한다 — 이미 시작했으면 지금 박에서부터 */
    private fun joinEnsembleRun(run: EnsembleRun) {
        if (globalCollaborationManager.getClockOffsetNs() == null) {
            // 시계 동기는 연결 직후 1초 안에 끝난다 — 끝나면 onClockSynced 가 다시 부른다
            if (!clockWaitNoticeShown) {
                clockWaitNoticeShown = true
                Toast.makeText(this, "지휘자와 시계를 맞추는 중…", Toast.LENGTH_SHORT).show()
            }
            return
        }
        performerJoiningRunId = run.runId
        // 이 기기에서 하던 메트로놈 · 마디 고르기는 접는다 — 지휘자가 우선
        if (followState == FollowState.SELECTING) cancelMeasureSelection()
        if (metronome.isRunning || followState != FollowState.OFF) stopMetronome()

        val fileId = currentPdfFileId
        lifecycleScope.launch {
            val measures = if (!run.isFollowingScore || fileId == null) null else scoreMeasuresFor(fileId)
            val latest = performerRun
            if (performerJoiningRunId != run.runId) return@launch
            performerJoiningRunId = null
            if (latest == null || latest.runId != run.runId || latest.state != EnsembleRun.State.PLAYING ||
                latest.file != pdfFileName || latest.runId == performerDetachedRunId || isDestroyed
            ) return@launch
            startFollowingEnsemble(latest, fileId, measures)
        }
    }

    private fun startFollowingEnsemble(run: EnsembleRun, fileId: String?, measures: List<ScoreMeasure>?) {
        val schedule = EnsembleSchedule(run.timeline, run.timeSignature, run.dotted) {
            globalCollaborationManager.getClockOffsetNs() ?: 0L
        }
        val startable = ScoreFollower.startableMeasures(measures.orEmpty())
        val startIndex = startable.indexOfFirst { it.measureNumber == run.startMeasure }
        if (run.startMeasure != null && startIndex >= 0) {
            val scoreFollower = ensembleFollower(run, startable, run.startMeasure)
            schedule.update(run.timeline, run.timeSignature, run.dotted, scoreFollower.beatTimes)
            followMeasures = startable
            followFileId = fileId
            cursorIndex = startIndex
            follower = scoreFollower
            followStartMeasure = run.startMeasure
            followMeasure = startable[startIndex]
            followInCountIn = true
            turnRequestedTo = -1
            schedule.barPosition = { index -> scoreFollower.barPositionAt(index) }
            followState = FollowState.PLAYING
        } else if (run.startMeasure != null) {
            Toast.makeText(this, "이 기기 악보에서 ${run.startMeasure}번 마디를 찾지 못해 박만 따라갑니다", Toast.LENGTH_LONG).show()
        }
        // 표시용 — 저장하지 않는다 (지휘자 설정이다)
        metronome.bpm = run.timeline.bpm
        metronome.timeSignature = run.timeSignature
        metronome.dottedBeat = run.dotted
        metronome.soundEnabled = true
        metronome.volume = preferences.getFloat(PREF_METRONOME_VOLUME, 0.6f)
        ensembleSchedule = schedule
        ensembleRole = EnsembleRole.FOLLOWING
        metronome.startScheduled(schedule, withSound = preferences.getBoolean(PREF_ENSEMBLE_SOUND, false))
        metronomeFileId = currentPdfFileId
        binding.metronomeBeat.visibility = View.VISIBLE
        binding.metronomeBeat.removeCallbacks(metronomeTicker)
        binding.metronomeBeat.postOnAnimation(metronomeTicker)
        refreshScoreOverlay()
        val now = System.nanoTime()
        Log.d(
            "EnsembleSync",
            "join run=${run.runId} beatNow=${schedule.beatAtLocal(now)} offset=${globalCollaborationManager.getClockOffsetNs()}ns " +
                "rtt=${globalCollaborationManager.getClockRttNs()}ns startMeasure=${run.startMeasure}"
        )
    }

    /**
     * 연주자: 지휘자와 같은 마디 나눔 · 박 시각을 만든다. 구간 설정(#057)을 보낸 지휘자면 그것과 이 기기 악보로 구간 템포를,
     * 보내지 않은 지휘자(v0.2.4)면 곡 전체를 한 템포로 — 지휘자가 센 방식 그대로다.
     */
    private fun ensembleFollower(run: EnsembleRun, startable: List<ScoreMeasure>, startMeasure: Int): ScoreFollower {
        val sections = run.sections?.let { TempoSections.forFollowing(startable, run.timeline.bpm, run.dotted, it) }.orEmpty()
        return ScoreFollower(startable, startMeasure, run.dotted, sections, run.countInBars)
    }

    /**
     * 연주자: 따라가던 연주의 템포가 바뀌었다 — 시간표를 갈아 끼운다. 구간이 있으면 새 구간 템포로 [follower] 도 다시 만든다.
     * 지휘자는 마디 나눔이 같은 변경만 연주 중에 보내므로(세는 단위 변경은 다음 시작부터) 지금 마디는 그대로다.
     */
    private fun updatePerformerSchedule(run: EnsembleRun) {
        val schedule = ensembleSchedule ?: return
        val current = follower
        val start = followStartMeasure
        var times = schedule.times
        if (current != null && start != null && run.startMeasure == start) {
            val next = ensembleFollower(run, followMeasures, start)
            if (next.sameBeatsAs(current)) {
                follower = next
                schedule.barPosition = { index -> next.barPositionAt(index) }
                times = next.beatTimes
            } else {
                Log.w("EnsembleSync", "지휘자 템포 변경의 마디 나눔이 달라 시간표만 바꾼다 run=${run.runId}")
            }
        }
        schedule.update(run.timeline, run.timeSignature, run.dotted, times)
    }

    /**
     * 연주자: 지휘자가 일시정지했거나 마디를 고르는 중 — 멈추고 그 마디(멈춘 마디 · 지휘자의 커서)를 파란 상자로 보이며
     * 그 페이지로 간다. 커서는 보기만 한다 (고르는 건 지휘자). 따라가던 적이 없으면 악보부터 준비한다.
     */
    private fun showEnsembleFocus(run: EnsembleRun) {
        val measureNumber = run.focusMeasure
        val fileId = currentPdfFileId
        if (measureNumber == null || fileId == null || run.runId == performerDetachedRunId) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (metronome.isRunning) {
            metronome.stop()
            metronome.barPosition = null
            metronome.accompaniment = null
            binding.metronomeBeat.removeCallbacks(metronomeTicker)
            binding.metronomeBeat.visibility = View.GONE
            showCountIn(null)
        }
        if (followState == FollowState.SELECTING) cancelMeasureSelection()
        follower = null
        followInCountIn = false
        ensembleSchedule = null

        if (followFileId == fileId && followMeasures.isNotEmpty()) {
            applyEnsembleFocus(fileId, followMeasures, measureNumber)
            return
        }
        lifecycleScope.launch {
            val measures = scoreMeasuresFor(fileId) ?: return@launch
            val latest = performerRun ?: return@launch
            if (currentPdfFileId != fileId || latest.file != pdfFileName || latest.runId == performerDetachedRunId) return@launch
            if (latest.state != EnsembleRun.State.PAUSED && latest.state != EnsembleRun.State.SELECTING) return@launch
            applyEnsembleFocus(fileId, ScoreFollower.startableMeasures(measures), latest.focusMeasure ?: return@launch)
        }
    }

    private fun applyEnsembleFocus(fileId: String, startable: List<ScoreMeasure>, measureNumber: Int) {
        val index = startable.indexOfFirst { it.measureNumber == measureNumber }
        if (index < 0) return
        followMeasures = startable
        followFileId = fileId
        cursorIndex = index
        followMeasure = startable[index]
        ensembleRole = EnsembleRole.FOLLOWING
        followState = FollowState.PAUSED
        ensureMeasureVisible(startable[index])
        refreshScoreOverlay()
    }

    /** 이 파일의 악보 분석 결과 (캐시 → DB → 분석) */
    private suspend fun scoreMeasuresFor(fileId: String): List<ScoreMeasure>? {
        if (scoreMeasuresFileId == fileId) return scoreMeasures
        val file = File(pdfFilePath)
        val measures = withContext(Dispatchers.IO) { measuresForView(fileId, file) }
        if (measures != null && currentPdfFileId == fileId) {
            scoreMeasures = measures
            scoreMeasuresFileId = fileId
        }
        return measures
    }

    /** 지휘자: 시작 마디를 고르는 중 — 커서 위치를 연주자들에게 (#055). 일시정지 후 다시 고를 때도, 처음 고를 때도 */
    private fun broadcastConductorCursor() {
        if (!isConductingEnsemble() || followState != FollowState.SELECTING) return
        val measure = followMeasures.getOrNull(cursorIndex) ?: return
        val base = conductorRun ?: EnsembleRun(
            runId = "r${System.currentTimeMillis()}-${++conductorRunCounter}",
            file = pdfFileName,
            state = EnsembleRun.State.SELECTING,
            // 고르는 동안에는 시간표를 쓰지 않는다 — 형식만 채운다
            timeline = BeatTimeline(0, 0, metronome.bpm.coerceIn(MetronomeClock.MIN_BPM, MetronomeClock.MAX_BPM)),
            timeSignature = metronome.timeSignature.coerced(),
            dotted = metronome.dottedBeat,
            startMeasure = null,
        )
        val firstBroadcast = conductorRun == null
        conductorRun = base.copy(state = EnsembleRun.State.SELECTING, file = pdfFileName, focusMeasure = measure.measureNumber)
        ensembleRole = EnsembleRole.CONDUCTING
        ensembleSchedule = null
        if (firstBroadcast) {
            binding.root.removeCallbacks(ensembleRebroadcast)
            ensembleRebroadcast.run()
        } else {
            conductorRun?.let { globalCollaborationManager.broadcastMetronomeRun(it) }
        }
    }

    /** 연주자: 이 기기만 빠진다 (↑ · 뒤로 · OK 길게) — 지휘자에게는 영향이 없다 */
    private fun detachFromEnsemble() {
        performerDetachedRunId = performerRun?.runId
        stopMetronome()
        Toast.makeText(this, "이 기기만 메트로놈 연동에서 빠졌습니다 — ↑ 로 다시 합류", Toast.LENGTH_SHORT).show()
    }

    private fun canRejoinEnsemble(): Boolean {
        val run = performerRun ?: return false
        return collaborationMode == CollaborationMode.PERFORMER && run.state == EnsembleRun.State.PLAYING && run.file == pdfFileName
    }

    /**
     * 연주자의 ↑ 메뉴 (#055) — 연주자는 지휘자를 따라가기만 한다. 따라가는 중이면 "이 기기만 빠지기",
     * 빠져 있고 지휘자가 연주 중이면 "다시 합류". 설정은 소리 켜기/끄기뿐 (사용자 결정). 닫으면 그대로.
     */
    private fun showPerformerEnsembleMenu() {
        fun build(): Menu {
            val following = ensembleRole == EnsembleRole.FOLLOWING
            val soundOn = preferences.getBoolean(PREF_ENSEMBLE_SOUND, false)
            return ViewerMenus.performer(ViewerMenus.PerformerState(following, !following && canRejoinEnsemble(), soundOn))
        }
        metronomeMenuShowing = true
        showViewerMenu(::build, onDismiss = { metronomeMenuShowing = false }) { action ->
            when (action) {
                MenuAction.DETACH -> detachFromEnsemble()
                MenuAction.REJOIN -> {
                    performerDetachedRunId = null
                    performerRun?.let { joinEnsembleRun(it) }
                }
                // 소리는 누르면 바로 켬 ↔ 끔 (P13). 파트 보기 · 메모는 보기 메뉴(길게)에
                MenuAction.ENSEMBLE_SOUND -> setPerformerSound(!preferences.getBoolean(PREF_ENSEMBLE_SOUND, false), announce = !sheetMenus())
                else -> Unit
            }
        }
    }

    /** 메뉴를 하단 시트로 그리는 기기 — 태블릿 · 휴대폰 (P17 1단계). TV 는 목록 대화상자 */
    private fun sheetMenus(): Boolean = !com.mrgq.pdfviewer.utils.DeviceForm.isTv(this)

    /**
     * 악보 화면 메뉴 그리기 (P17) — 내용은 [ViewerMenus] 로 [build] 가 만들고, TV 는 목록 대화상자 · 터치 기기는 하단 시트([showMenuSheet]).
     * [onChosen] 은 줄을 고른 뒤, [onDismiss] 는 고르든 말든 닫힐 때
     */
    private fun showViewerMenu(build: () -> Menu, onDismiss: () -> Unit = {}, onChosen: (MenuAction) -> Unit) {
        if (sheetMenus()) return showMenuSheet(build, onDismiss, onChosen)
        val menu = build()
        AlertDialog.Builder(this)
            .setTitle(menu.title)
            .setItems(menu.items.map { it.text }.toTypedArray()) { _, which -> onChosen(menu.items[which].action) }
            .apply { if (menu.closeButton) setNegativeButton("닫기") { dialog, _ -> dialog.dismiss() } }
            .setOnDismissListener { onDismiss() }
            .show()
    }

    /**
     * 하단 시트 메뉴 (P17 1단계). 켜기 · 끄기 줄(`checked`)은 스위치 — 누르면 시트를 연 채로 바꾸고 내용을 [build] 로 다시 그린다.
     * 다른 줄은 목록 대화상자처럼 고른 뒤 닫는다(고른 동작이 먼저 — 다음 대화상자가 뜬 뒤 시트가 닫힌다). "닫기" 단추 대신 아래로 밀기 · 바깥 탭.
     * 태블릿은 폭을 [MENU_SHEET_MAX_WIDTH_DP] 로 줄여 가운데, 처음부터 다 펼친다(휴대폰 가로는 시트 안에서 스크롤)
     */
    private fun showMenuSheet(build: () -> Menu, onDismiss: () -> Unit, onChosen: (MenuAction) -> Unit) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.ThemeOverlay_MrgqPdfViewer_BottomSheet)
        val content = layoutInflater.cloneInContext(sheet.context).inflate(R.layout.sheet_viewer_menu, null)
        val title = content.findViewById<android.widget.TextView>(R.id.sheetTitle)
        val rows = content.findViewById<android.widget.LinearLayout>(R.id.sheetRows)

        fun render() {
            val menu = build()
            title.text = menu.title
            rows.removeAllViews()
            for (item in menu.items) {
                val row = layoutInflater.cloneInContext(sheet.context).inflate(R.layout.item_menu_sheet_row, rows, false)
                row.findViewById<android.widget.TextView>(R.id.rowLabel).text = item.label
                val toggle = item.checked
                if (toggle != null) {
                    row.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.rowSwitch).apply {
                        visibility = View.VISIBLE
                        isChecked = toggle
                    }
                } else if (item.value != null) {
                    row.findViewById<android.widget.TextView>(R.id.rowValue).apply {
                        visibility = View.VISIBLE
                        text = item.value
                    }
                }
                row.setOnClickListener {
                    onChosen(item.action)
                    if (toggle != null) render() else sheet.dismiss()
                }
                rows.addView(row)
            }
        }

        render()
        sheet.setContentView(content)
        sheet.behavior.maxWidth = (MENU_SHEET_MAX_WIDTH_DP * resources.displayMetrics.density).toInt()
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.setOnDismissListener { onDismiss() }
        sheet.show()
    }

    /** 연주자의 메트로놈 설정 — 이 기기가 소리를 낼지만 고른다. 템포 · 박자는 지휘자 것 */
    private fun showPerformerMetronomeSettings() {
        val soundOn = preferences.getBoolean(PREF_ENSEMBLE_SOUND, false)
        val choices = arrayOf("소리 끔 — 박 표시 · 현재 마디 · 넘김만 따라갑니다", "소리 켬 — 지휘자 메트로놈에 맞춰 이 기기도 소리를 냅니다")
        AlertDialog.Builder(this)
            .setTitle("메트로놈 설정 (연주자)")
            .setSingleChoiceItems(choices, if (soundOn) 1 else 0) { dialog, which ->
                setPerformerSound(which == 1)
                dialog.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 연주자 소리 설정을 바꾸고, 따라가는 중이면 바로 적용한다 — 같은 시간표로 다시 시작해도 박은 그대로다 */
    private fun setPerformerSound(on: Boolean, announce: Boolean = true) {
        preferences.edit().putBoolean(PREF_ENSEMBLE_SOUND, on).apply()
        val schedule = ensembleSchedule
        if (ensembleRole == EnsembleRole.FOLLOWING && metronome.isRunning && schedule != null) {
            metronome.startScheduled(schedule, withSound = on)
        }
        if (announce) Toast.makeText(this, if (on) "이 기기도 메트로놈 소리를 냅니다" else "이 기기는 메트로놈 소리를 내지 않습니다", Toast.LENGTH_SHORT).show()
    }

    /** 다른 곡으로 넘어가면 멈춘다 — 템포가 파일별이라 이전 곡 템포로 계속 도는 건 틀린 동작이다. */
    private fun onPdfFileChangedForMetronome(fileId: String) {
        val runningFor = metronomeFileId
        if (metronome.isRunning && runningFor != null && runningFor != fileId) stopMetronome()
        if (followState == FollowState.SELECTING && followFileId != fileId) cancelMeasureSelection()
        if (followState == FollowState.PAUSED && followFileId != fileId) stopMetronome()
        // 연주자: 지휘자가 이 파일로 연주 중이면 바로 합류 (#055)
        performerRun?.let { if (it.file == pdfFileName) onEnsembleRunReceived(it, retry = true) }
    }

    /**
     * 대화상자의 "시작". 악보에서 박자표를 읽을 수 있으면 먼저 시작 마디를 고르게 하고,
     * 아니면(박자표를 못 읽음, 악보 분석 안 됨) 연동 없이 일반 메트로놈으로 시작한다 — 사용자 결정 (#050).
     */
    private fun startMetronomeFromDialog() {
        val fileId = currentPdfFileId
        if (fileId == null) {
            startMetronome()
            return
        }
        val cached = if (scoreMeasuresFileId == fileId) scoreMeasures else null
        if (cached == null) Toast.makeText(this, "악보 분석 중…", Toast.LENGTH_SHORT).show()
        val file = File(pdfFilePath)
        lifecycleScope.launch {
            val measures = cached ?: withContext(Dispatchers.IO) { measuresForView(fileId, file) }
            if (currentPdfFileId != fileId) return@launch
            if (cached == null && measures != null) {
                scoreMeasures = measures
                scoreMeasuresFileId = fileId
            }
            val startable = ScoreFollower.startableMeasures(measures.orEmpty())
            if (startable.isEmpty()) {
                if (!measures.isNullOrEmpty()) {
                    Toast.makeText(this@PdfViewerActivity, "악보에서 박자표를 읽지 못해 악보 연동 없이 시작합니다", Toast.LENGTH_LONG).show()
                }
                startMetronome()
            } else {
                enterMeasureSelection(fileId, startable)
            }
        }
    }

    /** 시작 마디 고르기. [at] 이 있으면 그 마디에 커서를(🎙 "57마디" — 안내는 음성 쪽이 띄운다) */
    private fun enterMeasureSelection(fileId: String, startable: List<ScoreMeasure>, at: ScoreMeasure? = null) {
        followMeasures = startable
        followFileId = fileId
        // 마지막으로 멈춘 마디가 화면에 있으면 거기서 (일시정지 후 다시 고르기, 정지 후 다시 시작 — #053)
        val last = lastFollowPosition?.takeIf { it.first == fileId }?.second
        cursorIndex = at?.let { startable.indexOf(it) }?.takeIf { it >= 0 }
            ?: startable.indexOfFirst { it.measureNumber == last && isMeasureVisible(it) }.takeIf { it >= 0 }
            ?: startable.indexOf(firstShownMeasure(startable))
        followState = FollowState.SELECTING
        turnRequestedTo = -1
        ensureMeasureVisible(startable[cursorIndex])
        refreshScoreOverlay()
        broadcastConductorCursor()
        if (at == null) {
            val hint = if (com.mrgq.pdfviewer.utils.DeviceForm.isTv(this)) "←→ 마디, ↑↓ 줄, OK 시작, 뒤로 취소 (연주 중 ↑ 메뉴)"
                else "마디를 탭해 고르고 한 번 더 탭하면 시작, 두 번 탭 = 메뉴(🎤 그 마디부터 듣기)"
            Toast.makeText(this, "시작할 마디를 고르세요 — $hint", Toast.LENGTH_LONG).show()
        }
    }

    /** [page] 화면(두 쪽이면 펼침)의 첫 마디 — 없으면 그 쪽 뒤의 첫 마디, 그것도 없으면 맨 앞 */
    private fun firstShownMeasure(startable: List<ScoreMeasure>, page: Int = pageIndex): ScoreMeasure =
        startable.firstOrNull { it.pageIndex == page || (isTwoPageMode && it.pageIndex == page + 1) }
            ?: startable.firstOrNull { it.pageIndex >= page }
            ?: startable.first()

    private fun cancelMeasureSelection() {
        // 지휘자가 일시정지 → 마디 골라 다시 → 취소면 연주자들도 정지 (#055)
        if (ensembleRole == EnsembleRole.CONDUCTING) endEnsembleRun()
        followState = FollowState.OFF
        followMeasures = emptyList()
        refreshScoreOverlay()
    }

    /** 악보 연동 일시정지 (#053) — 메뉴를 띄울 때. 연주 중이 아니면 아무것도 하지 않는다. 멈춘 마디를 표시해 둔다. */
    private fun pauseFollowing() {
        if (followState != FollowState.PLAYING) return
        // 합주 연주자는 메뉴를 열어도 계속 따라간다 — 멈추고 고르는 건 지휘자 (#055)
        if (ensembleRole == EnsembleRole.FOLLOWING) return
        val at = followMeasure
        metronome.stop()
        metronome.barPosition = null
        metronome.accompaniment = null
        binding.metronomeBeat.removeCallbacks(metronomeTicker)
        binding.metronomeBeat.visibility = View.GONE
        showCountIn(null)
        follower = null
        followInCountIn = false
        cursorIndex = followMeasures.indexOfFirst { it.measureNumber == at?.measureNumber }.coerceAtLeast(0)
        followMeasure = followMeasures.getOrNull(cursorIndex)
        followState = FollowState.PAUSED
        if (ensembleRole == EnsembleRole.CONDUCTING) {
            ensembleSchedule = null
            conductorRun = conductorRun?.copy(state = EnsembleRun.State.PAUSED, focusMeasure = followMeasure?.measureNumber)
            conductorRun?.let { globalCollaborationManager.broadcastMetronomeRun(it) }
        }
        refreshScoreOverlay()
    }

    /** 이어서 — 멈춘 마디의 처음부터, 시작할 때처럼 예비박(기본 두 마디) 뒤 */
    private fun resumeFollowing() {
        if (followState == FollowState.PAUSED) startFollowing()
    }

    /** 마디 골라 다시 시작 — 멈춘 마디에 커서를 두고 시작 마디 선택으로 */
    private fun reselectFromPause() {
        val fileId = followFileId
        val measures = followMeasures
        if (followState != FollowState.PAUSED || fileId == null || measures.isEmpty()) return stopMetronome()
        followMeasure?.let { lastFollowPosition = fileId to it.measureNumber }
        follower = null
        followMeasure = null
        followState = FollowState.OFF
        enterMeasureSelection(fileId, measures)
    }

    /**
     * 메트로놈 메뉴 (↑ 키, #053). 자주 쓰는 동작만 모았다 — 세부 설정은 "메트로놈 설정…".
     * 악보 연동 중이면 먼저 일시정지하고 이어서 · 마디 골라 다시 · 정지 중에서 고른다 (사용자 결정). 일시정지 중에
     * 고르지 않고 닫으면(뒤로) 멈춘 채로 끝낸다 — 일시정지로 남겨 두면 포커스가 돌아올 때 메뉴가 다시 뜬다.
     */
    private fun showMetronomeMenu() {
        if (metronomeMenuShowing) return
        if (collaborationMode == CollaborationMode.PERFORMER) return showPerformerEnsembleMenu()
        pauseFollowing()
        val paused = followState == FollowState.PAUSED
        // 시작 마디를 고르는 중 (터치 기기의 두 번 탭, #087) — 고른 마디로 시작 · 듣기
        val selected = followMeasures.getOrNull(cursorIndex)?.takeIf { followState == FollowState.SELECTING }
        val accompaniment = musicXml?.takeIf { musicXmlFileId == currentPdfFileId }?.let {
            ViewerMenus.Accompaniment(
                preferences.getBoolean(PREF_ACCOMPANIMENT, true),
                (preferences.getFloat(PREF_ACCOMPANIMENT_VOLUME, 0.6f) * 100).toInt(),
            )
        }
        val menu = ViewerMenus.play(ViewerMenus.PlayState(
            micCapable = micFollowCapable(),
            micListening = micFollower != null,
            selectedMeasure = selected?.measureNumber,
            paused = paused,
            pausedMeasure = followMeasure?.measureNumber,
            running = metronome.isRunning,
            countInBars = countInBarsSetting(),
            accompaniment = accompaniment,
        ))

        var chosen = false
        metronomeMenuShowing = true
        showViewerMenu({ menu }, onDismiss = {
            metronomeMenuShowing = false
            if (!chosen && followState == FollowState.PAUSED) {
                stopMetronome()
                Toast.makeText(this, "메트로놈 정지", Toast.LENGTH_SHORT).show()
            }
        }) { action ->
            chosen = true
            when (action) {
                MenuAction.MIC_STOP, MenuAction.MIC_FROM_PAGE -> toggleMicFollow()
                MenuAction.MIC_FROM_SELECTION -> startMicFollowFromSelection()
                MenuAction.START_SELECTED -> startFollowing()
                MenuAction.CANCEL_SELECTION -> cancelMeasureSelection()
                MenuAction.RESUME -> resumeFollowing()
                MenuAction.RESELECT -> reselectFromPause()
                MenuAction.STOP -> stopMetronome()
                MenuAction.START -> startMetronomeWithSavedSettings()
                MenuAction.METRONOME_SETTINGS -> showMetronomeDialog()
                MenuAction.ACCOMPANIMENT -> showAccompanimentDialog()
                else -> Unit
            }
        }
    }

    /** ↑ 메뉴의 "시작" — 설정 대화상자를 거치지 않으므로 이 파일의 설정(없으면 악보 박자표)을 엔진에 넣고 시작한다. */
    private fun startMetronomeWithSavedSettings() {
        val fileId = currentPdfFileId
        lifecycleScope.launch {
            if (loadSavedMetronomeSettings(fileId)) startMetronomeFromDialog()
        }
    }

    // ── 명령 층 (P12 §5) ────────────────────────────────────────────────────

    /** 이 곡에서 마지막으로 악보 연동을 시작한 마디 — "다시"가 쓴다. 정지가 [followStartMeasure] 를 지워도 남는다 */
    private var lastFollowStart: Pair<String, Int>? = null
    /** 파일을 다시 열어 첫 쪽을 보인 순간 — 파트 보기를 바꾼 명령이 기다린다 ([applyPartStaves]) */
    private var fileShownSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** 이 발화의 쪽 명령이 넘어갈 쪽 — 이어지는 "시작"이 그 쪽 첫 마디에서("Mate, 1페이지 시작", #085). 발화마다 비운다([runCommand]) */
    private var commandPageTarget: Int? = null

    private fun failed(message: String) = CommandOutcome.Failed(message)

    /** 발화 하나를 실행 — 🎙 · 👂 가 모두 이 길로 */
    private suspend fun runCommand(parsed: com.mrgq.pdfviewer.voice.ParseResult): VoiceCommandRunner.Report {
        commandPageTarget = null
        return VoiceCommandRunner.run(parsed, viewerCommands)
    }

    /**
     * 음성 · 글자 명령이 부르는 악보 화면의 일. 기존 경로(↑ 메뉴 · 키 · 파트 보기 대화상자)와 같은 함수를 부른다 — 새로 생긴 동작은
     * 시작하는 명령이 **마디 고르기 없이 바로** 그 마디에서 악보 연동을 시작하는 것("57마디"만이면 고르기만, #084).
     * 합주 연주자는 쪽 · 파트만 (마디 · 템포 · 시작은 지휘자, P12 §3.3), 지휘자는 늘 총보.
     */
    private val viewerCommands = object : ViewerCommands {
        override suspend fun selectMeasure(measure: Int): CommandOutcome {
            val (fileId, startable) = when (val target = followableMeasures()) {
                is MeasureTarget.Unavailable -> return target.outcome
                is MeasureTarget.Ready -> target
            }
            val at = startable.firstOrNull { it.measureNumber == measure } ?: return noSuchMeasure("${measure}마디", startable)
            if (followState == FollowState.SELECTING && followFileId == fileId) {
                followMeasures = startable
                moveCursor(startable.indexOf(at) - cursorIndex)
                return CommandOutcome.Done
            }
            if (metronome.isRunning || followState != FollowState.OFF) stopMetronome()
            // 마디를 탭해 시작할 때 이 파일의 설정(방금 바꾼 템포 포함)으로 — ↑ 메뉴 "시작"과 같다
            if (!loadSavedMetronomeSettings(fileId)) return failed("다른 곡으로 넘어갔어요")
            enterMeasureSelection(fileId, startable, at)
            return CommandOutcome.Done
        }

        override suspend fun gotoMeasure(measure: Int) = followFrom("${measure}마디") { it.firstOrNull { m -> m.measureNumber == measure } }

        override suspend fun gotoStart() = followFrom("처음") { it.firstOrNull() }

        override suspend fun restart(): CommandOutcome {
            val at = lastFollowStart?.takeIf { it.first == currentPdfFileId }?.second
                ?: return failed("다시 시작할 마디가 없어요 — 이 곡에서 아직 시작한 적이 없어요")
            return followFrom("${at}마디") { it.firstOrNull { m -> m.measureNumber == at } }
        }

        override suspend fun resume(): CommandOutcome {
            val at = lastFollowPosition?.takeIf { it.first == currentPdfFileId }?.second
                ?: return failed("이어서 할 마디가 없어요 — 멈춘 적이 없어요")
            return followFrom("${at}마디") { it.firstOrNull { m -> m.measureNumber == at } }
        }

        override suspend fun gotoRehearsalMark(mark: String) = failed("레터(리허설 마크)는 아직 몰라요 — 마디 번호로 말하세요")

        override suspend fun nextPage(): CommandOutcome {
            if (isInputBlocked()) return failed("지휘자가 방금 넘겨 잠시 넘길 수 없어요")
            val more = (phoneView && phoneWindowEnd < phoneChunks.lastIndex) ||
                (if (isTwoPageMode) pageIndex + 2 else pageIndex + 1) < pageCount
            if (!more) return failed("마지막 쪽이에요")
            if (isNavigationGuideVisible) hideNavigationGuide()
            commandPageTarget = when {
                phoneView && phoneWindowEnd < phoneChunks.lastIndex -> pageIndex // 휴대폰: 같은 쪽 다음 화면
                isTwoPageMode -> pageIndex + 2
                else -> pageIndex + 1
            }
            turnForward()
            return CommandOutcome.Done
        }

        override suspend fun previousPage(): CommandOutcome {
            if (isInputBlocked()) return failed("지휘자가 방금 넘겨 잠시 넘길 수 없어요")
            if (!(phoneView && chunkIndex > 0) && pageIndex == 0) return failed("첫 쪽이에요")
            if (isNavigationGuideVisible) hideNavigationGuide()
            commandPageTarget = if (phoneView && chunkIndex > 0) pageIndex else maxOf(0, pageIndex - if (isTwoPageMode) 2 else 1)
            turnBack()
            return CommandOutcome.Done
        }

        override suspend fun gotoPage(page: Int): CommandOutcome {
            if (isInputBlocked()) return failed("지휘자가 방금 넘겨 잠시 넘길 수 없어요")
            if (pageCount == 0) return failed("악보를 아직 여는 중이에요")
            // 파트 보기면 원본 쪽 번호로 말한다 — 그 쪽이 놓인 파트 쪽으로 (합주 쪽 신호와 같게)
            val layout = partViewLayout
            val sourcePages = layout?.strips?.maxOfOrNull { it.srcPage + 1 } ?: pageCount
            if (page > sourcePages) return failed("${page}쪽이 없어요 (1 ~ $sourcePages)")
            val target = pairStart((layout?.dstPageForSource(page - 1) ?: (page - 1)).coerceIn(0, pageCount - 1))
            commandPageTarget = target
            if (target == pageIndex) return CommandOutcome.Done
            if (isNavigationGuideVisible) hideNavigationGuide()
            turnTo(target, if (target > pageIndex) 1 else -1)
            return CommandOutcome.Done
        }

        override suspend fun setTempo(bpm: Int): CommandOutcome {
            if (collaborationMode == CollaborationMode.PERFORMER) return failed("템포는 지휘자가 정해요")
            val fileId = currentPdfFileId
            metronome.bpm = bpm
            if (fileId != null) {
                // 파일별 저장 (대화상자를 닫을 때와 같다) — 이어지는 "57마디부터"가 이 템포로 시작한다
                val settings = metronomeSettingsFor(fileId)
                withContext(Dispatchers.IO) {
                    musicRepository.setMetronomeForFile(fileId, bpm, settings.meter.numerator, settings.meter.denominator, settings.dotted)
                }
            }
            refreshFollowTempo()
            retimeConductorRun()
            return CommandOutcome.Done
        }

        override suspend fun setCountIn(bars: Int): CommandOutcome {
            if (collaborationMode == CollaborationMode.PERFORMER) return failed("예비박은 지휘자가 정해요")
            // 박자 상세의 예비박 버튼과 같은 전역 설정 (#060) — 다음 시작부터
            preferences.edit().putInt(PREF_METRONOME_COUNT_IN_BARS, bars).apply()
            return CommandOutcome.Done
        }

        override suspend fun selectParts(parts: List<PartRef>): CommandOutcome {
            if (collaborationMode == CollaborationMode.CONDUCTOR) return failed("지휘자는 총보만 봅니다 — 파트 보기는 연주자 · 혼자 연습에서")
            val fileId = currentPdfFileId ?: return failed("악보를 아직 여는 중이에요")
            val staves = withContext(Dispatchers.IO) { musicRepository.getOrAnalyzeScoreStaves(fileId, File(pdfFilePath)) }
            val all = when (val result = staves?.let { ScoreParts.of(it) } ?: ScoreParts.Result.NoStaves) {
                is ScoreParts.Result.Parts -> result.parts
                ScoreParts.Result.NoStaves -> return failed("악보 구조를 찾지 못했어요 (벡터 악보 PDF 만)")
                ScoreParts.Result.SingleStaff -> return failed("보표가 하나라 이미 파트보예요")
                is ScoreParts.Result.VaryingStaves -> return failed("시스템마다 보표 수가 달라 파트 보기를 못 해요")
            }
            return when (val match = PartMatcher.match(all.map { it.staffIndex to knownPartName(it, all.size) }, parts)) {
                is PartMatcher.Result.Missing -> failed(
                    "${match.part.label} 파트가 없어요" + VoiceCommandRunner.partChoices(all.map { partName(it, all.size) })
                )
                is PartMatcher.Result.Ambiguous -> failed("${match.part.label} 파트가 여럿이에요 (${match.names.joinToString()}) — 번호까지 말하세요")
                is PartMatcher.Result.Staves -> applyPartStaves(fileId, match.staves.takeIf { it.size < all.size })
            }
        }

        override suspend fun partNames(): List<String> {
            if (collaborationMode == CollaborationMode.CONDUCTOR) return emptyList() // 지휘자는 늘 총보
            voiceStaffNames() // 보표 목록을 읽어 둔다
            val parts = voiceStaffParts?.takeIf { it.first == currentPdfFileId }?.second ?: return emptyList()
            return parts.map { partName(it, parts.size) }
        }

        override suspend fun showFullScore(): CommandOutcome {
            if (collaborationMode == CollaborationMode.CONDUCTOR) return CommandOutcome.Done // 지휘자는 늘 총보
            val fileId = currentPdfFileId ?: return failed("악보를 아직 여는 중이에요")
            return applyPartStaves(fileId, null)
        }

        override suspend fun start(): CommandOutcome {
            if (collaborationMode == CollaborationMode.PERFORMER) return failed("시작은 지휘자가 해요")
            val fileId = currentPdfFileId ?: return failed("악보를 아직 여는 중이에요")
            val startable = startableMeasuresNow(fileId) ?: return failed("다른 곡으로 넘어갔어요")
            if (startable.isEmpty()) {
                // 마디 · 박자표를 못 읽은 악보 — 악보 연동 없이 메트로놈만 (↑ 메뉴 "시작"과 같다)
                if (metronome.isRunning || followState != FollowState.OFF) stopMetronome()
                if (!loadSavedMetronomeSettings(fileId)) return failed("다른 곡으로 넘어갔어요")
                startMetronome()
                return CommandOutcome.Done
            }
            // 같은 말에 쪽 명령이 있었으면("3쪽 시작") 그 쪽이 보인 뒤 그 쪽 첫 마디에서 — 넘김 렌더를 기다린다(#085)
            commandPageTarget?.let { page ->
                awaitPageIndex(page)
                return startFollowingAt(fileId, startable, firstShownMeasure(startable, page))
            }
            // 고른 마디가 있으면 거기서, 없으면 지금 쪽 첫 마디에서 — 예비박 뒤 바로 (#084)
            val chosen = followMeasures.getOrNull(cursorIndex)
                ?.takeIf { followState == FollowState.SELECTING && followFileId == fileId && it in startable }
            return startFollowingAt(fileId, startable, chosen ?: firstShownMeasure(startable))
        }

        override suspend fun listen(): CommandOutcome {
            if (!micFollowCapable()) return failed("이 기기에서는 연주를 들을 수 없어요")
            if (micFollower != null) return CommandOutcome.Done
            micFollowBlocker()?.let { return failed(it) }
            val page = commandPageTarget
            micStartAt = when {
                // "3쪽 듣기" — 넘김이 끝나 그 쪽이 보인 뒤 그 쪽 첫 마디부터 (startMicFollow 가 지금 쪽에서 고른다)
                page != null -> { awaitPageIndex(page); null }
                // "57마디부터 듣기" · 고른 뒤 "듣기" — 고른 마디부터
                followState == FollowState.SELECTING -> followMeasures.getOrNull(cursorIndex)
                else -> null
            }
            if (followState == FollowState.SELECTING) cancelMeasureSelection()
            toggleMicFollow()
            return CommandOutcome.Done
        }

        override suspend fun stop(): CommandOutcome {
            when {
                ensembleRole == EnsembleRole.FOLLOWING -> detachFromEnsemble() // 연주자: 이 기기만 빠진다 (뒤로 키와 같다)
                followState == FollowState.SELECTING -> cancelMeasureSelection()
                metronome.isRunning || followState != FollowState.OFF -> stopMetronome()
                else -> return failed("멈출 연주가 없어요")
            }
            return CommandOutcome.Done
        }
    }

    /** [target] 쪽이 화면에 올 때까지(넘김 렌더 · 애니메이션) 기다린다 — [PAGE_SETTLE_TIMEOUT_MS] 넘으면 그냥 간다 */
    private suspend fun awaitPageIndex(target: Int) {
        kotlinx.coroutines.withTimeoutOrNull(PAGE_SETTLE_TIMEOUT_MS) {
            while (pageIndex != target || isAnimating) kotlinx.coroutines.delay(30)
        }
    }

    /** 이 악보의 시작 가능 마디 — 분석이 아직이면 불러온다. 그사이 다른 곡으로 넘어갔으면 null */
    private suspend fun startableMeasuresNow(fileId: String): List<ScoreMeasure>? {
        val cached = if (scoreMeasuresFileId == fileId) scoreMeasures else null
        val measures = cached ?: withContext(Dispatchers.IO) { measuresForView(fileId, File(pdfFilePath)) }
        if (currentPdfFileId != fileId) return null
        if (cached == null && measures != null) {
            scoreMeasures = measures
            scoreMeasuresFileId = fileId
        }
        return ScoreFollower.startableMeasures(measures.orEmpty())
    }

    private sealed class MeasureTarget {
        data class Ready(val fileId: String, val startable: List<ScoreMeasure>) : MeasureTarget()
        class Unavailable(val outcome: CommandOutcome) : MeasureTarget()
    }

    /** 마디 명령을 받을 수 있으면 파일과 시작 가능 마디, 아니면 실패 이유 */
    private suspend fun followableMeasures(): MeasureTarget {
        if (collaborationMode == CollaborationMode.PERFORMER) return MeasureTarget.Unavailable(failed("마디는 지휘자가 정해요"))
        val fileId = currentPdfFileId ?: return MeasureTarget.Unavailable(failed("악보를 아직 여는 중이에요"))
        val startable = startableMeasuresNow(fileId) ?: return MeasureTarget.Unavailable(failed("다른 곡으로 넘어갔어요"))
        if (startable.isEmpty()) {
            return MeasureTarget.Unavailable(failed("이 악보는 마디 · 박자표를 읽지 못해 마디로 갈 수 없어요"))
        }
        return MeasureTarget.Ready(fileId, startable)
    }

    private fun noSuchMeasure(what: String, startable: List<ScoreMeasure>) =
        failed("${what}가 없어요 (${startable.minOf { it.measureNumber }} ~ ${startable.maxOf { it.measureNumber }})")

    /** 이 파일의 메트로놈 설정(없으면 악보 박자표)을 엔진에 — 그사이 다른 곡으로 넘어갔으면 false */
    private suspend fun loadSavedMetronomeSettings(fileId: String?): Boolean {
        val settings = metronomeSettingsFor(fileId)
        if (currentPdfFileId != fileId) return false
        metronome.bpm = settings.bpm
        metronome.timeSignature = settings.meter
        metronome.dottedBeat = settings.dotted
        metronome.soundEnabled = preferences.getBoolean(PREF_METRONOME_SOUND, true)
        metronome.volume = preferences.getFloat(PREF_METRONOME_VOLUME, 0.6f)
        return true
    }

    /**
     * 시작 가능 마디 중 [pick] 이 고른 마디에서 바로 악보 연동을 시작한다 (마디 고르기 없이). [what] 은 없을 때 알릴 이름("57마디")
     */
    private suspend fun followFrom(what: String, pick: (List<ScoreMeasure>) -> ScoreMeasure?): CommandOutcome {
        val (fileId, startable) = when (val target = followableMeasures()) {
            is MeasureTarget.Unavailable -> return target.outcome
            is MeasureTarget.Ready -> target
        }
        val start = pick(startable) ?: return noSuchMeasure(what, startable)
        return startFollowingAt(fileId, startable, start)
    }

    /**
     * [start] 에서 악보 연동을 바로 시작한다. 엔진에는 이 파일의 설정(방금 바꾼 템포 포함)을 넣는다 — ↑ 메뉴 "시작"과 같다.
     * 이 곡의 시작 마디를 고르는 중이었으면 멈추지 않고 그대로 시작한다 — OK · 마디 탭과 같다(지휘자면 고르던 합주 진행을 이어 간다)
     */
    private suspend fun startFollowingAt(fileId: String, startable: List<ScoreMeasure>, start: ScoreMeasure): CommandOutcome {
        val selectingHere = followState == FollowState.SELECTING && followFileId == fileId
        if (metronome.isRunning || (followState != FollowState.OFF && !selectingHere)) stopMetronome()
        if (!loadSavedMetronomeSettings(fileId)) return failed("다른 곡으로 넘어갔어요")
        followMeasures = startable
        followFileId = fileId
        cursorIndex = startable.indexOf(start)
        ensureMeasureVisible(start)
        startFollowing()
        return CommandOutcome.Done
    }

    /** 파트 보기를 [target] 보표로 (null = 전체 악보) — 저장하고 파일을 다시 연다. 다음 명령이 새 화면에서 돌도록 다 열릴 때까지 기다린다 */
    private suspend fun applyPartStaves(fileId: String, target: Set<Int>?): CommandOutcome {
        if (target == partViewStaves) return CommandOutcome.Done
        if (metronome.isRunning || followState != FollowState.OFF) stopMetronome()
        withContext(Dispatchers.IO) { musicRepository.setPartStavesForFile(fileId, target) }
        if (currentPdfFileId != fileId) return failed("다른 곡으로 넘어갔어요")
        val shown = kotlinx.coroutines.CompletableDeferred<Unit>()
        fileShownSignal = shown
        loadFile(pdfFilePath, pdfFileName)
        kotlinx.coroutines.withTimeoutOrNull(FILE_RELOAD_TIMEOUT_MS) { shown.await() }
            ?: return failed("파트 보기로 다시 여는 데 너무 오래 걸려요")
        return CommandOutcome.Done
    }

    /**
     * 명령을 듣기 전에 (🎙, P12 §4.2): 메트로놈 정지 — 합주 연주자면 이 기기만 빠진다. 시작 마디를 고르는 중이면 그대로 둔다
     * ("57마디" → "시작", #084)
     */
    private fun stopForCommand() {
        if (ensembleRole == EnsembleRole.FOLLOWING) {
            detachFromEnsemble()
        } else if (metronome.isRunning || (followState != FollowState.OFF && followState != FollowState.SELECTING)) {
            stopMetronome()
        }
    }

    // ── 🎙 음성 명령 (P12 2단계) ─────────────────────────────────────────

    private var voiceListener: VoiceListener? = null
    private var voiceButtonReady = false
    private val hideVoiceStatus = Runnable { binding.voiceStatus.visibility = View.GONE }
    private val stopVoiceListening = Runnable { voiceListener?.stop() }
    /** 🎙 를 누른 때 — 짧게 누르기(정지)와 말하기를 가른다 */
    private var voicePressedAtMs = 0L
    private fun voiceHeldLongEnough() = SystemClock.uptimeMillis() - voicePressedAtMs >= VOICE_TAP_MS
    /** 누른 채로 [VOICE_TAP_MS] 가 지나서야 "듣는 중"을 띄운다 — 짧게 눌러 정지할 때 글자가 번쩍이지 않게 */
    private val showPushListening = Runnable { if (voicePushToTalk && binding.voiceButton.isActivated) showVoiceStatus("🎙 듣는 중…") }

    // 👂 계속 듣기 (#085): 🎙 를 누르지 않아도 듣기를 끝없이 다시 걸고, 호출어("메이트") 뒤의 말만 명령으로 실행한다
    /** 화면이 앞에 있다 — onResume ~ onPause */
    private var voiceResumed = false
    /** 지금 듣기가 🎙 단추로 시작한 것인가 (아니면 👂 계속 듣기) */
    private var voicePushToTalk = false
    /** "메이트"만 들린 때 — 그 뒤 [WAKE_WINDOW_MS] 동안은 호출어 없이 받는다("메이트" … "57마디부터") */
    private var wakeHeardAtMs = 0L
    private var alwaysErrorStreak = 0
    private val restartAlwaysListening = Runnable { startAlwaysListening() }

    /** 설정 → 앱 정보 → 🎙 음성 명령을 켰고, TV 가 아니고, 마이크와 음성 인식 서비스가 있으면 단추를 보인다. 🎤 추적 중에는 흐리게(§4.1) */
    private fun refreshVoiceButton() {
        val show = preferences.getBoolean(PREF_VOICE_COMMANDS, false) && micFollowCapable() && VoiceListener.isAvailable(this)
        binding.voiceButton.visibility = if (show) View.VISIBLE else View.GONE
        binding.voiceButton.alpha = if (micFollower != null) 0.4f else 1f
        // 👂 = 계속 듣는 중 (단추는 그대로 누른 채 말하기로도 쓴다)
        binding.voiceButton.text = if (voiceAlwaysOn()) "👂" else "🎙"
        prefetchVoiceStaffNames()
        if (show && !voiceButtonReady) {
            voiceButtonReady = true
            binding.voiceButton.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> onVoicePress()
                    android.view.MotionEvent.ACTION_UP -> { v.performClick(); onVoiceRelease() }
                    android.view.MotionEvent.ACTION_CANCEL -> onVoiceRelease()
                }
                true
            }
        }
        if (alwaysListenAllowed()) {
            scheduleAlwaysListening(0)
            muteRecognizerBeep(true)
        } else {
            stopAlwaysListening()
            muteRecognizerBeep(false)
        }
    }

    /** 알림 소리를 끄지 못했다고 이 화면에서 이미 알렸다 */
    private var beepMuteWarned = false

    /** 👂 계속 듣기 동안 음성 서비스의 시작음(알림 채널)을 끈다 — 몇 초마다 딸깍거리지 않게 (#092) */
    private fun muteRecognizerBeep(mute: Boolean) {
        if (com.mrgq.pdfviewer.voice.NotificationMute.set(this, mute) || !mute || beepMuteWarned) return
        beepMuteWarned = true
        showVoiceStatus("👂 이 기기에서는 알림 소리를 끌 수 없어 듣기를 다시 걸 때 딸깍 소리가 날 수 있어요", VOICE_STATUS_MS)
    }

    /** 👂 계속 듣기를 설정에서 켰고, 지금 쓸 수 있는 기능인가([VOICE_ALWAYS_AVAILABLE]) */
    private fun voiceAlwaysOn(): Boolean = VOICE_ALWAYS_AVAILABLE && preferences.getBoolean(PREF_VOICE_ALWAYS, false)

    /** 👂 계속 듣기를 켰고, 화면이 앞에 있고, 🎙 단추가 보이고, 🎤 연주 추적이 마이크를 쓰지 않을 때(준비 중도 아닐 때) */
    private fun alwaysListenAllowed(): Boolean =
        voiceResumed && voiceAlwaysOn() &&
            binding.voiceButton.visibility == View.VISIBLE && micFollower == null && !micFollowStarting

    private fun scheduleAlwaysListening(delayMs: Long = ALWAYS_RESTART_MS) {
        binding.voiceButton.removeCallbacks(restartAlwaysListening)
        if (alwaysListenAllowed()) binding.voiceButton.postDelayed(restartAlwaysListening, delayMs)
    }

    private fun startAlwaysListening() {
        binding.voiceButton.removeCallbacks(restartAlwaysListening)
        if (!alwaysListenAllowed()) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return showVoiceStatus("👂 계속 들으려면 🎙 를 한 번 눌러 마이크 권한을 주세요", VOICE_STATUS_MS)
        }
        val listener = voiceListener ?: VoiceListener(this, voiceCallback).also { voiceListener = it }
        if (listener.isActive) return
        voicePushToTalk = false
        listener.start(voiceHints() + wakeWords(), patientEnd = true)
    }

    /** 👂 계속 듣기를 접는다 — 🎙 단추로 듣는 중이면 그대로 둔다 */
    private fun stopAlwaysListening() {
        binding.voiceButton.removeCallbacks(restartAlwaysListening)
        if (!voicePushToTalk) voiceListener?.takeIf { it.isActive }?.cancel()
    }

    /** 설정의 호출어 (#091) */
    private fun wakeWords(): List<String> = WakeWord.parseSetting(preferences.getString(PREF_VOICE_WAKE_WORDS, null))

    private fun awaitingCommand(): Boolean = wakeHeardAtMs > 0 && SystemClock.uptimeMillis() - wakeHeardAtMs < WAKE_WINDOW_MS

    /** 👂 계속 듣기의 오류 — 조용함 · 못 알아들음은 바로 다시, 그 밖은 점점 길게 기다렸다 다시. 마이크 권한이 없으면 멈춘다 */
    private fun onAlwaysListeningError(error: Int) {
        when (error) {
            android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                alwaysErrorStreak = 0
                scheduleAlwaysListening()
            }
            android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                showVoiceStatus("👂 마이크 권한이 없어 계속 듣기를 멈췄어요", VOICE_STATUS_MS)
            else -> {
                alwaysErrorStreak++
                val delayMs = (ALWAYS_RESTART_MS shl alwaysErrorStreak.coerceAtMost(6)).coerceAtMost(ALWAYS_BACKOFF_MAX_MS)
                Log.i("VoiceCommand", "👂 계속 듣기 오류 $error (${alwaysErrorStreak}번째) — ${delayMs}ms 뒤 다시")
                if (alwaysErrorStreak == 3) showVoiceStatus("👂 음성 인식이 잘 안 돼요 (오류 $error) — 다시 시도하는 중", VOICE_STATUS_MS)
                scheduleAlwaysListening(delayMs)
            }
        }
    }

    private fun showVoiceStatus(text: String?, hideAfterMs: Long = 0) {
        binding.voiceStatus.removeCallbacks(hideVoiceStatus)
        if (text == null) {
            binding.voiceStatus.visibility = View.GONE
            return
        }
        binding.voiceStatus.text = text
        binding.voiceStatus.visibility = View.VISIBLE
        if (hideAfterMs > 0) binding.voiceStatus.postDelayed(hideVoiceStatus, hideAfterMs)
    }

    /** 누르는 순간: 메트로놈 정지(§4.2) → 듣기 시작. 짧게 눌렀다 떼면 정지만 ([onVoiceRelease]) */
    private fun onVoicePress() {
        binding.voiceButton.removeCallbacks(stopVoiceListening)
        binding.voiceButton.removeCallbacks(restartAlwaysListening)
        binding.voiceButton.removeCallbacks(showPushListening)
        if (micFollower != null) return showVoiceStatus("🎤 연주 추적을 멈춘 뒤에 말하세요", VOICE_STATUS_MS)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_VOICE)
            return
        }
        stopForCommand()
        val listener = voiceListener ?: VoiceListener(this, voiceCallback).also { voiceListener = it }
        if (listener.isActive) listener.cancel() // 👂 계속 듣는 중이었으면 접고 단추로
        voicePushToTalk = true
        voicePressedAtMs = SystemClock.uptimeMillis()
        binding.voiceButton.isActivated = true
        showVoiceStatus(null)
        binding.voiceButton.postDelayed(showPushListening, VOICE_TAP_MS)
        listener.start(voiceHints())
    }

    /**
     * 손을 뗐다 — [VOICE_RELEASE_TAIL_MS] 더 들은 뒤 지금까지 말한 것으로 결과를 낸다(서비스가 먼저 끝냈으면 이미 결과가 왔다).
     * 말을 끝내자마자 떼면 마지막 음절이 아직 들어오는 중이다
     */
    private fun onVoiceRelease() {
        binding.voiceButton.isActivated = false
        binding.voiceButton.removeCallbacks(showPushListening)
        val listener = voiceListener ?: return
        if (!listener.isActive || !voicePushToTalk) return
        // 짧게 눌렀다 뗐다 = 정지 단추 — 누르는 순간 이미 멈췄다. 듣기를 접고 아무것도 띄우지 않는다
        if (!voiceHeldLongEnough()) {
            voicePushToTalk = false
            listener.cancel()
            showVoiceStatus(null)
            scheduleAlwaysListening()
            return
        }
        showVoiceStatus("🎙 알아듣는 중…")
        binding.voiceButton.postDelayed(stopVoiceListening, VOICE_RELEASE_TAIL_MS)
    }

    /** 이 악보에서 나올 말 — 인식이 이쪽으로 기울게 (Android 13+). 사람 이름 보표는 부를 말(성 뺀 이름 포함, #094) */
    private fun voiceHints(): List<String> =
        VoiceLexicon.INSTRUMENT_LABELS.values.toList() +
            musicXml?.takeIf { musicXmlFileId == currentPdfFileId }?.parts?.map { it.name }.orEmpty() +
            PartNames.hints(cachedVoiceStaffNames())

    /** 음성 명령용 보표 목록 캐시 — (파일, 파트). 악보 분석은 DB 캐시라 파일마다 한 번 읽는다 (#094) */
    private var voiceStaffParts: Pair<String, List<com.mrgq.pdfviewer.score.ScorePart>>? = null

    /** 이 악보의 보표 이름(PDF, 없으면 MusicXML) — 사람 이름 보표를 말로 부를 수 있게. 못 읽으면 빈 목록 */
    private suspend fun voiceStaffNames(): List<String> {
        val fileId = currentPdfFileId ?: return emptyList()
        if (voiceStaffParts?.first != fileId) {
            val staves = withContext(Dispatchers.IO) {
                try { musicRepository.getOrAnalyzeScoreStaves(fileId, File(pdfFilePath)) } catch (e: Exception) { null }
            }
            val parts = (staves?.let { ScoreParts.of(it) } as? ScoreParts.Result.Parts)?.parts.orEmpty()
            if (currentPdfFileId == fileId) voiceStaffParts = fileId to parts
        }
        return cachedVoiceStaffNames()
    }

    /** 🎙 가 보이면 보표 이름을 미리 읽어 둔다 — 첫 듣기부터 사람 이름이 인식 힌트에 들어가게 */
    private fun prefetchVoiceStaffNames() {
        if (binding.voiceButton.visibility != View.VISIBLE) return
        val fileId = currentPdfFileId ?: return
        if (voiceStaffParts?.first != fileId) lifecycleScope.launch { voiceStaffNames() }
    }

    /** 이미 읽어 둔 보표 이름만 (기다리지 않는다) — 듣기 시작할 때의 힌트용 */
    private fun cachedVoiceStaffNames(): List<String> {
        val parts = voiceStaffParts?.takeIf { it.first == currentPdfFileId }?.second ?: return emptyList()
        return parts.mapNotNull { knownPartName(it, parts.size) }
    }

    private val voiceCallback = object : VoiceListener.Callback {
        override fun onListening() {
            if (voicePushToTalk && voiceHeldLongEnough()) showVoiceStatus("🎙 듣는 중…")
        }

        override fun onPartial(text: String) {
            if (voicePushToTalk) return showVoiceStatus("🎙 $text")
            // 👂 호출어가 들렸을 때만 띄운다 — 방 안의 다른 말은 보이지 않는다
            val command = WakeWord.commandAfter(text, wakeWords()) ?: text.takeIf { awaitingCommand() } ?: return
            showVoiceStatus("👂 $command")
        }

        override fun onResults(candidates: List<String>) {
            if (voicePushToTalk) {
                voicePushToTalk = false
                binding.voiceButton.isActivated = false
                return runVoiceCandidates(candidates, candidates, alwaysListening = false)
            }
            alwaysErrorStreak = 0
            val awaiting = awaitingCommand()
            val words = wakeWords()
            val commands = candidates.mapNotNull { WakeWord.commandAfter(it, words) ?: it.takeIf { awaiting } }
            when {
                commands.isEmpty() -> scheduleAlwaysListening() // 호출어 없는 말 — 흘려듣는다(기록하지 않는다)
                commands.all { it.isBlank() } -> {
                    // "메이트"만 — 이어지는 말을 호출어 없이 받는다. 곧바로 다시 들어야 이어 말한 첫머리를 놓치지 않는다 (#091)
                    Log.i("VoiceCommand", "👂 호출어만: $candidates")
                    wakeHeardAtMs = SystemClock.uptimeMillis()
                    showVoiceStatus("👂 네? — 명령을 말하세요", WAKE_WINDOW_MS)
                    scheduleAlwaysListening(0)
                }
                else -> {
                    wakeHeardAtMs = 0L
                    runVoiceCandidates(commands.filter { it.isNotBlank() }, candidates, alwaysListening = true)
                }
            }
        }

        override fun onError(error: Int, message: String?) {
            if (!voicePushToTalk) return onAlwaysListeningError(error)
            voicePushToTalk = false
            binding.voiceButton.isActivated = false
            showVoiceStatus(message?.let { "🎙 $it" }, VOICE_STATUS_MS)
            scheduleAlwaysListening()
        }
    }

    /**
     * 후보들 → 명령 실행 → 결과 표시 · 기록. 끝나면 👂 계속 듣기를 다시 건다. [heard] 는 기록할 원래 후보(👂 면 호출어 포함),
     * [candidates] 는 명령으로 읽을 말(👂 면 호출어 뒤)
     */
    private fun runVoiceCandidates(candidates: List<String>, heard: List<String>, alwaysListening: Boolean) {
        val icon = if (alwaysListening) "👂" else "🎙"
        lifecycleScope.launch {
            val best = CommandParser.parseBest(candidates, voiceStaffNames())
            if (best == null) {
                showVoiceStatus(if (alwaysListening) "👂 못 알아들었어요" else "🎙 못 알아들었어요 — 누른 채로 말하세요", VOICE_STATUS_MS)
                logVoiceCommand(heard, null, null, alwaysListening)
                scheduleAlwaysListening()
                return@launch
            }
            val (text, parsed) = best
            showVoiceStatus("$icon “$text”")
            val report = runCommand(parsed)
            showVoiceStatus("$icon “$text”\n" + (if (report.ok) "✓ " else "✗ ") + report.message, VOICE_STATUS_MS)
            logVoiceCommand(heard, text, report, alwaysListening)
            scheduleAlwaysListening()
        }
    }

    /**
     * 음성 명령 기록 (P12 §8 — 오인식을 모아 정규화 · 규칙을 늘린다): `files/voice/commands.jsonl` 한 줄에 후보들 · 고른 말 · 결과.
     * 기기 밖으로 보내지 않는다. 1 MB 를 넘으면 뒤 절반만 남긴다
     */
    private fun logVoiceCommand(candidates: List<String>, chosen: String?, report: VoiceCommandRunner.Report?, alwaysListening: Boolean) {
        val line = org.json.JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("mode", if (alwaysListening) "always" else "button")
            put("pdf", pdfFileName)
            put("candidates", org.json.JSONArray(candidates))
            put("chosen", chosen ?: org.json.JSONObject.NULL)
            put("ok", report?.ok ?: false)
            put("message", report?.message ?: org.json.JSONObject.NULL)
            put("app_version", BuildConfig.VERSION_NAME)
        }.toString()
        Log.i("VoiceCommand", line)
        val file = File(File(getExternalFilesDir(null), "voice"), "commands.jsonl")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                if (file.length() > VOICE_LOG_MAX_BYTES) {
                    val lines = file.readLines()
                    file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
                }
                file.appendText(line + "\n")
            } catch (e: java.io.IOException) {
                Log.w("VoiceCommand", "기록 실패", e)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 일시정지 중에 메뉴·설정 대화상자를 모두 닫으면(악보 화면이 포커스를 되찾으면) 다음 동작을 고르게 한다 (#053).
        // 메뉴에서 다른 대화상자로 넘어가는 사이 잠깐 포커스가 돌아올 수 있어 조금 기다렸다가 다시 확인한다
        if (hasFocus && followState == FollowState.PAUSED && ensembleRole != EnsembleRole.FOLLOWING) {
            binding.root.removeCallbacks(pausedMenuCheck)
            binding.root.postDelayed(pausedMenuCheck, PAUSED_MENU_DELAY_MS)
        }
    }

    private fun moveCursor(delta: Int) {
        if (followMeasures.isEmpty()) return
        cursorIndex = (cursorIndex + delta).coerceIn(0, followMeasures.lastIndex)
        ensureMeasureVisible(followMeasures[cursorIndex])
        refreshScoreOverlay()
        broadcastConductorCursor()
    }

    /** 위/아래 — 이전/다음 줄(시스템)의 첫 마디 */
    private fun moveCursorLine(direction: Int) {
        val current = followMeasures.getOrNull(cursorIndex) ?: return
        fun line(m: ScoreMeasure) = m.pageIndex * 1000 + m.systemIndex
        val currentLine = line(current)
        val target = if (direction > 0) {
            followMeasures.indexOfFirst { line(it) > currentLine }
        } else {
            val previousLine = followMeasures.map(::line).filter { it < currentLine }.maxOrNull()
            if (previousLine == null) 0 else followMeasures.indexOfFirst { line(it) == previousLine }
        }
        if (target >= 0) moveCursor(target - cursorIndex)
    }

    private fun startFollowing() {
        val start = followMeasures.getOrNull(cursorIndex) ?: return cancelMeasureSelection()
        // 구간별 빠르기 (#057) — 첫 구간은 엔진의 템포 · 세는 단위, 둘째 구간부터는 이 파일의 구간 설정
        val tempo = Triple(metronome.bpm, metronome.dottedBeat, sectionSettingsFor(followFileId))
        val sections = TempoSections.forFollowing(followMeasures, tempo.first, tempo.second, tempo.third)
        val scoreFollower = ScoreFollower(followMeasures, start.measureNumber, metronome.dottedBeat, sections, countInBarsSetting())
        follower = scoreFollower
        followStartMeasure = start.measureNumber
        followFileId?.let { lastFollowStart = it to start.measureNumber }
        followTempo = tempo
        followTempoDeferredNotice = false
        followMeasure = start
        followInCountIn = true
        metronome.timeSignature = scoreFollower.startTimeSignature
        metronome.barPosition = { index -> scoreFollower.barPositionAt(index) }
        metronome.accompaniment = accompanimentFor(scoreFollower)
        metronome.accompanimentVolume = preferences.getFloat(PREF_ACCOMPANIMENT_VOLUME, 0.6f)
        followState = FollowState.PLAYING
        turnRequestedTo = -1
        startMetronome()
        refreshScoreOverlay()
    }

    /**
     * 악보 연동 중 빠르기를 바꿨다 (대화상자) — 구간이 있는 곡이면 새 구간 템포로 [follower] 를 다시 만들어 다음 박부터 쓴다 (#057).
     * 세는 단위가 바뀌어 마디 나눔(박 번호 → 마디)이 달라지면 지금 박이 가리키는 마디가 바뀌므로, 저장만 하고 다음 시작(이어서)부터.
     * 구간이 없는 곡은 여기서 할 일이 없다 — 엔진 템포가 곧 곡 템포다 (v0.2.4 와 같다). 합주 지휘자면 [retimeConductorRun] 이 이어서 알린다.
     */
    private fun refreshFollowTempo() {
        val current = follower ?: return
        val start = followStartMeasure ?: return
        if (followState != FollowState.PLAYING || ensembleRole == EnsembleRole.FOLLOWING) return
        val tempo = Triple(metronome.bpm, metronome.dottedBeat, sectionSettingsFor(followFileId))
        if (tempo == followTempo) return
        val sections = TempoSections.forFollowing(followMeasures, tempo.first, tempo.second, tempo.third)
        if (sections.isEmpty()) return
        val next = ScoreFollower(followMeasures, start, tempo.second, sections, current.countInBars)
        if (!next.sameBeatsAs(current)) {
            if (!followTempoDeferredNotice) {
                followTempoDeferredNotice = true
                Toast.makeText(this, "세는 단위를 바꾼 구간은 다음 시작(이어서)부터 적용됩니다", Toast.LENGTH_SHORT).show()
            }
            return
        }
        follower = next
        followTempo = tempo
        metronome.barPosition = { index -> next.barPositionAt(index) }
    }

    /** 악보 연동 예비박 마디 수 (설정, 기본 2 — #060) */
    private fun countInBarsSetting(): Int =
        preferences.getInt(PREF_METRONOME_COUNT_IN_BARS, DEFAULT_COUNT_IN_BARS).coerceIn(1, 2)

    /** 예비박 동안 화면 가운데에 남은 마디 수를 크게 (노래방처럼 2 → 1, #060). null 이면 숨긴다 */
    private fun showCountIn(barsLeft: Int?) {
        val view = binding.countInNumber
        if (barsLeft == null) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            return
        }
        val text = barsLeft.toString()
        if (view.text != text) view.text = text
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
    }

    /** 메트로놈 틱마다: 들리는 박을 악보 위치로 옮겨 현재 마디를 표시하고 필요하면 넘긴다. */
    private fun updateFollow(beat: Beat?) {
        val scoreFollower = follower ?: return
        if (beat == null) return
        val position = scoreFollower.positionAt(beat.index)
        when (position) {
            is ScoreFollower.Position.CountIn -> showCountIn(position.barsLeft)
            is ScoreFollower.Position.InMeasure -> {
                showCountIn(null)
                metronome.timeSignature = position.timeSignature
                if (followInCountIn || followMeasure?.measureNumber != position.measure.measureNumber) {
                    followInCountIn = false
                    followMeasure = position.measure
                    refreshScoreOverlay()
                    logEnsembleMeasure(beat, position.measure.measureNumber)
                }
                // 다음 마디가 다른 페이지에 있으면 이 마디가 끝나기 TURN_LEAD_BEATS 박 전에 미리 편다 —
                // 사람이 넘기듯 다음 페이지를 미리 보게 (사용자 요청: 1~2박 전). 짧은 마디는 마디 안에서만 당긴다
                val lead = minOf(TURN_LEAD_BEATS, position.beatsInMeasure - 1)
                val turnEarly = position.beatInMeasure >= position.beatsInMeasure - lead
                ensureMeasureVisible(if (turnEarly) position.next ?: position.measure else position.measure)
            }
            ScoreFollower.Position.Finished -> {
                showCountIn(null)
                // 연주자는 끝난 연주를 다시 받아도 또 따라가지 않게 (#055)
                if (ensembleRole == EnsembleRole.FOLLOWING) performerDetachedRunId = performerRun?.runId
                stopMetronome()
                Toast.makeText(this, "악보 끝까지 따라왔습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isMeasureVisible(measure: ScoreMeasure): Boolean =
        measure.pageIndex == pageIndex || (isTwoPageMode && measure.pageIndex == pageIndex + 1)

    /**
     * [measure] 가 화면에 없으면 그 페이지로 넘긴다. 두 페이지 모드는 지금 펼침의 짝(홀짝)을 유지한다.
     * 넘김 애니메이션 중이면 다음 틱에 다시 시도하고, 같은 요청은 1.5초 안에 반복하지 않는다(캐시 미스로 렌더가 늦을 때).
     */
    private fun ensureMeasureVisible(measure: ScoreMeasure) {
        if (isMeasureVisible(measure) || isAnimating || pageCount == 0) return
        val target = (if (isTwoPageMode) measure.pageIndex - Math.floorMod(measure.pageIndex - pageIndex, 2) else measure.pageIndex)
            .coerceIn(0, pageCount - 1)
        val now = SystemClock.uptimeMillis()
        if (target == turnRequestedTo && now - turnRequestedAtMs < 1500) return
        turnRequestedTo = target
        turnRequestedAtMs = now
        showPageWithAnimation(target, if (target > pageIndex) 1 else -1)
    }

    /**
     * 마디 박스 오버레이 켜기/끄기 — 악보 분석 결과를 눈으로 확인하는 용도 (전역 설정).
     * 켜면 현재 파일을 처음 한 번 분석해 DB 에 캐시한다 (ScoreLayoutStore).
     */
    private fun toggleScoreOverlay(announce: Boolean = true) {
        val enabled = !isScoreOverlayEnabled()
        preferences.edit().putBoolean("score_overlay_enabled", enabled).apply()
        if (announce) Toast.makeText(this, if (enabled) "마디 박스 표시 켜짐" else "마디 박스 표시 꺼짐", Toast.LENGTH_SHORT).show()
        refreshScoreOverlay()
    }

    /** 현재 화면(페이지·두 페이지 모드·클리핑·여백)에 맞춰 마디 박스를 다시 그린다. */
    /**
     * 휴대폰: 지금 쪽의 칸 — 마디 분석이 있으면 시스템마다 한 칸(이웃 시스템과의 빈칸 가운데까지 — 오선 밖 음표 · 셈여림 · 가사 몫),
     * 없으면 위 · 아래 절반. 화면에는 [chunkIndex] 칸부터 화면 높이에 들어가는 만큼 이어 보인다(보통 시스템 1 ~ 2개)
     */
    private fun computePhoneSpans(bitmapH: Int): List<ClosedFloatingPointRange<Float>> {
        val h = bitmapH.toFloat()
        val halves = listOf(0f..h / 2f, h / 2f..h)
        val fileId = currentPdfFileId ?: return halves
        if (scoreMeasuresFileId != fileId) {
            loadScoreMeasures(fileId, announce = false) // 오면 행렬을 다시 잡는다
            return halves
        }
        val systems = overlayBoxes(scoreMeasures.filter { it.pageIndex == pageIndex })
            .groupBy { it.systemIndex }.values
            .map { b -> b.minOf { it.top } to b.maxOf { it.bottom } }
            .sortedBy { it.first }
        if (systems.isEmpty()) return halves
        return systems.mapIndexed { i, (top, bottom) ->
            val above = if (i > 0) (top - systems[i - 1].second) / 2f else null
            val below = if (i < systems.lastIndex) (systems[i + 1].first - bottom) / 2f else null
            val pad = (bottom - top) * 0.25f // 시스템이 하나뿐인 쪽 · 맨 위 · 맨 아래
            (top - (above ?: below ?: pad).coerceAtLeast(0f)).coerceAtLeast(0f)..
                (bottom + (below ?: above ?: pad).coerceAtLeast(0f)).coerceAtMost(h)
        }
    }

    /** 휴대폰: 폭에 맞춘 배율에서 화면에 들어가는 비트맵 높이 */
    private var phoneRoom = 0f
    /** 지금 화면의 마지막 칸 */
    private var phoneWindowEnd = 0
    /** 지금 행렬 — 세로로 끌 때 여기서 옮긴다 */
    private var phoneScale = 1f
    private var phoneDx = 0f
    private var phoneDy = 0f

    /** [start] 칸부터 화면에 들어가는 마지막 칸 */
    private fun phoneEndFrom(start: Int): Int {
        var end = start
        while (end + 1 <= phoneChunks.lastIndex && phoneChunks[end + 1].endInclusive - phoneChunks[start].start <= phoneRoom) end++
        return end
    }

    /** [end] 칸에서 끝나는 화면의 첫 칸 — ← 로 앞 화면 */
    private fun phoneStartEndingAt(end: Int): Int {
        var start = end
        while (start - 1 >= 0 && phoneChunks[end].endInclusive - phoneChunks[start - 1].start <= phoneRoom) start--
        return start
    }

    /** 휴대폰: 지금 화면(칸 [chunkIndex] ~)을 화면 가운데에 — 폭에 맞추되 화면보다 높으면 줄인다. 정수 translate (#042) */
    private fun setPhoneChunkMatrix(bitmap: Bitmap, viewW: Int, viewH: Int) {
        phoneChunks = computePhoneSpans(bitmap.height)
        phoneRoom = viewH / minOf(1f, viewW.toFloat() / bitmap.width)
        if (chunkIndex == LAST_CHUNK) chunkIndex = phoneStartEndingAt(phoneChunks.lastIndex)
        chunkIndex = chunkIndex.coerceIn(0, phoneChunks.lastIndex)
        phoneWindowEnd = phoneEndFrom(chunkIndex)
        val top = phoneChunks[chunkIndex].start
        val spanH = phoneChunks[phoneWindowEnd].endInclusive - top
        val raw = minOf(viewW.toFloat() / bitmap.width, viewH / spanH)
        phoneScale = if (kotlin.math.abs(raw - 1f) < 0.005f) 1f else raw
        phoneDx = ((viewW - bitmap.width * phoneScale) / 2f).toInt().toFloat()
        phoneDy = ((viewH - spanH * phoneScale) / 2f - top * phoneScale).toInt().toFloat()
        applyPhoneMatrix(0f)
        updatePageInfo()
        binding.pdfView.post { refreshScoreOverlay() }
    }

    private fun applyPhoneMatrix(drag: Float) {
        binding.pdfView.imageMatrix = android.graphics.Matrix().apply {
            setScale(phoneScale, phoneScale)
            postTranslate(phoneDx, (phoneDy + drag).toInt().toFloat())
        }
    }

    /** 휴대폰: 같은 쪽의 [start] 칸부터 (다시 그리지 않고 행렬만) */
    private fun showChunk(start: Int) {
        if (!phoneView) return
        flushNotePen()
        chunkIndex = start
        val bitmap = (binding.pdfView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        setImageViewMatrix(bitmap)
    }

    /** 휴대폰: 따라가는 마디(악보 연동 · 마이크 · 시작 마디 커서)가 화면 밖이면 그 시스템이 맨 위에 오게 — 아래를 미리 본다 */
    private fun followChunk(focus: ScoreMeasure?) {
        if (!phoneView || phoneDrag != null || focus == null || focus.pageIndex != pageIndex || phoneChunks.isEmpty()) return
        val box = overlayBoxes(listOf(focus)).firstOrNull() ?: return
        val y = (box.top + box.bottom) / 2f
        val target = phoneChunks.indexOfFirst { y <= it.endInclusive }.takeIf { it >= 0 } ?: phoneChunks.lastIndex
        if (target < chunkIndex || target > phoneWindowEnd) showChunk(target)
    }

    // ── 휴대폰: 세로로 끌기 — 손을 떼면 가까운 시스템에 맞춘다 (사용자 요청 2026-10-06) ──
    /** 끄는 중이면 지금까지 옮긴 거리(px, 아래로 +). null = 끌지 않음 */
    private var phoneDrag: Float? = null

    private fun phoneDragBy(dy: Float) {
        val drag = (phoneDrag ?: 0f) + dy
        phoneDrag = drag
        applyPhoneMatrix(drag)
        refreshScoreOverlay()
    }

    /** 손을 뗐다 — 화면 맨 위에 가장 가까운 시스템으로. 쪽 끝을 넘어 화면 1/4 넘게 끌었으면 다음 · 앞 쪽 */
    private fun phoneDragEnd() {
        val drag = phoneDrag ?: return
        phoneDrag = null
        val pull = binding.pdfView.height * 0.25f
        val over = phoneOverscroll(drag)
        if (over < -pull || over > pull) {
            // 쪽 끝 화면에 맞춰 두고 → · ← 와 같게 넘긴다 (끝 · 처음 안내, 합주 · 마이크도 같은 길)
            showChunk(if (over < 0) phoneStartEndingAt(phoneChunks.lastIndex) else 0)
            pressKey(if (over < 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            return
        }
        val topY = phoneDragTopY(drag)
        val nearest = phoneChunks.indices.minByOrNull { kotlin.math.abs(phoneChunks[it].start - topY) } ?: 0
        // 마지막 화면보다 아래로는 가지 않는다 — 마지막 시스템이 화면 아래쪽에 남게
        showChunk(minOf(nearest, phoneStartEndingAt(phoneChunks.lastIndex)))
    }

    /** 끈 뒤 화면 맨 위의 비트맵 y */
    private fun phoneDragTopY(drag: Float) = -(phoneDy + drag) / phoneScale

    /** 쪽 끝을 넘어 끈 만큼(px) — 위로 넘으면 −, 아래로 넘으면 + */
    private fun phoneOverscroll(drag: Float): Float {
        val viewH = binding.pdfView.height
        val pageTop = phoneDy + drag + phoneChunks.first().start * phoneScale
        val pageBottom = phoneDy + drag + phoneChunks.last().endInclusive * phoneScale
        return when {
            pageTop > 0f -> pageTop
            pageBottom < viewH -> pageBottom - viewH
            else -> 0f
        }
    }

    private fun pressKey(keyCode: Int) {
        onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }

    private fun refreshScoreOverlay() {
        refreshNotes()
        refreshMicStartButton()
        val overlay = binding.scoreOverlay
        val fileId = currentPdfFileId
        val focus = when (followState) {
            FollowState.SELECTING -> followMeasures.getOrNull(cursorIndex)
            FollowState.PLAYING, FollowState.PAUSED -> followMeasure
            FollowState.OFF -> micFocus // 마이크 추적의 지금 마디 (P10)
        }
        followChunk(focus) // 휴대폰: 조각을 바꾸면 행렬을 다시 잡고 이 함수가 다시 불린다
        val showAll = isScoreOverlayEnabled()
        val micSystem = micSystemFrame()
        val systemFrame = micSystem ?: conductorSystemFrame()
        if (fileId == null || isAnimating || (!showAll && focus == null && systemFrame == null)) {
            overlay.clear()
            return
        }
        if (showAll && scoreMeasuresFileId != fileId) {
            overlay.clear()
            loadScoreMeasures(fileId)
            return
        }
        fun mapped(measures: List<ScoreMeasure>) = overlayBoxes(measures)
        // 커서 모양: 고르는 중 · 예비박 · 일시정지(여기서 이어진다). 연주 중인 마디만 노란 표시
        val style = if (followState == FollowState.OFF && micFocus != null) {
            ScoreOverlayView.FocusStyle.CURRENT
        } else if (followState != FollowState.PLAYING || followInCountIn) {
            ScoreOverlayView.FocusStyle.CURSOR
        } else {
            ScoreOverlayView.FocusStyle.CURRENT
        }
        overlay.show(
            boxes = if (showAll) mapped(scoreMeasures) else emptyList(),
            imageMatrix = binding.pdfView.imageMatrix,
            focus = focus?.let { mapped(listOf(it)).firstOrNull() },
            focusStyle = style,
            systemFrame = systemFrame,
            systemStrong = micSystem == null, // 연주자 화면이면 조금 더 진하게
        )
    }

    /** 마디 박스 — 지금 화면(쪽 · 두 쪽 모드 · 클리핑 · 여백)의 표시 비트맵 픽셀 좌표 */
    private fun overlayBoxes(measures: List<ScoreMeasure>) = ScoreOverlayGeometry.boxes(
        measures = measures,
        leftPageIndex = pageIndex,
        twoPageMode = isTwoPageMode,
        pageCount = pageCount,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        topClipping = currentTopClipping,
        bottomClipping = currentBottomClipping,
        centerPadding = currentCenterPadding,
    )

    /**
     * 화면 좌표 ([x], [y] — 창 기준)에 있는 시작 가능한 마디 (태블릿: 마디를 탭해 시작 마디 고르기). 없으면 -1.
     * 비트맵 → 화면은 ImageView 에 실제로 걸린 행렬 그대로 (오버레이와 같다)
     */
    private fun followMeasureAt(x: Float, y: Float): Int {
        val hit = measureAt(followMeasures, x, y) ?: return -1
        return followMeasures.indexOfFirst { it.measureNumber == hit.measureNumber }
    }

    /** 화면 (x, y) 에 있는 [measures] 의 마디 — 없으면 null */
    private fun measureAt(measures: List<ScoreMeasure>, x: Float, y: Float): ScoreMeasure? {
        val view = binding.pdfView
        val toView = android.graphics.Matrix(view.imageMatrix).apply {
            postTranslate((view.left + view.paddingLeft).toFloat(), (view.top + view.paddingTop).toFloat())
        }
        val rect = android.graphics.RectF()
        val hit = overlayBoxes(measures).firstOrNull { box ->
            rect.set(box.left, box.top, box.right, box.bottom)
            toView.mapRect(rect)
            rect.contains(x, y)
        } ?: return null
        return measures.firstOrNull { it.measureNumber == hit.measureNumber && it.systemIndex == hit.systemIndex }
            ?: measures.firstOrNull { it.measureNumber == hit.measureNumber }
    }

    /** [announce] = 마디 수 · 못 찾음 안내 (연주자 시스템 표시처럼 뒤에서 읽을 때는 조용히) */
    private fun loadScoreMeasures(fileId: String, announce: Boolean = true) {
        if (scoreLoadingFileId == fileId) return
        scoreLoadingFileId = fileId
        val file = File(pdfFilePath)
        lifecycleScope.launch {
            val measures = withContext(Dispatchers.IO) { measuresForView(fileId, file) }
            scoreLoadingFileId = null
            // null = 파일 전환 중이라 레코드와 경로가 어긋났다. 전환이 끝나면 showPage 가 다시 부른다
            if (measures == null || currentPdfFileId != fileId) return@launch
            scoreMeasures = measures
            scoreMeasuresFileId = fileId
            // 휴대폰: 시스템 조각으로 다시 나눈다
            if (phoneView) (binding.pdfView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { setImageViewMatrix(it) }
            if (!announce) {
                // 조용히
            } else if (measures.isEmpty()) {
                Toast.makeText(
                    this@PdfViewerActivity,
                    "마디를 찾지 못했습니다 (지원: Sibelius 에서 PDF 로 인쇄한 벡터 악보)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@PdfViewerActivity, "마디 ${measures.size}개", Toast.LENGTH_SHORT).show()
            }
            refreshScoreOverlay()
        }
    }

    /** 세로 화면인가 (태블릿 · 휴대폰) — 늘 한 쪽 */
    private fun phoneOrientationMode(): String =
        getSharedPreferences("pdf_viewer_prefs", MODE_PRIVATE).getString(PREF_PHONE_ORIENTATION, PHONE_ORIENTATION_AUTO)
            ?: PHONE_ORIENTATION_AUTO

    /** 휴대폰 회전 모드 → 화면 방향 요청. 기기 회전 = FULL_USER (기기의 자동 회전 · 회전 잠금을 따른다) */
    private fun phoneOrientationRequest(): Int = when (phoneOrientationMode()) {
        PHONE_ORIENTATION_LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        PHONE_ORIENTATION_PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    private fun phoneOrientationName(mode: String): String = when (mode) {
        PHONE_ORIENTATION_LANDSCAPE -> "가로"
        PHONE_ORIENTATION_PORTRAIT -> "세로"
        else -> "기기 회전"
    }

    /** 보기 메뉴의 회전 모드 (P13 3단계) — 고르면 바로 그 방향으로. 돌면 새 폭으로 다시 그린다(#090) */
    private fun showPhoneOrientationDialog() {
        val modes = listOf(PHONE_ORIENTATION_AUTO, PHONE_ORIENTATION_LANDSCAPE, PHONE_ORIENTATION_PORTRAIT)
        val current = modes.indexOf(phoneOrientationMode()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("회전")
            .setSingleChoiceItems(modes.map(::phoneOrientationName).toTypedArray(), current) { dialog, which ->
                preferences.edit().putString(PREF_PHONE_ORIENTATION, modes[which]).apply()
                dialog.dismiss()
                requestedOrientation = phoneOrientationRequest()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 휴대폰 렌더 폭 — 고정 방향이면 그 방향의 폭, 기기 회전이면 지금 폭 */
    private fun phoneRenderWidth(width: Int, height: Int): Int = when (phoneOrientationMode()) {
        PHONE_ORIENTATION_LANDSCAPE -> maxOf(width, height)
        PHONE_ORIENTATION_PORTRAIT -> minOf(width, height)
        else -> width
    }

    /** 휴대폰을 돌렸다 (#090) — 새 폭으로 쪽 캐시를 새로 만들고 같은 쪽 · 같은 칸을 다시 그린다 */
    private fun rebuildForPhoneWidth(width: Int) {
        Log.i("PdfViewerActivity", "📱 휴대폰 회전: 렌더 폭 $screenWidth → $width")
        screenWidth = width
        screenHeight = width * 3
        val renderer = pdfRenderer ?: return
        if (pageCount == 0) return
        pageCache?.destroy()
        pageCache = PageCache(renderer, screenWidth, screenHeight)
        registerSettingsCallback()
        pageCache?.updateSettings(isTwoPageMode, 1f) // 배율은 PageCache 가 쪽마다 PageGeometry 로 정한다
        // 뷰가 새 크기로 놓인 뒤에 — 같은 쪽을 다시 그리면 보던 칸(chunkIndex)은 그대로다
        binding.pdfView.post { showPage(pageIndex) }
    }

    private fun isPortraitScreen(): Boolean =
        // 지금 방향이 아니라 기기 종류로 — 화면이 아직 돌기 전일 수 있다. 태블릿은 늘 세로, TV 는 가로, 휴대폰은 회전 모드(#090)라 여기서는 아님
        !phoneView && !com.mrgq.pdfviewer.utils.DeviceForm.isTv(this)

    /**
     * 방향이 바뀌어도(태블릿 · 휴대폰이 열자마자 돈다) 다시 만들지 않는다 — 새 뷰 크기로 행렬만 다시 잡는다.
     * 휴대폰은 폭이 바뀌면(가로 ↔ 세로, #090) 새 폭으로 다시 그린다 — 1× 렌더 · 정수 좌표(#042)를 지키려고 줄여 보이지 않는다
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (phoneView) {
            val width = resources.displayMetrics.widthPixels
            if (width != screenWidth) return rebuildForPhoneWidth(width)
        }
        binding.pdfView.post {
            (binding.pdfView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { setImageViewMatrix(it) }
        }
    }

    /**
     * 보기 메뉴 (OK 길게 · 길게 누르기, 예전 "PDF 표시 옵션") — 화면에 무엇을 어떻게 보일지만 (P13). 소리 · 시간(메트로놈 · 반주 ·
     * 시작)은 ↑ 연주 메뉴에. 그 기기 · 역할에서 못 쓰는 줄은 숨긴다
     */
    private fun showPdfDisplayOptions() {
        val twoPageCapable = !phoneView && !isPortraitScreen()
        fun build() = ViewerMenus.view(ViewerMenus.ViewState(
            conductor = collaborationMode == CollaborationMode.CONDUCTOR,
            partViewName = partViewName,
            measureBoxes = isScoreOverlayEnabled(),
            notesVisible = notesVisible(),
            notesWritable = notesWritable(),
            noteEditMode = notePen.editMode,
            twoPageCapable = twoPageCapable,
            twoPage = isTwoPageMode,
            phoneRotation = if (phoneView) phoneOrientationName(phoneOrientationMode()) else null,
        ))
        // 시트의 스위치는 스스로 상태를 보이므로 알림(토스트)을 띄우지 않는다
        val announce = !sheetMenus()
        showViewerMenu(::build) { action ->
            when (action) {
                MenuAction.PART_VIEW -> showPartViewDialog()
                MenuAction.MEASURE_BOXES -> toggleScoreOverlay(announce)
                MenuAction.SHOW_NOTES -> toggleNotesVisible(announce)
                MenuAction.NOTE_MODE -> toggleNoteMode()
                // 두 쪽 모드를 바꾼 뒤 지금 쪽을 다시 그린다
                MenuAction.TWO_PAGE -> showTwoPageModeDialog { showPage(pageIndex) }
                MenuAction.CLIPPING -> showClippingDialog()
                // 휴대폰 회전 모드 (#090) — 앱 설정 → 표시 모드와 같은 값, 악보를 보다가 바로 바꾼다
                MenuAction.PHONE_ROTATION -> showPhoneOrientationDialog()
                else -> Unit
            }
        }
    }

    /**
     * 파트보 보기 (P07) — 이 파일에서 보여 줄 파트를 고른다. 고르면 저장하고 파일을 다시 연다(렌더러를 파트 PDF 로).
     * 합주 중에도 쓴다 (P07 4단계) — 주고받는 쪽 번호는 원본 기준으로 옮긴다([outgoingPage] · [incomingPageIndex]), 마디 신호는 마디 번호라 그대로.
     */
    private fun showPartViewDialog() {
        val fileId = currentPdfFileId ?: return
        if (collaborationMode == CollaborationMode.CONDUCTOR) {
            toast("지휘자는 총보만 봅니다 — 파트 보기는 연주자 · 혼자 연습에서")
            return
        }
        val source = File(pdfFilePath)
        Toast.makeText(this, "악보 분석 중…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val staves = withContext(Dispatchers.IO) { musicRepository.getOrAnalyzeScoreStaves(fileId, source) }
            if (currentPdfFileId != fileId) return@launch
            val result = staves?.let { ScoreParts.of(it) } ?: ScoreParts.Result.NoStaves
            val parts = when (result) {
                is ScoreParts.Result.Parts -> result.parts
                ScoreParts.Result.NoStaves -> return@launch toast("악보 구조를 찾지 못했습니다 (벡터 악보 PDF 만 지원)")
                ScoreParts.Result.SingleStaff -> return@launch toast("보표가 하나라 이미 파트보입니다")
                is ScoreParts.Result.VaryingStaves -> return@launch toast("시스템마다 보표 수가 달라 아직 파트 보기를 지원하지 않습니다")
            }
            // 여러 파트를 고를 수 있다 (사용자 요청 2026-09-28). 하나도 안 고르거나 모두 고르면 전체 악보
            val checked = BooleanArray(parts.size) { partViewStaves?.contains(parts[it].staffIndex) == true }
            fun apply(staves: Set<Int>?) {
                val target = staves?.takeIf { it.isNotEmpty() && it.size < parts.size }
                if (target == partViewStaves) return
                if (metronome.isRunning || followState != FollowState.OFF) stopMetronome()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { musicRepository.setPartStavesForFile(fileId, target) }
                    if (currentPdfFileId == fileId) loadFile(pdfFilePath, pdfFileName)
                }
            }
            AlertDialog.Builder(this@PdfViewerActivity)
                .setTitle("파트 보기 (여러 개 고를 수 있음)")
                .setMultiChoiceItems(parts.map { partName(it, parts.size) }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("보기") { _, _ -> apply(parts.filterIndexed { i, _ -> checked[i] }.map { it.staffIndex }.toSet()) }
                .setNeutralButton("전체 악보") { _, _ -> apply(null) }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    /**
     * 뷰어가 쓰는 마디 목록 — 파트 보기면 파트 PDF 좌표로 옮긴 것 (P07 3단계, [PartLayout.mapMeasures]). 마디 박스 · 악보 연동이 모두 이것을 쓴다.
     * 캐시([scoreMeasures])는 파트 보기가 바뀔 때마다 비운다 ([applyPartViewSelection])
     */
    private suspend fun measuresForView(fileId: String, file: File): List<ScoreMeasure>? {
        val measures = musicRepository.getOrAnalyzeScoreMeasures(fileId, file) ?: return null
        return partViewLayout?.mapMeasures(measures) ?: measures
    }

    /**
     * 파일을 열 때 (IO): PDF 옆 같은 이름의 `.musicxml`(ScoreMate 동기화 · P06 §11)을 읽어 둔다. 반주는 악보 연동을 시작할 때 만든다
     */
    private suspend fun loadMusicXml() {
        val fileId = currentPdfFileId
        val file = com.mrgq.pdfviewer.scoremate.ScoreMateSync.musicXmlFileOf(File(pdfFilePath))
        val score = if (fileId != null && file.isFile) com.mrgq.pdfviewer.score.MusicXmlReader.read(file) else null
        if (file.isFile && score == null) Log.w("PdfViewerActivity", "MusicXML 을 읽지 못함: ${file.name}")
        withContext(Dispatchers.Main) {
            musicXml = score
            musicXmlFileId = fileId
        }
        score?.let { Log.i("PdfViewerActivity", "MusicXML: ${file.name} — 파트 ${it.parts.map { p -> p.name }}, 마디 ${it.measures.size}") }
    }

    /**
     * 악보 연동을 시작할 때 반주 (P07 6단계). 꺼 두었거나 · 합주 중이거나 · MusicXML 이 없거나 · 마디 구조가 악보와 다르면 null.
     * 파트 보기면 **보고 있는 파트를 빼고** 나머지를, 전체 악보면 모든 파트를 들려준다.
     */
    private fun accompanimentFor(follower: ScoreFollower): com.mrgq.pdfviewer.metronome.Accompaniment? {
        val score = musicXml?.takeIf { musicXmlFileId == currentPdfFileId } ?: return null
        if (!preferences.getBoolean(PREF_ACCOMPANIMENT, true) || collaborationMode != CollaborationMode.NONE) return null
        val measures = if (scoreMeasuresFileId == currentPdfFileId && scoreMeasures.isNotEmpty()) scoreMeasures else followMeasures
        val match = com.mrgq.pdfviewer.metronome.MusicXmlMatch.check(score, measures)
        if (!match.ok) {
            toast("반주를 쓸 수 없습니다: ${match.reason}")
            return null
        }
        val mine = partViewStaves
        val play = score.parts.indices.filter { p -> mine == null || score.stavesOf(p).none { it in mine } }.toSet()
        if (play.isEmpty()) return null
        val accompaniment = com.mrgq.pdfviewer.metronome.Accompaniment.build(score, follower, play, match::measureNumberOf)
        Toast.makeText(this, "반주: ${play.joinToString { score.parts[it].name }}", Toast.LENGTH_SHORT).show()
        Log.i("PdfViewerActivity", "반주: 파트 $play, 음 ${accompaniment.size}개")
        return accompaniment
    }

    /**
     * 파트 보기에 보일 이름 — PDF 에서 읽은 이름, 못 읽었으면(보표 n) **MusicXML 의 파트 이름**(보표 수가 같을 때만 — 순서로 맞춘다).
     * 서버 인식이 읽은 이름이라 웹에서 고칠 수 있다(서버 0.9.8)
     */
    private fun partName(part: com.mrgq.pdfviewer.score.ScorePart, staffCount: Int): String =
        knownPartName(part, staffCount) ?: part.name

    /** 아는 파트 이름 — PDF 에서 읽었거나 MusicXML 에 있으면. 둘 다 없으면(보표 n) null */
    private fun knownPartName(part: com.mrgq.pdfviewer.score.ScorePart, staffCount: Int): String? {
        if (part.named) return part.name
        val score = musicXml?.takeIf { musicXmlFileId == currentPdfFileId && it.staffCount == staffCount } ?: return null
        return score.staffName(part.staffIndex)
    }

    /**
     * 반주 설정 — 켜기와 **소리 크기(메트로놈 클릭과 따로)**. 바꾸면 바로 들린다: 크기는 엔진에 곧장, 켜고 끄기는 악보 연동 중이면
     * 지금 박 시간표로 반주를 다시 만들거나 뺀다 (P07 6단계, 사용자 요청 2026-09-28)
     */
    private fun showAccompanimentDialog() {
        val score = musicXml?.takeIf { musicXmlFileId == currentPdfFileId } ?: return
        val density = resources.displayMetrics.density
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0)
        }
        val mine = partViewStaves
        val playing = score.parts.indices.filter { p -> mine == null || score.stavesOf(p).none { it in mine } }
        val info = android.widget.TextView(this).apply {
            text = if (playing.isEmpty()) "보고 있는 파트 말고는 들려줄 파트가 없습니다"
            else "악보 연동 메트로놈을 켜면 함께 연주: ${playing.joinToString { score.parts[it].name }}" +
                if (mine == null) "\n(파트 보기로 내 파트를 고르면 그 파트는 빼고 들려줍니다)" else ""
            textSize = 15f
        }
        val toggle = android.widget.CheckBox(this).apply {
            text = "반주 켜기"
            textSize = 18f
            isChecked = preferences.getBoolean(PREF_ACCOMPANIMENT, true)
        }
        val volumeLabel = android.widget.TextView(this).apply { textSize = 16f }
        val seek = android.widget.SeekBar(this).apply {
            max = 100
            keyProgressIncrement = 5
            progress = (preferences.getFloat(PREF_ACCOMPANIMENT_VOLUME, 0.6f) * 100).toInt()
        }
        fun showVolume() {
            volumeLabel.text = "반주 소리 크기: ${seek.progress}%  (메트로놈 클릭과 따로)"
        }
        showVolume()
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                showVolume()
                preferences.edit().putFloat(PREF_ACCOMPANIMENT_VOLUME, progress / 100f).apply()
                metronome.accompanimentVolume = progress / 100f
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Unit
        })
        toggle.setOnCheckedChangeListener { _, on ->
            preferences.edit().putBoolean(PREF_ACCOMPANIMENT, on).apply()
            val current = follower
            metronome.accompaniment = if (on && current != null && followState == FollowState.PLAYING) accompanimentFor(current) else null
        }
        layout.addView(info)
        layout.addView(toggle)
        layout.addView(volumeLabel)
        layout.addView(seek)
        AlertDialog.Builder(this)
            .setTitle("반주 (MusicXML)")
            .setView(layout)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * 파일을 열 때 (checkAndSetTwoPageMode, IO): 이 파일에 파트를 골라 두었으면 파트 PDF 를 준비해 렌더러를 바꾼다 (P07).
     * 고르지 않았거나 합주 중이거나 만들 수 없으면 원본 그대로 — [partViewStaves] 는 null.
     */
    private suspend fun applyPartViewSelection() {
        val fileId = currentPdfFileId
        // 지휘자는 늘 총보 (사용자 결정 2026-09-28) — 저장된 파트 선택은 두고 이번에만 무시
        val staves = if (fileId != null && collaborationMode != CollaborationMode.CONDUCTOR) {
            PartStaves.fromMask(musicRepository.getUserPreference(fileId)?.partStavesMask)
        } else {
            null
        }
        if (fileId == null || staves == null) {
            withContext(Dispatchers.Main) {
                partViewStaves = null
                partViewName = null
                partViewLayout = null
                clearMeasureCache()
            }
            return
        }
        val source = File(pdfFilePath)
        val prepared = try {
            preparePartPdf(fileId, source, staves)
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "파트보 만들기 실패: ${source.name} 보표 $staves", e)
            null
        }
        withContext(Dispatchers.Main) {
            clearMeasureCache()
            if (prepared == null) {
                partViewStaves = null
                partViewName = null
                partViewLayout = null
                toast("파트보를 만들지 못해 전체 악보로 봅니다")
                return@withContext
            }
            val (file, name, layout) = prepared
            pageCache?.destroy()
            try {
                currentPage?.close()
            } catch (e: Exception) {
                Log.w("PdfViewerActivity", "Current page already closed: ${e.message}")
            }
            currentPage = null
            try {
                pdfRenderer?.close()
            } catch (e: Exception) {
                Log.w("PdfViewerActivity", "PdfRenderer already closed: ${e.message}")
            }
            pdfRenderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            pageCount = pdfRenderer?.pageCount ?: 0
            pageCache = PageCache(pdfRenderer!!, screenWidth, screenHeight)
            registerSettingsCallback()
            partViewStaves = staves
            partViewName = name
            partViewLayout = layout
            // 조각은 이미 잘려 있다 — 위/아래 클리핑은 쓰지 않는다 (저장된 값은 전체 악보용으로 그대로 둔다)
            currentTopClipping = 0f
            currentBottomClipping = 0f
            Log.i("PdfViewerActivity", "파트 보기: ${source.name} — $name, ${pageCount}쪽")
        }
    }

    /**
     * 합주로 보낼 쪽 번호 (1부터) — 파트 보기면 파트 쪽 [pageIndex] 의 **원본 쪽**으로 (P07 4단계). 총보를 보는 연주자와 섞여도 맞게,
     * 합주 신호는 늘 원본 쪽 기준이다
     */
    private fun outgoingPage(pageIndex: Int): Int =
        (partViewLayout?.sourcePageFor(pageIndex) ?: pageIndex) + 1

    /** 합주로 받은 쪽 번호(1부터, 원본 기준) → 이 화면의 쪽 순번(0부터). 파트 보기면 그 원본 쪽이 놓인 파트 쪽. 두 쪽 모드면 짝의 시작 */
    private fun incomingPageIndex(page: Int): Int {
        val source = page - 1
        val index = partViewLayout?.dstPageForSource(source.coerceAtLeast(0)) ?: source
        return pairStart(index.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }

    /** 두 쪽 모드면 [index] 가 든 짝의 첫 쪽 (1-2 · 3-4 … 의 왼쪽). 한 쪽 모드면 그대로 */
    private fun pairStart(index: Int): Int = if (isTwoPageMode && index > 0) (index / 2) * 2 else index

    /** 마디 캐시를 비운다 — 파트 보기가 바뀌면 같은 파일이라도 마디 좌표가 다르다 */
    private fun clearMeasureCache() {
        scoreMeasures = emptyList()
        scoreMeasuresFileId = null
        followMeasures = emptyList()
        followFileId = null
    }

    /** 파트 PDF (캐시에 없으면 만든다) · 파트 이름들 · 배치. 파트를 가를 수 없는 악보거나 고른 보표가 없으면 null */
    private suspend fun preparePartPdf(fileId: String, source: File, selected: Set<Int>): Triple<File, String, PartLayout>? {
        val staves = musicRepository.getOrAnalyzeScoreStaves(fileId, source) ?: return null
        val parts = (ScoreParts.of(staves) as? ScoreParts.Result.Parts)?.parts?.filter { it.staffIndex in selected } ?: return null
        if (parts.isEmpty()) return null
        val total = (ScoreParts.of(staves) as? ScoreParts.Result.Parts)?.parts?.size ?: parts.size
        val name = parts.joinToString(" + ") { partName(it, total) }
        // 악보 왼쪽 보표 앞에 쓸 이름 — 아는 것만 (모르면 Pt. n)
        val staffNames = parts.mapNotNull { part -> knownPartName(part, total)?.let { part.staffIndex to it } }.toMap()
        val out = PartPdfBuilder.cacheFile(cacheDir, fileId, source, selected, staffNames)
        val layoutFile = PartPdfBuilder.layoutFile(out)
        // 만들어 둔 것이 있으면 그 배치 그대로 (넓히기에 쪽 경로를 다시 읽지 않게)
        if (out.isFile && layoutFile.isFile) {
            PartLayout.decode(layoutFile.readText())?.let { return Triple(out, name, it) }
        }
        withContext(Dispatchers.Main) { Toast.makeText(this@PdfViewerActivity, "파트보 만드는 중…", Toast.LENGTH_SHORT).show() }
        val measures = musicRepository.getOrAnalyzeScoreMeasures(fileId, source) ?: return null
        // 가운데선을 걸친 슬러 · 빔 · 덧줄 음을 소속에 따라 넓혀 자르려고 쪽 경로를 읽는다 (PartClip)
        val boxes = com.mrgq.pdfviewer.score.ScoreLayoutAnalyzer.pathBoxes(source, measures.map { it.pageIndex }.toSet())
        val layout = PartLayout.build(staves, measures, selected, boxes) ?: return null
        PartPdfBuilder.build(source, layout, out, staffNames)
        layoutFile.writeText(PartLayout.encode(layout))
        PartPdfBuilder.prune(out, fileId, selected)
        return Triple(out, name, layout)
    }

    /**
     * PageCache에 설정 콜백을 등록하는 헬퍼 함수
     */
    private fun registerSettingsCallback() {
        pageCache?.setDisplaySettingsProvider { 
            Log.d("PdfViewerActivity", "설정 콜백 호출: 위 ${(currentTopClipping * 100).toInt()}%, 아래 ${(currentBottomClipping * 100).toInt()}%, 여백 ${(currentCenterPadding * 100).toInt()}%")
            Triple(currentTopClipping, currentBottomClipping, currentCenterPadding) 
        }
    }
    
    /**
     * 위/아래 자르기 · 가운데 여백 (보기 메뉴, P13 3단계) — 위 · 아래는 0~15%, 가운데 여백(0~15%)은 두 쪽일 때만.
     * 슬라이더를 움직이면 0.2초 뒤 미리 보이고, 적용하면 이 파일에 저장, 취소하면 되돌린다
     */
    private fun showClippingDialog() {
        val withPadding = isTwoPageMode
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }
        fun slider(label: String, value: Float): Pair<android.widget.TextView, android.widget.SeekBar> {
            val text = android.widget.TextView(this).apply {
                text = "$label: ${(value * 100).toInt()}%"
                textSize = 16f
                setPadding(0, 0, 0, 10)
            }
            val seek = android.widget.SeekBar(this).apply {
                max = 15 // 0-15%
                progress = (value * 100).toInt()
                setPadding(0, 0, 0, 30)
            }
            dialogView.addView(text)
            dialogView.addView(seek)
            return text to seek
        }
        val (topLabel, topSeekBar) = slider("위쪽 자르기", currentTopClipping)
        val (bottomLabel, bottomSeekBar) = slider("아래쪽 자르기", currentBottomClipping)
        val padding = if (withPadding) slider("가운데 여백 (두 쪽 사이)", currentCenterPadding) else null

        val originalTop = currentTopClipping
        val originalBottom = currentBottomClipping
        val originalPadding = currentCenterPadding

        // 임시로 적용해 다시 그린다 (저장하지 않음)
        val applyPreview = {
            currentTopClipping = topSeekBar.progress / 100f
            currentBottomClipping = bottomSeekBar.progress / 100f
            padding?.let { currentCenterPadding = it.second.progress / 100f }
            forceDirectRendering = true
            showPage(pageIndex)
        }
        val restore = {
            currentTopClipping = originalTop
            currentBottomClipping = originalBottom
            currentCenterPadding = originalPadding
            forceDirectRendering = true
            showPage(pageIndex)
        }

        val quickButtonsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }
        quickButtonsLayout.addView(android.widget.Button(this).apply {
            text = "초기화"
            setOnClickListener {
                topSeekBar.progress = 0
                bottomSeekBar.progress = 0
                padding?.second?.progress = 0
                applyPreview()
            }
        })
        quickButtonsLayout.addView(android.widget.Button(this).apply {
            text = "위/아래 5%"
            setOnClickListener {
                topSeekBar.progress = 5
                bottomSeekBar.progress = 5
                applyPreview()
            }
        })
        dialogView.addView(quickButtonsLayout)
        dialogView.addView(android.widget.TextView(this).apply {
            text = "움직이면 바로 미리 보입니다 · 이 파일에 저장"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            gravity = android.view.Gravity.CENTER
        })

        val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val previewRunnable = Runnable { applyPreview() }
        fun android.widget.SeekBar.follow(label: android.widget.TextView, name: String) {
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$name: $progress%"
                    if (fromUser) {
                        previewHandler.removeCallbacks(previewRunnable)
                        previewHandler.postDelayed(previewRunnable, 200)
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        topSeekBar.follow(topLabel, "위쪽 자르기")
        bottomSeekBar.follow(bottomLabel, "아래쪽 자르기")
        padding?.let { (label, seek) -> seek.follow(label, "가운데 여백 (두 쪽 사이)") }

        AlertDialog.Builder(this)
            .setTitle(if (withPadding) "자르기 · 여백" else "위/아래 자르기")
            .setView(dialogView)
            .setPositiveButton("적용") { _, _ ->
                previewHandler.removeCallbacks(previewRunnable)
                val top = topSeekBar.progress / 100f
                val bottom = bottomSeekBar.progress / 100f
                val center = padding?.second?.progress?.div(100f) ?: originalPadding
                saveClippingSettings(top, bottom, center)
                currentTopClipping = top
                currentBottomClipping = bottom
                currentCenterPadding = center
                registerSettingsCallback()
                forceDirectRendering = true
                showPage(pageIndex)
                // 적용 뒤 보기 메뉴로 돌아간다
                showPdfDisplayOptions()
            }
            .setNegativeButton("취소") { _, _ ->
                previewHandler.removeCallbacks(previewRunnable)
                restore()
            }
            .setOnCancelListener {
                previewHandler.removeCallbacks(previewRunnable)
                restore()
            }
            .show()
    }
    
    /**
     * 데이터베이스에서 표시 설정 로드 (동기)
     */
    private suspend fun loadDisplaySettingsSync() = withContext(Dispatchers.IO) {
        Log.d("PdfViewerActivity", "=== loadDisplaySettingsSync 시작 ===")
        Log.d("PdfViewerActivity", "currentPdfFileId: $currentPdfFileId")
        
        currentPdfFileId?.let { fileId ->
            try {
                val prefs = musicRepository.getUserPreference(fileId)
                Log.d("PdfViewerActivity", "DB에서 조회된 설정: $prefs")
                
                if (prefs != null) {
                    withContext(Dispatchers.Main) {
                        currentTopClipping = prefs.topClippingPercent
                        currentBottomClipping = prefs.bottomClippingPercent
                        currentCenterPadding = prefs.centerPadding
                        
                        // DisplayMode도 로드하여 적용
                        currentDisplayMode = prefs.displayMode
                        Log.d("PdfViewerActivity", "=== 데이터베이스에서 설정 로드 완료 ===")
                        Log.d("PdfViewerActivity", "로드된 설정: 위 클리핑 ${currentTopClipping * 100}%, 아래 클리핑 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px, 표시 모드 $currentDisplayMode")
                    }
                } else {
                    // 기본값 사용
                    withContext(Dispatchers.Main) {
                        currentTopClipping = 0f
                        currentBottomClipping = 0f
                        currentCenterPadding = 0f
                        currentDisplayMode = DisplayMode.AUTO
                    }
                    Log.d("PdfViewerActivity", "표시 설정 없음, 기본값 사용")
                }
            } catch (e: Exception) {
                Log.e("PdfViewerActivity", "표시 설정 로드 실패", e) // 기본값으로 폴백
                withContext(Dispatchers.Main) {
                    currentTopClipping = 0f
                    currentBottomClipping = 0f
                    currentCenterPadding = 0f
                    currentDisplayMode = DisplayMode.AUTO
                }
            }
        } ?: run {
            Log.w("PdfViewerActivity", "currentPdfFileId가 null이어서 설정을 로드할 수 없습니다")
        }
    }
    
    /**
     * 데이터베이스에서 표시 설정 로드 (비동기)
     */
    private fun loadDisplaySettings() {
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = musicRepository.getUserPreference(fileId)
                    if (prefs != null) {
                        currentTopClipping = prefs.topClippingPercent
                        currentBottomClipping = prefs.bottomClippingPercent
                        currentCenterPadding = prefs.centerPadding
                        currentDisplayMode = prefs.displayMode
                        Log.d("PdfViewerActivity", "표시 설정 로드 완료: 위 클리핑 ${currentTopClipping * 100}%, 아래 클리핑 ${currentBottomClipping * 100}%, 여백 ${currentCenterPadding}px, 표시 모드 $currentDisplayMode")
                    } else {
                        // 기본값 사용
                        currentTopClipping = 0f
                        currentBottomClipping = 0f
                        currentCenterPadding = 0f
                        currentDisplayMode = DisplayMode.AUTO
                        Log.d("PdfViewerActivity", "표시 설정 없음, 기본값 사용")
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "표시 설정 로드 실패", e)
                    // 기본값으로 폴백
                    currentTopClipping = 0f
                    currentBottomClipping = 0f
                    currentCenterPadding = 0f
                    currentDisplayMode = DisplayMode.AUTO
                }
            }
        }
    }
    
    /** 위/아래 자르기 · 가운데 여백을 이 파일의 표시 설정에 함께 저장 — 따로 저장하면 읽고 쓰는 사이에 서로 덮는다 */
    private fun saveClippingSettings(topPercent: Float, bottomPercent: Float, centerPadding: Float) {
        currentPdfFileId?.let { fileId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val currentPrefs = musicRepository.getUserPreference(fileId)
                    val updatedPrefs = currentPrefs?.copy(
                        topClippingPercent = topPercent,
                        bottomClippingPercent = bottomPercent,
                        centerPadding = centerPadding,
                        updatedAt = System.currentTimeMillis()
                    ) ?: UserPreference(
                        pdfFileId = fileId,
                        displayMode = DisplayMode.AUTO,
                        topClippingPercent = topPercent,
                        bottomClippingPercent = bottomPercent,
                        centerPadding = centerPadding
                    )
                    musicRepository.insertUserPreference(updatedPrefs)
                    Log.d("PdfViewerActivity", "자르기 · 여백 저장: 위 ${topPercent * 100}%, 아래 ${bottomPercent * 100}%, 여백 ${centerPadding * 100}%")
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "자르기 · 여백 저장 실패", e)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        voiceResumed = false
        commitPendingNote() // 그리던 메모 묶음은 확정해 저장 (P11)
        com.mrgq.pdfviewer.ensemble.VersionNotice.detach()
        
        // Clear collaboration callbacks when PdfViewerActivity goes to background
        // This allows MainActivity to properly register its callbacks when it resumes
        Log.d("PdfViewerActivity", "onPause - 협업 콜백 정리")

        // 연주 화면을 벗어나면 메트로놈을 멈춘다 (홈·다른 앱으로 가도 계속 울리지 않게). 마이크 추적도 마무리
        stopMetronome()
        stopMicFollow()
        binding.voiceButton.removeCallbacks(stopVoiceListening)
        binding.voiceButton.removeCallbacks(restartAlwaysListening)
        voiceListener?.cancel()
        muteRecognizerBeep(false) // 화면을 나가면 알림 소리를 되돌린다 (#092)
        voicePushToTalk = false
        wakeHeardAtMs = 0L
        binding.voiceButton.isActivated = false
        showVoiceStatus(null)
    }

    override fun onResume() {
        super.onResume()
        com.mrgq.pdfviewer.ensemble.VersionNotice.attach(this) // 합주 상대와 버전이 다르면 대화상자로 (#061)
        // 합주 연주자: 돌아오면 지휘자 연주에 다시 합류 (#055)
        performerRun?.let { onEnsembleRunReceived(it, retry = true) }
        voiceResumed = true
        refreshVoiceButton() // 설정에서 켜고 돌아왔을 수 있다 — 👂 계속 듣기도 여기서 건다
        if (phoneView) requestedOrientation = phoneOrientationRequest() // 회전 모드를 바꾸고 돌아왔을 수 있다 (#090)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.destroy()
        voiceListener = null
        com.mrgq.pdfviewer.voice.NotificationMute.set(this, false)
        
        // Clean up long press handler
        longPressHandler.removeCallbacks(longPressRunnable)

        // Phase 0: 예약된 동기 페이지 넘김 취소
        pendingSyncTurn?.let { syncTurnHandler.removeCallbacks(it) }
        pendingSyncTurn = null
        binding.root.removeCallbacks(ensembleRebroadcast)

        // Clean up collaboration resources
        // Note: 전역 매니저가 관리하므로 여기서 서버를 중지하지 않음
        // Note: 전역 매니저가 관리하므로 여기서 클라이언트를 끊지 않음
        
        // Clean up page cache
        try {
            pageCache?.destroy()
            Log.d("PdfViewerActivity", "PageCache 정리 완료")
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error destroying pageCache in onDestroy: ${e.message}")
        }
        
        try {
            currentPage?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing currentPage in onDestroy: ${e.message}")
        }
        try {
            pdfRenderer?.close()
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error closing pdfRenderer in onDestroy: ${e.message}")
        }
        
        // Clean up sound pool
        try {
            soundPool?.release()
            soundPool = null
            Log.d("PdfViewerActivity", "SoundPool 정리 완료")
        } catch (e: Exception) {
            Log.w("PdfViewerActivity", "Error releasing soundPool in onDestroy: ${e.message}")
        }
    }
    
    // ================ 사운드 이펙트 ================
    
    private fun initializeSoundPool() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            soundPool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(audioAttributes)
                .build()
            
            soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    soundsLoaded = true
                    Log.d("PdfViewerActivity", "Page turn sound loaded successfully")
                } else {
                    Log.e("PdfViewerActivity", "Failed to load page turn sound: $status")
                }
            }
            
            pageTurnSoundId = soundPool?.load(this, R.raw.page_turn, 1) ?: 0
            
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Error initializing sound pool: ${e.message}")
        }
    }
    
    private fun playPageTurnSound() {
        if (!isPageTurnSoundEnabled()) return
        
        try {
            soundPool?.let { pool ->
                if (soundsLoaded && pageTurnSoundId > 0) {
                    val volume = getPageTurnVolume()
                    pool.play(pageTurnSoundId, volume, volume, 1, 0, 1.0f)
                    Log.d("PdfViewerActivity", "Playing page turn sound at volume: $volume")
                }
            }
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "Error playing page turn sound: ${e.message}")
        }
    }
    
    private fun isPageTurnSoundEnabled(): Boolean {
        return preferences.getBoolean("page_turn_sound_enabled", true)
    }
    
    private fun getPageTurnVolume(): Float {
        return preferences.getFloat("page_turn_volume", 0.25f)
    }
    
    private fun isPageTurnAnimationEnabled(): Boolean {
        return preferences.getBoolean("page_turn_animation_enabled", true)
    }
    
    // ================ 페이지 전환 애니메이션 ================
    
    private var isAnimating = false
    
    /**
     * 애니메이션과 함께 페이지를 전환합니다.
     * @param index 이동할 페이지 인덱스
     * @param direction 애니메이션 방향 (1: 오른쪽으로 이동, -1: 왼쪽으로 이동)
     */
    private fun showPageWithAnimation(index: Int, direction: Int) {
        if (index < 0 || index >= pageCount || isAnimating) return
        flushNotePen()
        rollSpread = null
        halfPageShown = false
        cancelPendingRoll()
        
        Log.d("PdfViewerActivity", "showPageWithAnimation: index=$index, direction=$direction")
        
        // 애니메이션이 비활성화된 경우 기본 showPage 호출. 휴대폰 반 쪽 보기도 — 넘김 그림이 화면 높이 기준이다
        if (!isPageTurnAnimationEnabled() || phoneView) {
            showPage(index)
            return
        }
        
        // 캐시에서 대상 페이지 비트맵 가져오기
        val targetBitmap = if (isTwoPageMode) {
            if (index + 1 < pageCount) {
                val page1 = pageCache?.getPageImmediate(index)
                val page2 = pageCache?.getPageImmediate(index + 1)
                Log.d("PdfViewerActivity", "두 페이지 모드 캐시 확인: page${index}=${page1?.let { "${it.width}x${it.height}" } ?: "null"}, page${index + 1}=${page2?.let { "${it.width}x${it.height}" } ?: "null"}")
                if (page1 != null && page2 != null) {
                    val combined = combineTwoPagesUnified(page1, page2)
                    Log.d("PdfViewerActivity", "두 페이지 결합 결과: ${combined.width}x${combined.height}")
                    combined
                } else null
            } else {
                val page1 = pageCache?.getPageImmediate(index)
                Log.d("PdfViewerActivity", "마지막 페이지 캐시 확인: page${index}=${page1?.let { "${it.width}x${it.height}" } ?: "null"}")
                if (page1 != null) combineTwoPagesUnified(page1, null) else null
            }
        } else {
            val page = pageCache?.getPageImmediate(index)
            Log.d("PdfViewerActivity", "단일 페이지 캐시 확인: page${index}=${page?.let { "${it.width}x${it.height}" } ?: "null"}")
            page
        }
        
        if (targetBitmap != null) {
            // 캐시에 있는 경우 즉시 애니메이션 실행
            animatePageTransition(targetBitmap, direction, index)
        } else {
            // 캐시에 없는 경우 즉시 렌더링해서 애니메이션 실행
            Log.d("PdfViewerActivity", "Target page not in cache, rendering immediately for animation")
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val renderedBitmap = if (isTwoPageMode) {
                        if (index + 1 < pageCount) {
                            // 두 페이지 즉시 렌더링
                            val page1 = renderPageDirectly(index)
                            val page2 = renderPageDirectly(index + 1)
                            if (page1 != null && page2 != null) {
                                combineTwoPagesUnified(page1, page2)
                            } else null
                        } else {
                            // 마지막 페이지 즉시 렌더링
                            val page1 = renderPageDirectly(index)
                            if (page1 != null) combineTwoPagesUnified(page1, null) else null
                        }
                    } else {
                        // 단일 페이지 즉시 렌더링
                        renderPageDirectly(index)
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (renderedBitmap != null) {
                            Log.d("PdfViewerActivity", "즉시 렌더링 완료: ${renderedBitmap.width}x${renderedBitmap.height}")
                            animatePageTransition(renderedBitmap, direction, index)
                        } else {
                            Log.e("PdfViewerActivity", "즉시 렌더링 실패, 기본 showPage 호출")
                            showPage(index)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerActivity", "즉시 렌더링 중 오류: ${e.message}")
                    withContext(Dispatchers.Main) {
                        showPage(index)
                    }
                }
            }
        }
    }
    
    /**
     * 페이지를 즉시 렌더링합니다 (캐시 사용 안 함).
     *
     * isTwoPageMode 에 따라 단일 페이지 또는 두 페이지 모드 목표 크기로 렌더한다.
     * 결과는 combineTwoPagesUnified (두 페이지 모드) 또는 ImageView (단일 페이지 모드)
     * 가 추가 스케일 없이 그대로 사용할 수 있는 형태.
     */
    private fun renderPageDirectly(pageIndex: Int): Bitmap? {
        return try {
            if (pageIndex < 0 || pageIndex >= pageCount) {
                Log.e("PdfViewerActivity", "잘못된 페이지 인덱스: $pageIndex")
                return null
            }

            pdfRenderer?.let { renderer ->
                val page = renderer.openPage(pageIndex)
                try {
                    if (screenWidth <= 0 || screenHeight <= 0) {
                        Log.e("PdfViewerActivity", "화면 크기가 유효하지 않음: ${screenWidth}x${screenHeight}")
                        return null
                    }
                    if (isTwoPageMode) {
                        renderPageAtTwoPageTarget(page)
                    } else {
                        renderPageAtSinglePageTarget(page)
                    }
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            Log.e("PdfViewerActivity", "즉시 렌더링 중 오류: ${e.message}", e)
            null
        }
    }
    
    /**
     * 현재 페이지가 두 페이지 모드로 표시되는지 확인
     */
    private fun isCurrentPageTwoPageMode(): Boolean {
        return isTwoPageMode && pageIndex % 2 == 0
    }
    
    /**
     * 타겟 페이지가 두 페이지 모드로 표시되는지 확인
     */
    private fun isTargetPageTwoPageMode(targetIndex: Int): Boolean {
        return isTwoPageMode && targetIndex % 2 == 0
    }
    
    /**
     * 실제 페이지 전환 애니메이션을 실행합니다.
     */
    private fun animatePageTransition(targetBitmap: Bitmap, direction: Int, targetIndex: Int) {
        if (isAnimating) return
        
        isAnimating = true
        
        // ====================[ 핵심 수정 사항 ]====================
        // 누락된 상태 업데이트와 브로드캐스트를 애니메이션 시작 전에 추가
        pageIndex = targetIndex
        updatePageInfo()
        broadcastCollaborationPageChange(targetIndex)
        // ==========================================================
        
        // 페이지 넘기기 사운드 재생
        playPageTurnSound()
        
        // 다음 페이지 ImageView 설정
        binding.pdfViewNext.setImageBitmap(targetBitmap)
        
        // 현재 페이지가 두 페이지 모드인지 확인
        val currentIsTwoPage = isCurrentPageTwoPageMode()
        // 타겟 페이지가 두 페이지 모드인지 확인
        val targetIsTwoPage = isTargetPageTwoPageMode(targetIndex)
        
        Log.d("PdfViewerActivity", "애니메이션 시작: 현재 두페이지=$currentIsTwoPage, 타겟 두페이지=$targetIsTwoPage")
        Log.d("PdfViewerActivity", "타겟 비트맵 크기: ${targetBitmap.width}x${targetBitmap.height}")
        
        // setImageViewMatrix를 사용하여 일관된 방식으로 매트릭스 설정
        setImageViewMatrix(targetBitmap, binding.pdfViewNext)
        
        binding.pdfViewNext.visibility = View.VISIBLE
        
        // 화면 너비 계산
        val screenWidth = binding.pdfView.width.toFloat()
        
        // 애니메이션 시작 위치 설정
        if (direction > 0) {
            // 오른쪽 페이지로 이동: 새 페이지는 오른쪽에서 슬라이드 인
            binding.pdfViewNext.translationX = screenWidth
        } else {
            // 왼쪽 페이지로 이동: 새 페이지는 왼쪽에서 슬라이드 인
            binding.pdfViewNext.translationX = -screenWidth
        }
        
        // 애니메이션 실행
        val currentPageAnimator = ObjectAnimator.ofFloat(
            binding.pdfView, 
            "translationX", 
            0f, 
            if (direction > 0) -screenWidth else screenWidth
        )
        
        val nextPageAnimator = ObjectAnimator.ofFloat(
            binding.pdfViewNext, 
            "translationX", 
            binding.pdfViewNext.translationX, 
            0f
        )
        
        // 애니메이션 설정 (사용자 설정 적용)
        val animationDuration = preferences.getLong("page_animation_duration", 350L)
        
        if (animationDuration == 0L) {
            // 애니메이션 없이 즉시 전환
            binding.pdfView.setImageBitmap(targetBitmap)
            setImageViewMatrix(targetBitmap, binding.pdfView)
            binding.pdfView.translationX = 0f
            binding.pdfViewNext.visibility = View.GONE
            
            // 상태 업데이트는 이미 위에서 완료됨 (pageIndex, updatePageInfo, broadcastCollaboration)
            binding.loadingProgress.visibility = View.GONE
            saveLastPageNumber(targetIndex + 1)
            
            // 페이지 정보 표시
            if (preferences.getBoolean("show_page_info", true)) {
                binding.pageInfo.animate().alpha(1f).duration = 200
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.pageInfo.animate().alpha(0f).duration = 1000
                }, 1500)
            }
            
            isAnimating = false
            return
        }
        
        currentPageAnimator.duration = animationDuration
        nextPageAnimator.duration = animationDuration
        
        val interpolator = DecelerateInterpolator(1.8f)
        currentPageAnimator.interpolator = interpolator
        nextPageAnimator.interpolator = interpolator
        
        // 애니메이션 완료 리스너
        nextPageAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // 애니메이션 완료 후에는 UI 정리만 수행
                // (pageIndex 업데이트, updatePageInfo, broadcastCollaboration은 이미 위에서 완료됨)
                binding.pdfView.setImageBitmap(targetBitmap)
                setImageViewMatrix(targetBitmap, binding.pdfView)
                binding.pdfView.translationX = 0f
                binding.pdfViewNext.visibility = View.GONE
                binding.pdfViewNext.translationX = 0f
                
                // 로딩 프로그레스 숨기기
                binding.loadingProgress.visibility = View.GONE
                
                // 마지막 페이지 번호 저장
                saveLastPageNumber(targetIndex + 1)
                
                // 페이지 정보 잠시 표시 (설정이 활성화된 경우)
                if (preferences.getBoolean("show_page_info", true)) {
                    binding.pageInfo.animate().alpha(1f).duration = 200
                    Handler(Looper.getMainLooper()).postDelayed({
                        binding.pageInfo.animate().alpha(0f).duration = 1000
                    }, 1500)
                }
                
                isAnimating = false
                refreshNotes() // 넘기는 동안 숨겼던 메모 (P11)
                Log.d("PdfViewerActivity", "Page transition animation completed")
            }
        })
        
        // 애니메이션 시작
        currentPageAnimator.start()
        nextPageAnimator.start()
    }
    
    /**
     * 특정 ImageView에 매트릭스 설정
     */
    private fun setImageViewMatrix(bitmap: Bitmap, imageView: android.widget.ImageView) {
        if (bitmap.isRecycled) return
        
        val matrix = android.graphics.Matrix()
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        val viewWidth = imageView.width
        val viewHeight = imageView.height
        
        if (viewWidth > 0 && viewHeight > 0) {
            val scaleX = viewWidth.toFloat() / imageWidth
            val scaleY = viewHeight.toFloat() / imageHeight
            val scale = minOf(scaleX, scaleY)
            
            matrix.setScale(scale, scale)
            
            val scaledWidth = imageWidth * scale
            val scaledHeight = imageHeight * scale
            val dx = (viewWidth - scaledWidth) / 2
            val dy = (viewHeight - scaledHeight) / 2
            
            matrix.postTranslate(dx, dy)
        }
        
        imageView.imageMatrix = matrix
        // 넘김 애니메이션 동안(pdfViewNext 준비)은 박스를 숨기고, 끝나서 pdfView 에 행렬이 잡히면 다시 그린다
        if (imageView === binding.pdfView) {
            binding.pdfView.post { refreshScoreOverlay() }
        } else {
            binding.scoreOverlay.clear()
        }
    }
    
    /**
     * 협업 모드에서 페이지 변경을 브로드캐스트합니다.
     * 중복 코드를 제거하고 일관된 로직을 제공합니다.
     */
    private fun broadcastCollaborationPageChange(pageIndex: Int) {
        val roll = micRollBroadcast // 마이크 추적이 넘긴 것 (P10 §3.2) — 한 번만
        micRollBroadcast = false
        // Phase 0: 동기 예약 넘김이 실행되는 중에는 이미 turn_at 과 함께 브로드캐스트했으므로 재전송 억제
        if (System.currentTimeMillis() < suppressBroadcastUntil) {
            Log.d("PdfViewerActivity", "🎵 동기 예약 넘김 실행 중 - 재브로드캐스트 억제")
            return
        }
        if (collaborationMode == CollaborationMode.CONDUCTOR && !isHandlingRemotePageChange) {
            val actualPageNumber = outgoingPage(pageIndex)
            Log.d("PdfViewerActivity", "🎵 지휘자 모드: 페이지 $actualPageNumber 브로드캐스트 중..." + if (roll) " (roll)" else "")
            globalCollaborationManager.broadcastPageChange(actualPageNumber, pdfFileName, roll = roll)
        }
    }

    /**
     * 합주(지휘자) 동기 페이지 넘김 (Phase 0, 설계: devlog P03).
     * 넘길 목표 절대 시각(turn_at = 지금 + lead)을 먼저 연주자에게 브로드캐스트하고,
     * 지휘자 자신도 그 시각에 넘긴다. 모든 기기가 같은 벽시계 시각에 동시에 넘어가게 한다.
     * (기기들이 네트워크 자동 시간으로 동기돼 있다는 전제 — 설계 P03 접근법 A)
     */
    private fun conductorScheduledTurn(targetIndex: Int, direction: Int) {
        val lead = syncTurnLeadMs()
        val turnAt = System.currentTimeMillis() + lead
        Log.d("PdfViewerActivity", "🎵 지휘자 동기 넘김 예약: page ${targetIndex + 1}, ${lead}ms 후 (turn_at=$turnAt)")
        // 1) 연주자에게 목표 시각 즉시 브로드캐스트
        globalCollaborationManager.broadcastPageChange(outgoingPage(targetIndex), pdfFileName, turnAt)
        // 2) 지휘자 자신도 같은 시각에 넘김 (이미 브로드캐스트했으므로 실행 시 재브로드캐스트 억제)
        pendingSyncTurn?.let { syncTurnHandler.removeCallbacks(it) }
        val runnable = Runnable {
            suppressBroadcastUntil = System.currentTimeMillis() + 2000L
            showPageWithAnimation(targetIndex, direction)
        }
        pendingSyncTurn = runnable
        syncTurnHandler.postDelayed(runnable, lead)
    }
    
    /**
     * 원격 페이지 변경을 처리하고 있는지 여부를 추적하는 플래그
     */
    private var isHandlingRemotePageChange = false
    
    /**
     * Check if input is currently blocked due to recent synchronization
     */
    private fun isInputBlocked(): Boolean {
        if (collaborationMode != CollaborationMode.PERFORMER) {
            return false // Only block input for performers
        }
        val inputBlockDuration = getInputBlockDuration()
        val timeSinceSync = System.currentTimeMillis() - lastSyncTime
        val isBlocked = timeSinceSync < inputBlockDuration
        if (isBlocked) {
            Log.d("PdfViewerActivity", "Input blocked for ${inputBlockDuration - timeSinceSync}ms more")
        }
        return isBlocked
    }
    
    /**
     * Show message when input is blocked
     */
    private fun showInputBlockedMessage() {
        val inputBlockDuration = getInputBlockDuration()
        val remainingTime = inputBlockDuration - (System.currentTimeMillis() - lastSyncTime)
        Toast.makeText(this, "동기화 중... ${remainingTime}ms 후 다시 시도하세요", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Update sync time when receiving remote page change
     */
    private fun updateSyncTime() {
        lastSyncTime = System.currentTimeMillis()
        Log.d("PdfViewerActivity", "Sync time updated for input blocking")
    }
}