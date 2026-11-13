package com.chillpill.data.settings

import com.chillpill.data.restricted.PolicyResolver

/**
 * Background image shown on the block ("waiting") screen.
 *
 * Persisted by [SettingsRepository] as a single string: `builtin:<id>` or `custom:<fileName>`.
 * Anything unparseable falls back to [Default], so older installs need no migration.
 */
sealed interface BlockBackground {

    /** One of the images bundled in the APK; see [BlockBackgrounds]. */
    data class BuiltIn(val id: String) : BlockBackground

    /** A photo the user picked, stored by `BlockBackgroundStore` in `filesDir/backgrounds/`. */
    data class Custom(val fileName: String) : BlockBackground

    companion object {
        val Default: BlockBackground = BuiltIn(BlockBackgrounds.DEFAULT_ID)

        fun encode(background: BlockBackground): String = when (background) {
            is BuiltIn -> "builtin:${background.id}"
            is Custom -> "custom:${background.fileName}"
        }

        fun decode(raw: String?): BlockBackground {
            if (raw.isNullOrBlank()) return Default
            val separator = raw.indexOf(':')
            if (separator <= 0 || separator == raw.lastIndex) return Default
            val prefix = raw.substring(0, separator)
            val value = raw.substring(separator + 1)
            return when (prefix) {
                "builtin" -> if (BlockBackgrounds.isKnown(value)) BuiltIn(value) else Default
                // Guard against path traversal from a corrupted/restored value.
                "custom" -> if (value.contains('/') || value.contains('\\') || value.contains("..")) {
                    Default
                } else {
                    Custom(value)
                }
                else -> Default
            }
        }
    }
}

/**
 * Global settings: the wait before saving looser settings and the block screen background.
 * Per-app policies live with the restricted apps ([com.chillpill.data.restricted.AppPolicy],
 * specs/per-app-modes.md).
 */
data class Settings(
    val confirmationWaitSeconds: Int = PolicyResolver.DEFAULT_CONFIRMATION_WAIT_SECONDS,
    val blockBackground: BlockBackground = BlockBackground.Default,
    /** Read only: the single wait / grace from before per-app modes, if one was ever saved. */
    val legacyWaitSeconds: Int? = null,
    val legacyGraceMinutes: Int? = null
)
