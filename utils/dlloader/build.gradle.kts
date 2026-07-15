@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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



val processWindowsBuild = tasks.register<Exec>("processWindowsBuild") {
    onlyIf {
        System.getProperty("os.name").startsWith("Win")
    }
    workingDir = project.file("src/commonTest/native")
    commandLine(
        "powershell", "-NoProfile", "-Command",
        $$"""
            cmake -S . -B build;
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            cmake --build build --config Debug;
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        """.trimIndent()
    )
}

val processLinuxBuild = tasks.register<Exec>("processLinuxBuild") {
    onlyIf {
        System.getProperty("os.name").startsWith("Linux")
    }
    workingDir = project.file("src/commonTest/native")
    commandLine(
        "bash", "-c",
        """
            mkdir -p build && \
            cd build && \
            cmake .. && \
            make
        """.trimIndent()
    )
}

val processMacOSBuild = tasks.register<Exec>("processMacOSBuild") {
    onlyIf {
        System.getProperty("os.name").startsWith("Mac")
    }
    workingDir = project.file("src/commonTest/native")
    commandLine(
        "bash", "-c",
        """
            mkdir -p build && \
            cd build && \
            cmake .. && \
            make
        """.trimIndent()
    )
}

tasks.withType<KotlinNativeTest>().configureEach {
    dependsOn(processWindowsBuild,processLinuxBuild,processMacOSBuild)

    val libraryName = providers.systemProperty("os.name")
        .map {
            when {
                it.startsWith("Win") -> "dlloader_test.dll"
                it.startsWith("Mac") -> "libdlloader_test.dylib"
                else -> "libdlloader_test.so"
            }
        }

    environment("DLLOADER_TEST_LIBRARY_PATH", project.file("src/commonTest/native/build/${libraryName.get()}"))
}
