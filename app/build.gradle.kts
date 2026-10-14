import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pixelveil.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixelveil.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Fixed key so every build can be installed over the previous one.
        // Fine for a personal tool; use a private key before publishing to the Play Store.
        create("dev") {
            storeFile = file("pixelveil-dev.keystore")
            storePassword = "pixelveil"
            keyAlias = "pixelveil"
            keyPassword = "pixelveil"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            // Personal tool: release build signed with the debug key so it installs directly.
            signingConfig = signingConfigs.getByName("dev")
            ndk {
                // Release = real phones only (smaller APK). Debug keeps x86 so the PC emulator works.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += "tflite"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-container:$media3")
    implementation("androidx.media3:media3-muxer:$media3")

    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// Downloads the on-device models into src/main/assets on the first build (they are not in git).
// Tries Google's server first, then the copy on this repo's GitHub Releases page; retries each.
// Downloads go to a .part file first, so an interrupted download never leaves a broken model.
val modelFiles = mapOf(
    "selfie_multiclass_256x256.tflite" to listOf(
        "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
        "https://github.com/AIid1007/pixelveil/releases/download/models/selfie_multiclass_256x256.tflite",
    ),
    "blaze_face_short_range.tflite" to listOf(
        "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite",
        "https://github.com/AIid1007/pixelveil/releases/download/models/blaze_face_short_range.tflite",
    ),
)
val downloadModels by tasks.registering {
    val assetsDir = file("src/main/assets")
    doLast {
        assetsDir.mkdirs()
        modelFiles.forEach { (name, urls) ->
            val target = File(assetsDir, name)
            if (target.exists() && target.length() > 100_000L) return@forEach
            val part = File(assetsDir, "$name.part")
            var done = false
            var lastError: Exception? = null
            for (url in urls) {
                for (attempt in 1..3) {
                    try {
                        logger.lifecycle("Downloading model $name (attempt $attempt): $url")
                        val conn = URI(url).toURL().openConnection().apply {
                            connectTimeout = 20_000
                            readTimeout = 60_000
                        }
                        conn.getInputStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
                        if (part.length() < 100_000L) throw IllegalStateException("file too small (${part.length()} bytes)")
                        target.delete()
                        if (!part.renameTo(target)) throw IllegalStateException("could not save $name")
                        done = true
                        break
                    } catch (e: Exception) {
                        lastError = e
                        part.delete()
                        logger.warn("Download failed: ${e.message}")
                        Thread.sleep(2_000L * attempt)
                    }
                }
                if (done) break
            }
            if (!done) {
                throw GradleException(
                    "Could not download $name. Check your internet connection and build again, " +
                        "or copy the file into app/src/main/assets/ yourself. Last error: ${lastError?.message}",
                )
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }
