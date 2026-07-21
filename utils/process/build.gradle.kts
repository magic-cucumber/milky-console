@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("milky.native-cmake")
}

kotlin {
    macosArm64 {
        compilations.getByName("main").cinterops.create("posix_spawn") {
            defFile(project.file("src/nativeInterop/cinterop/posix_spawn.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
    }
    linuxX64 {
        compilations.getByName("main").cinterops.create("posix_spawn") {
            defFile(project.file("src/nativeInterop/cinterop/posix_spawn.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
    }
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils:logger"))
            implementation(project(":utils:pipe"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val nativeTestOutputDirectory = layout.buildDirectory.dir("native-test")
val nativeTestBuild = nativeCmake.build(
    baseDir = file("src/commonTest/native"),
    outputDir = nativeTestOutputDirectory,
)

tasks.withType<KotlinNativeTest>().configureEach {
    dependsOn(nativeTestBuild)

    val executableName = providers.systemProperty("os.name")
        .map {
            when {
                it.startsWith("Win") -> "process_test.exe"
                else -> "process_test"
            }
        }

    environment("PROCESS_TEST_EXECUTABLE_PATH", nativeTestOutputDirectory.get().file(executableName.get()).asFile)
}
