import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.buildConfig)
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
            implementation(libs.milky.types)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

buildConfig {
    packageName("top.kagg886.milky.console.protocol")
    buildConfigField("MAGIC_BYTES", byteArrayOf(0xC,0xA,0xF,0xE,0xB,0xA,0xB,0xE))
    buildConfigField("SCHEMA_VERSION", 1.toShort())
    buildConfigField("MAX_PACKET_SIZE", 512 * 1024)
}
