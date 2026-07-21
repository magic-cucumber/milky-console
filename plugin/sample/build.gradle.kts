import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
}

data class PluginPackageTarget(
    val kotlinTarget: String,
    val targetName: String,
    val platformFileName: String,
    val libraryFileName: String,
)

val pluginPackageDirectory = layout.buildDirectory.dir("plugin")

val pluginPackageTargets = listOf(
    PluginPackageTarget("linuxX64", "LinuxX64", "LINUX-X64.so", "libmilky_console_sample.so"),
    PluginPackageTarget("macosArm64", "MacosArm64", "MACOSX-ARM64.dylib", "libmilky_console_sample.dylib"),
    PluginPackageTarget("mingwX64", "MingwX64", "WINDOWS-X64.dll", "milky_console_sample.dll"),
)

pluginPackageTargets.forEach { target ->
    tasks.register<Sync>("packageRelease${target.targetName}Plugin") {
        group = "distribution"
        description = "Packages the release ${target.targetName} sample plugin."
        dependsOn("linkReleaseShared${target.targetName}")

        into(pluginPackageDirectory)
        from(layout.projectDirectory.file("manifest.json"))
        from(layout.projectDirectory.file("default-config.json"))
        from(layout.buildDirectory.file("bin/${target.kotlinTarget}/releaseShared/${target.libraryFileName}")) {
            into("platform")
            rename { target.platformFileName }
        }
    }
}

// Produces a loader-ready package for the platform running Gradle. Use the
// target-specific tasks above when packaging a cross-compiled library.
tasks.register("packagePlugin") {
    group = "distribution"
    description = "Packages the sample plugin for the current host platform."
    val hostTask = when {
        System.getProperty("os.name").startsWith("Linux") -> "packageReleaseLinuxX64Plugin"
        System.getProperty("os.name").startsWith("Mac") -> "packageReleaseMacosArm64Plugin"
        System.getProperty("os.name").startsWith("Windows") -> "packageReleaseMingwX64Plugin"
        else -> error("Unsupported host platform for sample plugin packaging")
    }
    dependsOn(hostTask)
}

kotlin {
    macosArm64()
    linuxX64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("milky_console") {
            defFile(project.file("../api/src/nativeInterop/cinterop/milky_console.def"))
            includeDirs(project.file("../api/include"))
        }

        binaries.sharedLib {
            baseName = "milky-console-sample"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The plugin consumes the public C ABI and Milky event/API models only.
            implementation(project(":plugin:api"))
            implementation(libs.milky.types)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
