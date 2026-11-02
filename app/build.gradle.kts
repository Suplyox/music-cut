plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.muzikkirp.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.muzikkirp.app"
        // Android 10+ : kaydetme için ek depolama izni gerekmez (MediaStore kullanılır)
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Bilerek hiçbir dış kütüphane yok: reklam, izleme veya internet erişimi içermez.
dependencies {
}
