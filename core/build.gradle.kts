import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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

            implementation(project(":utils:logger"))
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
    // BuildConfig configuration here.
    // https://github.com/gmazzo/gradle-buildconfig-plugin#usage-in-kts
}
