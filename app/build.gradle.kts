import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)                  // for Room annotation processing
}

val buildTime: String = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())

// ── Iterating build number ───────────────────────────────────────────────────
//
// Every debug build gets its own number, carried in versionName and the APK filename, so an
// installed build is identifiable from `adb shell dumpsys package` alone. Without it every build
// reported the same "1.2" and there was no way to tell which one a handset was running — which
// bit during field testing, when a stale APK from a failed build sat in the output directory
// looking current.
//
// Only increments when actually assembling or installing. Configuration runs for every Gradle
// invocation (`tasks`, `test`, an IDE sync), and incrementing on those would inflate the count
// without producing an APK.
val buildNumberFile = file("build-number.properties")
val isProducingApk = gradle.startParameter.taskNames.any {
    it.contains("assemble", ignoreCase = true) ||
    it.contains("install", ignoreCase = true) ||
    it.contains("bundle", ignoreCase = true)
}
val buildNumber: Int = run {
    val props = Properties()
    if (buildNumberFile.exists()) buildNumberFile.inputStream().use { props.load(it) }
    val current = (props.getProperty("buildNumber") ?: "0").toIntOrNull() ?: 0
    val next = if (isProducingApk) current + 1 else current
    if (isProducingApk) {
        props.setProperty("buildNumber", next.toString())
        buildNumberFile.outputStream().use { props.store(it, "Auto-incremented per APK build. Do not edit by hand.") }
    }
    next
}

android {
    namespace = "com.photoflowmobile.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photoflowmobile.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2 (build $buildNumber)"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
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
                .outputFileName =
                    "PhotoFlow-${variant.buildType.name}-b%03d-$buildTime.apk".format(buildNumber)
        }
    }
}

// Keep every debug APK outside build/, which `clean` wipes and which a failed build can leave
// holding a stale artifact that looks current. `builds/` is gitignored; prune it by hand.
tasks.register<Copy>("archiveDebugApk") {
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }
    into(rootProject.file("builds"))
    doLast { logger.lifecycle("Archived build $buildNumber to builds/") }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy("archiveDebugApk") }

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

    // ── Security (EncryptedSharedPreferences for credential storage) ─────────
    implementation(libs.androidx.security.crypto)

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
