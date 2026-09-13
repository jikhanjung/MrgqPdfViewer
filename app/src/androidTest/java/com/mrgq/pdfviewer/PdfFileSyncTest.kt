package com.mrgq.pdfviewer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mrgq.pdfviewer.database.MusicDatabase
import com.mrgq.pdfviewer.database.entity.DisplayMode
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.database.entity.UserPreference
import com.mrgq.pdfviewer.repository.PdfFileSync
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 파일 ↔ pdf_files 레코드 동기화 (PdfFileSync).
 *
 * 분석(PdfRenderer + PdfBox)은 가짜로 바꿔 끼우고, **언제 다시 분석하는지**와
 * **갱신이 표시 설정을 지우지 않는지**만 본다.
 */
@RunWith(AndroidJUnit4::class)
class PdfFileSyncTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: MusicDatabase
    private lateinit var file: File

    private var analyzeCalls = 0
    private var nextTitle: String? = "첫 제목"

    private val fakeAnalyze: (File) -> PdfFile? = { f ->
        analyzeCalls++
        PdfFile(
            id = "file-1",
            filename = f.name,
            filePath = f.absolutePath,
            totalPages = 13,
            orientation = PageOrientation.PORTRAIT,
            width = 595f,
            height = 842f,
            title = nextTitle,
            author = "홍길동",
            docInfoReadAt = System.currentTimeMillis(),
            createdAt = f.lastModified(),
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        file = File(context.cacheDir, "sync-test.pdf").apply { writeText("dummy") }
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    private fun sync(analyze: (File) -> PdfFile? = fakeAnalyze) =
        runBlocking { PdfFileSync.sync(db.pdfFileDao(), file, analyze) }

    @Test
    fun 처음_보는_파일은_분석해_저장한다() = runBlocking {
        val record = sync()
        assertEquals(1, analyzeCalls)
        assertEquals("첫 제목", record?.title)
        assertEquals("홍길동", db.pdfFileDao().getPdfFileByPath(file.absolutePath)?.author)
    }

    @Test
    fun 바뀌지_않은_파일은_다시_분석하지_않는다() {
        sync()
        sync()
        sync()
        assertEquals("목록을 열 때마다 PdfBox 로 다시 읽으면 안 된다", 1, analyzeCalls)
    }

    @Test
    fun 파일이_바뀌면_다시_읽고_표시설정은_보존된다() = runBlocking {
        sync()
        db.userPreferenceDao().insertUserPreference(
            UserPreference(pdfFileId = "file-1", displayMode = DisplayMode.DOUBLE, centerPadding = 0.1f)
        )

        nextTitle = "새 제목"
        assumeTrue("mtime 을 바꿀 수 없는 파일시스템", file.setLastModified(file.lastModified() + 60_000))
        val record = sync()

        assertEquals(2, analyzeCalls)
        assertEquals("새 제목", record?.title)
        assertEquals("새 제목", db.pdfFileDao().getPdfFileByPath(file.absolutePath)?.title)
        val pref = db.userPreferenceDao().getUserPreference("file-1")
        assertNotNull("재분석이 파일별 표시 설정을 지웠다 (REPLACE → CASCADE)", pref)
        assertEquals(DisplayMode.DOUBLE, pref!!.displayMode)
        assertEquals(0.1f, pref.centerPadding, 1e-6f)
    }

    /**
     * 위 테스트가 지키는 함정이 실제로 존재한다는 증거. 이게 깨지면(설정이 남으면) PdfFileSync 의
     * update 강제는 불필요해진 것이다 — 주석과 함께 정리할 것.
     */
    @Test
    fun REPLACE_삽입은_표시설정을_지운다() = runBlocking {
        sync()
        db.userPreferenceDao().insertUserPreference(
            UserPreference(pdfFileId = "file-1", displayMode = DisplayMode.DOUBLE)
        )
        val existing = db.pdfFileDao().getPdfFileByPath(file.absolutePath)!!
        db.pdfFileDao().insertPdfFile(existing.copy(title = "REPLACE"))

        assertNull(db.userPreferenceDao().getUserPreference("file-1"))
    }

    @Test
    fun v4_레코드는_문서정보를_한번_채운다() = runBlocking {
        // MIGRATION_4_5 직후 상태: 페이지 수 등은 있고 docInfoReadAt 은 null
        db.pdfFileDao().insertPdfFile(
            fakeAnalyze(file)!!.copy(title = null, author = null, docInfoReadAt = null)
        )
        analyzeCalls = 0

        sync()
        sync()

        assertEquals("v4 레코드는 한 번만 다시 읽어야 한다", 1, analyzeCalls)
        assertEquals("첫 제목", db.pdfFileDao().getPdfFileByPath(file.absolutePath)?.title)
    }

    @Test
    fun 분석에_실패하면_있던_레코드를_돌려준다() = runBlocking {
        assertNull("레코드도 없고 분석도 실패", sync { null })

        sync()
        assumeTrue(file.setLastModified(file.lastModified() + 60_000))
        val record = sync { null }
        assertEquals("첫 제목", record?.title)
    }
}
