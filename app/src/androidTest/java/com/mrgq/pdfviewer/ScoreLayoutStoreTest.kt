package com.mrgq.pdfviewer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mrgq.pdfviewer.database.MusicDatabase
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.score.PageLayout
import com.mrgq.pdfviewer.score.ScoreLayout
import com.mrgq.pdfviewer.score.ScoreLayoutStore
import com.mrgq.pdfviewer.score.SystemLayout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 마디 레이아웃 캐시 (ScoreLayoutStore). 분석은 가짜로 바꿔 끼우고 **언제 다시 분석하는지**,
 * **이전 결과가 남지 않는지**를 본다.
 */
@RunWith(AndroidJUnit4::class)
class ScoreLayoutStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: MusicDatabase
    private lateinit var file: File
    private var analyzeCalls = 0

    private fun layoutWith(vararg barlines: Float) = ScoreLayout(listOf(
        PageLayout(0, 595f, 842f, listOf(SystemLayout(100f, 380f, barlines.first(), barlines.last(), emptyList(), barlines.toList())))
    ))

    private fun counting(layout: ScoreLayout?): (File) -> ScoreLayout? = { analyzeCalls++; layout }

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        file = File(context.cacheDir, "score-store.pdf").apply { writeText("dummy") }
        db.pdfFileDao().insertPdfFile(
            PdfFile(
                id = "file-1", filename = file.name, filePath = file.absolutePath, totalPages = 1,
                orientation = PageOrientation.PORTRAIT, width = 595f, height = 842f,
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun get(analyze: (File) -> ScoreLayout?, target: File = file) =
        runBlocking { ScoreLayoutStore.getOrAnalyze(db, "file-1", target, analyze) }

    @Test
    fun 처음이면_분석해_저장한다() = runBlocking {
        val measures = get(counting(layoutWith(50f, 200f, 350f, 500f)))

        assertEquals(1, analyzeCalls)
        assertEquals(listOf(1, 2, 3), measures?.map { it.measureNumber })
        assertEquals(3, db.scoreMeasureDao().getMeasures("file-1").size)
        assertNotNull(db.pdfFileDao().getPdfFileById("file-1")?.scoreAnalyzedAt)
    }

    @Test
    fun 두번째부터는_저장된_결과를_쓴다() {
        get(counting(layoutWith(50f, 200f, 350f, 500f)))
        val again = get(counting(layoutWith(50f, 500f)))

        assertEquals("뷰어의 페이지 전환마다 PdfBox 로 다시 분석하면 안 된다", 1, analyzeCalls)
        assertEquals(3, again?.size)
    }

    @Test
    fun 악보가_아니어도_분석했음을_남겨_다시_하지_않는다() {
        val notScore = ScoreLayout(listOf(PageLayout(0, 595f, 842f, emptyList())))
        assertEquals(emptyList<Any>(), get(counting(notScore)))
        get(counting(notScore))
        assertEquals(1, analyzeCalls)
    }

    @Test
    fun 열지_못한_파일도_다시_시도하지_않는다() {
        assertEquals(emptyList<Any>(), get(counting(null)))
        get(counting(null))
        assertEquals(1, analyzeCalls)
    }

    @Test
    fun 파일이_바뀌면_다시_분석하고_이전_마디는_남지_않는다() = runBlocking {
        get(counting(layoutWith(50f, 200f, 350f, 500f)))
        // PdfFileSync 가 바뀐 파일을 update 하면 scoreAnalyzedAt 이 비워진다 (분석 결과를 보존하지 않음)
        val record = db.pdfFileDao().getPdfFileById("file-1")!!
        db.pdfFileDao().updatePdfFile(record.copy(scoreAnalyzedAt = null))

        val measures = get(counting(layoutWith(50f, 500f)))

        assertEquals(2, analyzeCalls)
        assertEquals(1, measures?.size)
        assertEquals("이전 분석의 마디 2, 3 이 남았다", 1, db.scoreMeasureDao().getMeasures("file-1").size)
    }

    @Test
    fun 레코드와_경로가_다른_파일은_분석하지_않는다() {
        // 뷰어가 다음 파일로 넘어가는 사이 이전 파일 ID 로 새 파일을 요청하는 경우
        val other = File(context.cacheDir, "other.pdf")
        assertNull(get(counting(layoutWith(50f, 500f)), target = other))
        assertEquals(0, analyzeCalls)
    }

    @Test
    fun 파일_레코드를_지우면_마디도_지워진다() = runBlocking {
        get(counting(layoutWith(50f, 200f, 500f)))
        db.pdfFileDao().deletePdfFileById("file-1")
        assertTrue(db.scoreMeasureDao().getMeasures("file-1").isEmpty())
    }
}
