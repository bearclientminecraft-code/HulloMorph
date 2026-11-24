import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chadmorph"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chadmorph"
        minSdk = 29 // Realme C25 = Android 11; MediaStore RELATIVE_PATH без legacy-кода
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a") } // Realme C25 — arm64; уменьшает APK
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // .task читается из assets напрямую, без распаковки/сжатия.
    androidResources { noCompress += listOf("task") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

// Скачивает официальную модель Face Landmarker в assets (только при сборке; в рантайме всё офлайн).
tasks.register("downloadFaceModel") {
    val out = file("src/main/assets/face_landmarker.task")
    outputs.file(out)
    doLast {
        if (!out.exists()) {
            out.parentFile.mkdirs()
            val part = File(out.parentFile, "face_landmarker.task.part")
            val conn = URL(
                "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
            ).openConnection()
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            check(part.length() > 1_000_000) { "Модель скачана не полностью" }
            check(part.renameTo(out)) { "Не удалось переименовать модель" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn("downloadFaceModel") }
