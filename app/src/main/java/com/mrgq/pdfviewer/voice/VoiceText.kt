package com.mrgq.pdfviewer.voice

import android.content.Context
import androidx.annotation.StringRes

/**
 * 음성 명령이 화면에 보일 글 (P18) — `voice/` 는 JVM 단위 테스트 대상이라 글 대신 **리소스 키 + 인자**를 돌려주고, 화면이
 * [resolve] 한다. 인자에 [VoiceText] 가 있으면 먼저 풀어 넣는다("57마디부터 · ♩=72" = 둘을 [Joined] 로).
 *
 * - [Res] = `R.string.voice_…` + 인자(String · Int · VoiceText)
 * - [Plain] = 이미 사람이 읽을 글(악보의 보표 이름, 화면이 만들어 넘긴 실패 이유, 들린 말)
 * - [Joined] = 이어 붙이기 — 나눔표는 두 언어가 같다(" · ")
 */
sealed class VoiceText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : VoiceText()
    data class Plain(val text: String) : VoiceText()
    data class Joined(val parts: List<VoiceText>, val separator: String = " · ") : VoiceText()

    /** [lookup] = (키, 인자) → 글. 인자 없는 키는 빈 배열로 부른다 */
    fun resolve(lookup: (Int, Array<Any>) -> String): String = when (this) {
        is Res -> lookup(id, args.map { if (it is VoiceText) it.resolve(lookup) else it }.toTypedArray())
        is Plain -> text
        is Joined -> parts.joinToString(separator) { it.resolve(lookup) }
    }

    /** 화면 글로 — [context] 는 액티비티(앱 언어가 걸린 Context)를 넘긴다 */
    fun resolve(context: Context): String =
        resolve { id, args -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args) }

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): VoiceText = Res(id, args.toList())
    }
}
