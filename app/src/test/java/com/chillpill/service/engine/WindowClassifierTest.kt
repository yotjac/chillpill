package com.chillpill.service.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowClassifierTest {

    private val classifier = WindowClassifier(
        ownPackage = OWN,
        ownMainUiClasses = setOf("com.chillpill.MainActivity", "com.chillpill.ChillpillSettingsActivity"),
        ownBlockUiClasses = setOf("com.chillpill.AppBlockActivity", "com.chillpill.ReInterventionActivity"),
        imePackages = { setOf("com.swiftkey", "com.gboard") },
        isActivityOf = { pkg, cls -> pkg == "com.swiftkey" && cls == "com.swiftkey.SettingsActivity" }
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

    @Test fun everythingElse_isAnApp() {
        assertEquals(WindowKind.APP, classifier.classify("com.instagram.android", "x.MainActivity"))
        assertEquals(WindowKind.APP, classifier.classify("com.google.android.apps.nexuslauncher", null))
    }
}
