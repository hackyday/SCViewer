# Keep WebView JS interface: R8 must not rename/remove the @JavascriptInterface
# methods (they are invoked from JS by NAME — unreachable from Java code, so
# without this rule R8 strips or renames them and the whole bridge dies in
# release builds while debug keeps working).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.scviewer.fire.web.JsInterface { *; }

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
