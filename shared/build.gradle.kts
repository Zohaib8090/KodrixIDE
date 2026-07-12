plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    jvm("desktop") {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.core:core-ktx:1.12.0")
                implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
                implementation(libs.termux.terminal.view)
                implementation(libs.termux.terminal.emulator)
                implementation(libs.gson)
                implementation(libs.androidx.security.crypto)
                implementation(libs.ktor.client.okhttp)
                implementation("com.google.android.material:material:1.11.0")
                implementation("io.coil-kt:coil-compose:2.6.0")
                implementation("org.tukaani:xz:1.10")
                implementation("androidx.activity:activity-compose:1.8.2")
                implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
                implementation("com.google.firebase:firebase-analytics")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
            }
        }
    }
}

android {
    namespace = "com.kodrix.zohaib"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
