plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.scviewer.fire"
    compileSdk = 34

    // Pin the JVM toolchain to 21 — AGP 8.5 supports both 17 and 21; we pick 21
    // because Debian Trixie ships OpenJDK 21 as the default (OpenJDK 17 was
    // dropped from Trixie). Combined with the foojay-resolver plugin in the
    // root build file, Gradle will auto-provision a JDK 21 if the system Java
    // is missing jlink / is the wrong version.
    kotlin {
        jvmToolchain(21)
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    defaultConfig {
        applicationId = "com.scviewer.fire"
        minSdk = 24          // Fire TV Stick Lite / 4K and most sticks still in use
        targetSdk = 34
        versionCode = 7      // v1.3.1 (v1.3.0=6)
        versionName = "1.3.1"

        // In case the user is on a non-UI build, but Fire TV always has Leanback.
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Self-signed debug-style build for easy sideload on Fire TV.
            // For real release signing, replace with a keystore config.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    // Note: sourceCompatibility / targetCompatibility / jvmTarget are inferred
    // automatically from the Java toolchain declared above. Specifying them
    // explicitly here would override the toolchain and re-introduce the
    // "jlink not found" error if the system JDK is broken.

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // WorkManager — periodic domain refresh every 30 minutes
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Leanback (D-pad support for Fire TV) — optional launcher category
    implementation("androidx.leanback:leanback:1.0.0")

    // ConstraintLayout for overlays
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OkHttp for domain fetching
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebViewCompat: addDocumentStartJavaScript runs the video agent in
    // EVERY frame — the player lives inside the vixcloud iframe, which
    // evaluateJavascript (main frame only) cannot reach.
    implementation("androidx.webkit:webkit:1.11.0")

}
