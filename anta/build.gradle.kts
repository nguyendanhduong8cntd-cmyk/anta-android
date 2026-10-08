import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Machine-specific settings come from the command line (-Panta.x=...) or from local.properties, which git ignores.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(name: String): String? = (findProperty(name) as String?) ?: localProperties.getProperty(name)
fun quoted(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val releaseApiUrl = setting("anta.apiUrl")
val releaseKeystore = setting("anta.keystore")

android {
    namespace = "vn.anta.booking"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.anta.booking"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = setting("anta.keystorePassword")
            keyAlias = setting("anta.keyAlias")
            keyPassword = setting("anta.keyPassword")
        }
    }

    buildTypes {
        debug {
            // The backend runs on the development machine; `adb reverse tcp:3000 tcp:3000` makes it this address on the device.
            buildConfigField("String", "API_BASE_URL", quoted(setting("anta.debugApiUrl") ?: "http://127.0.0.1:3000/api/v1"))
            // Lets a debug build read its own verification code and settle a sandbox payment on a local backend.
            // Only src/debug uses it; a release build has neither the key nor the code that would send it.
            buildConfigField("String", "DEV_KEY", quoted(setting("anta.devKey") ?: ""))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", quoted(releaseApiUrl ?: ""))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Unit tests run on the JVM, where android.util.Log is a stub: let its calls do nothing instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// A release build has no development address to fall back on: it must be told where its backend is.
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.project == project && it.name.contains("Release") }
    if (buildsRelease && releaseApiUrl?.startsWith("https://") != true) {
        throw GradleException("A release build needs the backend address: -Panta.apiUrl=https://<host>/api/v1 (or anta.apiUrl in local.properties)")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Google sign-in: the system's account picker, which hands back an ID token for the backend to verify.
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    // org.json ships with Android but not with the JVM that runs unit tests.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
