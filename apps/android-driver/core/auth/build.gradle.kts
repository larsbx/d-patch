// Sign-in through biz-kit (ADR-0011: RFC 8252 with PKCE, Section 23.1) and, to
// come, the installation key that signs offline events. Section 25.6 keeps
// BiometricPrompt results labelled SYSTEM_BIOMETRIC and never as a face match.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dispatch.driver.core.auth"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // `AuthSession` is a `SessionProvider`.
    api(projects.core.model)
    implementation(libs.androidx.biometric)
    // In `AuthApi`'s constructor.
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    // Checks a device signature independently of the code that made it.
    testImplementation(libs.bouncycastle.prov)
}
