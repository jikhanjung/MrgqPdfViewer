package com.mrgq.pdfviewer.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.mrgq.pdfviewer.R

/**
 * 🎙 누르는 동안 듣기 (P12 2단계) — 기기의 음성 인식 서비스(Android `SpeechRecognizer`, 보통 Google)를 쓴다. 기기 안 모델(Moonshine)
 * 대신 이것으로 정했다(사용자 결정 2026-10-09: APK 크기 · 모델 라이선스 없음, 한국어 품질). 대개 인터넷이 필요하다.
 *
 * [start] 로 듣기 시작, [stop] 으로 말 끝 — 결과는 [Callback.onResults] 로 후보 여럿(가능성 높은 순). 서비스가 먼저 말 끝을 알아채면
 * 손을 떼기 전에도 결과가 온다. 메인 스레드에서만 부른다.
 *
 * 인식 언어는 설정 "음성 명령 언어"([VoiceLanguage.current], P18) — 듣기를 시작할 때마다 읽는다. 안내 · 오류 글은 [context] 의 앱 언어로.
 * [context] 는 액티비티를 넘긴다(앱 언어가 걸린 Context — applicationContext 는 Android 12 이하에서 앱 언어를 모른다).
 */
class VoiceListener(private val context: Context, private val callback: Callback) {

    interface Callback {
        fun onListening()
        fun onPartial(text: String)
        fun onResults(candidates: List<String>)
        /** [error] = `SpeechRecognizer.ERROR_*`, [message] = 화면에 보일 말(null 이면 조용히) */
        fun onError(error: Int, message: String?)
    }

    private val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var active = false
    /** 이번 듣기에서 서비스가 준비됐다(onReadyForSpeech) / 손을 떼 끝내 달라고 했다 — 시작조차 못 한 실패를 가리려고 */
    private var ready = false
    private var stopping = false

    /** 마지막으로 듣기를 시작한 언어 — 결과를 같은 언어의 파서로 읽으려고 */
    var language: VoiceLanguage = VoiceLanguage.KOREAN
        private set

    /** [errorText] 의 "오류 (n)" 에 넣을 마지막 오류 번호 */
    private var lastError = 0

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
                callback.onListening()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.texts().firstOrNull()?.takeIf { it.isNotBlank() }?.let(callback::onPartial)
            }

            override fun onResults(results: Bundle?) {
                active = false
                callback.onResults(results.texts())
            }

            override fun onError(error: Int) {
                if (!active) return
                active = false
                lastError = error
                Log.i(TAG, "인식 오류 $error (준비 $ready, 끝냄 $stopping)")
                // ERROR_CLIENT 는 보통 듣기 전에 손을 뗀 것이라 조용히 넘긴다. 그런데 준비도 안 됐고 끝내 달라고 하지도 않았는데 오면
                // 서비스를 시작조차 못 한 것이다 — 기본 음성 입력이 비어 있으면 Android 가 곧바로 이 오류를 낸다(#084 끝)
                val message = if (error == SpeechRecognizer.ERROR_CLIENT && !ready && !stopping) R.string.voice_error_not_started else errorMessage(error)
                callback.onError(error, message?.let { errorText(it) })
            }
        })
    }

    val isActive: Boolean get() = active

    /**
     * [hints] = 이 악보에서 나올 말(파트 이름 등) — Android 13+ 에서 인식 쪽으로 기울인다.
     * [patientEnd] = 👂 계속 듣기 — "메이트, (쉼) 57마디부터"의 짧은 쉼에서 듣기를 끝내지 않게 말 끝 판단을 늦춰 달라고 한다
     * (서비스가 따르지 않을 수도 있다, #091).
     * [language] = 인식 언어 · 명령 문법 — 기본은 설정 "음성 명령 언어"(P18)
     */
    fun start(hints: List<String>, patientEnd: Boolean = false, language: VoiceLanguage = VoiceLanguage.current(context)) {
        this.language = language
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.recognizerTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (patientEnd) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PATIENT_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PATIENT_SILENCE_MS)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val base = if (language == VoiceLanguage.ENGLISH) EnglishCommandParser.BASE_HINTS else BASE_HINTS
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList((base + hints).distinct()))
            }
        }
        active = true
        ready = false
        stopping = false
        recognizer.startListening(intent)
    }

    /** 손을 뗐다 — 지금까지 말한 것으로 결과를 낸다 */
    fun stop() {
        if (!active) return
        stopping = true
        recognizer.stopListening()
    }

    fun cancel() {
        active = false
        recognizer.cancel()
    }

    fun destroy() {
        active = false
        recognizer.destroy()
    }

    /** 오류 키 → 화면 글 (언어 이름이 들어가는 키는 지금 인식 언어로) */
    private fun errorText(id: Int): String = when (id) {
        R.string.voice_error_language -> context.getString(
            id,
            context.getString(if (language == VoiceLanguage.ENGLISH) R.string.voice_language_name_english else R.string.voice_language_name_korean),
        )
        R.string.voice_error_other -> context.getString(id, lastError)
        else -> context.getString(id)
    }

    companion object {
        private const val TAG = "VoiceListener"
        private const val MAX_RESULTS = 5
        /** 👂 계속 듣기에서 말이 끝났다고 볼 조용함 — 호출어 뒤 숨 고르는 쉼(0.5 ~ 1초)을 넘기게 */
        private const val PATIENT_SILENCE_MS = 1500L

        /** 명령 낱말 — 숫자 · 단위가 맞게 들리도록 (한국어 — 인식 어휘라 옮기지 않는다. 영어는 [EnglishCommandParser.BASE_HINTS]) */
        private val BASE_HINTS = listOf(
            "마디", "마디부터", "템포", "쪽", "페이지", "처음부터", "다시", "이어서", "다음 쪽", "이전 쪽", "파트", "파트보", "보표", "총보", "레터", "시작",
        )

        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        /** 기기에 인식 서비스가 없을 때 깔 앱 — Google "음성 인식 및 합성"(Speech Recognition & Synthesis) */
        const val RECOGNIZER_PACKAGE = "com.google.android.tts"

        private fun Bundle?.texts(): List<String> =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

        /** 오류 → 화면 글 키. null = 조용히 넘긴다 (듣기 전에 손을 뗐을 때 등) */
        private fun errorMessage(error: Int): Int? = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_error_no_match
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> R.string.voice_error_network
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_error_permission
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
            // 중국판 롬 태블릿은 인식 앱(음성 인식 및 합성)에 마이크 권한이 없을 때도 이것이 왔다(#084)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_error_language
            SpeechRecognizer.ERROR_CLIENT -> null
            else -> R.string.voice_error_other
        }
    }
}
