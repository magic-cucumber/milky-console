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
    mingwX64 {
        compilations.getByName("main").cinterops.create("file_watcher_win") {
            defFile(project.file("src/nativeInterop/cinterop/file_watcher_win.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils:logger"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
        }
    }
}
