// The installable field application.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val dispatchApiBaseUrl =
    providers.gradleProperty("dispatchApiBaseUrl")
        .orElse("https://dispatch.invalid/")
        .get()

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
        buildConfigField("String", "API_BASE_URL", "\"$dispatchApiBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

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
    implementation(projects.infra.googlemaps)
    implementation(projects.infra.face)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.okhttp)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
}
