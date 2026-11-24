# Baby AI Assistant - R8 / ProGuard Rules

# Preserve line numbers and source filenames for readable crash diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin metadata & coroutines
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep,includedescriptorclasses class com.example.ui.navigation.** { *; }

# Retrofit & OkHttp
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Remote DTOs for Inception Mercury 3, Gemini 3.8 Flash, and Groq
-keep class com.example.data.remote.** { *; }
-keep interface com.example.data.remote.** { *; }

# Moshi reflection fallback & KotlinJsonAdapterFactory
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
    @com.squareup.moshi.JsonClass *;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory { *; }

# Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room Database Entities, DAOs, and Migrations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.example.data.local.** { *; }
-dontwarn androidx.room.paging.**

# Core settings / security models & EncryptedSharedPreferences / Tink
-keep class com.example.core.security.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# AndroidManifest Services, Receivers, Activities, and Widget
-keep class com.example.MainActivity { *; }
-keep class com.example.service.** { *; }
-keep class com.example.features.vision.CameraCaptureActivity { *; }
-keep class com.example.features.vision.ClipboardActionActivity { *; }
-keep class com.example.features.reminders.ReminderAlarmReceiver { *; }
-keep class com.example.features.reminders.GeofenceBroadcastReceiver { *; }
-keep class com.example.features.widget.BabyWidgetProvider { *; }
-keep class com.example.domain.tools.** { *; }
-keep class com.example.features.searchplay.SearchRecipeSpec { *; }
-keep class com.example.features.routines.RoutineStep { *; }
-keep class com.example.features.macros.MacroStep { *; }
