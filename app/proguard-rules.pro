# Mobile Harness R8 rules.
#
# Release builds are minified and resource-shrunk. Everything the app loads by
# name at runtime has to be spelled out here; plain references are handled by
# R8 automatically.
#
# Nothing in the app uses class-name based (de)serialization — transcripts,
# preferences and provider payloads are hand-built org.json trees with literal
# keys — so no data-model classes need to be kept for that reason.

# ---------------------------------------------------------------------------
# JNI: the C side (src/main/cpp/pocket_spawn.c) exports fixed symbol names
# (Java_com_jarves_mh_runtime_NativeSpawn_spawn/waitFor/kill/resizePty), so the
# Kotlin object that declares them must keep both its name and method names.
# ---------------------------------------------------------------------------
-keep class com.jarves.mh.runtime.NativeSpawn { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Android components referenced only from the manifest.
# ---------------------------------------------------------------------------
-keep class com.jarves.mh.widget.AskWidgetProvider { *; }
-keep class com.jarves.mh.tools.device.MhAccessibilityService { *; }
-keep class com.jarves.mh.runtime.AndroidAppInstallReceiver { *; }
-keep class com.jarves.mh.runtime.RuntimeExecutionService { *; }
-keep class com.jarves.mh.runtime.RuntimeSetupService { *; }
-keep class com.jarves.mh.runtime.SessionKeepAliveService { *; }
-keep class com.jarves.mh.runtime.ScreenShareService { *; }

# ---------------------------------------------------------------------------
# Room: generated implementations are looked up reflectively, and the DAO
# signatures are mirrored by generated code.
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Plugins: tool hosts are resolved through PackageManager across app
# boundaries (ACTION_TOOL_HOST), so their IPC entry points stay public API.
# ---------------------------------------------------------------------------
-keep class com.jarves.mh.plugins.** { *; }

# ---------------------------------------------------------------------------
# Crash reports: keep source file/line info, drop the rest of the metadata.
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku ships consumer rules; silence the optional-API warnings its
# annotations trigger on some AGP versions.
-dontwarn rikka.shizuku.**

# Termux terminal packages render through AndroidX views only; no reflection.
-dontwarn com.termux.**
