@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
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

    targets.matching { it is KotlinNativeTarget }.map { it as KotlinNativeTarget }.forEach { target ->
       target.binaries.executable {
            entryPoint = "main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.milky.types)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.clikt)
            implementation(libs.okio)

            implementation(project(":plugin:protocol"))

            implementation(project(":utils:event-bus"))
            implementation(project(":utils:logger"))
            implementation(project(":utils:pipe"))
            implementation(project(":utils:process"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.androidx.room3.runtime)
        }

        linuxMain.dependencies {
            implementation(libs.ktor.client.curl)
            implementation(libs.androidx.room3.runtime)
        }

        mingwMain.dependencies {
            implementation(libs.ktor.client.winhttp)
        }

    }
}

dependencies {
    add("kspMacosArm64", libs.androidx.room3.compiler)
    add("kspLinuxX64", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

buildConfig {
    packageName("top.kagg886.milky.console")
    className("CoreBuildConfig")
    buildConfigField("SCHEMA_VERSION_START", 1)
    buildConfigField("SCHEMA_VERSION_END", 1)
    buildConfigField("PROTOCOL_VERSION_START", 1)
    buildConfigField("PROTOCOL_VERSION_END", 1)
}
