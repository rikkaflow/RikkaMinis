# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-placed-storm-diag] v2: keep chat UI + diagnostics class/method
# names readable in release builds so PlaceStorm stack dumps (main-thread
# getStackTrace at storm time) map to real symbols. The 2026-09-01 dump was
# fully obfuscated (F2.j / a0.e0 / k0.k1 / c0.D0) and un-mappable without a
# mapping.txt. Cost: a few KB of retained names in one UI package — nothing
# hot-path. Keep rules are source-compatible with line-number usage:
-keepattributes SourceFile,LineNumberTable
-keep class com.openminis.app.ui.chat.** { *; }
-keep class com.openminis.app.diagnostics.** { *; }
