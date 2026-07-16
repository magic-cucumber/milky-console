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

val nativePluginProjects = file("src/commonTest/native")
    .listFiles()
    .orEmpty()
    .filter { it.isDirectory && it.resolve("CMakeLists.txt").isFile }

val processPluginBuildTasks = nativePluginProjects.map { pluginProject ->
    tasks.register<Exec>("process${pluginProject.name.replaceFirstChar(Char::uppercase)}Build") {
        workingDir = pluginProject
        when {
            System.getProperty("os.name").startsWith("Win") -> commandLine(
                "powershell", "-NoProfile", "-Command",
                $$"""
                    cmake -S . -B build;
                    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                    cmake --build build --config Debug;
                    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
                """.trimIndent()
            )
            System.getProperty("os.name").startsWith("Linux") ||
                System.getProperty("os.name").startsWith("Mac") -> commandLine(
                "bash", "-c",
                """
                    cmake -S . -B build &&
                    cmake --build build --config Debug
                """.trimIndent()
            )
            else -> error("Unsupported test platform: ${System.getProperty("os.name")}")
        }
        inputs.files(fileTree(pluginProject) {
            exclude("build/**")
        })
        outputs.dir(pluginProject.resolve("build/output"))
    }
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
    dependsOn(processPluginBuildTasks)
    dependsOn(pluginLoaderTestTarget.loaderTask)
    inputs.file(pluginLoaderTestTarget.loaderBinary)
    nativePluginProjects.forEach { pluginProject ->
        from(pluginProject.resolve("build/output")) {
            into("plugin")
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
}
