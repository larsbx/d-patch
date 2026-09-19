fun buildConfigString(value: String): String =
    """ + value.replace("\\", "\\\\").replace(""", "\\"") + """

val configuredApiBaseUrl = providers.gradleProperty("dispatchApiBaseUrl")
    .orElse(providers.environmentVariable("DISPATCH_API_BASE_URL"))
val debugBearerToken = providers.gradleProperty("dispatchDevBearerToken")
    .orElse(providers.environmentVariable("DISPATCH_DEV_BEARER_TOKEN"))
val debugRoleAssignmentId = providers.gradleProperty("dispatchDevRoleAssignmentId")
    .orElse(providers.environmentVariable("DISPATCH_DEV_ROLE_ASSIGNMENT_ID"))
val debugCapabilities = providers.gradleProperty("dispatchDevCapabilities")
    .orElse(providers.environmentVariable("DISPATCH_DEV_CAPABILITIES"))

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField(
                "String",
                "API_BASE_URL",
                buildConfigString(configuredApiBaseUrl.orElse("").get()),
            )
            buildConfigField("String", "DEV_BEARER_TOKEN", """")
            buildConfigField("String", "DEV_ROLE_ASSIGNMENT_ID", """")
            buildConfigField("String", "DEV_CAPABILITIES", """")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "API_BASE_URL",
                buildConfigString(
                    configuredApiBaseUrl.orElse("http://10.0.2.2:4000/").get(),
                ),
            )
            buildConfigField(
                "String",
                "DEV_BEARER_TOKEN",
                buildConfigString(debugBearerToken.orElse("").get()),
            )
            buildConfigField(
                "String",
                "DEV_ROLE_ASSIGNMENT_ID",
                buildConfigString(debugRoleAssignmentId.orElse("").get()),
            )
            buildConfigField(
                "String",
                "DEV_CAPABILITIES",
                buildConfigString(debugCapabilities.orElse("").get()),
            )
        }
    }
    buildFeatures { compose = true; buildConfig = true }
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
