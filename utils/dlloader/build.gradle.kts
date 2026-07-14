@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val nativeTestDirectory = layout.projectDirectory.dir("src/commonTest/native")
val nativeTestBuildDirectory = nativeTestDirectory.dir("build")
val nativeTestLibraryName = when {
    System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") -> "dlloader_test.dll"
    System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac") -> "libdlloader_test.dylib"
    else -> "libdlloader_test.so"
}
val nativeTestLibrary = nativeTestBuildDirectory.file(nativeTestLibraryName)

val configureNativeTestLibrary = tasks.register<Exec>("configureNativeTestLibrary") {
    workingDir = nativeTestDirectory.asFile
    inputs.files(fileTree(nativeTestDirectory) { exclude("build/**") })
    outputs.dir(nativeTestBuildDirectory)
    commandLine("cmake", "-S", ".", "-B", "build")
}

val buildNativeTestLibrary = tasks.register<Exec>("buildNativeTestLibrary") {
    dependsOn(configureNativeTestLibrary)
    workingDir = nativeTestDirectory.asFile
    inputs.files(fileTree(nativeTestDirectory) { exclude("build/**") })
    outputs.file(nativeTestLibrary)
    commandLine("cmake", "--build", "build", "--config", "Debug")
}

kotlin {
    macosArm64()
    linuxX64()
    mingwX64()

    applyDefaultHierarchyTemplate {
        group("common") {
            group("unix") {
                withMacos()
                withLinux()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<KotlinNativeTest>().configureEach {
    dependsOn(buildNativeTestLibrary)
    environment("DLLOADER_TEST_LIBRARY_PATH", nativeTestLibrary.asFile.absolutePath)
}
