plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.forge.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forge.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    // ── Signing ───────────────────────────────────────────────────────────────
    // Release signing is driven entirely by environment variables so no
    // secrets are committed to version control.
    //
    // Required env vars (set as GitHub Actions Secrets):
    //   KEYSTORE_PATH      — absolute path to the .jks / .keystore file
    //   KEYSTORE_PASSWORD  — keystore store password
    //   KEY_ALIAS          — key alias inside the keystore
    //   KEY_PASSWORD       — key password
    //
    // Local debug builds are signed with the debug keystore automatically.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrEmpty()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
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
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }

    // Exclude duplicate META-INF files that OkHttp/Okio bring in
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.security.crypto)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)
}
