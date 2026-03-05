package com.chillpill.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(
    // Placeholder: no permission check yet; will use AccessibilityService check later
) : ViewModel() {

    private val _permissionsOk = MutableStateFlow(false)
    val permissionsOk: StateFlow<Boolean> = _permissionsOk.asStateFlow()
}
