package com.chillpill.ui.home

import android.content.ComponentName
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.chillpill.ChillpillApp
import com.chillpill.service.ChillpillAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(
    private val app: ChillpillApp
) : ViewModel() {

    private val _permissionsOk = MutableStateFlow(false)
    val permissionsOk: StateFlow<Boolean> = _permissionsOk.asStateFlow()

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val expected = ComponentName(app, ChillpillAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        _permissionsOk.value = enabled.split(':').any { it.trim() == expected }
    }
}
