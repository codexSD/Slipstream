plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.screenshot)
}

android {
    namespace = "com.slipstream.meridian"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Design-system violations should not compile.
        freeCompilerArgs += listOf("-Xjvm-default=all", "-Werror")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)

    screenshotTestImplementation(libs.compose.ui.tooling)
}
