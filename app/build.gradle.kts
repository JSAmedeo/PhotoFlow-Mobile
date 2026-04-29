import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)                  // for Room annotation processing
}

val buildTime: String = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())

android {
    namespace = "com.photoflowmobile.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photoflowmobile.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Lock the app to landscape
    // (also set android:screenOrientation="landscape" in AndroidManifest.xml)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Apache Commons Net is a standard JAR — tell the packager to keep all META-INF
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "PhotoFlow-${variant.buildType.name}-$buildTime.apk"
        }
    }
}

dependencies {

    // ── Core ────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ── Jetpack Compose ─────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)   // extra icons for ops console

    // ── Navigation Compose ──────────────────────────────────
    implementation(libs.androidx.navigation.compose)

    // ── Lifecycle / ViewModel ───────────────────────────────
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ── CameraX ─────────────────────────────────────────────
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)               // PreviewView

    // ── ML Kit — Barcode Scanning ───────────────────────────
    implementation(libs.mlkit.barcode.scanning)

    // ── Room ────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)                  // coroutine extensions
    ksp(libs.androidx.room.compiler)

    // ── WorkManager ─────────────────────────────────────────
    implementation(libs.androidx.work.runtime.ktx)

    // ── Apache Commons Net (FTP) ─────────────────────────────
    // Pure-Java library; no extra native config needed
    implementation(libs.commons.net)

    // ── DataStore Preferences ────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── Coil (image loading) ─────────────────────────────────
    implementation(libs.coil.compose)

    // ── Testing ─────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
