plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.brahmkshatriya.echo.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.squareup.okhttp3:okhttp:5.1.0")
    // Echo Nightly exposes the full protobuf runtime to dynamically loaded extensions.
    // Some official extensions (including native-backed playback clients) reference
    // classes which are deliberately absent from protobuf-javalite.
    api(libs.protobuf.java)
    coreLibraryDesugaring(libs.desugaring)
}
