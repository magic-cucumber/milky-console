@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("milky.native-cmake")
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



val nativeTestOutputDirectory = layout.buildDirectory.dir("native-test")
val nativeTestBuild = nativeCmake.build(
    baseDir = file("src/commonTest/native"),
    outputDir = nativeTestOutputDirectory,
)

tasks.withType<KotlinNativeTest>().configureEach {
    dependsOn(nativeTestBuild)

    val libraryName = providers.systemProperty("os.name")
        .map {
            when {
                it.startsWith("Win") -> "dlloader_test.dll"
                it.startsWith("Mac") -> "libdlloader_test.dylib"
                else -> "libdlloader_test.so"
            }
        }

    environment("DLLOADER_TEST_LIBRARY_PATH", nativeTestOutputDirectory.get().file(libraryName.get()).asFile)
}
