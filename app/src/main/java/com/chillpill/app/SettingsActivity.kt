package com.chillpill.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.chillpill.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private val appPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            adapter.updateApps(loadWhitelistApps())
        }
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: WhitelistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        Log.d(TAG, "whitelist size=${prefs.whitelist.size} timerSeconds=${prefs.timerSeconds}")

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.editTimerSeconds.setText(prefs.timerSeconds.toString())
        binding.editTimerSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTimerSeconds()
        }

        adapter = WhitelistAdapter(
            prefs = prefs,
            apps = loadWhitelistApps(),
            onRemove = { pkg ->
                prefs.whitelist = prefs.whitelist - pkg
                adapter.updateApps(loadWhitelistApps())
            }
        )
        binding.recyclerWhitelist.layoutManager = LinearLayoutManager(this)
        binding.recyclerWhitelist.adapter = adapter

        binding.btnAddApp.setOnClickListener {
            appPickerLauncher.launch(Intent(this, AppPickerActivity::class.java))
        }
    }

    override fun onPause() {
        saveTimerSeconds()
        Log.d(TAG, "onPause whitelist size=${prefs.whitelist.size} timerSeconds=${prefs.timerSeconds}")
        super.onPause()
    }

    private fun saveTimerSeconds() {
        binding.editTimerSeconds.text?.toString()?.toIntOrNull()?.let { sec ->
            prefs.timerSeconds = sec.coerceIn(1, 999)
        }
    }

    private fun loadWhitelistApps(): List<AppItem> {
        val pm = packageManager
        return prefs.whitelist.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                AppItem(
                    pkg,
                    info.loadLabel(pm).toString(),
                    info.loadIcon(pm)
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    companion object {
        private const val TAG = "ChillPill/Settings"
    }
}

data class AppItem(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable)
