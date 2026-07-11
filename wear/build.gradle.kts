import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.aicallscreening.wear"
    compileSdk = 35

    defaultConfig {
        // Must match the mobile module's applicationId: the Wear OS Data Layer
        // routes messages to the app with the same package name on the paired
        // device. Distinct namespaces keep BuildConfig/R separate per module.
        applicationId = "com.aicallscreening"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Default capture source: live microphone. Set to "true" only for
        // CI/mic-less emulators to stream the bundled demo clip instead. The
        // watch UI can also override this per-session at runtime (see
        // AudioCaptureService.EXTRA_USE_DEBUG_CLIP).
        buildConfigField("boolean", "USE_DEBUG_AUDIO_CLIP", "false")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
}
