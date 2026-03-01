package com.chillpill.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.chillpill.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private val appPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            adapter.updateApps(loadBlacklistApps())
        }
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: BlacklistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        Log.i(TAG, "blacklist size=${prefs.blacklist.size} timerSeconds=${prefs.timerSeconds}")

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { saveAndFinish() }

        binding.editTimerSeconds.setText(prefs.timerSeconds.toString())
        binding.editTimerSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTimerSeconds()
        }

        binding.editGraceMinutes.setText(prefs.gracePeriodMinutes.toString())
        binding.editGraceMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveGraceMinutes()
        }

        adapter = BlacklistAdapter(
            prefs = prefs,
            apps = loadBlacklistApps(),
            onRemove = { pkg ->
                prefs.blacklist = prefs.blacklist - pkg
                adapter.updateApps(loadBlacklistApps())
            }
        )
        binding.recyclerWhitelist.layoutManager = LinearLayoutManager(this)
        binding.recyclerWhitelist.adapter = adapter

        binding.btnAddApp.setOnClickListener {
            appPickerLauncher.launch(Intent(this, AppPickerActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        menu.findItem(R.id.action_ok)?.actionView?.setOnClickListener { saveAndFinish() }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_ok) {
            saveAndFinish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun saveAndFinish() {
        saveTimerSeconds()
        saveGraceMinutes()
        setResult(RESULT_OK)
        finish()
    }

    override fun onPause() {
        saveTimerSeconds()
        saveGraceMinutes()
        Log.i(TAG, "onPause blacklist size=${prefs.blacklist.size} timerSeconds=${prefs.timerSeconds} graceMinutes=${prefs.gracePeriodMinutes}")
        super.onPause()
    }

    private fun saveTimerSeconds() {
        binding.editTimerSeconds.text?.toString()?.toIntOrNull()?.let { sec ->
            prefs.timerSeconds = sec.coerceIn(1, 999)
        }
    }

    private fun saveGraceMinutes() {
        binding.editGraceMinutes.text?.toString()?.toIntOrNull()?.let { min ->
            prefs.gracePeriodMinutes = min.coerceIn(1, 1440)
        }
    }

    private fun loadBlacklistApps(): List<AppItem> {
        val pm = packageManager
        return prefs.blacklist.mapNotNull { pkg ->
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
