package com.chillpill.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chillpill.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionButtonLabels() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnAccessibility.setOnClickListener { requestAccessibility() }
        binding.btnOverlay.setOnClickListener { requestOverlay() }
        binding.btnNotification.setOnClickListener { requestNotification() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.btnNotification.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume a11y=${isAccessibilityEnabled()} overlay=${canDrawOverlays()}")
        updatePermissionButtonLabels()
    }

    private fun updatePermissionButtonLabels() {
        val a11yOk = isAccessibilityEnabled()
        val overlayOk = canDrawOverlays()
        val notifOk = !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) || hasNotificationPermission()

        binding.btnAccessibility.text = if (a11yOk) getString(R.string.enable_accessibility) + " — " + getString(R.string.permission_granted)
            else getString(R.string.enable_accessibility) + " — " + getString(R.string.permission_missing)
        binding.btnOverlay.text = if (overlayOk) getString(R.string.enable_overlay) + " — " + getString(R.string.permission_granted)
            else getString(R.string.enable_overlay) + " — " + getString(R.string.permission_missing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.btnNotification.text = if (notifOk) getString(R.string.enable_notification) + " — " + getString(R.string.permission_granted)
                else getString(R.string.enable_notification) + " — " + getString(R.string.permission_missing)
        }

        val anyMissing = !a11yOk || !overlayOk || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOk)
        binding.permsWarning.visibility = if (anyMissing) View.VISIBLE else View.GONE
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "${packageName}/${AppDetectionAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.trim() == expected }
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

    private fun requestAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasNotificationPermission()) {
                val intent = Intent().apply {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } else {
                requestNotification.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private const val TAG = "ChillPill/Main"
    }
}
