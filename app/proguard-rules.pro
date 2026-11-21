# Keep WebView JS interface: R8 must not rename/remove the @JavascriptInterface
# methods (they are invoked from JS by NAME — unreachable from Java code, so
# without this rule R8 strips or renames them and the whole bridge dies in
# release builds while debug keeps working).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.scviewer.fire.web.JsInterface { *; }
# Bridge SCtv (M1): metodi invocati da JS per nome.
-keep class com.scviewer.fire.bridge.SctvBridge { *; }

# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class com.scviewer.fire.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Cast (C1): keeps difensivi. Il framework GMS riflette sulle sue classi
# (CastOptionsProvider via nome dal manifest, listener e callback) e alcune
# librerie cast/mediarouter usano annotazioni lette a runtime; senza keep il
# release R8 può rimuovere/rimappare pezzi e rompere discovery o sessione.
# Il telefono di test ha GMS reale: le -dontwarn evitano errori su riferimenti
# opzionali assenti nelle vecchie versioni di Play services.
-keep class com.scviewer.fire.cast.CastOptionsProvider { *; }
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }
-keep class androidx.mediarouter.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn androidx.mediarouter.**
