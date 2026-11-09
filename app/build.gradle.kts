import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// Machine-specific connection details live in the untracked .env file at the repo root
// (template: .env.example). FRIGATE_HOST is baked into BuildConfig as the default profile.
val envProps = Properties().apply {
    val file = rootProject.file(".env")
    if (file.exists()) file.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && "=" in trimmed) {
            val (key, value) = trimmed.split("=", limit = 2)
            setProperty(key.trim(), value.trim())
        }
    }
}
val frigateHost: String = envProps.getProperty("FRIGATE_HOST", "192.0.2.1")

android {
    namespace = "com.frigatetv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.frigatetv.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "FRIGATE_HOST", "\"$frigateHost\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 shrinks and optimizes code (including Compose); unused resources are dropped too.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // Installs the baseline profiles bundled with Compose/AndroidX so startup and scrolling run AOT-compiled.
    implementation(libs.androidx.profileinstaller)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // OkHttp carries Media3 HLS/clip traffic and Coil image loads through the same client as FrigateApi.
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Formatting rules (ktlint) run through the detekt task.
    detektPlugins(libs.detekt.formatting)
}
