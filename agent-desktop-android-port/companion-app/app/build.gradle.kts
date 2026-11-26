// Port skeleton app module. Dev/debug signing only; no distribution in scope.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.agentdesktop.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.agentdesktop.bridge"
        // minSdk 30: AccessibilityService.takeScreenshot requires API 30 (BLUEPRINT.md).
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // TODO: keep dependency-free for the MVP if possible (framework + LocalSocket only).
}
