import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.kys.ktransfer.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KTransfer"
            packageVersion = "0.1.0"
        }
    }
}

// Streams a WAV file to the Live Translate API in real time and reports latency.
// Usage: ./gradlew :desktopApp:liveProbe -PprobeArgs="<wav> <targetLang> [--resumption] [--out <wav>]"
tasks.register<JavaExec>("liveProbe") {
    group = "verification"
    description = "Measure Live Translate latency with a WAV file."
    mainClass.set("com.kys.ktransfer.probe.LiveProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    args(providers.gradleProperty("probeArgs").orElse("").get().split(" ").filter { it.isNotBlank() })
}
