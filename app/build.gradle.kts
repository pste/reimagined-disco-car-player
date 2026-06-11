plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.steo.autoproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.steo.autoproxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // URL del player web (reimagined-disco-ui). Cambiala qui o per buildType.
        // buildConfigField("String", "PLAYER_URL", "\"http://192.168.1.100:5173/\"")
        buildConfigField("String", "PLAYER_URL", "\"https://music.nestix.dev/\"")
    }

    buildFeatures {
        buildConfig = true
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

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("androidx.webkit:webkit:1.14.0")
}
