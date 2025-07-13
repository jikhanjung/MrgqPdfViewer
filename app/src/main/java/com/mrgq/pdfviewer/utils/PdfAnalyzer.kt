package com.mrgq.pdfviewer.utils

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.PdfFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object PdfAnalyzer {
    
    /**
     * PDF 파일을 분석하여 PdfFile 엔티티를 생성합니다.
     * 첫 페이지의 가로/세로 비율로 orientation을 판단합니다.
     */
    fun analyzePdfFile(file: File): PdfFile? {
        return try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(fileDescriptor)
            
            // 첫 페이지 정보 가져오기
            val firstPage = pdfRenderer.openPage(0)
            val width = firstPage.width.toFloat()
            val height = firstPage.height.toFloat()
            
            // Orientation 판단 (가로가 세로보다 크면 LANDSCAPE)
            val orientation = if (width > height) {
                PageOrientation.LANDSCAPE
            } else {
                PageOrientation.PORTRAIT
            }
            
            val totalPages = pdfRenderer.pageCount
            
            firstPage.close()
            pdfRenderer.close()
            fileDescriptor.close()
            
            // 파일 ID 생성 (파일 경로 기반 해시)
            val fileId = generateFileId(file.absolutePath)
            
            PdfFile(
                id = fileId,
                filename = file.name,
                filePath = file.absolutePath,
                totalPages = totalPages,
                orientation = orientation,
                width = width,
                height = height,
                createdAt = file.lastModified(),
                updatedAt = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            android.util.Log.e("PdfAnalyzer", "PDF 파일 분석 실패: ${file.name}", e)
            null
        }
    }
    
    /**
     * 파일 경로를 기반으로 고유한 ID를 생성합니다.
     */
    private fun generateFileId(filePath: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hashBytes = digest.digest(filePath.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // MD5 해시 생성 실패 시 UUID 사용
            UUID.randomUUID().toString()
        }
    }
    
    /**
     * 파일이 유효한 PDF인지 빠르게 확인합니다.
     */
    fun isValidPdf(file: File): Boolean {
        return try {
            if (!file.exists() || !file.canRead()) return false
            
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(fileDescriptor)
            val isValid = pdfRenderer.pageCount > 0
            
            pdfRenderer.close()
            fileDescriptor.close()
            
            isValid
        } catch (e: Exception) {
            false
        }
    }
}