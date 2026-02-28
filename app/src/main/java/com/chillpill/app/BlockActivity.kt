package com.chillpill.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.chillpill.app.databinding.ActivityBlockBinding

/**
 * Full-screen block/pause activity launched when user opens a non-whitelisted app.
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back pressed -> go home")
                exitToLauncher()
            }
        })

        val prefs = Prefs(this)
        val seconds = prefs.timerSeconds
        Log.i(TAG, "Timer seconds=$seconds")

        binding.blockTimer.text = seconds.toString()
        binding.blockMessage.text = getString(R.string.wait_message)
        binding.blockButtons.visibility = android.view.View.GONE

        var remaining = seconds
        tickRunnable = object : Runnable {
            override fun run() {
                remaining--
                Log.i(TAG, "Tick remaining=$remaining")
                if (remaining > 0) {
                    binding.blockTimer.text = remaining.toString()
                    handler.postDelayed(this, 1000)
                } else {
                    binding.blockTimer.visibility = android.view.View.GONE
                    binding.blockMessage.text = getString(R.string.wait_message)
                    binding.blockButtons.visibility = android.view.View.VISIBLE
                    Log.i(TAG, "Timer finished, buttons visible")
                }
            }
        }
        handler.postDelayed(tickRunnable!!, 1000)

        binding.btnContinue.setOnClickListener {
            Log.i(TAG, "Continue to app: $targetPackage")
            launchTargetApp()
            finish()
        }
        binding.btnCloseHome.setOnClickListener {
            Log.i(TAG, "Close and go home")
            exitToLauncher()
        }
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
