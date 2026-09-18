// The only module permitted to reference Google Maps SDK types (Section
// 28.4). GoogleMapRenderer and GoogleNavigationProvider implement the
// application-owned interfaces; no Google object crosses this boundary.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dispatch.driver.infra.googlemaps"
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
    implementation(projects.core.model)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
}
