package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.ensemble.EnsembleVersion
import com.mrgq.pdfviewer.ensemble.EnsembleVersion.Mismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(Mismatch("v0.7.1", "v0.2.5 이하", true), EnsembleVersion.check("0.7.1", "v0.1.5"))
        assertEquals(Mismatch("v0.7.1", "v0.2.5 이하", true), EnsembleVersion.check("0.7.1", null))
        assertEquals("v0.2.5 이하", EnsembleVersion.describe(""))
    }

    @Test
    fun 어느_쪽이_옛_합주인지_가린다() {
        assertEquals(Mismatch("v0.7.1", "v0.9.0", false), EnsembleVersion.check("0.7.1", "0.9.0", "0.9.0"))
        assertEquals(Mismatch("v0.7.1", "v0.3.2", true), EnsembleVersion.check("0.7.1", "0.3.2"))
        assertEquals("해석할 수 없으면 판단하지 않는다", null, EnsembleVersion.check("0.7.1", "dev-build")?.theirsOlder)
    }

    @Test
    fun 안내문은_업데이트할_기기를_가리킨다() {
        val performerSees = EnsembleVersion.performerMessage(EnsembleVersion.check("0.7.1", "v0.1.5")!!)
        assertTrue(performerSees, performerSees.contains("지휘자 기기를 v0.7.1 으로 업데이트"))
        val conductorSees = EnsembleVersion.conductorMessage("거실 TV", EnsembleVersion.check("0.7.1", "0.3.5")!!)
        assertTrue(conductorSees, conductorSees.contains("'거실 TV'") && conductorSees.contains("v0.7.1 으로 업데이트"))
        val conductorOlder = EnsembleVersion.conductorMessage("거실 TV", EnsembleVersion.check("0.7.1", "0.9.0", "0.9.0")!!)
        assertTrue(conductorOlder, conductorOlder.contains("이 기기를 v0.9.0 로 업데이트"))
    }
}
