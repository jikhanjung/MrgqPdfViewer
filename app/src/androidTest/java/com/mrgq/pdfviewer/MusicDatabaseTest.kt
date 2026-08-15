package com.mrgq.pdfviewer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mrgq.pdfviewer.database.MusicDatabase
import com.mrgq.pdfviewer.database.entity.DisplayMode
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.PdfFile
import com.mrgq.pdfviewer.database.entity.UserPreference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 저장 왕복 테스트 (계측 — 실제 SQLite 가 필요하다).
 *
 * 여기 저장되는 값이 **OK 길게 누르기로 조정하는 그 설정들**이다. 과거에 마이그레이션
 * 오류로 DisplayMode 저장이 깨진 적이 있고(v0.1.8), 설정이 파일 목록에서 돌아오면
 * 적용되지 않던 문제도 있었다(v0.1.7). 지금까지 어떤 테스트도 이 경로를 보지 않았다.
 *
 * 특히 **enum 은 `name()` 문자열로 저장된다**(Converters.kt). release 빌드에서 R8 이
 * enum 상수 이름을 난독화하면 저장된 "DOUBLE" 을 다시 못 읽는다 — 이 테스트가
 * minified 빌드에서도 돌면 그 회귀를 잡는다.
 */
@RunWith(AndroidJUnit4::class)
class MusicDatabaseTest {

    private lateinit var db: MusicDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MusicDatabase::class.java
        ).build()
    }

    @After
    fun closeDb() = db.close()

    private fun sampleFile(id: String = "file-1") = PdfFile(
        id = id,
        filename = "바흐 무반주 1번.pdf",
        filePath = "/data/PDFs/바흐 무반주 1번.pdf",
        totalPages = 13,
        orientation = PageOrientation.PORTRAIT,
        width = 595f,
        height = 842f,
    )

    @Test
    fun pdfFile_왕복_저장된다() = runBlocking {
        val dao = db.pdfFileDao()
        val file = sampleFile()
        dao.insertPdfFile(file)

        val loaded = dao.getPdfFileById(file.id)
        assertNotNull("저장한 파일을 다시 못 읽었다", loaded)
        assertEquals(file.filename, loaded!!.filename)
        assertEquals(file.totalPages, loaded.totalPages)
        // enum → 문자열 → enum 왕복 (TypeConverter)
        assertEquals(PageOrientation.PORTRAIT, loaded.orientation)
        // 한글 파일명이 인코딩에서 깨지지 않는지
        assertEquals("바흐 무반주 1번.pdf", loaded.filename)
    }

    @Test
    fun 표시설정_왕복_저장된다() = runBlocking {
        db.pdfFileDao().insertPdfFile(sampleFile())
        val prefDao = db.userPreferenceDao()

        val pref = UserPreference(
            pdfFileId = "file-1",
            displayMode = DisplayMode.DOUBLE,
            lastPageNumber = 7,
            topClippingPercent = 0.05f,
            bottomClippingPercent = 0.03f,
            centerPadding = 0.10f,
        )
        prefDao.insertUserPreference(pref)

        val loaded = prefDao.getUserPreference("file-1")
        assertNotNull(loaded)
        // **enum 이 name() 문자열로 저장된다** — R8 이 상수명을 난독화하면 여기서 깨진다.
        assertEquals(DisplayMode.DOUBLE, loaded!!.displayMode)
        assertEquals(7, loaded.lastPageNumber)
        // 클리핑·여백은 Float. v2→v3 에서 픽셀→퍼센티지로 의미가 바뀐 값들이다.
        assertEquals(0.05f, loaded.topClippingPercent, 1e-6f)
        assertEquals(0.03f, loaded.bottomClippingPercent, 1e-6f)
        assertEquals(0.10f, loaded.centerPadding, 1e-6f)
    }

    @Test
    fun 모든_DisplayMode_가_왕복한다() = runBlocking {
        val prefDao = db.userPreferenceDao()
        for ((i, mode) in DisplayMode.values().withIndex()) {
            val id = "file-$i"
            db.pdfFileDao().insertPdfFile(sampleFile(id))
            prefDao.insertUserPreference(UserPreference(pdfFileId = id, displayMode = mode))
            assertEquals("$mode 가 왕복에서 깨졌다", mode, prefDao.getUserPreference(id)!!.displayMode)
        }
    }

    @Test
    fun 설정_덮어쓰기가_반영된다() = runBlocking {
        db.pdfFileDao().insertPdfFile(sampleFile())
        val prefDao = db.userPreferenceDao()
        prefDao.insertUserPreference(UserPreference(pdfFileId = "file-1", displayMode = DisplayMode.SINGLE))
        prefDao.insertUserPreference(
            UserPreference(
                pdfFileId = "file-1",
                displayMode = DisplayMode.AUTO,
                topClippingPercent = 0.12f,
            )
        )
        val loaded = prefDao.getUserPreference("file-1")!!
        assertEquals(DisplayMode.AUTO, loaded.displayMode)
        assertEquals(0.12f, loaded.topClippingPercent, 1e-6f)
    }

    @Test
    fun 파일을_지우면_설정도_함께_사라진다() = runBlocking {
        // 외래키 CASCADE. 남으면 다른 파일이 같은 id 를 얻었을 때 엉뚱한 설정이 붙는다.
        db.pdfFileDao().insertPdfFile(sampleFile())
        db.userPreferenceDao().insertUserPreference(
            UserPreference(pdfFileId = "file-1", displayMode = DisplayMode.DOUBLE)
        )
        db.pdfFileDao().deletePdfFile(sampleFile())
        assertNull("파일을 지웠는데 설정이 남았다", db.userPreferenceDao().getUserPreference("file-1"))
    }

    @Test
    fun 없는_파일의_설정은_null_이다() = runBlocking {
        assertNull(db.userPreferenceDao().getUserPreference("존재하지-않음"))
    }
}
