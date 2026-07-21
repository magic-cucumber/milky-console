@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.gradle.api.Transformer
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.Sync
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
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

    targets.matching { it is KotlinNativeTarget }.map { it as KotlinNativeTarget }.forEach { target ->
       target.binaries.executable {
            entryPoint = "main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.saltify.core)
            implementation(libs.milky.types)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.okio)
            implementation(libs.ktoml.core)

            implementation(project(":plugin:protocol"))
            implementation(project(":core-utils"))

            implementation(project(":utils:event-bus"))
            implementation(project(":utils:logger"))
            implementation(project(":utils:pipe"))
            implementation(project(":utils:process"))
            implementation(project(":utils:file-watcher"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.datetime)
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

data class PluginLoaderTestTarget(
    val loaderTask: String,
    val loaderBinary: Provider<RegularFile>,
    val loaderExecutable: String,
)

data class PluginLoaderFileNameTransformer(
    val loaderExecutable: String,
) : Transformer<String?, String> {
    override fun transform(fileName: String): String? = loaderExecutable
}

val nativePluginSourceDirectory = file("src/commonTest/native")

val nativePluginProjects = nativePluginSourceDirectory
    .listFiles()
    .orEmpty()
    .filter { it.isDirectory && it.resolve("CMakeLists.txt").isFile }

val nativePluginBuildTasks = nativePluginProjects.associateWith { pluginProject ->
    nativeCmake.build(
        baseDir = pluginProject,
        outputDir = layout.buildDirectory.dir("plugin-test/plugins/${pluginProject.name}"),
    )
}

val pluginLoaderTestTarget = run {
    val loaderBuildDirectory = project(":plugin:loader").layout.buildDirectory
    when {
        System.getProperty("os.name").startsWith("Win") -> PluginLoaderTestTarget(
            ":plugin:loader:linkDebugExecutableMingwX64",
            loaderBuildDirectory.file("bin/mingwX64/debugExecutable/loader.exe"),
            "loader.exe",
        )
        System.getProperty("os.name").startsWith("Linux") -> PluginLoaderTestTarget(
            ":plugin:loader:linkDebugExecutableLinuxX64",
            loaderBuildDirectory.file("bin/linuxX64/debugExecutable/loader.kexe"),
            "loader",
        )
        System.getProperty("os.name").startsWith("Mac") -> PluginLoaderTestTarget(
            ":plugin:loader:linkDebugExecutableMacosArm64",
            loaderBuildDirectory.file("bin/macosArm64/debugExecutable/loader.kexe"),
            "loader",
        )
        else -> error("Unsupported test platform: ${System.getProperty("os.name")}")
    }
}

val pluginLoaderTestContainerDirectory = layout.buildDirectory.dir("plugin-test/container")

val preparePluginLoaderTestContainer = tasks.register<Sync>("preparePluginLoaderTestContainer") {
    dependsOn(nativePluginBuildTasks.values)
    dependsOn(pluginLoaderTestTarget.loaderTask)
    inputs.file(pluginLoaderTestTarget.loaderBinary)
    nativePluginProjects.forEach { pluginProject ->
        from(nativePluginBuildTasks.getValue(pluginProject).flatMap { it.outputDir }) {
            into("plugin/${pluginProject.name}")
        }
    }
    from(pluginLoaderTestTarget.loaderBinary) {
        rename(PluginLoaderFileNameTransformer(pluginLoaderTestTarget.loaderExecutable))
    }
    into(pluginLoaderTestContainerDirectory)
}

tasks.withType<KotlinNativeTest>().configureEach {
    dependsOn(preparePluginLoaderTestContainer)
    environment("MILKY_PLUGIN_TEST_DIRECTORY", pluginLoaderTestContainerDirectory.get().asFile.absolutePath)
    environment("MILKY_GRADLE_TEST_LOGGER_LEVEL", LogLevel.DEBUG)
}
