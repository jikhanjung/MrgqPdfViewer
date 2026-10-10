package com.mrgq.pdfviewer.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 🎙 누르는 동안 듣기 (P12 2단계) — 기기의 음성 인식 서비스(Android `SpeechRecognizer`, 보통 Google)를 쓴다. 기기 안 모델(Moonshine)
 * 대신 이것으로 정했다(사용자 결정 2026-10-09: APK 크기 · 모델 라이선스 없음, 한국어 품질). 대개 인터넷이 필요하다.
 *
 * [start] 로 듣기 시작, [stop] 으로 말 끝 — 결과는 [Callback.onResults] 로 후보 여럿(가능성 높은 순). 서비스가 먼저 말 끝을 알아채면
 * 손을 떼기 전에도 결과가 온다. 메인 스레드에서만 부른다.
 */
class VoiceListener(context: Context, private val callback: Callback) {

    interface Callback {
        fun onListening()
        fun onPartial(text: String)
        fun onResults(candidates: List<String>)
        /** [error] = `SpeechRecognizer.ERROR_*`, [message] = 화면에 보일 말(null 이면 조용히) */
        fun onError(error: Int, message: String?)
    }

    private val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var active = false

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callback.onListening()
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
                Log.i(TAG, "인식 오류 $error")
                callback.onError(error, errorMessage(error))
            }
        })
    }

    val isActive: Boolean get() = active

    /**
     * [hints] = 이 악보에서 나올 말(파트 이름 등) — Android 13+ 에서 인식 쪽으로 기울인다.
     * [patientEnd] = 👂 계속 듣기 — "메이트, (쉼) 57마디부터"의 짧은 쉼에서 듣기를 끝내지 않게 말 끝 판단을 늦춰 달라고 한다
     * (서비스가 따르지 않을 수도 있다, #091)
     */
    fun start(hints: List<String>, patientEnd: Boolean = false) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (patientEnd) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PATIENT_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PATIENT_SILENCE_MS)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList((BASE_HINTS + hints).distinct()))
            }
        }
        active = true
        recognizer.startListening(intent)
    }

    /** 손을 뗐다 — 지금까지 말한 것으로 결과를 낸다 */
    fun stop() {
        if (active) recognizer.stopListening()
    }

    fun cancel() {
        active = false
        recognizer.cancel()
    }

    fun destroy() {
        active = false
        recognizer.destroy()
    }

    companion object {
        private const val TAG = "VoiceListener"
        private const val MAX_RESULTS = 5
        /** 👂 계속 듣기에서 말이 끝났다고 볼 조용함 — 호출어 뒤 숨 고르는 쉼(0.5 ~ 1초)을 넘기게 */
        private const val PATIENT_SILENCE_MS = 1500L

        /** 명령 낱말 — 숫자 · 단위가 맞게 들리도록 */
        private val BASE_HINTS = listOf(
            "마디", "마디부터", "템포", "쪽", "페이지", "처음부터", "다시", "이어서", "다음 쪽", "이전 쪽", "파트", "파트보", "보표", "총보", "레터", "시작",
        )

        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        private fun Bundle?.texts(): List<String> =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

        /** null = 조용히 넘긴다 (듣기 전에 손을 뗐을 때 등) */
        private fun errorMessage(error: Int): String? = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "못 알아들었어요 — 누른 채로 말하세요"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "음성 인식 서버에 닿지 않아요 — 인터넷 연결을 확인하세요"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 다른 곳에서 쓰이고 있어요"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "이 기기의 음성 인식이 한국어를 지원하지 않아요"
            SpeechRecognizer.ERROR_CLIENT -> null
            else -> "음성 인식 오류 ($error)"
        }
    }
}
