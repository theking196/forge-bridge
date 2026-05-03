# ═══════════════════════════════════════════════════════════════════════════════
# Forge Bridge — R8/ProGuard rules
# Target: release APK ≤ 2 MB (minify + shrink resources both enabled)
# ═══════════════════════════════════════════════════════════════════════════════

# ── Debug info (stack traces remain readable in crash reports) ────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Annotations & generics (required by Gson and AndroidX) ───────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── NanoHTTPD ─────────────────────────────────────────────────────────────────
# The serve() method and all response/session types are called by the HTTP
# server loop — they must not be renamed or removed.
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ── Gson ──────────────────────────────────────────────────────────────────────
# Model classes are serialized/deserialized via reflection.
-keep class com.forge.bridge.data.model.** { *; }
-keepclassmembers class com.forge.bridge.data.model.** {
    <init>();
    <fields>;
}
-keep class com.google.gson.** { *; }
-keep interface com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }
-dontwarn com.google.gson.**

# ── OkHttp + OkHttp-SSE ───────────────────────────────────────────────────────
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okhttp3.sse.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okhttp3.sse.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── AndroidX Security / EncryptedSharedPreferences ────────────────────────────
-keep class androidx.security.crypto.** { *; }
-keepclassmembers class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-dontwarn com.google.crypto.tink.**

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── WebView JavaScript bridge ─────────────────────────────────────────────────
# Methods annotated @JavascriptInterface are invoked by name from JS — keep.
-keepclassmembers class com.forge.bridge.data.remote.browser.JavaScriptBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Android application components ────────────────────────────────────────────
# Registered in AndroidManifest.xml — instantiated by name by the framework.
-keep public class com.forge.bridge.ForgeBridgeApp extends android.app.Application { *; }
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep public class * extends android.app.Activity

# ── DI container ──────────────────────────────────────────────────────────────
-keep class com.forge.bridge.di.AppContainer { *; }

# ── Suppress common benign warnings ──────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**
-dontwarn kotlin.reflect.**
