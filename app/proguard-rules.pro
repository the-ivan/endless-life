# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Nothing Glyph SDK classes
-keep class com.nothing.ketchum.** { *; }

# Keep Compose runtime (R8/AGP usually handles, but be explicit for safety)
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Strip debug logs in release (reduce size and hide debug info)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep service for Glyph toy binding
-keep class com.theivan.endlesslife.EndlessLifeService { *; }

# Keep reflection for settings / enums if any
-keepclassmembers class **.EndlessLifeSettings { *; }
-keepclassmembers enum **.StartingAnimationType { *; }

# Standard Android/Kotlin keeps
-keepattributes Signature,Annotation,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# For the AAR library
-keep class **.R { *; }
-keep class **.R$* { *; }
