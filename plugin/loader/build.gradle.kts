import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
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
            implementation(libs.clikt)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":utils:pipe"))
            implementation(project(":utils:dlloader"))
        }


    }
}
