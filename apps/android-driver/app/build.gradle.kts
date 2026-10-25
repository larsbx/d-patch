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

// ADR-0009: the capability-document verification key (base64url Ed25519) is
// pinned at build time. Debug pins biz-kit's published development key so a
// clean checkout works against a local server; release takes the deployment's
// key and refuses the development one.
val developmentCapabilityKey: String =
    rootProject.file("../../infra/capability-signing/development.pub").readText().trim()
val releaseCapabilityKey: String? = providers.gradleProperty("dispatch.capabilityPublicKey").orNull?.trim()
val releaseApiBaseUrl: String? = providers.gradleProperty("dispatch.apiBaseUrl").orNull

// ADR-0011: sign-in goes through biz-kit's `/auth` (SpruceGoose), which is not
// the dispatch API's host. The provider is the path segment biz-kit routes on.
val authBaseUrl: String? = providers.gradleProperty("dispatch.authBaseUrl").orNull
val authProvider: String = providers.gradleProperty("dispatch.authProvider").orNull ?: "workos"

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
        // Hilt's test application, so instrumented tests get the app's own graph.
        testInstrumentationRunner = "com.dispatch.driver.HiltTestRunner"
    }

    packaging {
        resources {
            // BouncyCastle and jspecify both ship this OSGi descriptor; the
            // app is not an OSGi bundle and reads neither.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "API_BASE_URL", (releaseApiBaseUrl ?: "").asBuildConfigString())
            buildConfigField("String", "CAPABILITY_PUBLIC_KEY", (releaseCapabilityKey ?: "").asBuildConfigString())
            buildConfigField("String", "AUTH_BASE_URL", (authBaseUrl ?: "").asBuildConfigString())
            buildConfigField("String", "AUTH_PROVIDER", authProvider.asBuildConfigString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // The Android emulator's address for the host running `make up`.
            buildConfigField("String", "API_BASE_URL", "http://10.0.2.2:4000/".asBuildConfigString())
            buildConfigField("String", "CAPABILITY_PUBLIC_KEY", developmentCapabilityKey.asBuildConfigString())
            // SpruceGoose on the host, started with SPRUCE_GOOSE_MCP_ENABLED=true MCP_PORT=4001.
            buildConfigField("String", "AUTH_BASE_URL", (authBaseUrl ?: "http://10.0.2.2:4001/").asBuildConfigString())
            buildConfigField("String", "AUTH_PROVIDER", authProvider.asBuildConfigString())
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
    // Checks a registration proof independently of the code that made it.
    testImplementation(libs.bouncycastle.prov)

    // Against biz-kit's end-to-end stack on an emulator (src/androidTest).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.work.testing)
    kspAndroidTest(libs.hilt.compiler)
}

// A release without its deployment's key and API, or with the development key,
// would verify documents anyone with a checkout can sign. Fail the build instead.
val checkReleaseConfiguration by tasks.registering {
    val key = releaseCapabilityKey
    val url = releaseApiBaseUrl
    val development = developmentCapabilityKey
    val auth = authBaseUrl
    doLast {
        check(!url.isNullOrBlank() && url.startsWith("https://")) { "Set dispatch.apiBaseUrl to the HTTPS API base URL." }
        check(!auth.isNullOrBlank() && auth.startsWith("https://")) { "Set dispatch.authBaseUrl to biz-kit's HTTPS sign-in base URL (ADR-0011)." }
        check(!key.isNullOrBlank()) { "Set dispatch.capabilityPublicKey to the deployment's base64url Ed25519 capability key (ADR-0009)." }
        check(key != development) { "The development capability key cannot be pinned in a release build (ADR-0009)." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseConfiguration) }
