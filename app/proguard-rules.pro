# Keep model classes (used by JSON serialization via reflection / explicit names).
-keep class com.luxury.mobile.launcher.model.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
