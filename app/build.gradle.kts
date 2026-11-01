import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties next to settings.gradle.kts (git-ignored; the key itself lives
// outside the repository). Without it the release build is unsigned, so a fresh clone or CI still builds.
val signing: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { source ->
    val properties = Properties()
    source.inputStream().use { stream -> properties.load(stream) }
    properties
}

android {
    namespace = "kr.routinegongbang.app"
    compileSdk = 35

    // AAOS CarPropertyManager (GEAR_SELECTION, PERF_VEHICLE_SPEED) and CarUxRestrictionsManager.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.routinegongbang.app"
        // Pleos requirement: API 28+ (signing key rotation / lineage support)
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                // An absolute path: a relative one would resolve against app/.
                storeFile = file(requireNotNull(signing.getProperty("storeFile")) { "keystore.properties: storeFile is missing" })
                storePassword = requireNotNull(signing.getProperty("storePassword")) { "keystore.properties: storePassword is missing" }
                keyAlias = requireNotNull(signing.getProperty("keyAlias")) { "keystore.properties: keyAlias is missing" }
                keyPassword = requireNotNull(signing.getProperty("keyPassword")) { "keystore.properties: keyPassword is missing" }
                // v3 carries the key lineage, so the signing key can be rotated later (Pleos: API 28+).
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // The emulator accepts writes; the debug manifest declares the control permissions.
            buildConfigField("boolean", "CONTROL_SDK_OPEN", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // Vehicle Control SDK is "Coming Soon": writes downgrade to NOTIFY.
            buildConfigField("boolean", "CONTROL_SDK_OPEN", "false")
        }
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
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navi)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Pure UI-model tests on the JVM (no device needed).
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
