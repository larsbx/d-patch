// Room storage for the offline outbox of Section 25.2. A status submission
// writes the local event and the pending event in one transaction.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Exported so a schema change shows in review and a migration can be tested
// against the version it migrates from.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.dispatch.driver.core.database"
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

    // The server-signed capability fixture lives with the verifier's tests; the
    // store's tests read the same bytes rather than a copy that could drift.
    sourceSets["test"].resources.srcDir("../model/src/test/resources")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // `OutboxView` carries core:model types, so consumers need them too.
    api(projects.core.model)
    // `DispatchDatabase` is a `RoomDatabase`, so Room is part of this API.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
