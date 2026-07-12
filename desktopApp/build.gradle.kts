import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.jediterm:jediterm-core:3.72")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.72")
    implementation("org.jetbrains.pty4j:pty4j:0.12.35")
}

compose.desktop {
    application {
        mainClass = "com.kodrix.zohaib.desktop.MainKt"
        jvmArgs += listOf(
            "-Xmx4g",
            "-Xms128m",                         // start lean; JVM grows on demand
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UseStringDeduplication"       // de-duplicate identical Strings in heap
        )

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "kodrix-ide"
            packageVersion = "1.2.0"
            description = "Kodrix IDE for Linux"
            vendor = "Kodrix"

            linux {
                iconFile.set(project.file("icon.png"))
            }
        }
    }
}
