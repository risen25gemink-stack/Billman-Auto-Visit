plugins {
    id("com.android.application")
}

android {
    namespace = "com.billmanautovisit.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.billmanautovisit.app"
        minSdk = 26
        targetSdk = 32
        versionCode = 3
        versionName = "0.3.0-states-1-7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
