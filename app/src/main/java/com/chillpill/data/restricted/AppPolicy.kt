package com.chillpill.data.restricted

import com.chillpill.service.engine.AppRule
import com.chillpill.service.engine.Config
import com.chillpill.service.engine.Mode
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Everything the user configures for one restricted app (specs/per-app-modes.md): its [mode] plus
 * the numbers that mode uses. The engine only sees [toRule]; the wait times are read by the block
 * screens.
 *
 * Pure Kotlin (no Android), so the codec and the legacy resolution are unit-tested on the JVM.
 */
data class AppPolicy(
    val mode: Mode = Mode.TIME_LIMIT,
    /** Block screen wait when the app is opened. 0 is only allowed for [Mode.PICK]. */
    val openWaitSeconds: Int = DEFAULT_WAIT_SECONDS,
    /** Re-intervention wait when time runs out (TIME_LIMIT; PICK: before picking again). */
    val timesUpWaitSeconds: Int = DEFAULT_WAIT_SECONDS,
    /** PAUSE_ONLY: free re-entry window. TIME_LIMIT: time allowed. PICK: unused. */
    val minutes: Int = DEFAULT_MINUTES,
    /** "+10 s once" on the first warning pill (TIME_LIMIT / PICK). */
    val extensionAllowed: Boolean = true
) {
    fun toRule(): AppRule = AppRule(mode, minutes * 60_000L, extensionAllowed)

    /** Wait of a block screen: the opening one, or the re-intervention ("time's up") one. */
    fun waitSeconds(reIntervention: Boolean): Int = if (reIntervention) timesUpWaitSeconds else openWaitSeconds

    /** Every field inside its range; a 0-s opening wait outside PICK becomes 1 s. */
    fun sanitized(): AppPolicy = copy(
        openWaitSeconds = openWaitSeconds.coerceIn(minOpenWaitSeconds(mode), MAX_WAIT_SECONDS),
        timesUpWaitSeconds = timesUpWaitSeconds.coerceIn(MIN_TIMES_UP_WAIT_SECONDS, MAX_WAIT_SECONDS),
        minutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
    )

    companion object {
        const val DEFAULT_WAIT_SECONDS = 10
        const val DEFAULT_MINUTES = 5
        const val MAX_WAIT_SECONDS = 7200 // 2 hours
        const val MIN_TIMES_UP_WAIT_SECONDS = 1
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 1440 // 24 hours

        fun minOpenWaitSeconds(mode: Mode): Int = if (mode == Mode.PICK) 0 else 1

        /**
         * What every restricted app gets until the user changes it on its card: Time limit, 10 s
         * waits, 5 min. Fixed, not a setting (there is no "defaults" section).
         */
        val DEFAULT = AppPolicy()
    }
}

/** JSON encoding of [AppPolicy] for DataStore. Tolerant: unknown or missing fields fall back. */
object AppPolicyCodec {

    fun encode(policy: AppPolicy): String = toJson(policy).toString()

    fun decode(raw: String?): AppPolicy? = try {
        raw?.let { fromJson(JsonParser.parseString(it).asJsonObject) }
    } catch (_: Exception) {
        null
    }

    fun encodeMap(policies: Map<String, AppPolicy>): String {
        val o = JsonObject()
        for ((pkg, p) in policies.toSortedMap()) o.add(pkg, toJson(p))
        return o.toString()
    }

    fun decodeMap(raw: String?): Map<String, AppPolicy>? = try {
        raw?.let {
            JsonParser.parseString(it).asJsonObject.entrySet().mapNotNull { (pkg, v) ->
                (v as? JsonObject)?.let { obj -> pkg to fromJson(obj) }
            }.toMap()
        }
    } catch (_: Exception) {
        null
    }

    private fun toJson(p: AppPolicy) = JsonObject().apply {
        addProperty("mode", p.mode.name)
        addProperty("openWait", p.openWaitSeconds)
        addProperty("timesUpWait", p.timesUpWaitSeconds)
        addProperty("minutes", p.minutes)
        addProperty("extension", p.extensionAllowed)
    }

    private fun fromJson(o: JsonObject): AppPolicy {
        val d = AppPolicy()
        fun int(key: String, fallback: Int) = try { o.get(key)?.asInt ?: fallback } catch (_: Exception) { fallback }
        val mode = Mode.values().firstOrNull { it.name == o.get("mode")?.asString } ?: d.mode
        return AppPolicy(
            mode = mode,
            openWaitSeconds = int("openWait", d.openWaitSeconds),
            timesUpWaitSeconds = int("timesUpWait", d.timesUpWaitSeconds),
            minutes = int("minutes", d.minutes),
            extensionAllowed = try { o.get("extension")?.asBoolean ?: true } catch (_: Exception) { true }
        ).sanitized()
    }
}

/**
 * Turns what is stored into the policies in effect, falling back to the keys used before
 * per-app modes (one wait, one grace, a "re-intervention disabled" set). Nothing is migrated on
 * disk: the first Save writes the new keys.
 */
object PolicyResolver {
    /** Confirmation wait of a fresh install. */
    const val DEFAULT_CONFIRMATION_WAIT_SECONDS = 10
    /** Wait and grace an old install had when it never changed them. */
    const val LEGACY_DEFAULT_WAIT_SECONDS = 12
    const val LEGACY_DEFAULT_GRACE_MINUTES = 5

    /**
     * Before per-app modes the confirmation screen waited the opening wait, so an existing install
     * keeps that (an upgrade never shortens it); a fresh install gets 10 s.
     */
    fun confirmationWaitSeconds(stored: Int?, legacyWaitSeconds: Int?, setupCompleted: Boolean): Int =
        (stored ?: legacyWaitSeconds ?: if (setupCompleted) LEGACY_DEFAULT_WAIT_SECONDS else DEFAULT_CONFIRMATION_WAIT_SECONDS)
            .coerceIn(1, AppPolicy.MAX_WAIT_SECONDS)

    /**
     * Policies of [restricted] apps that differ from [AppPolicy.DEFAULT]. Without stored policies
     * but with settings from before per-app modes, every restricted app keeps what it had: the old
     * wait (opening and time's up) and grace, as PAUSE_ONLY if "Block again after grace" was off.
     */
    fun overrides(
        stored: Map<String, AppPolicy>?,
        legacyWaitSeconds: Int?,
        legacyGraceMinutes: Int?,
        legacyReInterventionDisabled: Set<String>,
        restricted: Set<String>
    ): Map<String, AppPolicy> {
        val hasLegacy = legacyWaitSeconds != null || legacyGraceMinutes != null || legacyReInterventionDisabled.isNotEmpty()
        val all = when {
            stored != null -> stored
            hasLegacy -> {
                val legacy = AppPolicy(
                    mode = Mode.TIME_LIMIT,
                    openWaitSeconds = legacyWaitSeconds ?: LEGACY_DEFAULT_WAIT_SECONDS,
                    timesUpWaitSeconds = legacyWaitSeconds ?: LEGACY_DEFAULT_WAIT_SECONDS,
                    minutes = legacyGraceMinutes ?: LEGACY_DEFAULT_GRACE_MINUTES
                ).sanitized()
                restricted.associateWith {
                    if (it in legacyReInterventionDisabled) legacy.copy(mode = Mode.PAUSE_ONLY) else legacy
                }
            }
            else -> emptyMap()
        }
        return normalized(all, restricted)
    }

    /** Only restricted apps, and only policies that differ from the default (same thing otherwise). */
    fun normalized(policies: Map<String, AppPolicy>, restricted: Set<String>): Map<String, AppPolicy> =
        policies.filter { (pkg, p) -> pkg in restricted && p != AppPolicy.DEFAULT }

    fun effective(pkg: String, overrides: Map<String, AppPolicy>): AppPolicy = overrides[pkg] ?: AppPolicy.DEFAULT

    fun config(restricted: Set<String>, overrides: Map<String, AppPolicy>): Config = Config(
        rules = restricted.associateWith { effective(it, overrides).toRule() },
        defaultRule = AppPolicy.DEFAULT.toRule()
    )
}

/** Restricted apps with the policy in effect for each: what the engine and the block screens use. */
data class Restrictions(
    val restricted: Set<String>,
    /** Apps whose policy differs from [AppPolicy.DEFAULT]; only restricted apps appear here. */
    val overrides: Map<String, AppPolicy>
) {
    fun policyFor(pkg: String): AppPolicy = PolicyResolver.effective(pkg, overrides)

    fun config(): Config = PolicyResolver.config(restricted, overrides)

    companion object {
        fun from(
            restricted: Set<String>,
            storedOverrides: Map<String, AppPolicy>?,
            legacyWaitSeconds: Int?,
            legacyGraceMinutes: Int?,
            legacyReInterventionDisabled: Set<String>
        ) = Restrictions(
            restricted,
            PolicyResolver.overrides(storedOverrides, legacyWaitSeconds, legacyGraceMinutes, legacyReInterventionDisabled, restricted)
        )
    }
}
