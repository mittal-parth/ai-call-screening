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
    namespace = "com.aicallscreening.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aicallscreening.mobile"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY", "")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")

        // When true, the offline path uses a stub analyzer instead of the real
        // LiteRT-LM/Gemma runtime (which needs a physical device + ~2.6 GB model).
        // Useful for emulator/CI builds. Override in local.properties.
        val useMockInference = localProperties.getProperty("USE_MOCK_INFERENCE", "false")
        buildConfigField("boolean", "USE_MOCK_INFERENCE", useMockInference)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // LiteRT-LM ships multiple native libs; keep the first of any dupes.
            pickFirsts += "**/*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.play.services.wearable)
    implementation(libs.okhttp)
    implementation(libs.litert.lm.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(project(":common"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
