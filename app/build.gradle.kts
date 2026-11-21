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
        versionCode = 18     // v1.8.0 (profili + tiering + fix player)
        versionName = "1.8.0"

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

    // MediaStyle per la notifica di riproduzione del percorso cast FAKE
    // (debug): SctvCastController costruisce quella notifica con
    // androidx.media.app.NotificationCompat.MediaStyle. Per il percorso
    // REALE la notifica la costruisce il framework cast (non serve qui).
    implementation("androidx.media:media:1.7.0")

    // OkHttp for domain fetching
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebViewCompat: addDocumentStartJavaScript runs the video agent in
    // EVERY frame — the player lives inside the vixcloud iframe, which
    // evaluateJavascript (main frame only) cannot reach.
    implementation("androidx.webkit:webkit:1.11.0")

    // Copertine negli overlay (async + cache di memoria/disco)
    implementation("io.coil-kt:coil:2.6.0")

    // Cast Chromecast (C1, solo phone mode): framework GMS + MediaRouter.
    // Su Fire TV il codice cast non viene MAI istanziato (guard DeviceProfile
    // + GoogleApiAvailability in SctvActivity) — la dipendenza resta nel
    // pacchetto ma inerte. Nessun media3.
    // NOTA versione: la 23.0.0 trascina androidx.mediarouter 1.9.0-beta01 e
    // core 1.17.0 (richiedono compileSdk 36 + AGP 9.1 — incompatibili con
    // questo progetto: compileSdk 34 + AGP 8.5.2). La 22.0.0 è l'ultima
    // compatibile: trascina mediarouter 1.6.0 e core 1.13.1. API equivalenti
    // (CastContext Task API, RemoteMediaClient, SessionManagerListener).
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
}
