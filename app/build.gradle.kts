plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keyfekederradyo.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.keyfekederradyo.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
        buildConfigField("String", "RELAY_BASE_URL", "\"\"")
    }

    buildFeatures { buildConfig = true }

    // data/stations.json is bundled as assets/stations.json (offline / first-launch station list)
    sourceSets["main"].assets.srcDir(rootProject.file("data"))

    signingConfigs {
        create("playRelease") {
            val storeFilePath = System.getenv("KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("playRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    testImplementation("junit:junit:4.13.2")
    // real org.json for local unit tests (the Android one is a stub off-device)
    testImplementation("org.json:json:20240303")
}
