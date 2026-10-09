package com.mrgq.pdfviewer.ensemble

import com.mrgq.pdfviewer.update.AppVersion

/**
 * 합주 연결 때 지휘자 · 연주자의 **합주 버전** 비교 (#061 → #086). 합주 메시지는 판마다 필드가 늘어(구간별 빠르기 #057, 예비박 마디 수 #060,
 * 차례 넘김 · 시스템 표시 v0.4.0) 서로 다르면 박 · 마디가 어긋날 수 있다 — 연결할 때 양쪽에 알려 맞추게 한다.
 *
 * **합주 버전 = 합주 메시지 · 명령이 마지막으로 바뀐 판의 앱 버전**([CURRENT], 사용자 결정 2026-10-09). 합주 메시지를 바꾸는 판에서
 * 그 판의 앱 버전으로 올린다. 연결 메시지(`client_connect` · `connect_response`)의 `ensemble_version` 으로 주고받고, 이것이 다를 때만
 * 알린다 — 앱 버전만 다르면(0.7.0 ↔ 0.7.1-beta.2) 알리지 않는다. 지금은 0.4.0(차례 넘김 · 시스템 표시 · 끝내기, P10) — 그 뒤로
 * 합주 메시지는 바뀌지 않았다(v0.4.0..v0.7.1 비교).
 *
 * `ensemble_version` 을 보내지 않는 옛 기기(v0.7.1-beta.1 까지)는 앱 버전으로 짐작한다: [BEFORE_FIELD] 이상이면 그 합주, 그 아래는
 * 그 앱 버전. v0.2.5 까지는 `client_connect.app_version` · `connect_response.server_version` 을 **"v0.1.5" 로 고정**해 보냈다 →
 * 그 값이나 값이 없으면 "v0.2.5 이하".
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object EnsembleVersion {

    /** 이 앱의 합주 버전 — 합주 메시지 · 명령을 바꾸는 판에서 그 판의 앱 버전으로 */
    const val CURRENT = "0.4.0"

    /** `ensemble_version` 을 보내기 전(v0.7.1-beta.1 까지)의 마지막 합주 버전 — 고정. [CURRENT] 를 올려도 바꾸지 않는다 */
    private val BEFORE_FIELD = AppVersion(0, 4, 0)

    /** 옛 버전이 고정해 보내던 값 */
    const val LEGACY_REPORTED = "v0.1.5"
    private const val LEGACY_LABEL = "v0.2.5 이하"

    /** 합주 버전이 다르다. [mine] · [theirs] 는 보일 **앱** 버전(업데이트할 판), [theirsOlder] = 상대가 더 옛 합주 (판단할 수 없으면 null) */
    data class Mismatch(val mine: String, val theirs: String, val theirsOlder: Boolean?)

    /** 상대가 보낸 값을 보일 이름으로 */
    fun describe(reported: String?): String =
        if (reported.isNullOrBlank() || reported == LEGACY_REPORTED) LEGACY_LABEL else "v" + reported.removePrefix("v")

    /**
     * 내 앱 버전 [mine] 과 상대의 앱 버전 [theirs] · 합주 버전 [theirsEnsemble](없으면 앱 버전으로 짐작). 합주 버전이 같으면 null
     */
    fun check(mine: String, theirs: String?, theirsEnsemble: String? = null): Mismatch? {
        val mineLabel = "v" + mine.removePrefix("v")
        if (theirsEnsemble == null && (theirs.isNullOrBlank() || theirs == LEGACY_REPORTED)) {
            return Mismatch(mineLabel, LEGACY_LABEL, theirsOlder = true)
        }
        val ours = AppVersion.parse(CURRENT) ?: error("합주 버전 $CURRENT 을 읽지 못함")
        val other = theirsEnsemble?.let(AppVersion::parse) ?: theirs?.let(::ensembleOf)
            ?: return Mismatch(mineLabel, describe(theirs), theirsOlder = null)
        if (other.compareTo(ours) == 0) return null
        return Mismatch(mineLabel, describe(theirs), theirsOlder = other < ours)
    }

    /** `ensemble_version` 이 없는 기기의 합주 버전 — 앱 버전으로 짐작 */
    private fun ensembleOf(appVersion: String): AppVersion? {
        val v = AppVersion.parse(appVersion) ?: return null
        return if (v >= BEFORE_FIELD) BEFORE_FIELD else v
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
