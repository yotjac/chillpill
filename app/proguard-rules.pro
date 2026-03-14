# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ---------------------------------------------------------------------------
# Kotlin
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
# Gson
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# ---------------------------------------------------------------------------
# Room
# Room's AAR ships consumer rules for entities/DAOs; keep query-result classes.
# ---------------------------------------------------------------------------
-keep class com.chillpill.data.usage.EventCountRow { *; }
-keep class com.chillpill.data.usage.DailyEventCountRow { *; }
-keepclassmembers class com.chillpill.data.usage.UsageEvent { *; }

# ---------------------------------------------------------------------------
# AccessibilityService (manifest-referenced; explicit keep for safety)
# ---------------------------------------------------------------------------
-keep class com.chillpill.service.ChillpillAccessibilityService { *; }
