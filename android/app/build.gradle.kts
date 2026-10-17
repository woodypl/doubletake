plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes a stable debug keystore so successive builds install over each
// other (keeping pairing credentials). Without it the default debug key is used.
val ciKeystore = System.getenv("DOUBLETAKE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "io.github.woodypl.doubletake"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.woodypl.doubletake"
        minSdk = 30
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = System.getenv("DOUBLETAKE_KEYSTORE_PASSWORD")
                keyAlias = "doubletake"
                keyPassword = System.getenv("DOUBLETAKE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (ciKeystore != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Built by `gomobile bind` from ../../mobile (see .github/workflows/android.yml).
    implementation(files("libs/doubletake.aar"))
}
