package com.chillpill.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.chillpill.app.databinding.ActivityBlockBinding
import kotlin.random.Random

/**
 * Full-screen block/pause activity launched when user opens a blacklisted app.
 * Shows a countdown then "Continue" or "Go home". Started by AppDetectionAccessibilityService.
 */
class BlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockBinding
    private var targetPackage: String? = null
    private var targetClassName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        targetClassName = intent.getStringExtra(EXTRA_CLASS_NAME)
        Log.i(TAG, "onCreate package=$targetPackage className=$targetClassName")

        if (targetPackage == null) {
            Log.w(TAG, "onCreate: missing EXTRA_PACKAGE, finishing")
            finish()
            return
        }

        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackgroundImageFitByHeight()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back pressed -> go home")
                exitToLauncher()
            }
        })

        startTimerFromIntent(intent)

        binding.btnContinue.setOnClickListener {
            Log.i(TAG, "Continue to app: $targetPackage")
            targetPackage?.let { Prefs(this).setGraceStarted(it) }
            launchTargetApp()
            finish()
        }
        binding.btnCloseHome.setOnClickListener {
            Log.i(TAG, "Close and go home")
            exitToLauncher()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        targetClassName = intent.getStringExtra(EXTRA_CLASS_NAME)
        Log.i(TAG, "onNewIntent package=$targetPackage className=$targetClassName")
        if (targetPackage != null) {
            startTimerFromIntent(intent)
        }
    }

    /** Starts or restarts the breath timer from the configured duration. Call from onCreate and onNewIntent. */
    private fun startTimerFromIntent(intent: Intent) {
        tickRunnable?.let { handler.removeCallbacks(it) }
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        targetClassName = intent.getStringExtra(EXTRA_CLASS_NAME)

        val prefs = Prefs(this)
        val seconds = prefs.timerSeconds
        Log.i(TAG, "Timer seconds=$seconds (restart)")

        binding.blockTimer.visibility = View.VISIBLE
        binding.blockTimer.text = seconds.toString()
        binding.blockButtons.visibility = View.GONE
        binding.blockOverlayDimmer.visibility = View.GONE
        binding.blockOverlayDimmer.alpha = 0f

        var remaining = seconds
        tickRunnable = object : Runnable {
            override fun run() {
                remaining--
                Log.i(TAG, "Tick remaining=$remaining")
                if (remaining > 0) {
                    binding.blockTimer.text = remaining.toString()
                    handler.postDelayed(this, 1000)
                } else {
                    binding.blockTimer.visibility = View.GONE
                    fadeInDimmerAndShowButtons()
                    Log.i(TAG, "Timer finished, buttons visible")
                }
            }
        }
        handler.postDelayed(tickRunnable!!, 1000)
    }

    private fun fadeInDimmerAndShowButtons() {
        binding.blockOverlayDimmer.visibility = View.VISIBLE
        binding.blockOverlayDimmer.alpha = 0f
        binding.blockOverlayDimmer.animate()
            .alpha(1f)
            .setDuration(400)
            .withEndAction {
                positionContinueButtonRandomCorner()
                binding.blockButtons.visibility = View.VISIBLE
            }
            .start()
    }

    private fun positionContinueButtonRandomCorner() {
        val marginPx = (24 * resources.displayMetrics.density).toInt()
        val corners = listOf(
            Gravity.TOP or Gravity.START,
            Gravity.TOP or Gravity.END,
            Gravity.BOTTOM or Gravity.START,
            Gravity.BOTTOM or Gravity.END
        )
        val gravity = corners[Random.nextInt(corners.size)]
        val params = binding.btnContinue.layoutParams as? FrameLayout.LayoutParams ?: return
        params.gravity = gravity
        params.setMargins(marginPx, marginPx, marginPx, marginPx)
        binding.btnContinue.layoutParams = params
    }

    private fun launchTargetApp() {
        val pkg = targetPackage ?: return
        val cls = targetClassName
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (cls != null) {
                    component = ComponentName(pkg, cls)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Launch with component failed, trying getLaunchIntent", e)
            val fallback = packageManager.getLaunchIntentForPackage(pkg)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
            } else {
                Log.e(TAG, "No launch intent for $pkg")
                exitToLauncher()
            }
        }
    }

    private fun exitToLauncher() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    /** Scale image to fit view height and center horizontally (crop left/right). */
    private fun setupBackgroundImageFitByHeight() {
        val iv = binding.blockBackgroundImage
        iv.post {
            val d = iv.drawable ?: return@post
            val vw = iv.width
            val vh = iv.height
            val iw = d.intrinsicWidth.toFloat()
            val ih = d.intrinsicHeight.toFloat()
            if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return@post
            val scale = vh / ih
            val scaledW = iw * scale
            val tx = (vw - scaledW) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(tx, 0f)
            }
            iv.imageMatrix = matrix
        }
    }

    override fun onDestroy() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChillPill/Block"
        const val EXTRA_PACKAGE = "packageName"
        const val EXTRA_CLASS_NAME = "className"
    }
}
