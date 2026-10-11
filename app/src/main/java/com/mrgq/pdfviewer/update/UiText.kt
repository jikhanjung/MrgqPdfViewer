package com.mrgq.pdfviewer.update

import android.content.Context
import androidx.annotation.StringRes

/**
 * 화면 글 — 리소스 키 + 인자 (P18). Android 에 의존하지 않는 코드(JVM 단위 테스트 대상 — 업데이트 · ScoreMate · 합주 버전)가
 * 글 대신 이것을 돌려주고, 화면이 [resolve] 한다. 인자가 [UiText] 면 그것도 풀어서 끼운다. 테스트는 키 · 인자로 단언한다.
 */
class UiText(@StringRes val id: Int, vararg args: Any) {
    val args: List<Any> = args.toList()

    fun resolve(context: Context): String =
        if (args.isEmpty()) context.getString(id)
        else context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())

    override fun equals(other: Any?): Boolean = other is UiText && other.id == id && other.args == args
    override fun hashCode(): Int = 31 * id + args.hashCode()
    /** 로그용 — 화면에는 [resolve] */
    override fun toString(): String = "UiText(0x${Integer.toHexString(id)}, $args)"
}
