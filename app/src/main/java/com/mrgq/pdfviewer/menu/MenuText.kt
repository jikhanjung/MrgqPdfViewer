package com.mrgq.pdfviewer.menu

import android.content.Context
import com.mrgq.pdfviewer.R

/** [Text] → 화면 글 (P18). 인자 중 [Text] 는 먼저 글로 바꾼다 */
fun Text.resolve(context: Context): String = when (this) {
    is Text.Plain -> value
    is Text.Res -> if (args.isEmpty()) {
        context.getString(id)
    } else {
        val resolved: Array<Any> = args.map { a -> if (a is Text) a.resolve(context) else (a ?: "null") }.toTypedArray()
        context.getString(id, *resolved)
    }
}

/** TV 목록 대화상자에 쓰는 한 줄 — 값이 있으면 "이름: 값" */
fun MenuItem.tvText(context: Context): String {
    val name = label.resolve(context)
    val v = value?.resolve(context) ?: return name
    return context.getString(R.string.viewer_menu_label_value, name, v)
}
