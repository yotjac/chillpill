package com.chillpill.data.settings

/**
 * User-configurable settings for wait time and grace period.
 */
data class Settings(
    val waitTimeSeconds: Int = 12,
    val gracePeriodMinutes: Int = 5
)
