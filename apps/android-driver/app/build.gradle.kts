// The installable field application. Section 17 leaves the final application ID
// and the supported minimum Android version open; `minSdk 29` is fixed by
// Section 19.1 and the placeholder ID below is recorded in ADR-0003.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dispatch.driver"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.dispatch.driver"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
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
    implementation(projects.core.model)
    implementation(projects.core.auth)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.designsystem)

    implementation(projects.feature.home)
    implementation(projects.feature.status)
    implementation(projects.feature.map)
    implementation(projects.feature.inbox)
    implementation(projects.feature.approvals)
    implementation(projects.feature.settings)

    implementation(projects.service.location)
    implementation(projects.service.notifications)

    // Section 28.4: the Google adapter is wired in here and nowhere else.
    implementation(projects.infra.googlemaps)
    implementation(projects.infra.face)

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.bundles.unit.test)
}
