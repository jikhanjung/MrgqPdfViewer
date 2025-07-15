package com.mrgq.pdfviewer

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * Handles all input events for the PDF viewer.
 * Single responsibility: Process D-pad and key events.
 */
class ViewerInputHandler(private val listener: InputListener) {
    
    // Navigation state
    private var isNavigationGuideVisible: Boolean = false
    private var navigationGuideType: String = ""
    private var isLongPressing: Boolean = false
    
    // Long press handling
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressDelay = 800L // 800ms
    private var longPressRunnable: Runnable = Runnable {
        if (isLongPressing) {
            Log.d("ViewerInputHandler", "Long press detected - showing options menu")
            listener.onShowOptionsMenu()
            isLongPressing = false
        }
    }
    
    interface InputListener {
        fun onNextPage()
        fun onPreviousPage() 
        fun onNextFile()
        fun onPreviousFile()
        fun onShowOptionsMenu()
        fun onTogglePageInfo()
        fun onBack()
        fun onShowStartOfFileGuide()
        fun onShowEndOfFileGuide()
        fun onHideNavigationGuide()
        fun canGoToNextPage(): Boolean
        fun canGoToPreviousPage(): Boolean
        fun canGoToNextFile(): Boolean
        fun canGoToPreviousFile(): Boolean
        fun isAtFirstPage(): Boolean
        fun isAtLastPage(): Boolean
    }
    
    /**
     * Handle key down events
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("ViewerInputHandler", "onKeyDown: keyCode=$keyCode, isNavigationGuideVisible=$isNavigationGuideVisible")
        
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleLeftKey()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleRightKey()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Currently not used in navigation, but could be extended
                false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Currently not used in navigation, but could be extended
                false
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    // Start long press detection
                    isLongPressing = true
                    longPressHandler.postDelayed(longPressRunnable, longPressDelay)
                }
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                listener.onBack()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                listener.onTogglePageInfo()
                true
            }
            else -> false
        }
    }
    
    /**
     * Handle key up events
     */
    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                // Cancel long press and handle short press
                longPressHandler.removeCallbacks(longPressRunnable)
                
                if (isLongPressing && event?.isCanceled != true) {
                    // This was a short press, not a long press
                    if (isNavigationGuideVisible) {
                        listener.onHideNavigationGuide()
                    } else {
                        listener.onTogglePageInfo()
                    }
                }
                
                isLongPressing = false
                true
            }
            else -> false
        }
    }
    
    /**
     * Handle left key press
     */
    private fun handleLeftKey(): Boolean {
        if (isNavigationGuideVisible) {
            if (navigationGuideType == "start" && listener.canGoToPreviousFile()) {
                // At start of file guide, left key -> go to previous file
                listener.onHideNavigationGuide()
                listener.onPreviousFile()
                return true
            }
            // Navigation guide is visible - block normal page movement
            return true
        } else if (listener.canGoToPreviousPage()) {
            listener.onPreviousPage()
            return true
        } else if (listener.isAtFirstPage()) {
            // At first page - show start of file guide
            listener.onShowStartOfFileGuide()
            return true
        }
        return false
    }
    
    /**
     * Handle right key press
     */
    private fun handleRightKey(): Boolean {
        if (isNavigationGuideVisible) {
            if (navigationGuideType == "end" && listener.canGoToNextFile()) {
                // At end of file guide, right key -> go to next file
                listener.onHideNavigationGuide()
                listener.onNextFile()
                return true
            }
            // Navigation guide is visible - block normal page movement
            return true
        } else if (listener.canGoToNextPage()) {
            listener.onNextPage()
            return true
        } else if (listener.isAtLastPage()) {
            // At last page - show end of file guide
            listener.onShowEndOfFileGuide()
            return true
        }
        return false
    }
    
    /**
     * Update navigation guide visibility state
     */
    fun setNavigationGuideVisible(visible: Boolean, type: String = "") {
        isNavigationGuideVisible = visible
        navigationGuideType = type
        Log.d("ViewerInputHandler", "Navigation guide visibility: $visible, type: $type")
    }
    
    /**
     * Check if navigation guide is visible
     */
    fun isNavigationGuideVisible(): Boolean = isNavigationGuideVisible
    
    /**
     * Get navigation guide type
     */
    fun getNavigationGuideType(): String = navigationGuideType
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        longPressHandler.removeCallbacks(longPressRunnable)
        isLongPressing = false
        isNavigationGuideVisible = false
        navigationGuideType = ""
    }
}