// The installable field application. Section 17 leaves the final application ID
// and the supported minimum Android version open; `minSdk 29` is fixed by
// Section 19.1 and the placeholder ID below is recorded in ADR-0003.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ADR-0009: the capability-document verification key is pinned at build time.
// Debug pins the published development key so a clean checkout works against
// `make up`; release takes the deployment's key and refuses the development one.
val developmentCapabilityKey: String =
    rootProject.file("../../infra/capability-signing/development.pub.pem").readText().trim()
val releaseCapabilityKey: String? =
    providers.gradleProperty("dispatch.capabilityPublicKeyFile").orNull?.let { file(it).readText().trim() }
val releaseApiBaseUrl: String? = providers.gradleProperty("dispatch.apiBaseUrl").orNull

fun String.asBuildConfigString() = "\"" + replace("\n", "\\n") + "\""

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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "API_BASE_URL", (releaseApiBaseUrl ?: "").asBuildConfigString())
            buildConfigField("String", "CAPABILITY_PUBLIC_KEY_PEM", (releaseCapabilityKey ?: "").asBuildConfigString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // The Android emulator's address for the host running `make up`.
            buildConfigField("String", "API_BASE_URL", "http://10.0.2.2:4000/".asBuildConfigString())
            buildConfigField("String", "CAPABILITY_PUBLIC_KEY_PEM", developmentCapabilityKey.asBuildConfigString())
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

    // The server-signed capability fixture, shared with the verifier's tests.
    sourceSets["test"].resources.srcDir("../core/model/src/test/resources")

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
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
}

// A release without its deployment's key and API, or with the development key,
// would verify documents anyone with a checkout can sign. Fail the build instead.
val checkReleaseConfiguration by tasks.registering {
    val key = releaseCapabilityKey
    val url = releaseApiBaseUrl
    val development = developmentCapabilityKey
    doLast {
        check(!url.isNullOrBlank() && url.startsWith("https://")) { "Set dispatch.apiBaseUrl to the HTTPS API base URL." }
        check(!key.isNullOrBlank()) { "Set dispatch.capabilityPublicKeyFile to the deployment's capability public key (ADR-0009)." }
        check(key != development) { "The development capability key cannot be pinned in a release build (ADR-0009)." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseConfiguration) }
