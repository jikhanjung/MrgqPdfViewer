package com.mrgq.pdfviewer.update

/**
 * `vX.Y.Z[-suffix]` 형식의 버전 (릴리스 태그, versionName 공통).
 *
 * 모자란 자리는 0 이고, 접미사가 있으면 같은 숫자의 정식 버전보다 낮다 (`0.3.0-rc1 < 0.3.0`).
 * 접미사끼리는 **SemVer 우선순위** — 점으로 나눈 조각을 차례로, 숫자 조각은 숫자로(`beta.10 > beta.2`), 숫자는 글자보다 낮고,
 * 앞이 같으면 조각이 많은 쪽이 높다: `0.5.7-beta.1 < 0.5.7-beta.2 < 0.5.7-rc.1 < 0.5.7`. 사전 릴리스 받기(설정)에서 실제로 쓴다
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
            else -> compareSuffix(suffix, other.suffix)
        }
    }

    /** 릴리스 워크플로의 시험 태그(`-test`, `-beta.1-test`) — 업데이트 후보가 아니다 */
    val isTest: Boolean get() = suffix != null && (suffix == "test" || suffix.endsWith("-test"))

    val isPrerelease: Boolean get() = suffix != null

    override fun toString() = "v$major.$minor.$patch" + (suffix?.let { "-$it" } ?: "")

    companion object {
        private fun compareSuffix(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i].toIntOrNull()
                val q = y[i].toIntOrNull()
                val c = when {
                    p != null && q != null -> p.compareTo(q)
                    p != null -> -1
                    q != null -> 1
                    else -> x[i].compareTo(y[i])
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }

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
