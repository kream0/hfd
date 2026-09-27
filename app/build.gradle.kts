plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release builds get their version from the tag (v1.2.3 -> versionCode 1002003); test builds
// from branch pushes are "0.dev.<run>" with a small versionCode, so any release updates them.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val releaseVersionName = System.getenv("HFD_VERSION_NAME")?.takeIf { it.isNotBlank() }
val releaseVersionCode = System.getenv("HFD_VERSION_CODE")?.toIntOrNull()

// Where the in-app updater looks for new builds (the latest published release).
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: "kream0/hfd"

// A private keystore can be supplied through env vars (see README / the CI workflow).
// Without one, builds are signed with the public dev key in /keystore so updates still
// install over each other.
val releaseKeystore = System.getenv("HFD_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "app.hfd"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hfd"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode ?: runNumber
        versionName = releaseVersionName ?: "0.dev.$runNumber"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        create("sideload") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HFD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HFD_KEY_ALIAS")
                keyPassword = System.getenv("HFD_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/hfd-dev.jks")
                storePassword = "hfd-dev"
                keyAlias = "hfd"
                keyPassword = "hfd-dev"
            }
        }
    }

    buildTypes {
        release {
            // Small sideloaded app: shrinking isn't worth the reflection / serialization risk.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // Robolectric screenshot tests (src/testDebug, run by .github/workflows/screenshots.yml)
            // need the app's resources and assets, and write their PNGs to docs/screenshots.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("hfd.screenshots", rootProject.file("docs/screenshots").absolutePath)
                // A stalled test fails instead of holding the runner (the shots so far are kept).
                it.timeout.set(java.time.Duration.ofMinutes(8))
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
