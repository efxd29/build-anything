import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The scrcpy server component is the official build from the scrcpy project
// (Genymobile/scrcpy), pinned to v3.3.4. It is downloaded once into
// src/main/assets if not present (e.g. in CI), so the repo stays free of
// binaries while every build embeds the exact same server.
val scrcpyServerVersion = "3.3.4"
val scrcpyServerFile = file("src/main/assets/scrcpy-server")
val fetchScrcpyServer by tasks.registering {
    description = "Downloads the pinned official scrcpy-server into assets if missing."
    outputs.file(scrcpyServerFile)
    onlyIf { !scrcpyServerFile.exists() }
    doLast {
        scrcpyServerFile.parentFile.mkdirs()
        val url = "https://github.com/Genymobile/scrcpy/releases/download/" +
            "v$scrcpyServerVersion/scrcpy-server-v$scrcpyServerVersion"
        URI(url).toURL().openStream().use { input ->
            scrcpyServerFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(fetchScrcpyServer) }

android {
    namespace = "app.scrcpy"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.scrcpy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle's three jars each ship this OSGi manifest.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.spake2)
}
