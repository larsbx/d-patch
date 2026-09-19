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
        versionCode = 1; versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        debug { isMinifyEnabled = false }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    implementation(projects.core.model); implementation(projects.core.auth)
    implementation(projects.core.network); implementation(projects.core.database)
    implementation(projects.core.designsystem); implementation(projects.feature.home)
    implementation(projects.feature.status); implementation(projects.feature.map)
    implementation(projects.feature.inbox); implementation(projects.feature.approvals)
    implementation(projects.feature.settings); implementation(projects.service.location)
    implementation(projects.service.notifications); implementation(projects.infra.googlemaps)
    implementation(projects.infra.face)
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom)); implementation(libs.bundles.compose)
    implementation(libs.room.runtime); implementation(libs.room.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.retrofit); implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp); implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.unit.test); testImplementation(libs.work.testing)
}
