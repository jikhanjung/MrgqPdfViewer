package com.mrgq.pdfviewer

/**
 * Manages PDF file switching and file list navigation.
 * Single responsibility: Handle file operations and file list management.
 */
class ViewerFileManager(
    private val filePathList: List<String>,
    private val fileNameList: List<String>,
    private val initialFileIndex: Int,
    private val listener: FileListener
) {
    
    private var currentFileIndex: Int = initialFileIndex
    
    interface FileListener {
        fun onFileLoading(fileName: String)
        fun onFileLoaded(filePath: String, success: Boolean)
        fun onFileLoadError(error: String)
    }
    
    /**
     * Load the next file in the list
     */
    fun loadNextFile() {
        if (currentFileIndex < filePathList.size - 1) {
            currentFileIndex++
            loadCurrentFile()
        }
    }
    
    /**
     * Load the previous file in the list
     */
    fun loadPreviousFile() {
        if (currentFileIndex > 0) {
            currentFileIndex--
            loadCurrentFile()
        }
    }
    
    /**
     * Load file by specific index
     */
    fun loadFileByIndex(index: Int) {
        if (index in 0 until filePathList.size) {
            currentFileIndex = index
            loadCurrentFile()
        }
    }
    
    /**
     * Get current file name
     */
    fun getCurrentFileName(): String {
        return if (currentFileIndex in fileNameList.indices) {
            fileNameList[currentFileIndex]
        } else {
            ""
        }
    }
    
    /**
     * Get current file path
     */
    fun getCurrentFilePath(): String {
        return if (currentFileIndex in filePathList.indices) {
            filePathList[currentFileIndex]
        } else {
            ""
        }
    }
    
    /**
     * Get current file index
     */
    fun getCurrentFileIndex(): Int = currentFileIndex
    
    /**
     * Check if can go to next file
     */
    fun canGoNext(): Boolean = currentFileIndex < filePathList.size - 1
    
    /**
     * Check if can go to previous file
     */
    fun canGoPrevious(): Boolean = currentFileIndex > 0
    
    /**
     * Get total file count
     */
    fun getFileCount(): Int = filePathList.size
    
    /**
     * Load the current file
     */
    private fun loadCurrentFile() {
        val fileName = getCurrentFileName()
        val filePath = getCurrentFilePath()
        
        if (fileName.isNotEmpty() && filePath.isNotEmpty()) {
            listener.onFileLoading(fileName)
            listener.onFileLoaded(filePath, true)
        } else {
            listener.onFileLoadError("Invalid file index: $currentFileIndex")
        }
    }
}