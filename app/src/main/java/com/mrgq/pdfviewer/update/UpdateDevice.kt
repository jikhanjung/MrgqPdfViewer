package com.mrgq.pdfviewer.update

/**
 * 업데이트 확인 · APK 받기 때 ScoreMate 서버에 알리는 이 기기 (#120) — 서버가 어느 기기가 어느 판을 쓰는지 · 받았는지 기록한다.
 * **ScoreMate 서버로 가는 요청에만** 붙인다(GitHub · 저장소 서명 URL 에는 안 붙임). 인증이 아니라 기록용이라 토큰은 보내지 않는다
 * — 만료된 토큰으로 401 이 나 업데이트 확인이 막히지 않게.
 *
 * Android 에 의존하지 않는다 — 값은 [UpdateController] 가 채운다.
 */
data class UpdateDevice(
    val appVersion: String,
    /** "tv" · "tablet" · "phone" */
    val form: String,
    /** 제조사 + 모델 (예: "Lenovo TB371FC") */
    val model: String,
    val android: String,
    /** 앱 설치마다 무작위 — 연결하지 않은 기기 · 같은 모델 두 대를 가른다 */
    val installId: String,
    /** ScoreMate 에 연결돼 있으면 연결 때 받은 device_id */
    val scoreMateDeviceId: String?,
) {
    /** `MrgqPdfViewer/0.7.7-beta.1 (tv; Z18TV Pro; Android 14)` */
    val userAgent: String
        get() = "MrgqPdfViewer/${token(appVersion)} (${part(form)}; ${part(model)}; Android ${part(android)})"

    val headers: Map<String, String>
        get() = buildMap {
            put("User-Agent", userAgent)
            put(HEADER_INSTALL, part(installId))
            scoreMateDeviceId?.let { put(HEADER_DEVICE, part(it)) }
        }

    companion object {
        const val HEADER_INSTALL = "X-Mrgq-Install"
        const val HEADER_DEVICE = "X-ScoreMate-Device"

        /** 머리글 값은 ASCII 만 — 아니면 연결이 예외로 끝나 업데이트 확인이 막힌다. 괄호 · 쌍반점은 형식을 깨므로 뺀다 */
        internal fun part(s: String): String =
            s.map { if (it in ' '..'~' && it !in "();") it else ' ' }.joinToString("")
                .replace(Regex(" +"), " ").trim().ifEmpty { "?" }

        /** 판 번호 — 공백 없이 */
        private fun token(s: String) = part(s).replace(' ', '_')

        /**
         * 에뮬레이터인가 — CI 계측 · 스모크가 앱을 띄우면 자동 확인이 운영 서버의 기기 기록에 남는다(#121).
         * 에뮬레이터는 자동 확인을 하지 않는다(설정의 업데이트 확인은 그대로)
         */
        fun isEmulator(hardware: String, product: String, fingerprint: String): Boolean =
            hardware == "ranchu" || hardware == "goldfish" || product.startsWith("sdk") || fingerprint.startsWith("generic")

        /** 모델 이름 — 모델이 이미 제조사로 시작하면 한 번만 */
        fun modelName(manufacturer: String, model: String): String =
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }
}
