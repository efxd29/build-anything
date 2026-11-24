import java.awt.Image
import java.awt.image.BufferedImage
import java.net.URI
import java.util.Base64

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

// The launcher icon is the user's own original image, byte for byte.
// It cannot be stored as a binary in the repo (the GitHub integration
// only pushes text files), so it lives here as base64 and this task
// decodes it into generated resources at build time. The xxxhdpi copy
// is the original 192x192 file exactly as sent; smaller densities are
// plain downscales of the same artwork, never a redraw.
val launcherIconFile = file("icon-original.b64")
val launcherIconResDir = layout.buildDirectory.dir("generated/launcherIconRes")
val installLauncherIcon by tasks.registering {
    description = "Decodes the original launcher icon into generated resources."
    outputs.dir(launcherIconResDir)
    doLast {
        val outDir = launcherIconResDir.get().asFile
        val bytes = Base64.getDecoder().decode(launcherIconFile.readText().trim())
        val densities = mapOf(
            "mipmap-xxxhdpi" to 192, "mipmap-xxhdpi" to 144,
            "mipmap-xhdpi" to 96, "mipmap-hdpi" to 72, "mipmap-mdpi" to 48
        )
        densities.forEach { (dir, size) ->
            val d = outDir.resolve(dir).apply { mkdirs() }
            if (size == 192) {
                d.resolve("ic_launcher.png").writeBytes(bytes)
                d.resolve("ic_launcher_round.png").writeBytes(bytes)
            } else {
                val img = javax.imageio.ImageIO.read(bytes.inputStream())
                val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val g = scaled.createGraphics()
                g.drawImage(img.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
                g.dispose()
                listOf("ic_launcher.png", "ic_launcher_round.png").forEach { name ->
                    javax.imageio.ImageIO.write(scaled, "png", d.resolve(name))
                }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(installLauncherIcon) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Resources") }
    .configureEach { dependsOn(installLauncherIcon) }

android {
    namespace = "app.scrcpy"

    sourceSets {
        named("main") {
            res.srcDirs("src/main/res", "build/generated/launcherIconRes")
        }
    }
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
