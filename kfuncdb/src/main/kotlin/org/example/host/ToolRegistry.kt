package org.example.host

import com.fasterxml.jackson.databind.JsonNode
import org.example.api.LlmTool
import org.example.api.LlmToolParamDoc
import org.example.util.Json
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.javaType

data class ToolParam(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String = ""
)

data class ToolDefinition(
    val name: String,
    // Human-readable concise description (typically same as annotation.shortDescription)
    val description: String,
    val shortDescription: String,
    val longDescription: String,
    val parameters: List<ToolParam>,
    // Parameters technical specification (from annotation.paramsSpec)
    val paramsSpec: String,
    val pluginId: String
)

private data class ToolHandle(
    val pluginId: String,
    val container: Any,
    val fn: KFunction<*>,
    val valueParams: List<KParameter>,
    val dtoParamKlass: KClass<*>? // if single-arg DTO, else null
)

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolHandle>()
    private val defs = mutableListOf<ToolDefinition>()

    fun registerFrom(loaded: LoadedPlugin) {
        val pluginId = loaded.manifest.id
        val containers = runCatching { loaded.instance.tools() }.getOrElse { emptyList() }
        containers.forEach { container ->
            container::class.memberFunctions.forEach { fn ->
                val ann = fn.findAnnotation<LlmTool>() ?: return@forEach
                val toolName = if (ann.name.isNotBlank()) ann.name else fn.name
                val params = fn.parameters.filter { it.kind == KParameter.Kind.VALUE }

                val dtoParamKlass: KClass<*>? = if (params.size == 1) {
                    val cls = (params.first().type.classifier as? KClass<*>)
                    if (cls != null && isDtoCandidate(cls)) cls else null
                } else null

                val paramDocMap = ann.params.associate { it.name to it.description }
                val definition = ToolDefinition(
                    name = toolName,
                    description = ann.shortDescription.ifBlank { ann.longDescription },
                    shortDescription = ann.shortDescription,
                    longDescription = ann.longDescription,
                    parameters = buildParamDefs(params, dtoParamKlass, paramDocMap),
                    paramsSpec = ann.paramsSpec,
                    pluginId = pluginId
                )
                defs += definition
                tools[toolName] = ToolHandle(pluginId, container, fn, params, dtoParamKlass)
            }
        }
    }

    fun listTools(): List<ToolDefinition> = defs.toList()

    fun invoke(name: String, args: JsonNode): Any? {
        val handle = tools[name] ?: throw NoSuchElementException("Tool not found: ${'$'}name")
        val callArgs = mutableMapOf<KParameter, Any?>()
        // bind instance
        handle.fn.parameters.firstOrNull { it.kind == KParameter.Kind.INSTANCE }?.let { callArgs[it] = handle.container }

        if (handle.dtoParamKlass != null) {
            // DTO style: map entire args node to DTO
            val dto = Json.mapper.convertValue<Any>(args, Json.mapper.typeFactory.constructType(handle.dtoParamKlass.java))
            callArgs[handle.valueParams.first()] = dto
        } else {
            // Multi-param: args should be object with fields mapping to param names
            if (!args.isObject) throw IllegalArgumentException("Args must be a JSON object for tool ${'$'}name")
            handle.valueParams.forEach { p ->
                val pname = p.name ?: throw IllegalStateException("Parameter name unavailable for ${'$'}name")
                val node = args.get(pname)
                if (node == null || node.isNull) {
                    if (p.isOptional) return@forEach
                    else throw IllegalArgumentException("Missing required parameter: ${'$'}pname")
                }
                val targetType = p.type.javaType
                val value = Json.mapper.convertValue<Any?>(node, Json.mapper.typeFactory.constructType(targetType))
                callArgs[p] = value
            }
        }

        return handle.fn.callBy(callArgs)
    }

    private fun isDtoCandidate(klass: KClass<*>): Boolean {
        val pkg = klass.qualifiedName ?: return false
        if (klass.java.isPrimitive) return false
        if (klass.java.isArray) return false
        if (klass.java.isEnum) return false
        // common scalars
        if (klass == String::class || Number::class.java.isAssignableFrom(klass.java) || klass == Boolean::class) return false
        // Collections and maps are NOT DTOs
        if (java.util.Collection::class.java.isAssignableFrom(klass.java)) return false
        if (java.util.Map::class.java.isAssignableFrom(klass.java)) return false
        // Jackson tree nodes are NOT DTOs
        if (com.fasterxml.jackson.databind.JsonNode::class.java.isAssignableFrom(klass.java)) return false
        // treat data classes or regular classes as DTO candidates
        return true
    }

    private fun buildParamDefs(
        params: List<KParameter>,
        dto: KClass<*>?,
        paramDocs: Map<String, String>
    ): List<ToolParam> {
        return if (dto != null) {
            // single DTO param; represent as one param named "input" with type dto simple name
            val desc = paramDocs["input"] ?: "Entire input object mapped to ${'$'}{dto.simpleName ?: dto.toString()}"
            listOf(ToolParam(name = "input", type = dto.simpleName ?: dto.toString(), required = true, description = desc))
        } else {
            params.map { p ->
                val pname = p.name ?: "param"
                val t = p.type.toString()
                val desc = paramDocs[pname] ?: ""
                ToolParam(name = pname, type = t, required = !p.isOptional, description = desc)
            }
        }
    }
}
