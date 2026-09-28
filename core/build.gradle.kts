import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (no Android): Qur'an data, the faḍāʾil dataset, the playback plan, FSRS and the
// progress model. Kept apart so its unit tests run on a plain JVM, locally and in CI.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // Dataset tests read the app's bundled assets: an input, so changing them reruns the tests.
    val assets = file("../app/src/main/assets")
    inputs.dir(assets).withPropertyName("assets").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("hfd.assets", assets.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
