import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/**
 * Generates Markdown files describing each provided JSON Schema
 */
object DocumentationGenerator {

    suspend fun generate(
        schemas: Map<Path, JsonObject>,
        outputDirectory: Path,
    ) {
        coroutineScope {
            schemas.forEach { (path, schema) ->
                launch(Dispatchers.Default) {
                    val schemaMarkdown = buildString {
                        appendLine("# ${path.name}")
                        appendLine()
                        appendLine("## Schema")
                        appendLine()
                        describeSchema(schema, this)
                    }

                    val snippetsMarkdown = buildString {
                        val name = CustomNames.getItemName(path).replaceFirstChar { it.titlecase(Locale.ROOT) }
                        appendLine("""--8<-- "snippets/$name.md"""")
                    }

                    val markdown = buildString {
                        appendLine(schemaMarkdown)
                        appendLine("## Code snippets")
                        appendLine(snippetsMarkdown)
                    }

                    val outFile = outputDirectory.resolve(path.nameWithoutExtension + ".md")
                    outFile.writeText(markdown)
                }
            }
        }
    }

    private fun describeSchema(schema: JsonObject, out: StringBuilder, indent: Int = 0, title: String? = null) {
        val prefix = "    ".repeat(indent)

        title?.let {
            out.appendLine("${prefix}## $it")
        }

        when (val type = schema["type"]?.jsonPrimitive?.contentOrNull) {
            "object" -> {
                val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                val required = schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
                if (properties.isEmpty()) {
                    out.appendLine("${prefix}This object has no defined properties.")
                } else {
                    properties.entries.forEach { (propertyName, propertySchema) ->
                        val propSchema = propertySchema.jsonObjectOrNull() ?: JsonObject(emptyMap())
                        val shortType = getShortTypeName(propSchema)
                        val isRequired = if (propertyName in required) " <span style=\"color: var(--md-code-hl-special-color)\">(required)</span>" else ""
                        out.appendLine("${prefix}- `$propertyName`$isRequired: <span style=\"color: var(--md-code-hl-keyword-color)\">$shortType</span>")

                        describeConstraints(propSchema, out, indent + 1)

                        when (propSchema["type"]?.jsonPrimitive?.contentOrNull) {
                            "object" -> {
                                describeSchema(propSchema, out, indent + 1)
                            }
                            "array" -> {
                                val items = propSchema["items"]?.jsonObjectOrNull()
                                if (items != null) {
                                    describeSchema(items, out, indent + 1)
                                }
                            }
                        }
                    }
                    out.appendLine()
                }
            }

            "array" -> {
                val minItems = schema["minItems"]?.jsonPrimitive?.intOrNull
                val maxItems = schema["maxItems"]?.jsonPrimitive?.intOrNull
                out.appendLine("${prefix}Type: `array`")
                if (minItems != null || maxItems != null) {
                    out.appendLine("${prefix}Items count: $minItems .. $maxItems")
                }
                describeConstraints(schema, out, indent)

                val items = schema["items"]?.jsonObjectOrNull()
                if (items != null) {
                    out.appendLine()
                    out.appendLine("${prefix}### Items")
                    out.appendLine("${prefix}- ${getShortTypeName(items)}")
                    describeConstraints(items, out, indent + 1)
                    // Recurse to detail nested items (objects, arrays, etc.)
                    when (items["type"]?.jsonPrimitive?.contentOrNull) {
                        "object", "array" -> {
                            out.appendLine()
                            describeSchema(items, out, indent + 1)
                        }
                    }
                }
            }

            "string", "number", "integer", "boolean", "null", null -> {
                val shownType = type ?: "unknown"
                out.appendLine("${prefix}<br/>Type: `$shownType`")
                describeConstraints(schema, out, indent)
            }
        }
    }

    private fun getShortTypeName(element: JsonElement): String {
        val obj = element.jsonObjectOrNull() ?: return "unknown"
        val t = obj["type"]?.jsonPrimitive?.contentOrNull
        return when {
            obj.containsKey("anyOf") -> obj["anyOf"]!!.jsonArray.joinToString(" or ") { getShortTypeName(it) }
            t == "object" -> "object"
            t == "array" -> "array of ${obj["items"]?.let { getShortTypeName(it) } ?: "unknown"}"
            obj.containsKey("enum") -> "enum"
            t != null -> t
            else -> "unknown"
        }
    }

    private fun describeConstraints(schema: JsonObject, out: StringBuilder, indent: Int) {
        val prefix = "  ".repeat(indent)

        // String constraints and enums
        schema["enum"]?.let { enumElement ->
            val values = enumElement.jsonArray.joinToString(", ") { "`${it.jsonPrimitive.content}`" }
            out.appendLine("${prefix}<br/>Enum values: $values")
        }

        schema["format"]?.jsonPrimitive?.contentOrNull?.let { fmt ->
            out.appendLine("${prefix}<br/>Format: $fmt")
        }

        // Number constraints
        val minimum = schema["minimum"]?.jsonPrimitive?.doubleOrNull
        val maximum = schema["maximum"]?.jsonPrimitive?.doubleOrNull
        if (minimum != null && maximum != null) {
            val isInteger = schema["type"]?.jsonPrimitive?.contentOrNull == "integer"
            if (isInteger) {
                out.appendLine("${prefix}<br/>Range: <span style=\"color: var(--md-code-hl-number-color)\">${minimum.toInt()} .. ${maximum.toInt()}</span>")
            } else {
                out.appendLine(
                    "${prefix}<br/>Range: <span style=\"color: var(--md-code-hl-number-color)\">${formatDouble(minimum)} .. ${formatDouble(maximum)}</span>"
                )
            }
        }
    }

    private fun formatDouble(d: Double): String {
        return String.format("%.12f", d).trimEnd('0').trimEnd('.')
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = (this as? JsonObject)
}
