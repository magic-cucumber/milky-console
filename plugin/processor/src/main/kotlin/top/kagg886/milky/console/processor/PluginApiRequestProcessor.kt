package top.kagg886.milky.console.processor

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
private const val GENERATED_PACKAGE = "top.kagg886.milky.console.protocol"
private const val GENERATED_FILE = "PluginApiRequestExtensions"

class PluginApiRequestProcessor(
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val apiEndpoint = resolver.getClassDeclarationByName(resolver.getKSNameFromString(API_ENDPOINT))
            ?: return emptyList()
        if (!apiEndpoint.validate()) return listOf(apiEndpoint)

        val endpoints = apiEndpoint.getSealedSubclasses()
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (endpoints.any { !it.validate() }) return endpoints.filterNot { it.validate() }

        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true),
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_FILE,
        ).bufferedWriter().use { writer ->
            writer.appendLine("package $GENERATED_PACKAGE")
            writer.appendLine()
            writer.appendLine("import kotlinx.serialization.json.decodeFromJsonElement")
            writer.appendLine("import kotlinx.serialization.json.encodeToJsonElement")
            writer.appendLine("import kotlin.uuid.Uuid")
            writer.appendLine("import org.ntqqrev.milky.ApiEndpoint")
            writer.appendLine("import org.ntqqrev.milky.milkyJsonModule")
            writer.appendLine()
            writer.appendLine("/**")
            writer.appendLine(" * Validates this JSON request body against the input type for [type] and creates a plugin API request.")
            writer.appendLine(" * Returns null when the JSON is invalid, [type] is not an API endpoint path, or the body cannot be decoded.")
            writer.appendLine(" */")
            writer.appendLine("public fun String.toPluginApiRequest(type: String, tag: Uuid = Uuid.random()): PluginApiRequest? = runCatching {")
            writer.appendLine("    val rawPayload = milkyJsonModule.parseToJsonElement(this)")
            writer.appendLine("    when (type) {")

            endpoints.forEach { endpoint ->
                val endpointName = endpoint.qualifiedName?.asString()
                    ?: error("ApiEndpoint subclass without a qualified name: ${endpoint.simpleName.asString()}")
                val inputType = endpoint.superTypes
                    .map { it.resolve() }
                    .firstOrNull { it.declaration.qualifiedName?.asString() == API_ENDPOINT }
                    ?.arguments
                    ?.firstOrNull()
                    ?.type
                    ?.resolve()
                    ?.declaration
                    ?.qualifiedName
                    ?.asString()
                    ?: error("Unable to resolve request type for $endpointName")
                writer.appendLine("        $endpointName.path -> {")
                writer.appendLine("            val request = milkyJsonModule.decodeFromJsonElement<$inputType>(rawPayload)")
                writer.appendLine("            PluginApiRequest(type = type, tag = tag, payload = milkyJsonModule.encodeToJsonElement(request))")
                writer.appendLine("        }")
            }

            writer.appendLine("        else -> return null")
            writer.appendLine("    }")
            writer.appendLine("}.getOrNull()")
        }
        generated = true
        return emptyList()
    }
}

class PluginApiRequestProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        PluginApiRequestProcessor(environment.codeGenerator)
}
