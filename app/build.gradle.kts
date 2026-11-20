import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

// The YouTube extractor version lives in extractor.version, so the daily check on
// GitHub can move to a newer one (with YouTube fixes) without touching this file.
val extractorVersion: String = System.getenv("EXTRACTOR_VERSION")?.trim()?.takeIf { it.isNotEmpty() }
    ?: rootProject.file("extractor.version").readText().trim()

android {
    namespace = "app.jubal.qeshev"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.jubal.qeshev"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // The key lives in the repo on purpose, so every new build installs over the old one.
    // See KEY.md before sharing the app with anyone else.
    signingConfigs {
        create("qeshev") {
            storeFile = file("qeshev.jks")
            storePassword = "qeshev-jubal-2026"
            keyAlias = "qeshev"
            keyPassword = "qeshev-jubal-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("qeshev")
        }
        debug {
            signingConfig = signingConfigs.getByName("qeshev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.md",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:$extractorVersion")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
