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

// Optional long-lived QA signing identity. Signing material is provided from
// secure CI secrets or a local developer environment, NEVER checked into Git.
// An unsigned config is an error: do not silently fall back to an ephemeral
// debug key when a QA identity was explicitly requested.
val qaKeystorePath = providers.gradleProperty("PUBCHAT_QA_KEYSTORE_PATH").orNull
val qaStorePassword = providers.environmentVariable("PUBCHAT_QA_STORE_PASSWORD").orNull
val qaKeyAlias = providers.environmentVariable("PUBCHAT_QA_KEY_ALIAS").orNull
val qaKeyPassword = providers.environmentVariable("PUBCHAT_QA_KEY_PASSWORD").orNull
val qaSigningValues = listOf(qaKeystorePath, qaStorePassword, qaKeyAlias, qaKeyPassword)
val qaSigningRequested = qaSigningValues.any { !it.isNullOrBlank() }
require(!qaSigningRequested || qaSigningValues.all { !it.isNullOrBlank() }) {
    "QA signing was requested but key path/alias/passwords are incomplete"
}
if (qaSigningRequested) {
    require(file(qaKeystorePath!!).isFile) { "QA signing keystore path does not exist" }
}

val testVersionCode = providers.gradleProperty("PUBCHAT_TEST_VERSION_CODE").orNull
    ?.toIntOrNull() ?: 1
require(testVersionCode > 0) { "PUBCHAT_TEST_VERSION_CODE must be a positive integer" }

android {
    namespace = "ru.pubchat.app"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (qaSigningRequested) {
            create("deviceQa") {
                storeFile = file(qaKeystorePath!!)
                storePassword = qaStorePassword
                keyAlias = qaKeyAlias
                keyPassword = qaKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "ru.pubchat.app"
        minSdk = 26
        targetSdk = 37
        versionCode = testVersionCode
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$pubChatApiBaseUrl\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (qaSigningRequested) {
                signingConfig = signingConfigs.getByName("deviceQa")
            }
        }
        // Disposable smoke-test build uses a THIRD package name, so it never
        // overwrites an existing release or debug install on a user's phone.
        // Its runner-generated debug signature is intentionally NOT stable.
        create("ci") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".ci"
            versionNameSuffix = "-ci"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
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
    // Raw Ed25519/X25519 portable on minSdk 26; never use mutable Android platform BC provider.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
