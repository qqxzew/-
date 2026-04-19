# MeemawAssist ProGuard Rules

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.meemaw.assist.data.api.** { *; }
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
