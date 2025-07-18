package com.mrgq.pdfviewer

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.mrgq.pdfviewer.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val SPLASH_DURATION = 2500L // 2.5 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide system UI for immersive experience
        hideSystemUI()

        // Set version number
        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"

        // Start animations
        startSplashAnimations()

        // Navigate to MainActivity after delay
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToMain()
        }, SPLASH_DURATION)
    }

    private fun startSplashAnimations() {
        // Initial state - hide elements
        binding.logoImage.scaleX = 0f
        binding.logoImage.scaleY = 0f
        binding.logoImage.alpha = 0f
        
        binding.appNameText.translationY = 50f
        binding.appNameText.alpha = 0f
        
        binding.versionText.alpha = 0f
        binding.taglineText.alpha = 0f
        
        // Logo animation - scale and fade in with overshoot
        val logoScaleX = ObjectAnimator.ofFloat(binding.logoImage, "scaleX", 0f, 1.1f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.logoImage, "scaleY", 0f, 1.1f, 1f)
        val logoAlpha = ObjectAnimator.ofFloat(binding.logoImage, "alpha", 0f, 1f)
        
        logoScaleX.duration = 800
        logoScaleY.duration = 800
        logoAlpha.duration = 600
        
        logoScaleX.interpolator = OvershootInterpolator(2f)
        logoScaleY.interpolator = OvershootInterpolator(2f)
        
        // App name animation - slide up and fade in
        val nameTranslation = ObjectAnimator.ofFloat(binding.appNameText, "translationY", 50f, 0f)
        val nameAlpha = ObjectAnimator.ofFloat(binding.appNameText, "alpha", 0f, 1f)
        
        nameTranslation.duration = 700
        nameAlpha.duration = 700
        nameTranslation.interpolator = DecelerateInterpolator()
        
        // Version and tagline fade in
        val versionFade = ObjectAnimator.ofFloat(binding.versionText, "alpha", 0f, 1f)
        val taglineFade = ObjectAnimator.ofFloat(binding.taglineText, "alpha", 0f, 1f)
        
        versionFade.duration = 500
        taglineFade.duration = 600
        
        // Create animator set with delays
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(logoScaleX, logoScaleY, logoAlpha)
        animatorSet.play(nameTranslation).with(nameAlpha).after(200)
        animatorSet.play(versionFade).after(nameTranslation)
        animatorSet.play(taglineFade).after(versionFade)
        
        // Add a subtle pulse animation to the logo after initial animation
        logoScaleX.addListener(object : Animator.AnimatorListener {
            override fun onAnimationEnd(animation: Animator) {
                startPulseAnimation()
            }
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })
        
        animatorSet.start()
    }

    private fun startPulseAnimation() {
        val pulseX = ObjectAnimator.ofFloat(binding.logoImage, "scaleX", 1f, 1.05f, 1f)
        val pulseY = ObjectAnimator.ofFloat(binding.logoImage, "scaleY", 1f, 1.05f, 1f)
        
        pulseX.duration = 1000
        pulseY.duration = 1000
        pulseX.repeatCount = ObjectAnimator.INFINITE
        pulseY.repeatCount = ObjectAnimator.INFINITE
        
        val pulseSet = AnimatorSet()
        pulseSet.playTogether(pulseX, pulseY)
        pulseSet.start()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        
        // Custom transition animation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        
        finish()
    }
}