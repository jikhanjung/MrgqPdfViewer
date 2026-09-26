package com.mrgq.pdfviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mrgq.pdfviewer.update.UpdateController
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 받은 업데이트 APK 를 설치 화면에 넘기는 FileProvider 설정 (#054).
 * 매니페스트 authority 나 `file_paths.xml` 이 [UpdateController.updatesDir] 와 어긋나면
 * 실행 중에만 IllegalArgumentException 으로 드러나므로 계측으로 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class UpdateFileProviderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun updates_폴더의_APK_는_content_URI_로_읽힌다() {
        val dir = UpdateController.updatesDir(context).apply { mkdirs() }
        val apk = File(dir, "MrgqPdfViewer-v9.9.9-release.apk")
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        apk.writeBytes(bytes)
        try {
            val uri = UpdateController.installUriFor(context, apk)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(bytes, read)
        } finally {
            dir.deleteRecursively()
        }
    }
}
