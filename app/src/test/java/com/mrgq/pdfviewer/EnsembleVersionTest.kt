package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.ensemble.EnsembleVersion
import com.mrgq.pdfviewer.ensemble.EnsembleVersion.Mismatch
import com.mrgq.pdfviewer.update.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 합주 연결 때 합주 버전 비교 (#061 → #086). 지금 합주 버전 = 0.4.0 */
class EnsembleVersionTest {

    @Test
    fun 합주_버전이_같으면_앱_버전이_달라도_알리지_않는다() {
        assertNull(EnsembleVersion.check("0.7.1-beta.2", "0.7.1-beta.2", EnsembleVersion.CURRENT))
        assertNull(EnsembleVersion.check("0.7.1-beta.2", "0.9.3", EnsembleVersion.CURRENT))
        assertNull(EnsembleVersion.check("0.7.1-beta.2", "v0.7.0", "v" + EnsembleVersion.CURRENT))
    }

    @Test
    fun 합주_버전을_보내지_않는_옛_기기는_앱_버전으로_짐작한다() {
        // 합주 메시지는 v0.4.0 뒤로 바뀌지 않았다 — 0.4.0 이상은 합주 0.4.0
        assertNull(EnsembleVersion.check("0.7.1-beta.2", "0.7.0"))
        assertNull(EnsembleVersion.check("0.7.1-beta.2", "0.7.1-beta.1"))
        assertNull(EnsembleVersion.check("0.7.1-beta.2", "0.4.0"))
        assertEquals(Mismatch("v0.7.1-beta.2", "v0.3.5", true), EnsembleVersion.check("0.7.1-beta.2", "0.3.5"))
    }

    @Test
    fun 옛_버전은_v0_1_5_고정값이나_값이_없다() {
        // theirs null = "v0.2.5 이하" (화면 글은 ensemble_version_legacy, P18)
        assertEquals(Mismatch("v0.7.1", null, true), EnsembleVersion.check("0.7.1", "v0.1.5"))
        assertEquals(Mismatch("v0.7.1", null, true), EnsembleVersion.check("0.7.1", null))
        assertNull(EnsembleVersion.describe(""))
    }

    @Test
    fun 어느_쪽이_옛_합주인지_가린다() {
        assertEquals(Mismatch("v0.7.1", "v0.9.0", false), EnsembleVersion.check("0.7.1", "0.9.0", "0.9.0"))
        assertEquals(Mismatch("v0.7.1", "v0.3.2", true), EnsembleVersion.check("0.7.1", "0.3.2"))
        assertEquals("해석할 수 없으면 판단하지 않는다", null, EnsembleVersion.check("0.7.1", "dev-build")?.theirsOlder)
    }

    @Test
    fun 안내문은_업데이트할_기기를_가리킨다() {
        // 글은 리소스(키 + 인자, P18) — 어느 문장 · 어느 버전을 끼우는지를 본다. 끝에는 늘 공통 안내(ensemble_version_notice)
        fun notice(id: Int, vararg args: Any) = UiText(R.string.ensemble_version_notice, UiText(id, *args))
        val legacy = UiText(R.string.ensemble_version_legacy)
        // 연주자: 지휘자가 옛 기기 → 지휘자 기기를 v0.7.1 으로
        assertEquals(
            notice(R.string.ensemble_version_performer_conductor_older, legacy, "v0.7.1"),
            EnsembleVersion.performerMessage(EnsembleVersion.check("0.7.1", "v0.1.5")!!),
        )
        // 지휘자: 연주자 '거실 TV' 가 옛 기기 → 그 기기를 v0.7.1 으로
        assertEquals(
            notice(R.string.ensemble_version_conductor_performer_older, "거실 TV", "v0.3.5", "v0.7.1"),
            EnsembleVersion.conductorMessage("거실 TV", EnsembleVersion.check("0.7.1", "0.3.5")!!),
        )
        // 지휘자가 옛 기기 → 이 기기를 v0.9.0 로
        assertEquals(
            notice(R.string.ensemble_version_conductor_self_older, "v0.7.1", "거실 TV", "v0.9.0"),
            EnsembleVersion.conductorMessage("거실 TV", EnsembleVersion.check("0.7.1", "0.9.0", "0.9.0")!!),
        )
        assertEquals(
            notice(R.string.ensemble_version_performer_self_older, "v0.7.1", "v0.9.0"),
            EnsembleVersion.performerMessage(EnsembleVersion.check("0.7.1", "0.9.0", "0.9.0")!!),
        )
    }
}
