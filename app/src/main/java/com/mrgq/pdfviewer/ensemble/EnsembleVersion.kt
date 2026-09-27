package com.mrgq.pdfviewer.ensemble

import com.mrgq.pdfviewer.update.AppVersion

/**
 * 합주 연결 때 지휘자 · 연주자의 앱 버전 비교 (#061). 합주 메시지는 버전마다 필드가 늘어(구간별 빠르기 #057, 예비박 마디 수 #060)
 * 버전이 다르면 박 · 마디가 어긋날 수 있다 — 연결할 때 양쪽에 알려 같은 버전으로 맞추게 한다.
 *
 * v0.2.5 까지는 `client_connect.app_version` · `connect_response.server_version` 을 **"v0.1.5" 로 고정**해 보냈다 →
 * 그 값이나 값이 없으면 "v0.2.5 이하" 로 본다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object EnsembleVersion {

    /** 옛 버전이 고정해 보내던 값 */
    const val LEGACY_REPORTED = "v0.1.5"
    private const val LEGACY_LABEL = "v0.2.5 이하"

    /** 버전이 다르다. [theirsOlder] = 상대가 더 옛 버전 (판단할 수 없으면 null) */
    data class Mismatch(val mine: String, val theirs: String, val theirsOlder: Boolean?)

    /** 상대가 보낸 값을 보일 이름으로 */
    fun describe(reported: String?): String =
        if (reported.isNullOrBlank() || reported == LEGACY_REPORTED) LEGACY_LABEL else "v" + reported.removePrefix("v")

    /** 같으면 null */
    fun check(mine: String, theirs: String?): Mismatch? {
        val mineLabel = "v" + mine.removePrefix("v")
        if (theirs.isNullOrBlank() || theirs == LEGACY_REPORTED) return Mismatch(mineLabel, LEGACY_LABEL, theirsOlder = true)
        val a = AppVersion.parse(mine)
        val b = AppVersion.parse(theirs)
        if (a != null && b != null) {
            if (a.compareTo(b) == 0) return null
            return Mismatch(mineLabel, describe(theirs), theirsOlder = b < a)
        }
        return if (mine.removePrefix("v") == theirs.removePrefix("v")) null else Mismatch(mineLabel, describe(theirs), null)
    }

    /** 연주자 화면: 지휘자와 버전이 다르다 */
    fun performerMessage(m: Mismatch): String = when (m.theirsOlder) {
        true -> "지휘자 기기가 ${m.theirs} 입니다 (이 기기 ${m.mine}).\n지휘자 기기를 ${m.mine} 으로 업데이트하세요."
        false -> "이 기기가 ${m.mine} 입니다 (지휘자 ${m.theirs}).\n이 기기를 ${m.theirs} 로 업데이트하세요."
        null -> "지휘자 ${m.theirs} · 이 기기 ${m.mine} — 두 기기를 같은 버전으로 맞추세요."
    } + FOOTER

    /** 지휘자 화면: 연주자 [deviceName] 과 버전이 다르다 */
    fun conductorMessage(deviceName: String, m: Mismatch): String = when (m.theirsOlder) {
        true -> "연주자 '$deviceName' 이(가) ${m.theirs} 입니다 (지휘자 ${m.mine}).\n그 기기를 ${m.mine} 으로 업데이트하세요."
        false -> "지휘자(이 기기)가 ${m.mine} 입니다 (연주자 '$deviceName' ${m.theirs}).\n이 기기를 ${m.theirs} 로 업데이트하세요."
        null -> "연주자 '$deviceName' ${m.theirs} · 지휘자 ${m.mine} — 같은 버전으로 맞추세요."
    } + FOOTER

    private const val FOOTER =
        "\n\n버전이 다르면 합주 메트로놈의 박 · 마디 · 예비박이 어긋날 수 있습니다. 업데이트: 설정 → 앱 정보 → 업데이트 확인"
}
