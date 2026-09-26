package com.mrgq.pdfviewer.update

/**
 * `vX.Y.Z[-suffix]` 형식의 버전 (릴리스 태그, versionName 공통).
 *
 * 모자란 자리는 0 이고, 접미사가 있으면 같은 숫자의 정식 버전보다 낮다 (`0.3.0-rc1 < 0.3.0`).
 * 접미사끼리는 문자열로 비교한다 — `releases/latest` 는 pre-release 를 주지 않으므로 방어용이다.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String? = null
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
            .let { if (it != 0) return it }
        return when {
            suffix == other.suffix -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    override fun toString() = "v$major.$minor.$patch" + (suffix?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?$""")

        /** 해석할 수 없으면 null. */
        fun parse(text: String): AppVersion? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, suffix) = m.destructured
            return AppVersion(
                major.toInt(),
                minor.toIntOrNull() ?: 0,
                patch.toIntOrNull() ?: 0,
                suffix.ifEmpty { null }
            )
        }
    }
}
