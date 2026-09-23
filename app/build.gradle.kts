plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.threeinone"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.threeinone"
        minSdk = 34
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.8"
    }
    buildTypes {
        create("compat") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            // Preserve update compatibility with the existing test installation.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
