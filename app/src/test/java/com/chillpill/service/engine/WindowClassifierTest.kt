package com.chillpill.service.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowClassifierTest {

    /** Visible packages and their activities; a package missing here is invisible (lookup = null). */
    private val activities = mapOf(
        "com.swiftkey" to setOf("com.swiftkey.SettingsActivity"),
        "com.gboard" to emptySet(),
        "com.instagram.android" to setOf("com.instagram.mainactivity.MainActivity"),
        "com.google.android.apps.nexuslauncher" to setOf("com.android.launcher3.Launcher"),
        "android" to setOf("com.android.internal.app.ChooserActivity")
    )

    private val classifier = WindowClassifier(
        ownPackage = OWN,
        ownMainUiClasses = setOf("com.chillpill.MainActivity", "com.chillpill.ChillpillSettingsActivity"),
        ownBlockUiClasses = setOf("com.chillpill.AppBlockActivity", "com.chillpill.ReInterventionActivity"),
        imePackages = { setOf("com.swiftkey", "com.gboard") },
        isActivityOf = { pkg, cls -> activities[pkg]?.let { cls in it } }
    )

    @Test fun ownWindows() {
        assertEquals(WindowKind.OWN_MAIN_UI, classifier.classify(OWN, "com.chillpill.MainActivity"))
        assertEquals(WindowKind.OWN_MAIN_UI, classifier.classify(OWN, "com.chillpill.ChillpillSettingsActivity"))
        assertEquals(WindowKind.OWN_BLOCK_UI, classifier.classify(OWN, "com.chillpill.AppBlockActivity"))
        assertEquals(WindowKind.OWN_BLOCK_UI, classifier.classify(OWN, "com.chillpill.ReInterventionActivity"))
        assertEquals(WindowKind.OWN_OVERLAY, classifier.classify(OWN, "com.chillpill.service.GraceWarningView"))
        assertEquals(WindowKind.OWN_OVERLAY, classifier.classify(OWN, "android.widget.FrameLayout"))
        assertEquals(WindowKind.OWN_OVERLAY, classifier.classify(OWN, null))
    }

    @Test fun systemUi_isAlwaysAnOverlay() {
        assertEquals(WindowKind.SYSTEM_OVERLAY, classifier.classify("com.android.systemui", "any"))
        assertEquals(WindowKind.SYSTEM_OVERLAY, classifier.classify("com.android.systemui", null))
    }

    @Test fun keyboards() {
        assertEquals(WindowKind.SYSTEM_OVERLAY, classifier.classify("com.gboard", "android.inputmethodservice.SoftInputWindow"))
        assertEquals(WindowKind.SYSTEM_OVERLAY, classifier.classify("com.swiftkey", "android.inputmethodservice.SoftInputWindow"))
        assertEquals(WindowKind.APP, classifier.classify("com.swiftkey", "com.swiftkey.SettingsActivity"))
        assertEquals(WindowKind.SYSTEM_OVERLAY, classifier.classify("com.swiftkey", null))
    }

    @Test fun activities_areApps() {
        assertEquals(WindowKind.APP, classifier.classify("com.instagram.android", "com.instagram.mainactivity.MainActivity"))
        assertEquals(WindowKind.APP, classifier.classify("com.google.android.apps.nexuslauncher", "com.android.launcher3.Launcher"))
        assertEquals(WindowKind.APP, classifier.classify("android", "com.android.internal.app.ChooserActivity"))
    }

    /** Dialogs, bottom sheets, popups and toasts of a visible package never change presence. */
    @Test fun nonActivityWindows_ofVisibleApps_areOverlays() {
        assertEquals(WindowKind.APP_OVERLAY, classifier.classify("com.instagram.android", "android.app.Dialog"))
        assertEquals(WindowKind.APP_OVERLAY, classifier.classify("com.instagram.android", "android.widget.FrameLayout"))
        assertEquals(WindowKind.APP_OVERLAY, classifier.classify("android", "android.widget.PopupWindow"))
        assertEquals(WindowKind.APP_OVERLAY, classifier.classify("android", "android.widget.Toast\$TN"))
    }

    /** The two windows of the Instagram-comments trace: Assistant over the app, Pixel display service. */
    @Test fun recordedCulprits_areOverlays() {
        val visible = mapOf("com.google.android.googlequicksearchbox" to setOf("com.google.android.googlequicksearchbox.SearchActivity"))
        val c = WindowClassifier(OWN, emptySet(), emptySet(), { emptySet() }) { p, cls -> visible[p]?.let { cls in it } }
        assertEquals(WindowKind.APP_OVERLAY, c.classify("com.google.android.googlequicksearchbox", "android.service.voice.VoiceInteractionWindow"))
        assertEquals(WindowKind.APP_OVERLAY, c.classify("com.android.pixeldisplayservice", "android.widget.FrameLayout"))
        assertEquals(WindowKind.APP, c.classify("com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.SearchActivity"))
    }

    /** Probes carry no class: always an app (a RESUME is an activity). */
    @Test fun unknownClass_isAnApp() {
        assertEquals(WindowKind.APP, classifier.classify("com.instagram.android", null))
        assertEquals(WindowKind.APP, classifier.classify("com.instagram.android", ""))
        assertEquals(WindowKind.APP, classifier.classify("com.google.android.apps.nexuslauncher", null))
        assertEquals(WindowKind.APP, classifier.classify("com.invisible", null))
    }

    /** A package we cannot see: framework classes are overlays, anything else is an app. */
    @Test fun invisiblePackage_decidedByNamespace() {
        assertEquals(WindowKind.APP, classifier.classify("com.invisible", "com.invisible.Main"))
        assertEquals(WindowKind.APP, classifier.classify("com.google.android.photopicker", "com.android.photopicker.MainActivity"))
        assertEquals(WindowKind.APP, classifier.classify("com.invisible", "androidx.activity.ComponentActivity"))
        assertEquals(WindowKind.APP_OVERLAY, classifier.classify("com.invisible", "android.app.Dialog"))
        assertEquals(WindowKind.APP_OVERLAY, classifier.classify("com.invisible", "android.widget.FrameLayout"))
        assertEquals(WindowKind.APP_OVERLAY, classifier.classify("com.invisible", "android.view.View"))
    }
}
