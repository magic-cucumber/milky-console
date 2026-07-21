plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    macosArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kache)
            implementation(project(":utils:logger"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
