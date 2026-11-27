import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val envKeystorePass = System.getenv("ANDROID_KEYSTORE_PASS")
val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val envKeyPass = System.getenv("ANDROID_KEY_PASS")

val hasReleaseKeystore = (!envKeystorePath.isNullOrBlank() && file(envKeystorePath).exists()) ||
    (keystorePropsFile.exists() && !keystoreProps.getProperty("storeFile").isNullOrBlank())

android {
    namespace = "com.cs22study.seethere"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cs22study.seethere"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        if (hasReleaseKeystore) {
            create("releasePersistent") {
                if (!envKeystorePath.isNullOrBlank() && file(envKeystorePath).exists()) {
                    storeFile = file(envKeystorePath)
                    storePassword = envKeystorePass
                    keyAlias = envKeyAlias
                    keyPassword = envKeyPass
                } else {
                    storeFile = rootProject.file("keystore/${keystoreProps.getProperty("storeFile")}")
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("releasePersistent")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("assets/dexopt/**")
        }
    }
}

tasks.configureEach {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

configurations.all {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core-stream"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
