import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.screenshot)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":sharedLogic"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.jxl.coder)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Names each composable in Perfetto traces, to find what makes a frame slow; debug builds only, and off until
    // turned on in the running app with
    //   adb shell am broadcast -a androidx.tracing.perfetto.action.ENABLE_TRACING \
    //     com.chencohensolution.journal/androidx.tracing.perfetto.TracingReceiver
    // and recorded with the "track_event" data source, category "rendering".
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.tracing.perfetto.binary)

    // Screenshot tests: render @PreviewTest previews to PNGs without a device (see docs/previews.md).
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}

android {
    namespace = "com.chencohensolution.journal"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.chencohensolution.journal"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // A release branch release/<version> sets both (.github/workflows/android-release.yml, docs/releases.md).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "1.0"
        // -Pabi=arm64-v8a (scripts/android-run.sh with ABI=…) packs only that processor's native libraries: about a third of
        // the debug APK's 200 MB, for a faster install on one phone. Without it, every ABI (emulators and old phones too).
        (findProperty("abi") as String?)?.let { abis -> ndk { abiFilters += abis.split(",") } }
    }
    packaging {
        // Native libraries compressed in the APK (ONNX Runtime, MapLibre, MediaPipe: about 57 MB stored, 22 MB
        // compressed), unpacked on install: a smaller download for a little more space on the phone.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // The release key, when the build is given one (the release workflow, from the repository's secrets). Every APK
    // published must be signed with the same key, or a phone refuses to install it over the one it has.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        // R8 and resource shrinking: an arm64 release went from 123 MB to 42 MB with the native libraries compressed.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // Release as it runs, signed with the debug key so it installs over a debug build and keeps its data: for
        // measuring scrolling and start-up, which a debug build makes several times slower.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        // The UI Lab app (scripts/ui-lab.sh): the debug build under its own id and name that only opens the lab, so it
        // installs beside Journal on a phone without replacing it or stopping its backup. Its code is src/debug's.
        create("uiLab") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uilab"
            matchingFallbacks += listOf("debug")
        }
    }
    sourceSets.named("uiLab") {
        kotlin.srcDir("src/debug/kotlin")
        res.srcDir("src/debug/res")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}
