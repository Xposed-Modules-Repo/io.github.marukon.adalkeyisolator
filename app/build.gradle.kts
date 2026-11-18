plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.marukon.adalkeyisolator"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.marukon.adalkeyisolator"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.xposed.service)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
