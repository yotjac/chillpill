package com.chillpill.data.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.chillpill.R

/** A background image bundled in the APK. */
data class BuiltInBackground(
    val id: String,
    @StringRes val labelRes: Int,
    @DrawableRes val drawableRes: Int
)

/**
 * Registry of the bundled block screen backgrounds — the single source of truth for both the
 * settings picker and the block screen loader.
 *
 * Removing an entry in a later version is safe: unknown ids decode back to the default.
 */
object BlockBackgrounds {

    const val DEFAULT_ID = "default"

    val all: List<BuiltInBackground> = listOf(
        BuiltInBackground(DEFAULT_ID, R.string.background_default, R.drawable.block_activity_background)
    )

    private val byId: Map<String, BuiltInBackground> = all.associateBy { it.id }

    fun isKnown(id: String): Boolean = id in byId

    fun find(id: String): BuiltInBackground? = byId[id]

    @DrawableRes
    fun drawableResFor(id: String): Int =
        byId[id]?.drawableRes ?: byId[DEFAULT_ID]?.drawableRes ?: R.drawable.block_activity_background
}
