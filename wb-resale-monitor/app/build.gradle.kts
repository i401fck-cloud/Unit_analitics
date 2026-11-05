plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер сборки GitHub Actions -> versionCode, чтобы каждая новая APK ставилась поверх старой.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Необязательная стабильная подпись (см. README): если в CI заданы секреты с keystore,
// все APK подписываются одним ключом и обновляются без удаления приложения.
val stableKeystore = System.getenv("SIGNING_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "ru.resalemonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.resalemonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = file(stableKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
