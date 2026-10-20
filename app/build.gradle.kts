plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Version stamps for a release build.
 *
 * The defaults keep a local build working; CI passes `-PversionCode=<run
 * number>` and `-PversionName=<tag without the v>` so an artifact says which
 * tag it came from. Never hand-bump these for a release — the workflow owns
 * them, the same way the iOS repo's release job owns MARKETING_VERSION.
 */
val releaseVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val releaseVersionName = (project.findProperty("versionName") as String?) ?: "1.0.0"

/**
 * Release signing material, from the environment on CI (decoded from the
 * repository secrets into a temp file) or from `~/.gradle/gradle.properties`
 * for a local signed build. Absent means the release variant stays unsigned,
 * which is what a laptop without the key should produce rather than failing.
 */
val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    ?: (project.findProperty("release.storeFile") as String?)

android {
    namespace = "com.dwprod.librariumscanner"
    // 37, not 36: navigation-compose 2.10.2, coil3 3.6.3 and Compose 1.12.1 all
    // publish AAR metadata requiring compileSdk >= 37, and `checkDebugAarMetadata`
    // fails the build outright on a mismatch.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dwprod.librariumscanner"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null && file(releaseStoreFile).exists()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    ?: (project.findProperty("release.storePassword") as String?)
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: (project.findProperty("release.keyAlias") as String?)
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: (project.findProperty("release.keyPassword") as String?)
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
