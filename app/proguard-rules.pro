# Keep WebView JS interface (must be public to be invoked from JS)
-keepclassmembers class com.scviewer.fire.web.*$JsInterface {
    public *;
}
-keep class com.scviewer.fire.web.*$JsInterface { *; }

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
