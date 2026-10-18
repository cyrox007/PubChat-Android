// `apply false` in plugins {} still resolves the plugin during every sync.
// Load the implementation directly from Google Maven only for Firebase builds.
buildscript {
    if (file("google-services.json").isFile) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.google.gms:google-services:4.4.2")
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firebase is opt-in; keep this condition in sync with buildscript above.
if (file("google-services.json").isFile) {
    pluginManager.apply("com.google.gms.google-services")
}

val pubChatApiBaseUrl = providers.gradleProperty("PUBCHAT_API_BASE_URL")
    .orElse("https://pubchat.ru/api")
    .get()

android {
    namespace = "ru.pubchat.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ru.pubchat.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$pubChatApiBaseUrl\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
