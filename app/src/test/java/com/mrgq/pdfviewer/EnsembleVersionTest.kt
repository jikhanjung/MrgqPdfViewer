package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.ensemble.EnsembleVersion
import com.mrgq.pdfviewer.ensemble.EnsembleVersion.Mismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 합주 연결 때 버전 비교 (#061) */
class EnsembleVersionTest {

    @Test
    fun 같은_버전이면_알리지_않는다() {
        assertNull(EnsembleVersion.check("0.2.6", "0.2.6"))
        assertNull(EnsembleVersion.check("0.2.6", "v0.2.6"))
    }

    @Test
    fun 옛_버전은_v0_1_5_고정값이나_값이_없다() {
        assertEquals(Mismatch("v0.2.6", "v0.2.5 이하", true), EnsembleVersion.check("0.2.6", "v0.1.5"))
        assertEquals(Mismatch("v0.2.6", "v0.2.5 이하", true), EnsembleVersion.check("0.2.6", null))
        assertEquals("v0.2.5 이하", EnsembleVersion.describe(""))
    }

    @Test
    fun 어느_쪽이_옛_버전인지_가린다() {
        assertEquals(Mismatch("v0.2.6", "v0.2.7", false), EnsembleVersion.check("0.2.6", "0.2.7"))
        assertEquals(Mismatch("v0.2.7", "v0.2.6", true), EnsembleVersion.check("0.2.7", "0.2.6"))
        assertEquals("해석할 수 없으면 판단하지 않는다", null, EnsembleVersion.check("0.2.6", "dev-build")?.theirsOlder)
    }

    @Test
    fun 안내문은_업데이트할_기기를_가리킨다() {
        val performerSees = EnsembleVersion.performerMessage(EnsembleVersion.check("0.2.6", "v0.1.5")!!)
        assertTrue(performerSees, performerSees.contains("지휘자 기기를 v0.2.6 으로 업데이트"))
        val conductorSees = EnsembleVersion.conductorMessage("거실 TV", EnsembleVersion.check("0.2.7", "0.2.6")!!)
        assertTrue(conductorSees, conductorSees.contains("'거실 TV'") && conductorSees.contains("v0.2.7 으로 업데이트"))
        val conductorOlder = EnsembleVersion.conductorMessage("거실 TV", EnsembleVersion.check("0.2.6", "0.2.7")!!)
        assertTrue(conductorOlder, conductorOlder.contains("이 기기를 v0.2.7 로 업데이트"))
    }
}
