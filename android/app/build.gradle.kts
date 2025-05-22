// File: android/app/build.gradle.kts

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" // Ensure this matches your Kotlin version or is compatible
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "dev.optimus.lyricslistener"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "dev.optimus.lyricslistener"
        minSdk = 29
        targetSdk = 34
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(listOf("arm64-v8a")) // Consider if you need other ABIs for debug too
            }
            signingConfig = signingConfigs.getByName("debug")
            packagingOptions {
                resources {
                    excludes.add("/META-INF/{AL2.0,LGPL2.1}")
                    excludes.add("META-INF/versions/9/previous-compilation-data.bin")
                }
            }
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.palette:palette-ktx:1.0.0") // <-- ADDED THIS LINE

    // SLF4J API for logging (if you use it directly, otherwise Ktor might bundle its own logger needs)
    implementation("org.slf4j:slf4j-api:2.0.7") // Or the latest version

    // Ktor - Define version once and use it for all Ktor modules
    val ktorVersion = "2.3.11" // Or your chosen Ktor version
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-android:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion") // Ktor's adapter for Kotlinx Serialization

    // Kotlinx Serialization - Runtime library
    val kotlinxSerializationVersion = "1.6.3"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Coroutines
    val kotlinxCoroutinesVersion = "1.8.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")
}