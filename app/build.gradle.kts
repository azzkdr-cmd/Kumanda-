plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kumanda.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kumanda.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = (findProperty("vc") as String?)?.toInt() ?: 1
        versionName = "1.0." + ((findProperty("vc") as String?) ?: "0")
    }

    // Sabit imza: her derleme ayni anahtarla imzalanir, boylece APK ustune guncellenebilir.
    signingConfigs {
        create("shared") {
            storeFile = file("kumanda.keystore")
            storePassword = "android"
            keyAlias = "kumanda"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Kendinden imzali istemci sertifikasi uretmek icin
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
