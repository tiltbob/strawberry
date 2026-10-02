plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release workflow passes the version from the git tag (v1.2.3 -> 1.2.3 / 1002003).
val strawberryVersionName: String = (project.findProperty("strawberryVersionName") as String?) ?: "0.1.0"
val strawberryVersionCode: Int = (project.findProperty("strawberryVersionCode") as String?)?.toInt() ?: 1

// Release signing comes from the environment so the keystore never lives in the repo.
// Without these variables assembleRelease still works and produces an unsigned APK.
val releaseKeystore: String? = System.getenv("STRAWBERRY_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.tiltbob.strawberry"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tiltbob.strawberry"
        minSdk = 30
        targetSdk = 36
        versionCode = strawberryVersionCode
        versionName = strawberryVersionName
    }

    signingConfigs {
        // A committed, publicly known debug key, so every CI build is signed with the same key
        // and can be installed over the previous one (an uninstall would drop the adb grant).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The project's release key, held only as GitHub Actions secrets (scripts/setup-signing.sh).
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("STRAWBERRY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STRAWBERRY_KEY_ALIAS")
                keyPassword = System.getenv("STRAWBERRY_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Newer AndroidX releases and target SDKs need compileSdk 37 (see libs.versions.toml).
        disable += setOf("GradleDependency", "OldTargetApi")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric 4.17 reflects into jdk.internal.access.SharedSecrets when emulating
    // SDK 36+ and otherwise fails with "Failed to interact with raw FileDescriptor internals"
    // (https://github.com/robolectric/robolectric/issues/11434).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
