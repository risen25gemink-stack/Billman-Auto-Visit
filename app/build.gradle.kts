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
        versionCode = 12
        versionName = "0.7.1-migration-camera-hotfix"
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
