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

val nativePluginTestDirectory = layout.projectDirectory.dir("src/commonTest/native")
val nativePluginTestBuildDirectory = nativePluginTestDirectory.dir("build")
val hostOs = System.getProperty("os.name").lowercase(Locale.ROOT)
val nativePluginTestExtension = when {
    hostOs.startsWith("windows") -> "dll"
    hostOs.startsWith("mac") -> "dylib"
    else -> "so"
}
val nativePluginAcceptLibrary = nativePluginTestBuildDirectory.file("plugin_accept.$nativePluginTestExtension")
val nativePluginRejectLibrary = nativePluginTestBuildDirectory.file("plugin_reject.$nativePluginTestExtension")
val loaderTargetName = when {
    hostOs.startsWith("windows") -> "MingwX64"
    hostOs.startsWith("mac") -> "MacosArm64"
    else -> "LinuxX64"
}
val loaderTargetDirectory = loaderTargetName.replaceFirstChar(Char::lowercase)
val loaderExecutableName = if (hostOs.startsWith("windows")) "loader.exe" else "loader.kexe"
val loaderExecutable = rootProject.layout.projectDirectory.file(
    "plugin/loader/build/bin/$loaderTargetDirectory/debugExecutable/$loaderExecutableName",
)
val pluginMessageMarker = layout.buildDirectory.file("plugin-native-test/message.marker")

val buildNativePluginTests = if (hostOs.startsWith("windows")) {
    fun compileFixture(name: String, source: String, output: RegularFile) = tasks.register<Exec>(name) {
        val sourceFile = nativePluginTestDirectory.file(source)
        inputs.file(sourceFile)
        outputs.file(output)
        doFirst {
            output.asFile.parentFile.mkdirs()
            val dependencies = file(System.getProperty("user.home")).resolve(".konan/dependencies")
            val compiler = dependencies.listFiles().orEmpty().asSequence()
                .filter { it.isDirectory && it.name.startsWith("msys2-mingw-w64-x86_64-") }
                .map { it.resolve("bin/gcc.exe") }
                .firstOrNull(File::isFile)
                ?: error("Kotlin/Native MinGW compiler was not found under $dependencies")
            environment("PATH", "${compiler.parent}${File.pathSeparator}${System.getenv("PATH")}")
            commandLine(compiler, "-std=c11", "-shared", "-o", output.asFile, sourceFile.asFile)
        }
    }
    val accept = compileFixture("buildNativeAcceptPlugin", "plugin_accept.c", nativePluginAcceptLibrary)
    val reject = compileFixture("buildNativeRejectPlugin", "plugin_reject.c", nativePluginRejectLibrary)
    tasks.register("buildNativePluginTests") { dependsOn(accept, reject) }
} else {
    val configure = tasks.register<Exec>("configureNativePluginTests") {
        workingDir = nativePluginTestDirectory.asFile
        inputs.files(fileTree(nativePluginTestDirectory) { exclude("build/**") })
        outputs.dir(nativePluginTestBuildDirectory)
        commandLine("cmake", "-S", ".", "-B", "build")
    }
    tasks.register<Exec>("buildNativePluginTests") {
        dependsOn(configure)
        workingDir = nativePluginTestDirectory.asFile
        inputs.files(fileTree(nativePluginTestDirectory) { exclude("build/**") })
        outputs.files(nativePluginAcceptLibrary, nativePluginRejectLibrary)
        commandLine("cmake", "--build", "build", "--config", "Debug")
    }
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

            implementation(project(":utils:logger"))
            implementation(project(":utils:pipe"))
            implementation(project(":plugin:protocol"))
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

tasks.withType<KotlinNativeTest>().configureEach {
    dependsOn(buildNativePluginTests)
    dependsOn(":plugin:loader:linkDebugExecutable$loaderTargetName")
    environment("MILKY_PLUGIN_TEST_ACCEPT_LIBRARY", nativePluginAcceptLibrary.asFile.absolutePath)
    environment("MILKY_PLUGIN_TEST_REJECT_LIBRARY", nativePluginRejectLibrary.asFile.absolutePath)
    environment("MILKY_PLUGIN_TEST_LOADER", loaderExecutable.asFile.absolutePath)
    environment("MILKY_PLUGIN_TEST_MESSAGE_MARKER", pluginMessageMarker.get().asFile.absolutePath)
}
