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

val currentOs = System.getProperty("os.name").lowercase()
val appTargetFormats = when {
    currentOs.contains("mac") || currentOs.contains("darwin") -> listOf(TargetFormat.Dmg)
    currentOs.contains("win") -> listOf(TargetFormat.Msi, TargetFormat.Exe)
    else -> listOf(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
}

compose.desktop {
    application {
        mainClass = "com.kodrix.zohaib.desktop.MainKt"
        jvmArgs += listOf(
            "-Xmx4g",
            "-Xms128m",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UseStringDeduplication"
        )

        tasks.withType<JavaExec> {
            environment("DISPLAY", System.getenv("DISPLAY") ?: ":99")
        }

        nativeDistributions {
            targetFormats(*appTargetFormats.toTypedArray())
            packageName = "kodrix-ide"
            packageVersion = "1.2.0"
            description = "Kodrix IDE — A Kotlin Multiplatform IDE"
            vendor = "Kodrix"
            copyright = "© 2025 Kodrix"

            linux {
                iconFile.set(project.file("icon.png"))
                menuGroup = "Development"
                rpmLicenseType = "MIT"
            }

            macOS {
                iconFile.set(project.file("icon.png"))
                bundleID = "com.kodrix.zohaib.desktop"
                appCategory = "public.app-category.developer-tools"
            }
        }
    }
}
