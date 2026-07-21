import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    // KSP common metadata generation requires a JVM target.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    macosArm64()
    linuxX64()
    mingwX64()

    targets.matching { it is KotlinNativeTarget }.forEach { target ->
        (target as KotlinNativeTarget).binaries.staticLib()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")

            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.milky.types)
                implementation(libs.saltify.core)
                implementation(project(":plugin:protocol"))
            }
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":processor:core"))
}

tasks.withType<KotlinNativeCompile>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}

tasks.withType<KotlinJvmCompile>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}
