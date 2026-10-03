import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.compose") version "1.9.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.6.11")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    implementation("com.google.zxing:core:3.5.3")
}

// Mirror ref-tungate's vendor pattern: the engine binary lives in vendor/ and is
// copied into app-resources/macos before packaging.
tasks.register<Copy>("syncEngine") {
    from("vendor")
    into("app-resources/macos")
    filePermissions { unix("755") }
}

tasks.register<Copy>("syncEngineFromCore") {
    val bin = File("../simorgh-core/target/release/simorghd")
    inputs.file(bin)
    outputs.file(File("vendor/simorghd"))
    onlyIf { bin.exists() }
    doLast {
        bin.copyTo(File("vendor/simorghd"), overwrite = true)
        File("vendor/simorghd").setExecutable(true)
    }
}
tasks.named("syncEngine") { dependsOn("syncEngineFromCore") }

tasks.matching { it.name == "prepareAppResources" || it.name.startsWith("package") || it.name.startsWith("createRuntimeImage") }.configureEach {
    dependsOn("syncEngine")
}

compose.desktop {
    application {
        mainClass = "com.simorgh.mac.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Simorgh"
            packageVersion = "0.1.0"
            vendor = "simorgh"
            description = "Simorgh - censorship circumvention client for macOS"
            appResourcesRootDir = project.layout.projectDirectory.dir("app-resources")

            macOS {
                bundleID = "sh.simorgh.mac"
                minimumSystemVersion = "11.0"
                iconFile = project.file("icons/Simorgh.icns")
                dockName = "Simorgh"
            }
        }
    }
}
