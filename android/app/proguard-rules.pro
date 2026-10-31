# ==============================================================================
# HexNext One UI 9.0 (Android 16 / SDK 36) — R8 & ProGuard Optimization Rules
# ==============================================================================

# Optimize bytecode while preserving line numbers for stack traces
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod

# Keep Android 16 / One UI 9.0 OverlayManager & FabricatedOverlay APIs accessed via Shizuku/reflection
-keep class android.content.om.** { *; }
-keep interface android.content.om.** { *; }

# Keep Shizuku Binder, Provider & AIDL interfaces
-keep class com.hexnext.themer.shizuku.ThemeService { *; }
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep class * extends android.os.Binder { *; }
-keep class * implements android.os.IInterface { *; }

# Keep HiddenApiBypass reflective entry points for UID 1000 / UID 0 transactions
-keep class org.lsposed.hiddenapibypass.** { *; }

# Keep Material 3 Monet HCT / DynamicScheme color utilities
-keep class com.google.android.material.color.utilities.** { *; }

# Keep HexNext native RRO bridge JNI methods (64-bit & 32-bit ABIs)
-keepclasseswithmembernames,includedescriptorclasses class com.hexnext.themer.** {
    native <methods>;
}
