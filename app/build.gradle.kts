plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alobo.clone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alobo.sportUser.clone"
        minSdk = 24
        targetSdk = 36
        versionCode = 2603
        versionName = "2.10.1"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
