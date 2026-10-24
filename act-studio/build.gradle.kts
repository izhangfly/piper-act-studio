import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    id("com.nervus.packaging")
}

group = "com.nervus.act"
version = "1.0.1"

// Target ABI of the package. Defaults to the Dell (x86_64); pass -Pnervus.abi=linux-arm64 for RK3588 boards
val nervusAbi: String = (findProperty("nervus.abi") as String?) ?: "linux-x86_64"

dependencies {
    implementation("com.nervus.sdk:nervus-app-sdk:0.1.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(
        when (nervusAbi) {
            "linux-arm64" -> "org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:0.8.18"
            else -> "org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.18"
        }
    )
    testImplementation(libs.kotlin.test)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
}

kotlin {
    jvmToolchain(21)
    sourceSets.all {
        languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

tasks.test {
    useJUnitPlatform()
    // The UI test drives a running engine (python -m nervus_act --sim); it is skipped without one.
    environment("ACT_ENGINE_TEST", System.getenv("ACT_ENGINE_TEST") ?: "")
    systemProperty("java.awt.headless", "true")
    // The test has its own 5-minute limit; this one catches a JVM that hangs outside it.
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "com.nervus.act.app.ActStudioAppKt"
        // createDistributable: a self-contained desktop app for hosted/bin/nervus-standalone (no NervusOS).
        nativeDistributions {
            packageName = "act-studio"
            packageVersion = project.version.toString()
            includeAllModules = true
        }
    }
}

nspkg {
    packageId = "com.nervus.act"
    label = "ACT Studio"
    version = project.version.toString()
    versionCode = 2L
    minNervusApi = 1
    targetNervusApi = 1
    supportedAbis = listOf(nervusAbi)

    runtimeDeps.minJavaRelease = 21

    // ACT Studio drives the arm and both robot cameras through the ACT engine (nervus-act.service, on
    // loopback), so the App Store asks for the same consent an app driving them through NervusOS needs.
    permissions = listOf(
        "perm.camera.capture",
        "perm.manipulator.control",
    )

    components.app("main") {
        mainClass = "com.nervus.act.app.ActStudioAppKt"
        runtime = "jvm"
        launchMode = "manual"
        interfaces = listOf()
    }

    signing.keyFile = file("signing/developer.pem")
    signing.role = "developer"
}
