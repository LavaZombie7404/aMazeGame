plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lavazombie.amazegame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lavazombie.amazegame"
        minSdk = 26 // Android 8.0 — covers ~99% of in-use devices
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // arm64-v8a covers every modern Android device. Add armeabi-v7a
            // and x86_64 back once the WAMR build is validated on those
            // targets too (its cmake needs a bit of love per arch).
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                // Only build our library — skip WAMR's downstream "all" target
                // (the iwasm_shared link is intentionally disabled in
                // CMakeLists.txt; this stops Gradle from asking for it).
                targets += "amaze_native"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            // core.wasm is staged into assets by the `:app:stageCoreWasm`
            // task below — Android packages everything under assets/ into
            // the APK; the WAMR-backed JNI bridge loads it from the asset
            // manager at runtime.
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Embedded HTTP server for the puppet API (adb forward → curl/Playwright).
    implementation("io.ktor:ktor-server-core:3.0.1")
    implementation("io.ktor:ktor-server-cio:3.0.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Build the shared Rust core to WASM and stage it into the APK's assets.
// Calls the same `node scripts/build-core.mjs` the web app uses so the
// platforms ship byte-identical .wasm.
val stageCoreWasm by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds amaze-core (Rust → wasm32) and stages it as an asset."
    val repoRoot = rootProject.projectDir.parentFile
    workingDir = repoRoot
    commandLine("node", "scripts/build-core.mjs")
    val srcWasm = file("$repoRoot/target/wasm32-unknown-unknown/release/amaze_core.wasm")
    val dstDir = file("src/main/assets")
    val dstWasm = file("src/main/assets/core.wasm")
    inputs.dir("$repoRoot/core")
    outputs.file(dstWasm)
    doLast {
        dstDir.mkdirs()
        srcWasm.copyTo(dstWasm, overwrite = true)
    }
}

tasks.named("preBuild") {
    dependsOn(stageCoreWasm)
}
