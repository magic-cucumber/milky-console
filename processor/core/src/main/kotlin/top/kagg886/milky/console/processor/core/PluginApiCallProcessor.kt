package top.kagg886.milky.console.processor.core

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

private const val API_ENDPOINT = "org.ntqqrev.milky.ApiEndpoint"
private const val GENERATED_PACKAGE = "top.kagg886.milky.console.plugin"
private const val GENERATED_FILE = "PluginApiCallExtensions"

class PluginApiCallProcessor(
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val apiEndpoint = resolver.getClassDeclarationByName(resolver.getKSNameFromString(API_ENDPOINT))
            ?: return emptyList()
        if (!apiEndpoint.validate()) return listOf(apiEndpoint)

        val endpoints = apiEndpoint.getSealedSubclasses().filterIsInstance<KSClassDeclaration>().toList()
        if (endpoints.any { !it.validate() }) return endpoints.filterNot { it.validate() }

        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true),
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_FILE,
        ).bufferedWriter().use { writer ->
            writer.appendLine("package $GENERATED_PACKAGE")
            writer.appendLine()
            writer.appendLine("import kotlinx.serialization.json.JsonElement")
            writer.appendLine("import kotlinx.serialization.json.decodeFromJsonElement")
            writer.appendLine("import kotlinx.serialization.json.encodeToJsonElement")
            writer.appendLine("import org.ntqqrev.milky.ApiEndpoint")
            writer.appendLine("import org.ntqqrev.milky.ApiGeneralResponse")
            writer.appendLine("import org.ntqqrev.milky.milkyJsonModule")
            writer.appendLine("import org.ntqqrev.saltify.core.SaltifyApplication")
            writer.appendLine()
            writer.appendLine("/** Calls the endpoint named by [category] with its JSON [payload]. */")
            writer.appendLine("public suspend fun SaltifyApplication.callApi(category: String, payload: JsonElement): ApiGeneralResponse = when (category) {")
            endpoints.forEach { endpoint ->
                val endpointName = endpoint.qualifiedName?.asString()
                    ?: error("ApiEndpoint subclass without a qualified name: ${endpoint.simpleName.asString()}")
                val inputType = endpoint.apiType(0)
                val outputType = endpoint.apiType(1)
                val name = endpoint.simpleName.asString()
                writer.appendLine("    $endpointName.path -> {")
                writer.appendLine("        val request = milkyJsonModule.decodeFromJsonElement<$inputType>(payload)")
                writer.appendLine("        val response = callApi<$inputType, $outputType>(ApiEndpoint.$name, request)")
                writer.appendLine("        ApiGeneralResponse(status = \"ok\", retcode = 0, data = milkyJsonModule.encodeToJsonElement(response))")
                writer.appendLine("    }")
            }
            writer.appendLine("    else -> ApiGeneralResponse(status = \"failed\", retcode = -1, message = \"Unsupported API endpoint: ${'$'}category\")")
            writer.appendLine("}")
        }
        generated = true
        return emptyList()
    }
}

private fun KSClassDeclaration.apiType(index: Int): String = superTypes
    .map { it.resolve() }
    .firstOrNull { it.declaration.qualifiedName?.asString() == API_ENDPOINT }
    ?.arguments
    ?.getOrNull(index)
    ?.type
    ?.resolve()
    ?.declaration
    ?.qualifiedName
    ?.asString()
    ?: error("Unable to resolve API type $index for ${qualifiedName?.asString()}")

class PluginApiCallProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        PluginApiCallProcessor(environment.codeGenerator)
}
