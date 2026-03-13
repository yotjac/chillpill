package com.chillpill.data.suggestion

/**
 * Hardcoded list of apps that should never be suggested for restriction.
 *
 * This intentionally includes common browsers, communication tools, system utilities,
 * and critical alert apps so ChillPill does not interfere with essential usage.
 */
object ExcludedApps {

    val EXCLUDED_PACKAGES: Set<String> = setOf(
        // Browsers
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",

        // AI / chat assistants
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",

        // Phone / Contacts / SMS
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.google.android.contacts",
        "com.samsung.android.contacts",
        "com.android.contacts",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",

        // Spotify
        "com.spotify.music",

        // WhatsApp
        "com.whatsapp",
        "com.whatsapp.w4b",

        // Google apps
        "com.google.android.apps.maps",
        "com.google.android.gm",
        "com.google.android.apps.docs",
        "com.google.android.apps.photos",
        "com.android.vending",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.google.android.calendar",
        "com.google.android.keep",
        "com.google.android.googlequicksearchbox",

        // Settings
        "com.android.settings",
        "com.samsung.android.app.settings",

        // Clock
        "com.google.android.deskclock",
        "com.samsung.android.app.clockpack",
        "com.android.deskclock",

        // Red alert / emergency apps (Israel)
        "com.red.alert",
        "com.cumta.pikud",

        // Launchers / home screens
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.miui.home",
        "com.oneplus.launcher",
        "com.oppo.launcher",
        "com.android.launcher3",
        "com.android.launcher",

        // Own package (ChillPill itself)
        "com.chillpill"
    )
}

