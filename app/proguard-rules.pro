# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Nothing Glyph SDK classes
-keep class com.nothing.ketchum.** { *; }

# Glyph Toy services
-keep class com.theivan.endlesslife.EndlessLifeService { *; }
-keep class com.theivan.endlesslife.GlyphMatrixService { *; }

# Enum persistence (StartingAnimationType saved by name)
-keepclassmembers enum com.theivan.endlesslife.StartingAnimationType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Strip debug logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Standard Android/Kotlin keeps
-keepattributes Signature,Annotation,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# For the AAR library
-keep class **.R { *; }
-keep class **.R$* { *; }