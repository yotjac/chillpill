package com.chillpill.ui.settings

import com.chillpill.data.restricted.AppPolicy
import com.chillpill.service.engine.EngineCore
import com.chillpill.service.engine.Mode

/*
 * Pure pieces of the settings screen (no Android): the editable form of an [AppPolicy] and the
 * rule that decides whether saving needs the confirmation wait. Unit-tested on the JVM.
 */

/** The number fields of a policy editor. */
enum class PolicyField { OPEN_WAIT, TIMES_UP_WAIT, MINUTES }

/**
 * An [AppPolicy] as edited: number fields are text, so they can be empty or out of range while
 * the user types. [toPolicy] turns it back, falling back to saved values for invalid fields.
 */
data class PolicyDraft(
    val mode: Mode,
    val openWait: String,
    val timesUpWait: String,
    val minutes: String,
    val extensionAllowed: Boolean
) {
    fun text(field: PolicyField): String = when (field) {
        PolicyField.OPEN_WAIT -> openWait
        PolicyField.TIMES_UP_WAIT -> timesUpWait
        PolicyField.MINUTES -> minutes
    }

    fun withText(field: PolicyField, text: String): PolicyDraft = when (field) {
        PolicyField.OPEN_WAIT -> copy(openWait = text)
        PolicyField.TIMES_UP_WAIT -> copy(timesUpWait = text)
        PolicyField.MINUTES -> copy(minutes = text)
    }

    /** Only PICK may open without waiting: leaving it with a 0-s wait makes the wait 1 s. */
    fun withMode(newMode: Mode): PolicyDraft {
        val wait = openWait.toIntOrNull()
        return if (wait != null && wait < AppPolicy.minOpenWaitSeconds(newMode)) {
            copy(mode = newMode, openWait = AppPolicy.minOpenWaitSeconds(newMode).toString())
        } else {
            copy(mode = newMode)
        }
    }

    fun isValid(field: PolicyField): Boolean = text(field).toIntOrNull()?.let { it in range(field, mode) } ?: false

    /** Field value if valid, else [fallback]'s. */
    fun value(field: PolicyField, fallback: AppPolicy): Int =
        if (isValid(field)) text(field).toInt() else valueOf(fallback, field)

    fun toPolicy(fallback: AppPolicy): AppPolicy = AppPolicy(
        mode = mode,
        openWaitSeconds = value(PolicyField.OPEN_WAIT, fallback),
        timesUpWaitSeconds = value(PolicyField.TIMES_UP_WAIT, fallback),
        minutes = value(PolicyField.MINUTES, fallback),
        extensionAllowed = extensionAllowed
    ).sanitized()

    companion object {
        fun of(p: AppPolicy) = PolicyDraft(
            mode = p.mode,
            openWait = p.openWaitSeconds.toString(),
            timesUpWait = p.timesUpWaitSeconds.toString(),
            minutes = p.minutes.toString(),
            extensionAllowed = p.extensionAllowed
        )

        fun range(field: PolicyField, mode: Mode): IntRange = when (field) {
            PolicyField.OPEN_WAIT -> AppPolicy.minOpenWaitSeconds(mode)..AppPolicy.MAX_WAIT_SECONDS
            PolicyField.TIMES_UP_WAIT -> AppPolicy.MIN_TIMES_UP_WAIT_SECONDS..AppPolicy.MAX_WAIT_SECONDS
            PolicyField.MINUTES -> AppPolicy.MIN_MINUTES..AppPolicy.MAX_MINUTES
        }

        fun valueOf(p: AppPolicy, field: PolicyField): Int = when (field) {
            PolicyField.OPEN_WAIT -> p.openWaitSeconds
            PolicyField.TIMES_UP_WAIT -> p.timesUpWaitSeconds
            PolicyField.MINUTES -> p.minutes
        }

        /** Which number fields a mode shows. */
        fun fields(mode: Mode): List<PolicyField> = when (mode) {
            Mode.PAUSE_ONLY -> listOf(PolicyField.OPEN_WAIT, PolicyField.MINUTES)
            Mode.TIME_LIMIT -> listOf(PolicyField.OPEN_WAIT, PolicyField.MINUTES, PolicyField.TIMES_UP_WAIT)
            Mode.PICK -> listOf(PolicyField.OPEN_WAIT, PolicyField.TIMES_UP_WAIT)
        }
    }
}

/** Longest visit a mode allows, in minutes; null for PAUSE_ONLY (never stopped). */
private fun AppPolicy.maxVisitMinutes(): Int? = when (mode) {
    Mode.PAUSE_ONLY -> null
    Mode.TIME_LIMIT -> minutes
    Mode.PICK -> (EngineCore.PICK_MAX_MS / 60_000L).toInt()
}

/**
 * True when [new] lets the user in sooner or for longer than [old] (specs/per-app-modes.md).
 * Moving to a stricter mode, or adding friction, is never looser.
 */
internal fun isPolicyLooser(old: AppPolicy, new: AppPolicy): Boolean {
    if (new.openWaitSeconds < old.openWaitSeconds) return true
    val oldMax = old.maxVisitMinutes()
    val newMax = new.maxVisitMinutes()
    return when {
        oldMax != null && newMax == null -> true // stopped -> never stopped
        oldMax == null && newMax == null -> new.minutes > old.minutes // longer free re-entry
        oldMax == null -> false // never stopped -> stopped
        else -> newMax!! > oldMax ||
            new.timesUpWaitSeconds < old.timesUpWaitSeconds ||
            (!old.extensionAllowed && new.extensionAllowed)
    }
}

/**
 * True only if saving would lower the user's standards: a restricted app removed, the
 * confirmation wait shortened, or the policy in effect for any app that was already restricted
 * loosened (apps without their own policy have [AppPolicy.DEFAULT]). Everything else (stricter
 * values, added apps, background) saves without the confirmation wait.
 */
internal fun isRestrictionReducing(
    originalRestricted: Set<String>,
    draftRestricted: Set<String>,
    originalOverrides: Map<String, AppPolicy>,
    draftOverrides: Map<String, AppPolicy>,
    originalConfirmationWaitSeconds: Int,
    draftConfirmationWaitSeconds: Int
): Boolean =
    !draftRestricted.containsAll(originalRestricted) ||
        draftConfirmationWaitSeconds < originalConfirmationWaitSeconds ||
        originalRestricted.any { pkg ->
            isPolicyLooser(
                originalOverrides[pkg] ?: AppPolicy.DEFAULT,
                draftOverrides[pkg] ?: AppPolicy.DEFAULT
            )
        }
