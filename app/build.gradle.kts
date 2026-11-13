plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.inchat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.inchat"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        )
    }
}

dependencies {

    // Local-first message storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Jetpack Navigation Compose
    implementation(
        "androidx.navigation:navigation-compose:2.7.7"
    )

    // Compose & AndroidX Core
    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )

    // Material Icons Extended
    implementation(
        "androidx.compose.material:material-icons-extended"
    )

    // MVVM & ViewModel Compose
    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0"
    )

    // Jetpack DataStore Preferences
    implementation(
        "androidx.datastore:datastore-preferences:1.1.1"
    )

    // Kotlin coroutines support for Firebase Tasks (.await())
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0"
    )

    // Firebase BoM
    implementation(
        platform(
            "com.google.firebase:firebase-bom:34.19.0"
        )
    )

    // Firebase Authentication
    implementation(
        "com.google.firebase:firebase-auth"
    )

    // Google sign-in through Android Credential Manager (Firebase Spark compatible)
    implementation(
        "androidx.credentials:credentials:1.3.0"
    )

    implementation(
        "androidx.credentials:credentials-play-services-auth:1.3.0"
    )

    implementation(
        "com.google.android.libraries.identity.googleid:googleid:1.1.1"
    )

    // Firebase Realtime Database
    implementation(
        "com.google.firebase:firebase-database"
    )

    // Firebase Cloud Messaging
    implementation(
        "com.google.firebase:firebase-messaging"
    )

    // Image loading
    implementation(
        "io.coil-kt:coil-compose:2.7.0"
    )

    // Testing dependencies
    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}