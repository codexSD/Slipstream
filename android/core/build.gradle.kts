plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.slipstream.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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
        freeCompilerArgs += listOf("-Werror")
    }
}

tasks.withType<Test> {
    // Conscrypt (used transitively for TLS in unit tests) reflectively inspects
    // java.net.InetAddress internals during the handshake. Under JDK 17's module system this
    // throws InaccessibleObjectException unless the module is explicitly opened - normally
    // masked because *something* in the JVM happens to trigger it first, but that "something"
    // depends on unit-test execution order/worker assignment (e.g. whether a Robolectric test
    // ran first in the same forked JVM), which is not guaranteed. Open it unconditionally so
    // TLS-using tests (ControlChannelTest, PairingTest, ...) pass regardless of order.
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
}
