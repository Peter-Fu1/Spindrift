# CI UPGRADE LAB ONLY. The instrumentation APK treats this shared runtime library as provided.
# Normal MAIN release/preview/debug builds do not load this file.
-keep class androidx.tracing.** { *; }
