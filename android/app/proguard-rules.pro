# ==============================================================================
# HexNext One UI 9.0 (Android 16 / SDK 36) — R8 & ProGuard Optimization Rules
# Release builds only (see android/app/build.gradle.kts); debug builds are not shrunk.
# ==============================================================================

# Optimize bytecode while preserving line numbers for stack traces
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Keep Android 16 / One UI 9.0 OverlayManager & FabricatedOverlay APIs accessed via Shizuku/reflection
-keep class android.content.om.** { *; }
-keep interface android.content.om.** { *; }

# Keep the app's AIDL surface (IThemeService, its Stub and Proxy) and the service behind it:
# Shizuku instantiates the service by name and talks to it over Binder.
-keep class com.hexnext.themer.IThemeService { *; }
-keep class com.hexnext.themer.IThemeService$* { *; }
-keep class com.hexnext.themer.shizuku.ThemeService { *; }

# Keep Shizuku Binder, Provider & AIDL interfaces
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep class * extends android.os.Binder { *; }
-keep class * implements android.os.IInterface { *; }

# Keep HiddenApiBypass reflective entry points for UID 1000 / UID 0 transactions
-keep class org.lsposed.hiddenapibypass.** { *; }

# Keep Material 3 Monet HCT / DynamicScheme color utilities
-keep class com.google.android.material.color.utilities.** { *; }

# kotlinx.serialization: the generated serializers are reached through reflection-like lookups
# (Companion.serializer(), the $$serializer singletons), which R8 cannot see.
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The engine's @Serializable models (ThemeSpec, ApplyReport, OneUiRules, Pack, ...) are read
# back from files written by earlier builds; keep their names and fields stable.
-keep @kotlinx.serialization.Serializable class com.hexnext.engine.** { *; }
-keep,includedescriptorclasses class com.hexnext.engine.**$$serializer { *; }
-keepclassmembers class com.hexnext.engine.** {
    *** Companion;
}
-keepclasseswithmembers class com.hexnext.engine.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep HexNext native RRO bridge JNI methods (64-bit & 32-bit ABIs)
-keepclasseswithmembernames,includedescriptorclasses class com.hexnext.themer.** {
    native <methods>;
}
