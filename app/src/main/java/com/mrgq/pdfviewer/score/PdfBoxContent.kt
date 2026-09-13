package com.mrgq.pdfviewer.score

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * [PathContentInterpreter] 와 PdfBox 사이의 얇은 연결부. PdfBox 는 여기서 **스트림 압축 해제와 XObject 조회**에만 쓴다
 * (토큰화·연산자 실행은 해석기가 한다 — PdfBox 엔진은 할당이 너무 많다, #049).
 */
internal object PdfBoxContent {

    /** 페이지의 콘텐츠 스트림들을 이어 붙인다. 스펙상 하나의 스트림으로 해석하며, 토큰이 붙지 않게 사이에 줄바꿈을 넣는다. */
    fun pageContent(page: PDPage): ByteArray {
        val out = ByteArrayOutputStream()
        page.contentStreams.forEach { stream ->
            stream.createInputStream().use { it.copyTo(out) }
            out.write('\n'.code)
        }
        return out.toByteArray()
    }
}

/** 리소스에서 Form XObject 를 찾는다. 폼에 리소스가 없으면 바깥 리소스를 이어 쓴다 (PdfBox 엔진과 같다). */
internal class PdfBoxXObjects(private val resources: PDResources?) : PathContentInterpreter.XObjectResolver {

    override fun form(name: String): PathContentInterpreter.FormXObject? {
        val res = resources ?: return null
        val cosName = COSName.getPDFName(name)
        val form = try {
            // 이미지는 객체를 만들지 않고 넘어간다 — 경로가 아니다
            if (res.isImageXObject(cosName)) null else res.getXObject(cosName) as? PDFormXObject
        } catch (e: IOException) {
            null
        } ?: return null

        val m = form.matrix
        return PathContentInterpreter.FormXObject(
            content = form.contents.use { it.readBytes() },
            matrix = floatArrayOf(m.scaleX, m.shearY, m.shearX, m.scaleY, m.translateX, m.translateY),
            resolver = PdfBoxXObjects(form.resources ?: resources),
        )
    }
}
