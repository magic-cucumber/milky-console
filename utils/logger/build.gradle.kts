@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

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
            api(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
